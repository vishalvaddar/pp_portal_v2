package com.rcf.imas.modules.merge;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergePreviewIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (970010,'GOKAK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        // phase1: two apps, block 970010, district 970001, year 2025
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, father_name, student_name_key)
            VALUES (7101,'2025',970001,970010,'Asha Rani','Ravi','asharani'),
                   (7102,'2025',970001,970010,'Kiran Kumar','Suresh','kirankumar')
            """).update();
        // phase2: one match for Asha (1:1), TWO matches for Kiran (conflict)
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (8101,'2025',970001,970010,'24010000001','Asha Rani','55','60','asharani'),
                   (8102,'2025',970001,970010,'24010000002','Kiran Kumar','50','50','kirankumar'),
                   (8103,'2025',970001,970010,'24010000003','Kiran Kumar','40','40','kirankumar')
            """).update();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase1_applications_id_seq', (SELECT MAX(id)::bigint FROM pp.stg_nmms_phase1_applications))").query(Long.class).single();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase2_results_result_stg_id_seq', (SELECT MAX(result_stg_id)::bigint FROM pp.stg_nmms_phase2_results))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 970001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 970001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 970010").update();
    }

    @Test
    void previewCountsMappedAndConflicts() throws Exception {
        mvc.perform(post("/api/merge/preview-merge").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"970001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.summary.total_students").value(2))
           .andExpect(jsonPath("$.summary.mapped").value(1))         // Asha 1:1
           .andExpect(jsonPath("$.summary.conflicts").value(1))      // Kiran 2 candidates
           .andExpect(jsonPath("$.blockWise.GOKAK").isArray())
           .andExpect(jsonPath("$.blockWise.GOKAK.length()").value(2));
    }

    @Test
    void previewIsAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(post("/api/merge/preview-merge").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"970001\"}"))
           .andExpect(status().isForbidden());
    }
}
