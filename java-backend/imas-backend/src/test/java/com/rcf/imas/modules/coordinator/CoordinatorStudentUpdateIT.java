package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorStudentUpdateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965401,'coordUser965401','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965401,'Update Cohort 965401')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965401,'Update Batch 965401',965401)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn, father_name, contact_no1, student_email_password)
                VALUES (965411,'Update Target Student 965411',965401,'F','ACTIVE','Original Father','9990001111','orig-secret-pw')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965401", "coordUser965401", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 965411").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 965411").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965401").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965401").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965401").update();
    }

    @Test
    void whitelistedFieldsUpdateSuccessfully() throws Exception {
        String body = """
            {"father_name":"Updated Father","contact_no1":"9998887777"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        String contact = jdbc.sql("SELECT contact_no1 FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Updated Father", fatherName);
        org.junit.jupiter.api.Assertions.assertEquals("9998887777", contact);
    }

    @Test
    void nonWhitelistedStudentIdKeyIsIgnored() throws Exception {
        String body = """
            {"student_id":"999999","father_name":"Attempted PK Overwrite Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        // student_id UNCHANGED (row still addressable at 965411 -- if the PK had been overwritten this
        // lookup would return no rows).
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Attempted PK Overwrite Father", fatherName);
        Integer bogusRowCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_master WHERE student_id = 999999")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, bogusRowCount);
    }

    @Test
    void injectionStyleKeyIsIgnoredNotInterpolated() throws Exception {
        String body = """
            {"created_by; DROP TABLE pp.student_master; --":"x","father_name":"Post Injection Attempt Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        // Table still exists and the legitimate field DID update -- proves the bogus key was silently
        // dropped, never interpolated into SQL.
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Post Injection Attempt Father", fatherName);
    }

    @Test
    void studentEmailPasswordIsIgnored() throws Exception {
        // student_email_password is deliberately NOT in the whitelist (see Task 4). A caller trying to
        // change it must have the attempt silently dropped -- the stored value stays 'orig-secret-pw'.
        String body = """
            {"student_email_password":"hacked-pw","father_name":"Pw Attempt Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        String pw = jdbc.sql("SELECT student_email_password FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("orig-secret-pw", pw); // UNCHANGED -- not in whitelist
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Pw Attempt Father", fatherName); // whitelisted field DID update
    }

    @Test
    void activeYnInactiveWithReasonRoutesToInactiveBranch() throws Exception {
        String body = """
            {"active_yn":"INACTIVE","inactive_reason":"Moved to another program"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student marked inactive successfully"));

        String activeYn = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE", activeYn);

        String reason = jdbc.sql("SELECT inactive_reason FROM pp.inactive_students WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Moved to another program", reason);
    }
}
