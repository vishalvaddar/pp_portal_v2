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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantCreateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        // create the FK user whose user_id matches the ADMIN token subject
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('creator','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='creator'").query(Long.class).single();
        // jurisdiction_type rows are required by the jurisdiction FK (jurisdiction_juris_type_fkey)
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        admin = jwt.issueFinalToken(String.valueOf(uid), "creator", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
    }

    @AfterEach
    void cleanup() {
        // this IT inserts applicant rows (FK -> jurisdiction/user); clean children before parents
        // so sibling ITs' teardowns don't hit FK violations.
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (910001, 910002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='creator'").update();
    }

    private String flatBody(String reg) {
        return """
            {"nmms_year":2025,"nmms_reg_number":%s,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":null,"nmms_block":null,
             "gender":"F","dob":"15-06-2011"}
            """.formatted(reg);
    }

    @Test
    void createsApplicantAndReturns201WithApplicantId() throws Exception {
        // district/nmms_block are required-non-falsy in Node; supply real jurisdiction codes
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000055,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":910001,"nmms_block":910002,
             "gender":"F","dob":"15-06-2011"}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant created successfully"))
           .andExpect(jsonPath("$.data.applicant_id").isNotEmpty());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_reg_number=24010000055").query(Long.class).single();
        assertThat(n).isEqualTo(1);
        Long sec = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info").query(Long.class).single();
        assertThat(sec).isEqualTo(1);  // secondary row created in same transaction
    }

    @Test
    void missingRequiredFieldsIs400WithList() throws Exception {
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"nmms_year\":2025,\"student_name\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message", org.hamcrest.Matchers.startsWith("Missing fields:")));
    }

    @Test
    void invalidContactIs400() throws Exception {
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000056,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"12345","district":910001,"nmms_block":910002}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Invalid contact_no1"));
    }

    @Test
    void duplicateRegNumberIs400() throws Exception {
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000077,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":910001,"nmms_block":910002}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Registration Number already exists"));
    }

    @Test
    void createIsAdminOnly() throws Exception {
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content(flatBody("24010000099")))
           .andExpect(status().isForbidden());
    }
}
