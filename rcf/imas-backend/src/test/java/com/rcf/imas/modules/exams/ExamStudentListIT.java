package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamStudentListIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('slseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='slseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "slseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, contact_person, active_yn)
            VALUES (84001,'SL Centre','Mr Contact','Y') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (840201,'SL Exam','2027-06-01','09:00:00','11:00:00','2027',84001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('DISE001','SL School') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (840003,'SL BLOCK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, current_institute_dise_code,
                student_name, father_name, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (840101,2027,24084000001,840003,'DISE001','HighScorer','f','9000000001',75,80,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, current_institute_dise_code,
                student_name, father_name, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (840102,2027,24084000002,840003,'DISE001','LowScorer','f','9000000002',40,55,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (840101, 840201, '27000001')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (840102, 840201, '27000002')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = 840201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (840101,840102)").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 840201").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DISE001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 840003").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 84001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'slseed'").update();
    }

    @Test
    void studentListMissingExamIs404() throws Exception {
        mvc.perform(get("/api/exams/999999999/student-list").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No students found for this exam."));
    }

    @Test
    void studentListBuildsWorkbookWithHeaderInfoAndScoreColoring() throws Exception {
        byte[] bytes = mvc.perform(get("/api/exams/840201/student-list").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"SL_Exam_Calling_List.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Student Calling List");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("STUDENT CALLING LIST");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Exam Name:");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("SL Exam");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("Mr Contact"); // Contact Person

            int headerRowIdx = 8; // examInfoData has 10 rows (0..9); header row is at index equal to examInfoData.size()
            Row header = sheet.getRow(headerRowIdx);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Sl. No.");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Student Name");

            Row highScorer = sheet.getRow(headerRowIdx + 1);
            assertThat(highScorer.getCell(3).getStringCellValue()).isEqualTo("HighScorer");
            assertThat(highScorer.getCell(4).getStringCellValue()).isEqualTo("SL School"); // dise_code -> institute_name
            assertThat(highScorer.getCell(5).getStringCellValue()).isEqualTo("SL BLOCK");  // nmms_block -> juris_name
            Cell gmatHigh = highScorer.getCell(8);
            assertThat(((XSSFColor) gmatHigh.getCellStyle().getFillForegroundColorColor()).getARGBHex()).isEqualToIgnoringCase("FFE6F3E6"); // >=70 green

            Row lowScorer = sheet.getRow(headerRowIdx + 2);
            Cell gmatLow = lowScorer.getCell(8);
            assertThat(((XSSFColor) gmatLow.getCellStyle().getFillForegroundColorColor()).getARGBHex()).isEqualToIgnoringCase("FFFFE6E6"); // <70 red

            Row totalRow = sheet.getRow(headerRowIdx + 2 + 2); // blank row + total row
            assertThat(totalRow.getCell(0).getStringCellValue()).isEqualTo("Total Students: 2");

            assertThat(wb.getSheet("Score Summary")).isNotNull(); // present because scores exist
        }
    }

    @Test
    void studentListEndpointIsAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/840201/student-list").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
