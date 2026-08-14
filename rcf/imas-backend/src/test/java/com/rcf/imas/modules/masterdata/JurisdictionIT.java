package com.rcf.imas.modules.masterdata;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class JurisdictionIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String token;

    @BeforeEach
    void seed() {
        token = jwt.issueFinalToken("1", "any", "STUDENT"); // reads open to all authenticated roles
        jdbc.sql("DELETE FROM pp.institute").update();
        jdbc.sql("DELETE FROM pp.jurisdiction").update();
        // jurisdiction_type rows must exist first (FK) — baseline seeds them; insert defensively:
        jdbc.sql("""
            INSERT INTO pp.jurisdiction_type(juris_type) VALUES
            ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK'),('CLUSTER')
            ON CONFLICT DO NOTHING""").update();
        jdbc.sql("""
            INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES
            (1,'Karnataka','STATE',NULL),
            (2,'Belagavi Division','DIVISION',1),
            (3,'Dharwad','EDUCATION DISTRICT',2),
            (4,'Hubballi Block','BLOCK',3),
            (5,'Cluster-A','CLUSTER',4)""").update();
        jdbc.sql("""
            INSERT INTO pp.institute(institute_id, dise_code, institute_name, juris_code) VALUES
            (100, 29010100101, 'Govt High School A', 5)""").update();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM pp.institute WHERE institute_id = 100").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (1,2,3,4,5)").update();
    }

    @Test
    void statesCascade() throws Exception {
        mvc.perform(get("/api/states").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("1"))
           .andExpect(jsonPath("$[0].name").value("Karnataka"));
        mvc.perform(get("/api/divisions-by-state/1").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Belagavi Division"));
        mvc.perform(get("/api/districts-by-division/2").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Dharwad"));
        mvc.perform(get("/api/blocks-by-district/3").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Hubballi Block"));
        mvc.perform(get("/api/clusters-by-block/4").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Cluster-A"));
        mvc.perform(get("/api/institutes-by-cluster/5").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"))
           .andExpect(jsonPath("$[0].dise_code").value("29010100101"));
    }

    @Test
    void jurisNameSingleObject() throws Exception {
        mvc.perform(get("/api/juris-name/3").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("Dharwad"));
    }

    @Test
    void districtsAllAndInstituteSearch() throws Exception {
        mvc.perform(get("/api/districts/all").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].district").value("Dharwad"))
           .andExpect(jsonPath("$[0].district_code").value("3"));
        mvc.perform(get("/api/institutes/all").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"));
        mvc.perform(get("/api/institutes/search?query=govt").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"));
        mvc.perform(get("/api/institutes/search").header("Authorization", "Bearer " + token))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing query parameter"));
    }

    @Test
    void jurisNamesBulkResolve() throws Exception {
        mvc.perform(post("/api/juris-names").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("{\"districtIds\":[3],\"blockIds\":[4],\"instituteIds\":[29010100101]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.districts.3").value("Dharwad"))
           .andExpect(jsonPath("$.blocks.4").value("Hubballi Block"))
           .andExpect(jsonPath("$.institutes.29010100101").value("Govt High School A"));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/states")).andExpect(status().isUnauthorized());
    }
}
