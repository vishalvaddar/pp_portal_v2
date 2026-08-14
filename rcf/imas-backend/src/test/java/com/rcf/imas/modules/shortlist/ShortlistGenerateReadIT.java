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
class ShortlistGenerateReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        // state 700001 → division 700002 → education district 700003 → block 700004
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700002,'BELGAUM DIV','DIVISION',700001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700003,'BELAGAVI','EDUCATION DISTRICT',700002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700004,'GOKAK','BLOCK',700003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria_id, criteria) VALUES (91,'Top 6% students per block') ON CONFLICT (criteria) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.criteria_id_seq', (SELECT MAX(criteria_id)::bigint FROM pp.shortlist_criteria))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria_id = 91").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (700001,700002,700003,700004)").update();
    }

    @Test
    void allStates() throws Exception {
        mvc.perform(get("/api/shortlist/generate/allstates").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.juris_name=='KARNATAKA')].juris_code").value(org.hamcrest.Matchers.hasItem("700001")));
    }

    @Test
    void divisionsByState() throws Exception {
        mvc.perform(get("/api/shortlist/generate/divisions/KARNATAKA").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("BELGAUM DIV"))
           .andExpect(jsonPath("$[0].juris_code").value("700002"));
    }

    @Test
    void districtsByDivision() throws Exception {
        mvc.perform(get("/api/shortlist/generate/districts/BELGAUM DIV").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("BELAGAVI"));
    }

    @Test
    void blocksByDistrictWithFrozenFlag() throws Exception {
        mvc.perform(get("/api/shortlist/generate/blocks/KARNATAKA/BELGAUM DIV/BELAGAVI/2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("GOKAK"))
           .andExpect(jsonPath("$[0].is_frozen_block").value(false));
    }

    @Test
    void criteriaList() throws Exception {
        mvc.perform(get("/api/shortlist/generate/criteria").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.criteria_id=='91')].criteria").value(org.hamcrest.Matchers.hasItem("Top 6% students per block")));
    }

    @Test
    void generateReadsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/shortlist/generate/allstates").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/shortlist/generate/criteria").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
