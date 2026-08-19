package com.rcf.imas.modules.events;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EventTypesAndJurisdictionsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    Integer eventTypeId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970101,'evAdmin970','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("970101", "evAdmin970", "ADMIN");

        // jurisdiction_type rows are required by jurisdiction_juris_type_fkey
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT (juris_type) DO NOTHING").update();

        // Jurisdiction hierarchy: state -> division -> education district -> block
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970110,'Karnataka970','STATE',NULL)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970111,'Bangalore Div970','DIVISION',970110)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970112,'Bangalore North970','EDUCATION DISTRICT',970111)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970113,'Yelahanka970','BLOCK',970112)").update();

        // Frozen shortlist batch that references the block, to exercise is_frozen_block=true
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_id, shortlist_batch_name, frozen_yn, shortlisted_year) VALUES (970101,'SB970','Y',2025)").update();
        jdbc.sql("INSERT INTO pp.shortlist_batch_jurisdiction(shortlist_batch_id, juris_code) VALUES (970101,970113)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = 970101").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = 970101").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code BETWEEN 970110 AND 970113").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'Sammelan970'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970101").update();
    }

    @Test
    void createEventTypeThenListThenUpdate() throws Exception {
        String createBody = "{\"event_type_name\":\"Sammelan970\"}";
        String createResp = mvc.perform(post("/api/event-types").contentType("application/json").content(createBody)
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.event_type_name").value("Sammelan970"))
            .andReturn().getResponse().getContentAsString();
        Integer id = com.jayway.jsonpath.JsonPath.read(createResp, "$.event_type_id");

        mvc.perform(get("/api/event-types").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.event_type_name=='Sammelan970')]", hasSize(1)));

        mvc.perform(put("/api/event-type/" + id).contentType("application/json")
                .content("{\"event_type_name\":\"Sammelan970-Renamed\"}")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_type_name").value("Sammelan970-Renamed"));

        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_id = :id").param("id", id).update();
    }

    @Test
    void createEventTypeMissingNameIs400() throws Exception {
        mvc.perform(post("/api/event-types").contentType("application/json").content("{}")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Event type name is required"));
    }

    @Test
    void updateEventTypeInvalidIdIs400() throws Exception {
        mvc.perform(put("/api/event-type/abc").contentType("application/json").content("{\"event_type_name\":\"x\"}")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid event ID"));
    }

    @Test
    void jurisdictionsState() throws Exception {
        mvc.perform(get("/api/attendance/jurisdictions").param("type", "state")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[?(@.juris_name=='Karnataka970')]", hasSize(1)));
    }

    @Test
    void jurisdictionsDivision() throws Exception {
        mvc.perform(get("/api/attendance/jurisdictions").param("type", "division")
                .param("stateName", "Karnataka970")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.juris_name=='Bangalore Div970')]", hasSize(1)));
    }

    @Test
    void jurisdictionsDistrict() throws Exception {
        mvc.perform(get("/api/attendance/jurisdictions").param("type", "district")
                .param("divisionNames", "Bangalore Div970")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.juris_name=='Bangalore North970')]", hasSize(1)));
    }

    @Test
    void jurisdictionsBlockWithFrozenFlag() throws Exception {
        mvc.perform(get("/api/attendance/jurisdictions").param("type", "block")
                .param("stateName", "Karnataka970")
                .param("divisionNames", "Bangalore Div970")
                .param("districtNames", "Bangalore North970")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].juris_name").value("Yelahanka970"))
            .andExpect(jsonPath("$.data[0].is_frozen_block").value(true));
    }

    @Test
    void jurisdictionsUnknownTypeOmitsDataKey() throws Exception {
        mvc.perform(get("/api/attendance/jurisdictions").param("type", "bogus")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void withoutAdminTokenIsForbidden() throws Exception {
        mvc.perform(get("/api/event-types")).andExpect(status().isUnauthorized());
    }
}
