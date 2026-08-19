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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CustomListExportIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('exseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='exseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "exseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (7201,'Cohort EX') ON CONFLICT (cohort_number) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number, medium) VALUES (7201,'Batch EX',7201,'KANNADA') ON CONFLICT (batch_id) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (720101,2025,24072000001,'ExportKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, batch_id, active_yn) VALUES (720201,720101,'ExportKid',7201,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.custom_list(list_id, list_name) VALUES (7201,'Export List') ON CONFLICT (list_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.custom_list_id_seq', (SELECT MAX(list_id)::bigint FROM pp.custom_list))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_students(list_id, student_id) VALUES (7201, 720201)").update();
        // Node adds the ID/Name columns only if student_id/student_name are among the list's fields
        // (customListController.js:142-150). Seed all three so hasId/hasName are true and the batch_id column is exercised.
        jdbc.sql("INSERT INTO pp.field_master(field_id, tab_name, col_name) VALUES (7201,'pp.student_master','batch_id'),(7202,'pp.student_master','student_id'),(7203,'pp.student_master','student_name') ON CONFLICT (field_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.field_id_seq', (SELECT MAX(field_id)::bigint FROM pp.field_master))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_fields(list_id, field_id) VALUES (7201,7201),(7201,7202),(7201,7203)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = 7201").update();
        jdbc.sql("DELETE FROM pp.field_master WHERE field_id IN (7201,7202,7203)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 720201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 720101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 7201").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 7201").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'exseed'").update();
    }

    @Test
    void downloadXlsxHasNameColumnAndBatchNameMapping() throws Exception {
        byte[] bytes = mvc.perform(get("/api/custom-list/download-xlsx/7201").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Disposition", "attachment; filename=\"Export List.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Student List");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Student ID");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Student Name");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Batch Name"); // display_name for batch_id
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("ExportKid");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Batch EX"); // batch_id -> batch_name mapping
        }
    }

    @Test
    void downloadPdfReturnsApplicationPdfWithQuotedFilename() throws Exception {
        mvc.perform(get("/api/custom-list/download-pdf/7201").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Type", "application/pdf"))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Disposition", "attachment; filename=\"Export List.pdf\""));
    }

    @Test
    void exportEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/custom-list/download-xlsx/7201").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/custom-list/download-pdf/7201").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
