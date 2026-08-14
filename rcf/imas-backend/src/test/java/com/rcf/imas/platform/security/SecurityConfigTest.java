package com.rcf.imas.platform.security;

import com.rcf.imas.platform.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@EnableConfigurationProperties(JwtProperties.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         GlobalExceptionHandler.class, SecurityConfigTest.ProbeController.class})
@TestPropertySource(properties = {
        "imas.jwt.secret=shortsecret",
        "imas.jwt.expires-in=1d",
        "imas.jwt.pre-auth-expires-in=15m"
})
class SecurityConfigTest {

    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        public String open() { return "ok"; }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/probe-admin")
        public String admin() { return "admin-ok"; }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        String t = jwt.issueFinalToken("1", "u", "TEACHER");
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + t))
           .andExpect(status().isOk());
    }

    @Test
    void roleEnforced() throws Exception {
        String teacher = jwt.issueFinalToken("1", "u", "TEACHER");
        String admin = jwt.issueFinalToken("2", "a", "ADMIN");
        mvc.perform(get("/api/probe-admin").header("Authorization", "Bearer " + teacher))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/probe-admin").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk());
    }

    @Test
    void roleWithSpaceWorks() throws Exception {
        String t = jwt.issueFinalToken("3", "c", "BATCH COORDINATOR");
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + t))
           .andExpect(status().isOk());
    }

    @Test
    void badTokenBodyMatchesNodeAuthMiddleware() throws Exception {
        mvc.perform(get("/api/probe").header("Authorization", "Bearer not-a-token"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }
}
