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
class BatchStudentAssignmentIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (950001,'saAdmin950','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (950001,'Cohort SA950')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (950001,'SA Batch',950001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (950002,'Other SA Batch',950001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (950001, 24950000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (950002, 24950000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (950001)").update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (950002)").update();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn)
            VALUES (950001, 950001, 95000001, 'Assignable Student', 'F', 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (950002, 950002, 95000002, 'Status Student', 'M', 950001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("950001", "saAdmin950", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 950001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 950001").update();
    }

    @Test
    void addStudentsToBatchBulkAssignsAndReturnsCount() throws Exception {
        String body = """
            {"student_ids":[950001]}
            """;
        mvc.perform(post("/api/batches/950001/add-students").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Students successfully assigned to batch"))
           .andExpect(jsonPath("$.count").value(1));

        Integer newBatch = jdbc.sql("SELECT batch_id FROM pp.student_master WHERE student_id = 950001").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(newBatch).isEqualTo(950001);
    }

    @Test
    void addStudentsMissingIdsIs400() throws Exception {
        mvc.perform(post("/api/batches/950001/add-students").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void removeStudentsIgnoresBatchIdInBodyAndNullsRegardless() throws Exception {
        // batch_id in the body is a MISMATCHED / wrong value on purpose -- ground truth §7 quirk 10:
        // the server never scopes removal by it, only by student_ids.
        String body = """
            {"batch_id":950002,"student_ids":[950002]}
            """;
        mvc.perform(post("/api/batches/students/remove").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Students removed from batch successfully"))
           .andExpect(jsonPath("$.count").value(1));

        Object batchId = jdbc.sql("SELECT batch_id FROM pp.student_master WHERE student_id = 950002").query(Integer.class).optional().orElse(null);
        org.assertj.core.api.Assertions.assertThat(batchId).isNull();
    }

    @Test
    void removeStudentsMissingIdsIs400() throws Exception {
        mvc.perform(post("/api/batches/students/remove").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("student_ids are required"));
    }

    @Test
    void updateStudentStatusIgnoresBatchIdInUrlAndResolvesViaEnrId() throws Exception {
        // batchId in the URL path is a WRONG/unrelated id on purpose -- ground truth §7 quirk 9: it is never
        // used to scope the update, only enr_id matters.
        String body = """
            {"active_yn":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/999999999/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student status updated successfully"));

        String newStatus = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 950002").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(newStatus.trim()).isEqualTo("INACTIVE");
    }

    @Test
    void updateStudentStatusMissingActiveYnIs400() throws Exception {
        mvc.perform(put("/api/batches/950001/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("active_yn is required"));
    }

    @Test
    void updateStudentStatusUnknownEnrIdIs404WithErrorKey() throws Exception {
        String body = """
            {"active_yn":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/950001/students/99999999/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Student not found")); // "error" key -- distinct from endpoint #18's "message" key
    }

    @Test
    void updateStudentStatusEmptyActiveYnIsAcceptedNotRejectedAs400() throws Exception {
        // Node: `if (active_yn == null)` only rejects null/undefined -- "" is NOT null, so it flows through
        // to the DB (batchController.js:404-405). The active_yn CHECK constraint (ACTIVE/INACTIVE only) then
        // rejects "" the same way it rejects "Y" -- a 500 with err.message under "details", NOT the 400
        // "active_yn is required" a stricter isBlank() guard would incorrectly produce.
        String body = """
            {"active_yn":""}
            """;
        mvc.perform(put("/api/batches/950001/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void updateStudentStatusInvalidValueLeaksErrMessageUnderDetails() throws Exception {
        // active_yn CHECK constraint only allows ACTIVE/INACTIVE -- an invalid value throws a raw PG
        // check-violation, caught and surfaced with err.message under "details" (ground truth §7 quirk 11,
        // the ONE handler in this module that does this).
        String body = """
            {"active_yn":"Y"}
            """; // classroom's convention leaking into a student_master column -- exactly the quirk scenario
        mvc.perform(put("/api/batches/950001/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }
}
