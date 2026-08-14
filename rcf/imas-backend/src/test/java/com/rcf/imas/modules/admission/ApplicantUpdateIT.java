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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantUpdateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;
    Long uid;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('upd','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='upd'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "upd", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, gender, medium, contact_no1, created_by, updated_by)
                VALUES (700001, 2025, 24010000900, 'Old Name', 'Old Father', 'M', 'English', '9000000000', :u, :u)
                """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @AfterEach
    void cleanup() {
        // this IT seeds applicant rows (FK -> user); clean children before parents
        // so sibling ITs' teardowns don't hit FK violations.
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='upd'").update();
    }

    @Test
    void updatesPrimaryAndUpsertsSecondaryLeavingRegNumberUntouched() throws Exception {
        String body = """
            {"primaryData":{"nmms_year":2026,"nmms_reg_number":99999999999,"student_name":"New Name",
              "father_name":"New Father","gender":"F","medium":"Kannada","contact_no1":"9111111111"},
             "secondaryData":{"village":"NewVillage"}}
            """;
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant updated successfully"));

        String name = jdbc.sql("SELECT student_name FROM pp.applicant_primary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(name).isEqualTo("New Name");
        // nmms_reg_number must NOT change (not in the UPDATE column list)
        String reg = jdbc.sql("SELECT nmms_reg_number::text FROM pp.applicant_primary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(reg).isEqualTo("24010000900");
        String village = jdbc.sql("SELECT village FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(village).isEqualTo("NewVillage");
    }

    @Test
    void secondUpsertUpdatesExistingSecondaryRow() throws Exception {
        String first = "{\"secondaryData\":{\"village\":\"V1\"}}";
        String second = "{\"secondaryData\":{\"village\":\"V2\"}}";
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(first)).andExpect(status().isOk());
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(second)).andExpect(status().isOk());
        Long rows = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(Long.class).single();
        assertThat(rows).isEqualTo(1);  // ON CONFLICT updated, not inserted twice
        String v = jdbc.sql("SELECT village FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(v).isEqualTo("V2");
    }

    @Test
    void nonMatchingIdReturns200NoError() throws Exception {
        // Node preserves this quirk: 0-row UPDATE is silent, still 200
        mvc.perform(put("/api/applicants/888888/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"primaryData\":{\"student_name\":\"Ghost\"}}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void updateIsAdminOnly() throws Exception {
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"primaryData\":{\"student_name\":\"X\"}}"))
           .andExpect(status().isForbidden());
    }
}
