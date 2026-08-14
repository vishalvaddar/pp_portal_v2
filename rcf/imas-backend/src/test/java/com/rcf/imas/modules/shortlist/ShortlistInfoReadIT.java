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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistInfoReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid, batchId, criteriaId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (720004,'GOKAK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('siseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='siseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% info') ON CONFLICT (criteria) DO NOTHING").update();
        criteriaId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% info'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, description, criteria_id, shortlisted_year, frozen_yn) VALUES ('InfoIT-Batch','desc',:c,2025,'Y')").param("c", criteriaId).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='InfoIT-Batch'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch_jurisdiction(shortlist_batch_id, juris_code) VALUES (:b, 720004)").param("b", batchId).update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, medium, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (620001,2025,24010000021,720004,720004,'Asha',' f','Kannada','9000000001',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (620001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 620001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name='InfoIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='InfoIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 620001").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% info'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 720004").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='siseed'").update();
    }

    @Test
    void namesForYear() throws Exception {
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasItem("InfoIT-Batch")));
    }

    @Test
    void nonFrozenNamesExcludesFrozen() throws Exception {
        // our only batch is frozen → not present
        mvc.perform(get("/api/shortlist-info/non-frozen-names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.name=='InfoIT-Batch')]").isEmpty());
    }

    @Test
    void countsForYear() throws Exception {
        mvc.perform(get("/api/shortlist-info/counts?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalApplicants").value(1))
           .andExpect(jsonPath("$.totalShortlisted").value(1));   // frozen batch, shortlisted_yn=Y
    }

    @Test
    void detailByName() throws Exception {
        mvc.perform(get("/api/shortlist-info/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("InfoIT-Batch"))
           .andExpect(jsonPath("$.criteria").value("Top 6% info"))
           .andExpect(jsonPath("$.blocks[0]").value("GOKAK"))
           .andExpect(jsonPath("$.totalStudents").value(1))
           .andExpect(jsonPath("$.shortlistedCount").value(1))
           .andExpect(jsonPath("$.isFrozen").value("Yes"));
    }

    @Test
    void detailMissingIs404() throws Exception {
        mvc.perform(get("/api/shortlist-info/NoSuchBatch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }

    @Test
    void showDataHasWeightedScoreDecimal() throws Exception {
        mvc.perform(get("/api/shortlist-info/show-data/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("InfoIT-Batch"))
           .andExpect(jsonPath("$.data[0].student_name").value("Asha"))
           .andExpect(jsonPath("$.data[0].nmms_reg_number").value("24010000021"))
           // weighted = 55*0.70 + 60*0.30 = 38.50 + 18.00 = 56.50 — must NOT truncate to "56"
           .andExpect(jsonPath("$.data[0].weighted_score").value("56.50"));
    }

    @Test
    void catchAllDoesNotSwallowNames() throws Exception {
        // GET /names must hit the names handler, not GET /{shortlistName} with name="names"
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray());
    }

    @Test
    void infoReadsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/shortlist-info/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
