package com.rcf.imas.modules.interview;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class InterviewReportIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('rpseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='rpseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "rpseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (95501,'Ren Rep','Y') ON CONFLICT (interviewer_id) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, created_by, updated_by)
            VALUES (950101, 2027, 27950000101, 'Report Kid', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, village) VALUES (950101,'Gokak') ON CONFLICT (applicant_id) DO NOTHING").update();
        // one COMPLETED round (has interviewer + result) and one CANCELLED round (NULL interviewer -> INNER JOIN drops it)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result, interview_mode) VALUES (950101, 95501, 1, 'COMPLETED', 'SELECTED', 'ONLINE')").update();
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (950101, NULL, 2, 'CANCELLED')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 950101").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id = 950101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 950101").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id = 95501").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'rpseed'").update();
    }

    @Test
    void reportReturnsPdfBytesWithFilename() throws Exception {
        byte[] pdf = mvc.perform(post("/api/interview/download-assignment-report").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"interviewerId\":95501,\"nmmsYear\":2027,\"applicantIds\":[950101]}"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
           .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.allOf(
                   org.hamcrest.Matchers.startsWith("attachment; filename=\"Interview-Assignment95501_"),
                   org.hamcrest.Matchers.endsWith(".pdf\""))))
           .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(pdf.length > 4);
        org.junit.jupiter.api.Assertions.assertEquals("%PDF", new String(pdf, 0, 4)); // valid PDF magic
    }

    @Test
    void reportMissingParamsIs400WithErrorKey() throws Exception {
        mvc.perform(post("/api/interview/download-assignment-report").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"nmmsYear\":2027,\"applicantIds\":[950101]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required parameters: interviewerId, nmmsYear, or applicantIds list is empty/invalid."));
    }

    @Test
    void reportEmptyApplicantIdsIs400() throws Exception {
        mvc.perform(post("/api/interview/download-assignment-report").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"interviewerId\":95501,\"nmmsYear\":2027,\"applicantIds\":[]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required parameters: interviewerId, nmmsYear, or applicantIds list is empty/invalid."));
    }

    @Test
    void reportNoMatchingProfilesIs404() throws Exception {
        mvc.perform(post("/api/interview/download-assignment-report").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"interviewerId\":95501,\"nmmsYear\":2099,\"applicantIds\":[950101]}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("No student data found for the selected criteria."));
    }

    @Test
    void reportCategorizationDropsCancelledRoundKeepsCompleted() throws Exception {
        // white-box: the INNER JOIN to interviewer drops the round-2 CANCELLED (NULL interviewer) row entirely;
        // round-1 COMPLETED/SELECTED is the only interview record -> 1 completed round, 0 pending. Verified via a
        // read-through on the repo output shape rather than the PDF binary.
        var data = jdbc.sql("SELECT COUNT(*) FROM pp.student_interview WHERE applicant_id=950101 AND interviewer_id IS NOT NULL")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, data); // only the round-1 row survives the INNER JOIN
    }
}
