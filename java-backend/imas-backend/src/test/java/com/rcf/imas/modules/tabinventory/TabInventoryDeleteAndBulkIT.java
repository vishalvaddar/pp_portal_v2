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
class TabInventoryDeleteAndBulkIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (954001,'saAdmin954','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (954001,'Cohort DB954')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (954001,'DB Batch',954001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (95400" + i + ", 2495400000" + i + ")").update();
            jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (95400" + i + ")").update();
        }
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954001,954001,95400001,'Bulk Student One 954','F',954001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954002,954002,95400002,'Bulk Student Two 954','M',954001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954003,954003,95400003,'Bulk Student Three 954','F',954001,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (954001,'BrandA954','ModelA954',954001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 954001: for the deleteTab test, with an open student_issue that must cascade-delete.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (954001,'SN-DB-954001',954001,'ASSIGNED',954001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (954001,954001,CURRENT_DATE,954001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("954001", "saAdmin954", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (SELECT tab_id FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%')").update();
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (SELECT tab_id FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%')").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%'").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 954001 OR brand_name = 'BulkBrand954'").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 954001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 954001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 954001").update();
    }

    @Test
    void deleteTabRemovesRowAndCascadesIssueRows() throws Exception {
        mvc.perform(delete("/api/tabs/954001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Deleted"))
           .andExpect(jsonPath("$.data.tab_id").value("954001"));

        Long remaining = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE tab_id = 954001").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(0L);
        Long remainingIssues = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 954001").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(remainingIssues).isEqualTo(0L);
    }

    @Test
    void deleteTabOnMissingRowStillReturns200AndOmitsDataKey() throws Exception {
        // Node returns `data: rows[0]` -> on a miss rows[0] is undefined and JSON.stringify DROPS the key.
        // The Java 200 body must likewise OMIT `data` entirely (not emit data:null) for byte parity.
        mvc.perform(delete("/api/tabs/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Deleted"))
           .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void bulkCreateInsertsNewTabAndAssignsStudent() throws Exception {
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-1","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"ASSIGNED","enr_id":"95400002","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(1));

        String tabId = jdbc.sql("SELECT tab_id FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-1'").query(String.class).single();
        Long assignedCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = :t::numeric AND student_id = 954002 AND return_date IS NULL")
                .param("t", tabId).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(assignedCount).isEqualTo(1L);
    }

    @Test
    void bulkCreateEmptyDevicesIs400() throws Exception {
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"devices\":[]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Excel is empty"));
    }

    @Test
    void bulkCreateUnknownEnrIdCollectsRowErrorWithNoMessageKeyAndRollsBackWholeBatch() throws Exception {
        // Row 1 is a perfectly valid NEW tab; row 2 references an enr_id that does not exist. PASS 1 must
        // collect the error for row 2 and reject the WHOLE batch (including row 1) -- nothing gets written.
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-2","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"IN_OFFICE","created_by":954001},
              {"rowNumber":3,"serial_number":"SN-DB-BULK-3","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"ASSIGNED","enr_id":"99999999","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").doesNotExist())
           .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.errors[0]", org.hamcrest.Matchers.containsString("99999999")));

        Long rowOneWritten = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-2'").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(rowOneWritten).isEqualTo(0L);
    }

    @Test
    void bulkCreateInvalidStatusIsCollectedAsRowError() throws Exception {
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-4","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"NOT_A_STATUS","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void bulkCreatePass2FailureRollsBackWholeBatchIncludingEarlierRowsInSamePass() throws Exception {
        // Both rows are individually PASS-1-clean (valid status, no FK violations), so PASS 2 runs.
        // Row 1 would insert fine; row 2's brand_name exceeds pp.tab_brand.brand_name's varchar(15) and
        // blows up mid-PASS-2 -- the whole @Transactional method must roll back, including row 1's
        // already-applied insert (tab_brand + tab_inventory), since both writes share ONE connection.
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-5","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"IN_OFFICE","created_by":954001},
              {"rowNumber":3,"serial_number":"SN-DB-BULK-6","brand_name":"ThisBrandNameIsWayTooLongForVarchar15","model_name":"BulkModel954",
               "status":"IN_OFFICE","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false));

        Long rowOneWritten = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-5'").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(rowOneWritten).isEqualTo(0L);
        Long rowTwoWritten = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-6'").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(rowTwoWritten).isEqualTo(0L);
    }
}
