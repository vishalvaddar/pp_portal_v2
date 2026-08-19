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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistFreezeIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('fzseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='fzseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% freeze') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% freeze'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn) VALUES ('FreezeIT-Batch',:c,2025,'N')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='FreezeIT-Batch'").query(Long.class).single();

        // single-medium GOVERNMENT school 'ss1' (Kannada) → auto-set + kept; multi-medium school 'ms1' → conflict
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('SS100000000001','SingleGov','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('MS100000000001','MultiMed','PRIVATE UNAIDED') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute_medium(dise_code, medium) VALUES ('SS100000000001','KANNADA')").update();
        jdbc.sql("INSERT INTO pp.institute_medium(dise_code, medium) VALUES ('MS100000000001','ENGLISH'),('MS100000000001','KANNADA')").update();

        // applicant 630001 at single-medium school (medium NULL → will be auto-set to KANNADA, GOVERNMENT → kept)
        // applicant 630002 at multi-medium school (medium NULL) → conflict
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES
              (630001,2025,24010000031,'SingleStu','f','9000000001','SS100000000001',55,60,:u,:u),
              (630002,2025,24010000032,'MultiStu','f','9000000002','MS100000000001',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (630001,'Y',:b,:u,:u),(630002,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id IN (630001,630002)").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='FreezeIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (630001,630002)").update();
        jdbc.sql("DELETE FROM pp.institute_medium WHERE dise_code IN ('SS100000000001','MS100000000001')").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code IN ('SS100000000001','MS100000000001')").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% freeze'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='fzseed'").update();
    }

    @Test
    void freezeWithMultiMediumConflictReturns400RequiresCorrection() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.requiresCorrection").value(true))
           .andExpect(jsonPath("$.students[?(@.applicant_id=='630002')]").exists())
           .andExpect(jsonPath("$.students[?(@.applicant_id=='630002')].supported_mediums").exists());

        // batch stays non-frozen; single-medium student got auto-set to KANNADA
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(frozen).isEqualTo("N");
        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=630001").query(String.class).single();
        assertThat(med).isEqualTo("KANNADA");
    }

    @Test
    void freezeSucceedsWhenNoConflicts() throws Exception {
        // remove the multi-medium student so no conflicts remain
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 630002").update();
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Shortlist filtered and frozen successfully"));
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(frozen).isEqualTo("Y");
    }

    @Test
    void freezeMissingBatchIdIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Batch ID required"));
    }

    @Test
    void freezeMissingMediumsIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Select at least one medium"));
    }
}
