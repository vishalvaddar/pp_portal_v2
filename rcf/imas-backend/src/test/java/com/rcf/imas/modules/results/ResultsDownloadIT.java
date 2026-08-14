package com.rcf.imas.modules.results;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ResultsDownloadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (830001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830003,'BELAGAVI','EDUCATION DISTRICT',830001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830004,'GOKAK BLOCK!','BLOCK',830003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('DL200000000001','DlSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dxseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dxseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "dxseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES (840001,2025,24030000001,830001,830003,830004,'Downloadee','f','KANNADA','9000000001','DL200000000001',88,77,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (840101, 'Download Exam!!', '2025-06-15', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (840001, 840101)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 840001").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 840101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 840001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DL200000000001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (830001,830003,830004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'dxseed'").update();
    }

    @Test
    void downloadByBlocksNoDataIs404() throws Exception {
        mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_state\":999999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found"));
    }

    @Test
    void downloadByBlocksReturnsXlsxWithHeadersFillAndFilename() throws Exception {
        String body = """
            {"division":830001,"district":830003,"blocks":[830004],"app_state":830001}
            """;
        byte[] bytes = mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
           .andReturn().getResponse().getContentAsByteArray();

        String disposition = null;
        var result = mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andReturn();
        disposition = result.getResponse().getHeader("Content-Disposition");
        // block name "GOKAK BLOCK!" -> strip punctuation -> "GOKAK BLOCK" -> spaces to underscore -> "GOKAK_BLOCK"
        assertThat(disposition).isEqualTo("attachment; filename=\"results_KARNATAKA_BELAGAVI_GOKAK_BLOCK.xlsx\"");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Results");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Applicant ID");
            assertThat(header.getCell(20).getStringCellValue()).isEqualTo("Block");
            assertThat(((org.apache.poi.xssf.usermodel.XSSFColor) header.getCell(0).getCellStyle().getFillForegroundColorColor()).getARGBHex()).isEqualToIgnoringCase("FFE6E6FA");
            Row data = sheet.getRow(1);
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Downloadee");   // student_name
            assertThat(data.getCell(7).getStringCellValue()).isEqualTo("N/A");          // pp_exam_cleared fallback
        }
    }

    @Test
    void downloadByExamMissingIdIs400() throws Exception {
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Exam ID is required"));
    }

    @Test
    void downloadByExamNoDataIs404() throws Exception {
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":999999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found for this exam"));
    }

    @Test
    void downloadByExamHasCellDateAndFilenameDateInDifferentFormats() throws Exception {
        var result = mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":840101}"))
           .andExpect(status().isOk())
           .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String disposition = result.getResponse().getHeader("Content-Disposition");
        // exam_name "Download Exam!!" -> "Download_Exam"; date 2025-06-15 -> filename uses ISO/UTC yyyy_MM_dd
        assertThat(disposition).isEqualTo("attachment; filename=\"results_Download_Exam_2025_06_15.xlsx\"");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Exam Results");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(21).getStringCellValue()).isEqualTo("Exam Name");
            assertThat(header.getCell(22).getStringCellValue()).isEqualTo("Exam Date");
            assertThat(((org.apache.poi.xssf.usermodel.XSSFColor) header.getCell(0).getCellStyle().getFillForegroundColorColor()).getARGBHex()).isEqualToIgnoringCase("FFE6F5E6");
            Row data = sheet.getRow(1);
            // cell value uses en-US locale M/d/yyyy (no leading zeros) -- distinct from the ISO filename format above.
            assertThat(data.getCell(22).getStringCellValue()).isEqualTo("6/15/2025");
        }
    }

    @Test
    void downloadEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":1}")).andExpect(status().isForbidden());
    }
}
