package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistMutateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mtseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mtseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% mutate') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% mutate'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn, medium_filtered_yn) VALUES ('MutateIT-Batch',:c,2025,'N','N')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='MutateIT-Batch'").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, contact_no1, medium, gmat_score, sat_score, created_by, updated_by)
            VALUES (640001,2025,24010000041,'Stu','f','9000000001',NULL,55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (640001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 640001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='MutateIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 640001").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% mutate'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='mtseed'").update();
    }

    @Test
    void bulkUpdateAppliesMediumStatusAndFreezes() throws Exception {
        String body = """
            {"batchId":%d,"updates":[{"applicant_id":640001,"selected_medium":"KANNADA","status":"Y"}]}
            """.formatted(batchId);
        mvc.perform(post("/api/shortlist-info/bulk-update-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Medium decisions updated successfully"));

        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=640001").query(String.class).single();
        assertThat(med).isEqualTo("KANNADA");
        String flags = jdbc.sql("SELECT frozen_yn || medium_filtered_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(flags).isEqualTo("YY");   // Step 3 sets both flags
    }

    @Test
    void bulkUpdateMissingDataIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/bulk-update-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"batchId\":" + batchId + "}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing data"));
    }

    @Test
    void resetMediumsNullsMediumWhenNotMediumFiltered() throws Exception {
        jdbc.sql("UPDATE pp.applicant_primary_info SET medium='KANNADA' WHERE applicant_id=640001").update();
        mvc.perform(post("/api/shortlist-info/reset-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Medium filtering reset successfully."));
        // null-safe read: query(String.class).optional() would collapse a SQL NULL to Optional.empty(),
        // so use a row mapper + list().get(0) to distinguish "row exists, value NULL" from "no row".
        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=640001").query((rs, i) -> rs.getString(1)).list().get(0);
        assertThat(med).isNull();
    }

    @Test
    void resetMediumsFailsWhenMediumFiltered() throws Exception {
        jdbc.sql("UPDATE pp.shortlist_batch SET medium_filtered_yn='Y' WHERE shortlist_batch_id=:b").param("b", batchId).update();
        mvc.perform(post("/api/shortlist-info/reset-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Reset failed. Batch may be frozen."));
    }

    @Test
    void deleteBatchCascadesAndReturnsMessage() throws Exception {
        mvc.perform(delete("/api/shortlist-info/delete?year=2025").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Shortlist deleted successfully"));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void deleteWithNoBodyReturns404NotFiveHundred() throws Exception {
        // Node destructures req.body -- no body sent leaves shortlistBatchId undefined, the model call
        // matches nothing, and it returns 404 (shortlistInfoController.js:118-124), not a 500 on a required body.
        mvc.perform(delete("/api/shortlist-info/delete?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }

    @Test
    void deleteMissingBatchIs404() throws Exception {
        mvc.perform(delete("/api/shortlist-info/delete?year=2025").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":99999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }
}
