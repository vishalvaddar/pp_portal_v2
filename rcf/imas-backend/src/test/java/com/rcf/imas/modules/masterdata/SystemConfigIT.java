package com.rcf.imas.modules.masterdata;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SystemConfigIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    private final ObjectMapper om = new ObjectMapper();

    String admin;
    String student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        jdbc.sql("DELETE FROM pp.system_config").update();
    }

    @Test
    void createReadActivateFlow() throws Exception {
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"Admissions in Progress\",\"is_active\":true}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.academic_year").value("2026-27"))
           .andExpect(jsonPath("$.is_active").value(true))
           .andExpect(jsonPath("$.system_config_id").isNotEmpty());

        mvc.perform(get("/api/system-config/active").header("Authorization", "Bearer " + student))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].academic_year").value("2026-27"));

        mvc.perform(get("/api/system-config/all").header("Authorization", "Bearer " + admin))
           .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateAcademicYearIsAllowed() throws Exception {
        // Live schema has NO UNIQUE constraint on academic_year: a duplicate insert SUCCEEDS (200)
        // and yields its own system_config_id.
        MvcResult first = mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"P1\",\"is_active\":false}"))
           .andExpect(status().isOk())
           .andReturn();
        MvcResult second = mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"P2\",\"is_active\":false}"))
           .andExpect(status().isOk())
           .andReturn();

        JsonNode firstBody = om.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = om.readTree(second.getResponse().getContentAsString());
        assertNotEquals(
                firstBody.get("system_config_id").asLong(),
                secondBody.get("system_config_id").asLong());
    }

    @Test
    void updateDeleteNotFoundBehaviour() throws Exception {
        mvc.perform(put("/api/system-config/999999").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2027-28\",\"phase\":\"X\",\"is_active\":false}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Configuration not found"));
        mvc.perform(put("/api/system-config/abc").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid or missing config ID"));
        mvc.perform(delete("/api/system-config/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound());
    }

    @Test
    void writesAreAdminOnly() throws Exception {
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2030-31\",\"phase\":\"X\"}"))
           .andExpect(status().isForbidden());
    }
}
