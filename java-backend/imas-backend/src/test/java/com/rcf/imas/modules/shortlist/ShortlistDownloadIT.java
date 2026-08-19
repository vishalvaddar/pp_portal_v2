package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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
class ShortlistDownloadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (730003,'BELAGAVI','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (730004,'GOKAK','BLOCK',730003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('DL100000000001','DownloadSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dlseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dlseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% dl') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% dl'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn) VALUES ('DownloadIT-Batch',:c,2025,'Y')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='DownloadIT-Batch'").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 650001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='DownloadIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 650001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DL100000000001'").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% dl'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (730003,730004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='dlseed'").update();
    }

    private void addShortlistedApplicant() {
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, contact_no1, current_institute_dise_code, medium, gmat_score, sat_score, created_by, updated_by)
            VALUES (650001,2025,24010000051,730003,730004,'Asha','f','9000000001','DL100000000001','KANNADA',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (650001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @Test
    void downloadNoDataReturns200Json() throws Exception {
        mvc.perform(get("/api/shortlist-info/download-data/DownloadIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("no_data"))
           .andExpect(jsonPath("$.message").value("No shortlisted students found."));
    }

    @Test
    void downloadReturnsXlsxWithHeadersAndData() throws Exception {
        addShortlistedApplicant();
        byte[] bytes = mvc.perform(get("/api/shortlist-info/download-data/DownloadIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"DownloadIT-Batch_Applicants.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Applicants");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("S. No.");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("NMMS Registration No");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Student Name");
            Row data = sheet.getRow(1);
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Asha");
        }
    }

    @Test
    void downloadMissingBatchIs404() throws Exception {
        mvc.perform(get("/api/shortlist-info/download-data/NoSuchBatch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }
}
