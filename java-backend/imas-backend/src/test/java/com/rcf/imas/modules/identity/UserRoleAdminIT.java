package com.rcf.imas.modules.identity;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class UserRoleAdminIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;
    @Autowired BCryptPasswordEncoder bcrypt;

    String adminToken;
    String teacherToken;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.teacher").update();
        jdbc.sql("DELETE FROM pp.user_role").update();
        jdbc.sql("DELETE FROM pp.user").update();
        jdbc.sql("DELETE FROM pp.role").update();
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (1,'ADMIN','Y'),(2,'TEACHER','Y')").update();
        jdbc.sql("SELECT setval('pp.role_id_seq', (SELECT MAX(role_id)::bigint FROM pp.role))").query(java.math.BigDecimal.class).single();
        jdbc.sql("INSERT INTO pp.user(user_id, user_name, enc_password, locked_yn) VALUES (10,'admin1', :pw,'N')")
            .param("pw", bcrypt.encode("x")).update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.user))").query(java.math.BigDecimal.class).single();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (10,1)").update();
        adminToken = jwt.issueFinalToken("10", "admin1", "ADMIN");
        teacherToken = jwt.issueFinalToken("99", "somebody", "TEACHER");
    }

    @Test
    void nonAdminGets403OnUserAdmin() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void createUserWithTeacherRoleSyncsTeacherTable() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"newteach\",\"password\":\"pw12345\",\"roles\":[\"teacher\"]}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("User \"newteach\" created successfully"))
           .andExpect(jsonPath("$.userId").isNotEmpty());

        Integer inTeacher = jdbc.sql("""
                SELECT count(*) FROM pp.teacher t JOIN pp.user u ON u.user_id=t.user_id
                WHERE u.user_name='newteach'""").query(Integer.class).single();
        assertThat(inTeacher).isEqualTo(1);
    }

    @Test
    void duplicateUsernameIs409() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin1\",\"password\":\"pw\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value("Username already exists."));
    }

    @Test
    void listUsersReturnsBareArrayWithAggregatedRoles() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].username").value("admin1"))
           .andExpect(jsonPath("$[0].status").value("N"))
           .andExpect(jsonPath("$[0].roles[0]").value("ADMIN"));
    }

    @Test
    void deleteUserIs204AndMissingUserIs404() throws Exception {
        mvc.perform(delete("/api/users/10").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNoContent());
        mvc.perform(delete("/api/users/10").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("User not found."));
    }

    @Test
    void roleLifecycle() throws Exception {
        mvc.perform(post("/api/roles").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON).content("{\"roleName\":\"librarian\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Role \"LIBRARIAN\" created successfully"))
           .andExpect(jsonPath("$.role.role_name").value("LIBRARIAN"));

        // role in use cannot be deleted
        mvc.perform(delete("/api/roles/1").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest());
    }

    @Test
    void selfServicePasswordChangeAllowedForOwnUserOnly() throws Exception {
        // admin1 (user_id 10) changes own password: requires correct current password
        mvc.perform(put("/api/user/change-password/10").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"y1234567\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Password updated successfully."));

        // teacher token (user_id 99) may not change user 10's password
        mvc.perform(put("/api/user/change-password/10").header("Authorization", "Bearer " + teacherToken)
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"z1234567\"}"))
           .andExpect(status().isForbidden());
    }
}
