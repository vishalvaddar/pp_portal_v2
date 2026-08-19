package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorScopedReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965201,'coordUser965201','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Two cohorts: 965201 (coordinator IS assigned, via batch 965201), 965202 (coordinator is NOT assigned).
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965201,'Scoped Cohort 965201')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965202,'Unassigned Cohort 965202')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965201,'Assigned Batch 965201',965201)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965202,'Unassigned Batch 965202',965202)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965201,965201)").update();

        // Students: 965211 ACTIVE in assigned batch, 965212 INACTIVE in assigned batch, 965213 ACTIVE in
        // the UNASSIGNED batch (must never appear in any coordinator-scoped result).
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965211,'Active Assigned Student 965211',965201,'F','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965212,'Inactive Assigned Student 965212',965201,'M','INACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965213,'Unassigned Batch Student 965213',965202,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965201", "coordUser965201", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965211,965212,965213)").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965201").update();
    }

    @Test
    void cohortsReturnsOnlyAssignedCohort() throws Exception {
        mvc.perform(get("/api/coordinator/cohorts").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].cohort_number").value(965201));
    }

    @Test
    void batchesNoFilterReturnsOnlyAssignedBatch() throws Exception {
        mvc.perform(get("/api/coordinator/batches").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(965201));
    }

    @Test
    void batchesFilteredByCohortNumber() throws Exception {
        mvc.perform(get("/api/coordinator/batches").param("cohort_number", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(965201));
    }

    @Test
    void studentsCohortAndBatchReturnsBothStatuses() throws Exception {
        mvc.perform(get("/api/coordinator/students")
                .param("cohortNumber", "965201").param("batchId", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[?(@.student_id=='965211')]").exists())
           .andExpect(jsonPath("$[?(@.student_id=='965212')]").exists());
    }

    @Test
    void studentsNoFiltersReturnsCoordinatorScopedOnly() throws Exception {
        mvc.perform(get("/api/coordinator/students").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[?(@.student_id=='965213')]").doesNotExist());
    }

    @Test
    void studentsCohortOnlyFiltersByCohort() throws Exception {
        mvc.perform(get("/api/coordinator/students").param("cohortNumber", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void studentsBlankCohortParamFallsThroughToScopedListNot500() throws Exception {
        // A cleared filter sends `?cohortNumber=`. Node's `if (cohortNumber && ...)` is falsy for "", so it
        // falls through to the coordinator-scoped list (200). Must NOT hit ''::integer -> 500.
        mvc.perform(get("/api/coordinator/students").param("cohortNumber", "")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[?(@.student_id=='965213')]").doesNotExist());
    }

    @Test
    void batchesBlankCohortParamReturnsAllAssignedNot500() throws Exception {
        // `?cohort_number=` (cleared) is falsy in Node -> getAllBatchesForCoordinator (200), not ''::integer.
        mvc.perform(get("/api/coordinator/batches").param("cohort_number", "")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(965201));
    }

    @Test
    void studentsIsAttendanceModeReturnsStrictlyActiveOnlyWithNarrowShape() throws Exception {
        mvc.perform(get("/api/coordinator/students")
                .param("cohortNumber", "965201").param("batchId", "965201").param("isAttendance", "true")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].student_id").value("965211"))
           .andExpect(jsonPath("$[0].father_name").doesNotExist()); // narrow column set, unlike the other branches
    }
}
