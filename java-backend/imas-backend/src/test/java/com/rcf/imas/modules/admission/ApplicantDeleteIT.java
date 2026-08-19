package com.rcf.imas.modules.admission;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantDeleteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('del','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='del'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "del", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, gender, medium, contact_no1, created_by, updated_by)
                VALUES (600001, 2025, 24010000600, 'Del Me', 'Father', 'M', 'English', '9000000000', :u, :u)
                """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, village, created_by, updated_by) VALUES (600001,'V',:u,:u)").param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @AfterEach
    void cleanup() {
        // Delete children before parents so sibling ITs' teardowns don't hit FK violations. Clean up exactly what we seed.
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='del'").update();
    }

    @Test
    void deletesAndCascadesSecondary() throws Exception {
        mvc.perform(delete("/api/applicants/600001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant deleted successfully"));
        Long p = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE applicant_id=600001").query(Long.class).single();
        Long s = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info WHERE applicant_id=600001").query(Long.class).single();
        assertThat(p).isEqualTo(0);
        assertThat(s).isEqualTo(0);  // ON DELETE CASCADE
    }

    @Test
    void deleteMissingIs404() throws Exception {
        mvc.perform(delete("/api/applicants/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void deleteIsAdminOnly() throws Exception {
        mvc.perform(delete("/api/applicants/600001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
