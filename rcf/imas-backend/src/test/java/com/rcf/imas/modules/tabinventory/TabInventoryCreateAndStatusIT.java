package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryCreateAndStatusIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (953001,'saAdmin953','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (953001,'Cohort TS953')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (953001,'TS Batch',953001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (953001, 24953000001)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (953001)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (953001,953001,95300001,'Status Student 953','F',953001,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (953001,'BrandA953','ModelA953',953001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 953001: IN_OFFICE, target of the changeTabStatus tests.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (953001,'SN-CS-953001',953001,'IN_OFFICE',953001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("953001", "saAdmin953", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (953001,953002)").update();
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (953001,953002)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (953001,953002) OR serial_number LIKE 'SN-CS-%'").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 953001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 953001").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id = 953001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 953001").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 953001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 953001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 953001").update();
    }

    @Test
    void createTabDefaultsStatusToInOffice() throws Exception {
        String body = """
            {"serial_number":"SN-CS-953002","brand_id":953001,"created_by":953001}
            """;
        mvc.perform(post("/api/tabs").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Tablet created"))
           .andExpect(jsonPath("$.data.tab_id").exists());

        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE serial_number = 'SN-CS-953002'")
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("IN_OFFICE");
    }

    @Test
    void createTabMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/tabs").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Required fields missing."));
    }

    @Test
    void changeStatusToAssignedWritesStudentIssueRow() throws Exception {
        String body = """
            {"status":"ASSIGNED","assignment_type":"STUDENT","student_id":953001,"user_id":953001}
            """;
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Status updated successfully"));

        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("ASSIGNED");
        Long openCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 953001 AND student_id = 953001 AND return_date IS NULL")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(openCount).isEqualTo(1L);
    }

    @Test
    void changeStatusInvalidValueIsCleanBadRequestBeforeSql() throws Exception {
        String body = """
            {"status":"NOT_A_REAL_STATUS"}
            """;
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Invalid status: NOT_A_REAL_STATUS"));

        // status column must be untouched -- the invalid value never reached SQL.
        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("IN_OFFICE");
    }

    @Test
    void settingInOfficeDoesNotAutoCloseOpenStudentIssue() throws Exception {
        // First assign, leaving an open student_issue row.
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ASSIGNED\",\"assignment_type\":\"STUDENT\",\"student_id\":953001,\"user_id\":953001}"))
           .andExpect(status().isOk());

        // Then set IN_OFFICE directly (skipping RETURNED) -- ground truth §7 quirk 4: this must NOT close
        // the open student_issue row.
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_OFFICE\"}"))
           .andExpect(status().isOk());

        String tabStatus = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(tabStatus.trim()).isEqualTo("IN_OFFICE");
        Long stillOpen = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 953001 AND student_id = 953001 AND return_date IS NULL")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(stillOpen).isEqualTo(1L);
    }
}
