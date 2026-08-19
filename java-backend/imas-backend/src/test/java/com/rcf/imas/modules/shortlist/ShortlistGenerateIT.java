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
class ShortlistGenerateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;
    long criteriaId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (710001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (710003,'BELAGAVI','EDUCATION DISTRICT',710001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (710004,'GOKAK','BLOCK',710003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('slseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='slseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "slseed", "ADMIN");
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 8% students per block') ON CONFLICT (criteria) DO NOTHING").update();
        criteriaId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 8% students per block'").query(Long.class).single();

        // 5 applicants in block 710004, year 2025 — weighted = 0.7*gmat + 0.3*sat.
        // ids/scores chosen so ranking is deterministic; top 8% keeps only the single top row (5 rows → only rank 0 <= 0.08).
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block, student_name, father_name, medium, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES
              (610001,2025,24010000001,710001,710003,710004,'A','f','Kannada','9000000001',90,90,:u,:u),
              (610002,2025,24010000002,710001,710003,710004,'B','f','Kannada','9000000002',80,80,:u,:u),
              (610003,2025,24010000003,710001,710003,710004,'C','f','Kannada','9000000003',70,70,:u,:u),
              (610004,2025,24010000004,710001,710003,710004,'D','f','Kannada','9000000004',60,60,:u,:u),
              (610005,2025,24010000005,710001,710003,710004,'E','f','Kannada','9000000005',50,50,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id BETWEEN 610001 AND 610005").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name LIKE 'GenIT%'").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name LIKE 'GenIT%'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id BETWEEN 610001 AND 610005").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 8% students per block'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (710001,710003,710004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='slseed'").update();
    }

    private String body(String name) {
        return """
            {"criteriaId":%d,"name":"%s","description":"d","year":2025,
             "userId":%d,"locations":{"state":"KARNATAKA","district":"BELAGAVI","blocks":["GOKAK"]}}
            """.formatted(criteriaId, name, uid);
    }

    @Test
    void startShortlistRanksTopSliceAndPersistsBatch() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-A")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.shortlistBatchId").isNotEmpty())
           .andExpect(jsonPath("$.shortlistedCountInBatch").value(1))   // 5 rows, top 8% → only PERCENT_RANK 0 (the single top applicant)
           .andExpect(jsonPath("$.totalApplicantsCount").value("5"))    // COUNT() → String
           .andExpect(jsonPath("$.totalShortlistedInBlocks").value("1"));

        Long shortlisted = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE applicant_id = 610001 AND shortlisted_yn='Y'").query(Long.class).single();
        assertThat(shortlisted).isEqualTo(1);   // the top applicant (weighted 90) was chosen
    }

    @Test
    void duplicateNonFrozenBatchForBlockIs409() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-B"))).andExpect(status().isOk());
        // second run over the same block/year while the first is non-frozen → 409
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-C")))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Shortlists already exist for these blocks in 2025. Please delete them first."));
    }

    @Test
    void missingFieldsIs400() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"name\":\"x\",\"year\":2025,\"locations\":{\"state\":\"\",\"district\":\"\",\"blocks\":[]}}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Required fields missing."));
    }

    @Test
    void unknownCriteriaIs500WithMessage() throws Exception {
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 3% weird') ON CONFLICT (criteria) DO NOTHING").update();
        long badId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 3% weird'").query(Long.class).single();
        String b = """
            {"criteriaId":%d,"name":"GenIT-D","description":"d","year":2025,"userId":%d,
             "locations":{"state":"KARNATAKA","district":"BELAGAVI","blocks":["GOKAK"]}}
            """.formatted(badId, uid);
        try {
            mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                    .contentType(APPLICATION_JSON).content(b))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.error").value("Criteria \"top 3% weird\" logic not implemented."));
        } finally {
            jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name='GenIT-D'").update();
            jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='GenIT-D'").update();
            jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 3% weird'").update();
        }
    }
}
