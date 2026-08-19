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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorInactiveFlowIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965501,'coordUser965501','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965501,'Inactive Flow Cohort 965501')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965501,'Inactive Flow Batch 965501',965501)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965511,'Inactive Flow Student 965511',965501,'M','ACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        // A student with pre-existing history rows, for the GET .../inactive-history ordering test.
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965512,'History Student 965512',965501,'F','INACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965512,'Earlier reason', DATE '2025-01-01')").update();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965512,'Later reason', DATE '2025-06-01')").update();

        coordToken = jwt.issueFinalToken("965501", "coordUser965501", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id IN (965511,965512)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965511,965512)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965501").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965501").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965501").update();
    }

    @Test
    void missingReasonIs400WithErrorKey() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Inactive reason is required"))
           .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void blankReasonIs400() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inactive_reason\":\"   \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Inactive reason is required"));
    }

    @Test
    void validReasonMarksInactiveAndLogsHistory() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inactive_reason\":\"Family relocation\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student marked inactive successfully"));

        String activeYn = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 965511")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE", activeYn);

        String reason = jdbc.sql("SELECT inactive_reason FROM pp.inactive_students WHERE student_id = 965511")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Family relocation", reason);
    }

    @Test
    void inactiveHistoryReturnsOrderedByDateDescending() throws Exception {
        mvc.perform(get("/api/coordinator/students/965512/inactive-history")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].inactive_reason").value("Later reason"))
           .andExpect(jsonPath("$[1].inactive_reason").value("Earlier reason"));
    }
}
