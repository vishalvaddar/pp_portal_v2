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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BatchSimpleWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (930001,'bwAdmin930','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930001,'Cohort BW930')").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (930001,'Existing Batch 930',930001)").update();

        // Reserved small cohort_number range for createCohort's server-derived year math (77/78/79, distinct
        // from every other test class's range -- same constraint as Task 2's 500/501).
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (77,'Existing Cohort Name 930')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (78,'Different Name 930')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("930001", "bwAdmin930", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch WHERE cohort_number = 930001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (930001,77,78,79)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 930001").update();
    }

    @Test
    void addBatchNameSucceedsWithNewNameUnderCohort() throws Exception {
        String body = """
            {"batch_name":"New Batch 930","cohort_number":930001,"created_by":930001}
            """;
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Batch created successfully"))
           .andExpect(jsonPath("$.batch.batch_name").value("New Batch 930"));
    }

    @Test
    void addBatchNameConflictIsSilent200NotAnError() throws Exception {
        String body = """
            {"batch_name":"Existing Batch 930","cohort_number":930001,"created_by":930001}
            """;
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Batch name already exists for this cohort"));

        Integer count = jdbc.sql("SELECT COUNT(*) FROM pp.batch WHERE cohort_number = 930001 AND batch_name = 'Existing Batch 930'")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1); // no duplicate row inserted
    }

    @Test
    void addBatchNameMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void createCohortDerivesCohortNumberFromStartDateYear() throws Exception {
        String body = """
            {"cohort_name":"New Cohort 930","start_date":"2100-06-01","description":"desc"}
            """;
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Cohort created successfully"))
           .andExpect(jsonPath("$.data.cohort_number").value(79)) // 2100 - 2021
           .andExpect(jsonPath("$.data.cohort_name").value("New Cohort 930"));
    }

    @Test
    void createCohortDuplicateNameIs409() throws Exception {
        String body = """
            {"cohort_name":"Existing Cohort Name 930","start_date":"2200-06-01"}
            """;
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Cohort name already exists"));
    }

    @Test
    void createCohortDuplicateYearIs409() throws Exception {
        String body = """
            {"cohort_name":"Brand New Name 930","start_date":"2099-06-01"}
            """; // 2099 - 2021 = 78, already used by "Different Name 930"
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Cohort for year 2099 already exists."));
    }

    @Test
    void createCohortMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }
}
