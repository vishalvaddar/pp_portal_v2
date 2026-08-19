package com.rcf.imas.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcf.imas.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthFlowIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired BCryptPasswordEncoder bcrypt;
    @Autowired ObjectMapper om;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.user_role").update();
        jdbc.sql("DELETE FROM pp.user").update();
        jdbc.sql("DELETE FROM pp.role").update();
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (1,'ADMIN','Y'),(2,'TEACHER','Y'),(3,'STUDENT','N')").update();
        jdbc.sql("INSERT INTO pp.user(user_id, user_name, enc_password, locked_yn) VALUES (10,'admin1', :pw, 'N'), (11,'lockedguy', :pw, 'Y')")
            .param("pw", bcrypt.encode("secret123")).update();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (10,1),(10,2),(11,1)").update();
    }

    @Test
    void fullTwoStepLogin() throws Exception {
        MvcResult r1 = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"ADMIN1\",\"password\":\"secret123\"}")) // case-insensitive lookup
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Credentials verified"))
            .andExpect(jsonPath("$.user_name").value("admin1"))
            .andExpect(jsonPath("$.roles.length()").value(2))   // STUDENT role inactive -> excluded
            .andReturn();

        JsonNode body = om.readTree(r1.getResponse().getContentAsString());
        String pre = body.get("preAuthToken").asText();

        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"" + pre + "\",\"selectedRole\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Login complete"))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.user.user_id").value("10"))
            .andExpect(jsonPath("$.user.user_name").value("admin1"))
            .andExpect(jsonPath("$.user.role_name").value("ADMIN"));
    }

    @Test
    void wrongPasswordAndUnknownUserBothSay401InvalidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"admin1\",\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid credentials"));
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"ghost\",\"password\":\"x\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void lockedAccountIs403() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"lockedguy\",\"password\":\"secret123\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Account is locked. Contact support."));
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Username and password are required"));
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Missing session token or role selection"));
    }

    @Test
    void roleNotHeldIs403() throws Exception {
        MvcResult r1 = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"admin1\",\"password\":\"secret123\"}")).andReturn();
        String pre = om.readTree(r1.getResponse().getContentAsString()).get("preAuthToken").asText();
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"" + pre + "\",\"selectedRole\":\"INTERVIEWER\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("You are not authorized for this role"));
    }

    @Test
    void garbagePreAuthTokenIs401WithCode() throws Exception {
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"garbage\",\"selectedRole\":\"ADMIN\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("PRE_AUTH_TOKEN_INVALID"));
    }
}
