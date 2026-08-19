package com.rcf.imas.modules.classroom;

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
class BatchReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920001,'brAdmin920','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Active-cohort scoping (Firm Decision 4): cohort_number must satisfy year-2021 where year is a
        // plausible 4-digit academic_year prefix (pp.system_config.academic_year format check) -- small
        // numbers only, NOT the 920xxx range used elsewhere in this file.
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, start_date) VALUES (500,'Cohort Active 500','2025-06-01')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, start_date) VALUES (501,'Cohort Other 501','2026-06-01')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920001,'Active Batch',500)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920002,'Other Batch',501)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920002,'brCoordinator920','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (920002,920001)").update();

        jdbc.sql("INSERT INTO pp.system_config(system_config_id, academic_year, phase, is_active) VALUES (920001,'2521-22','ADMISSION',true)").update();
        jdbc.sql("SELECT setval('pp.system_config_id_seq', (SELECT MAX(system_config_id)::bigint FROM pp.system_config))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (920001, 24920000001)").update();
        // NOTE (seed fix vs. plan draft): student_master.applicant_id has a UNIQUE constraint
        // (student_master_applicant_id_key), so the second student row needs its own applicant row --
        // the plan's "share applicant_id 920001" approach violates that constraint.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (920002, 24920000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (920001)").update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (920002)").update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (920001, 920001, 92000001, 'Batch Student 920', 'F', 920001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn)
            VALUES (920002, 920002, 92000002, 'Unassigned Student 920', 'M', 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("920001", "brAdmin920", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.role WHERE role_id = 9001").update();
        jdbc.sql("DELETE FROM pp.user_role WHERE role_id = 9001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.system_config WHERE system_config_id = 920001").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (500,501)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (920001,920002)").update();
    }

    @Test
    void coordinatorsReturnsUsersInBatchCoordinatorRole() throws Exception {
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (9001,'BATCH COORDINATOR','Y')").update();
        jdbc.sql("SELECT setval('pp.role_id_seq', (SELECT MAX(role_id)::bigint FROM pp.role))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (920002,9001)").update();

        mvc.perform(get("/api/batches/coordinators").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("920002"))
           .andExpect(jsonPath("$[0].name").value("brCoordinator920"));
    }

    @Test
    void coordinatorsRoleMissingIs404() throws Exception {
        // role NOT seeded in this test -> role lookup fails
        mvc.perform(get("/api/batches/coordinators").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Coordinator role not found"));
    }

    @Test
    void batchNamesReturnsLabelValuePairs() throws Exception {
        mvc.perform(get("/api/batches/names").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.value=='Active Batch')].label").value("Active Batch"));
    }

    @Test
    void allCohortsReturnsProjectedColumnsOnly() throws Exception {
        mvc.perform(get("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==500)].cohort_name").value("Cohort Active 500"));
    }

    @Test
    void activeCohortsReturnsFullRowWhereEndDateIsNull() throws Exception {
        mvc.perform(get("/api/batches/cohorts/active").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==500)].description").exists()); // SELECT * shape
    }

    @Test
    void studentsUnassignedReturnsStudentsWithoutBatch() throws Exception {
        mvc.perform(get("/api/batches/students/unassigned").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.student_id=='920002')].student_name").value("Unassigned Student 920"));
    }

    @Test
    void studentInfoByEnrIdDuplicatesRegNumberUnderTwoKeys() throws Exception {
        mvc.perform(get("/api/batches/students/92000001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reg_number").value("24920000001"))
           .andExpect(jsonPath("$.nmms_reg_number").value("24920000001"));
    }

    @Test
    void studentInfoByEnrIdNotFoundUsesMessageKey() throws Exception {
        mvc.perform(get("/api/batches/students/99999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student not found"));
    }

    @Test
    void batchesByCohortBatchSideReturnsSelectStarShape() throws Exception {
        // Distinct from classroom-side (batch_id+batch_name only) -- ground truth §7 quirk 5.
        mvc.perform(get("/api/batches/500/batches").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].created_at").exists())
           .andExpect(jsonPath("$[0].medium").exists());
    }

    @Test
    void allBatchesScopedToActiveCohortOnly() throws Exception {
        mvc.perform(get("/api/batches").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.id==920001)]").exists())
           .andExpect(jsonPath("$[?(@.id==920002)]").doesNotExist())
           .andExpect(jsonPath("$[?(@.id==920001)].coordinator_name").value("brCoordinator920"));
    }

    @Test
    void batchByIdReturnsProjectedShape() throws Exception {
        mvc.perform(get("/api/batches/920001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.batch_name").value("Active Batch"))
           .andExpect(jsonPath("$.cohort_name").value("Cohort Active 500"));
    }

    @Test
    void batchByIdNotFoundHasTrailingPeriod() throws Exception {
        mvc.perform(get("/api/batches/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found."));
    }

    @Test
    void studentsInBatchReturnsRows() throws Exception {
        mvc.perform(get("/api/batches/920001/students").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("Batch Student 920"))
           .andExpect(jsonPath("$[0].nmms_reg_number").value("24920000001"));
    }
}
