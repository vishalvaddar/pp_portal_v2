package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryTabReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (952001,'saAdmin952','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Two cohorts + two batches -- one holder in each, so the tab hands over across cohorts.
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (952001,'CohortA952')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (952002,'CohortB952')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (952001,'Batch A 952',952001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (952002,'Batch B 952',952002)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (95200" + i + ", 2495200000" + i + ")").update();
            jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (95200" + i + ")").update();
        }
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        // student 952001: batch A (cohort A). student 952002: batch B (cohort B). student 952003: NO batch
        // (batch_id NULL) -- used to demonstrate getTabMovementReport's INNER JOIN drop vs getAllTabs' LEFT.
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (952001,952001,95200001,'Holder A 952','F',952001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (952002,952002,95200002,'Holder B 952','M',952002,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn) VALUES (952003,952003,95200003,'No Batch 952','F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (952001,'BrandA952','ModelA952',952001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 952001: hands over from student 952001 (cohort A) to student 952002 (cohort B). Tab is
        // currently ASSIGNED to student 952002 (return_date NULL on the second row).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952001,'SN-TR-952001',952001,'ASSIGNED',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, return_date, created_by) VALUES (952001,952001,'2026-01-01','2026-02-01',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (952001,952002,'2026-02-01',952001)").update();

        // tab 952002: currently ASSIGNED to student 952003 (no batch) -- appears in getAllTabs (LEFT JOIN)
        // but must be ABSENT from getTabMovementReport (INNER JOIN batch/cohort silently drops it).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952002,'SN-TR-952002',952001,'ASSIGNED',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (952002,952003,'2026-02-01',952001)").update();

        // tab 952003: IN_OFFICE, no assignment history at all.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952003,'SN-TR-952003',952001,'IN_OFFICE',952001)").update();

        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("952001", "saAdmin952", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 952001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (952001,952002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (952001,952002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 952001").update();
    }

    @Test
    void allTabsProjectsCaseColumnsAndIncludesNoBatchHolder() throws Exception {
        mvc.perform(get("/api/tabs").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952001')].assigned_to").value("Holder B 952"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952001')].assignment_category").value("STUDENT"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952002')].assigned_to").value("No Batch 952"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952003')].assigned_to").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void tabByIdReturns200ForExistingAnd404ForMissing() throws Exception {
        mvc.perform(get("/api/tabs/952003").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.serial_number").value("SN-TR-952003"));

        mvc.perform(get("/api/tabs/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void tabHistoryUnionsStudentAndStaffRowsOrderedByAssignmentDateDesc() throws Exception {
        mvc.perform(get("/api/tabs/952001/history").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(2)))
           .andExpect(jsonPath("$.data[0].name").value("Holder B 952"))
           .andExpect(jsonPath("$.data[1].name").value("Holder A 952"));
    }

    @Test
    void movementReportShowsTransferAndDropsNoBatchRow() throws Exception {
        mvc.perform(get("/api/tabs/movement-report").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.data[0].previous_holder").value("Holder A 952"))
           .andExpect(jsonPath("$.data[0].from_cohort").value("CohortA952"))
           .andExpect(jsonPath("$.data[0].new_holder").value("Holder B 952"))
           .andExpect(jsonPath("$.data[0].to_cohort").value("CohortB952"));
    }

    @Test
    void movementReportFiltersByFromAndToCohort() throws Exception {
        mvc.perform(get("/api/tabs/movement-report?fromCohort=CohortA952&toCohort=CohortB952")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));

        mvc.perform(get("/api/tabs/movement-report?fromCohort=NoSuchCohort&toCohort=ALL")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(0)));
    }
}
