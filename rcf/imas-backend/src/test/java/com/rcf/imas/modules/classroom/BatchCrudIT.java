package com.rcf.imas.modules.classroom;

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
class BatchCrudIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940001,'bcAdmin940','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940002,'bcCoordA940','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940003,'bcCoordB940','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (940001,'Cohort BC940')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940001,'Pre-existing Batch',940001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (940001, 24940000001)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("940001", "bcAdmin940", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE applicant_id = 940001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 940001").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (SELECT batch_id FROM pp.batch WHERE cohort_number = 940001)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE cohort_number = 940001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 940001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (940001,940002,940003)").update();
    }

    @Test
    void createBatchSucceedsAndAssignsCoordinator() throws Exception {
        String body = """
            {"batch_name":"New BC Batch","cohort_number":940001,"coordinator_id":940002}
            """;
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.batch_name").value("New BC Batch"))
           .andExpect(jsonPath("$.batch_id").exists());

        Integer coordCount = jdbc.sql("""
                SELECT COUNT(*) FROM pp.batch_coordinator_batches bcb
                JOIN pp.batch b ON b.batch_id = bcb.batch_id
                WHERE b.batch_name = 'New BC Batch' AND bcb.user_id = 940002
                """).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(coordCount).isEqualTo(1);
    }

    @Test
    void createBatchMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("batch_name and cohort_number are required"));
    }

    @Test
    void createBatchDuplicateIs409() throws Exception {
        String body = """
            {"batch_name":"Pre-existing Batch","cohort_number":940001}
            """;
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Batch already exists for this cohort."));
    }

    @Test
    void updateBatchResyncsCoordinatorAndSilentlyDropsBatchStatus() throws Exception {
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (940002, 940001)").update();

        String body = """
            {"batch_name":"Renamed Batch","cohort_number":940001,"coordinator_id":940003,"batch_status":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/940001").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.batch_name").value("Renamed Batch"));
        // batch_status has no column to persist to -- the 200 above with no error IS the pinning assertion
        // (Firm Decision 3): accepted, ignored, no failure.

        Integer oldCoordGone = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id=940001 AND user_id=940002")
                .query(Integer.class).single();
        Integer newCoordPresent = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id=940001 AND user_id=940003")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(oldCoordGone).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(newCoordPresent).isEqualTo(1);
    }

    @Test
    void updateBatchMissingFieldsIs400() throws Exception {
        mvc.perform(put("/api/batches/940001").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields"));
    }

    @Test
    void updateBatchUnknownIdIs404NoTrailingPeriod() throws Exception {
        String body = """
            {"batch_name":"X","cohort_number":940001}
            """;
        mvc.perform(put("/api/batches/999999999").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found"));
    }

    @Test
    void updateBatchDuplicateNameInCohortIs409() throws Exception {
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940002,'Other Batch',940001)").update();
        String body = """
            {"batch_name":"Pre-existing Batch","cohort_number":940001}
            """;
        mvc.perform(put("/api/batches/940002").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Duplicate batch name in cohort."));
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 940002").update();
    }

    @Test
    void deleteBatchSucceedsAndCleansCoordinators() throws Exception {
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940003,'Deletable Batch',940001)").update();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (940002, 940003)").update();

        mvc.perform(delete("/api/batches/940003").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Batch deleted successfully"))
           .andExpect(jsonPath("$.deleted.batch_name").value("Deletable Batch"));

        Integer coordRows = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id = 940003")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(coordRows).isEqualTo(0);
    }

    @Test
    void deleteBatchUnknownIdIs404() throws Exception {
        mvc.perform(delete("/api/batches/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found"));
    }

    @Test
    void deleteBatchWithAssignedStudentSurfacesRawFkViolationAs500() throws Exception {
        // Firm Decision 6: no pre-check -- the FK violation itself propagates uncaught to
        // GlobalExceptionHandler's generic fallback.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (940001, 940001, 94000001, 'FK Guard Student', 'F', 940001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        mvc.perform(delete("/api/batches/940001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"));

        // batch row must still exist -- the transaction rolled back (Firm Decision 5 improvement)
        Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.batch WHERE batch_id = 940001").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillThere).isEqualTo(1);
    }
}
