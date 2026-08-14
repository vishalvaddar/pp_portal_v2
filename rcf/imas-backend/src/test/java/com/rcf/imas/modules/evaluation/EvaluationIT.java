package com.rcf.imas.modules.evaluation;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EvaluationIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('evseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='evseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "evseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_year, exam_date, exam_start_time, exam_end_time)
            VALUES (750001, 'NMMS Eval Exam', '2026-27', '2026-06-15', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        // applicant WITHOUT an applicant_secondary_info row, but WITH exam_results/attendance rows --
        // exercises the join-chain bug: er/aea join on asi.applicant_id, so these must NOT appear in getStudents' output.
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, mother_name, gender, created_by, updated_by)
            VALUES (750101,2026,24075000001,'EvalKid','f','m','M',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (750101, 750001)").update();
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score, pp_exam_cleared, interview_required_yn) VALUES (750101, 88, 'Y', 'N')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (750101, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_exam_attendance WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 750001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'evseed'").update();
    }

    @Test
    void examNamesReturnsApiResponseEnvelopeFilteredByYearPrefix() throws Exception {
        mvc.perform(get("/api/evaluation/exam_names").param("year", "2026-27").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.statusCode").value(200))
           .andExpect(jsonPath("$.message").value("ok"))
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[0].exam_name").value("NMMS Eval Exam"));
    }

    @Test
    void examNamesMissingYearIs400() throws Exception {
        mvc.perform(get("/api/evaluation/exam_names").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Academic year is required"));
    }

    @Test
    void downloadExcelHas34FixedColumnsAndPreservesJoinChainBug() throws Exception {
        var result = mvc.perform(post("/api/evaluation/download_excel").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_name\":\"NMMS Eval Exam\"}"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
           .andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
            .isEqualTo("attachment; filename=students_NMMS_Eval_Exam.xlsx"); // NOT quoted (Firm Decision/quirk 13)

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheet("Students");
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(34);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Applicant ID");
            assertThat(sheet.getRow(0).getCell(31).getStringCellValue()).isEqualTo("Exam Score");
            assertThat(((org.apache.poi.xssf.usermodel.XSSFColor) sheet.getRow(0).getCell(0).getCellStyle().getFillForegroundColorColor()).getARGBHex())
                .isEqualToIgnoringCase("FFFFFFCC");

            Row data = sheet.getRow(1);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("EvalKid");
            // BUG PRESERVED: exam_results/attendance joined on asi.applicant_id (secondary info), which has no row here
            // -> pp_exam_score/pp_exam_cleared/interview_required/pp_exam_appeared must all be blank, not 88/Y/N/Y.
            assertThat(data.getCell(31).getStringCellValue()).isEqualTo(""); // Exam Score blank
            assertThat(data.getCell(33).getStringCellValue()).isEqualTo(""); // Interview Required blank
        }
    }

    @Test
    void evaluationEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/evaluation/exam_names").param("year", "2026").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(post("/api/evaluation/download_excel").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
