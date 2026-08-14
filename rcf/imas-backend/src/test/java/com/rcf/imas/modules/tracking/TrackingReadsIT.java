package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TrackingReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (963001,'trAdmin963','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (963001,'Active Interviewer 963','Y')").update();
        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (963002,'Inactive Interviewer 963','N')").update();
        jdbc.sql("SELECT setval('pp.interviewer_id_seq', (SELECT MAX(interviewer_id)::bigint FROM pp.interviewer))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, nmms_year, student_name) VALUES (963001,963001,2025,'Tracking Student 963')").update();

        // two rounds under the same interviewer -- proves row-count (not distinct-student) pagination
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result,
                    home_verification_req_yn, life_goals_and_zeal)
                VALUES (963001,963001,1,'COMPLETED','SELECTED','N',4.5)
                """).update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (963001,963001,2,'COMPLETED','ANOTHER INTERVIEW REQUIRED','N')
                """).update();

        jdbc.sql("""
                INSERT INTO pp.home_verification(applicant_id, date_of_verification, status, verification_type)
                VALUES (963001, DATE '2025-06-01', 'ACCEPTED', 'PHYSICAL')
                """).update();

        adminToken = jwt.issueFinalToken("963001", "trAdmin963", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id IN (963001,963002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 963001").update();
    }

    @Test
    void interviewersIncludesInactiveOnes() throws Exception {
        // getAllInterviewers has no active_status filter (quirk) -- inactive interviewer still returned.
        mvc.perform(get("/api/tracking/interviewers").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.interviewer_id=='963002')]").exists());
    }

    @Test
    void byInterviewerListReturnsOneRowPerRoundNotDedupedToLatest() throws Exception {
        mvc.perform(get("/api/tracking/students/interviewer/963001").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2))) // both rounds, not deduped
           .andExpect(jsonPath("$.totalStudents").value(2)); // row count, not distinct-applicant count
    }

    @Test
    void byInterviewerInvalidIdIs400() throws Exception {
        mvc.perform(get("/api/tracking/students/interviewer/not-a-number")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid Interviewer ID provided."));
    }

    @Test
    void studentDetailsIgnoresFilteredFlagBothBranchesIdentical() throws Exception {
        var withoutFlag = mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var withFlag = mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025").param("filtered", "true")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(withoutFlag).isEqualTo(withFlag); // inert flag -- identical output
    }

    @Test
    void studentDetailsReturnsLatestRoundOnly() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].interview_round").value(2)); // round 2 is MAX
    }

    @Test
    void studentDetailsInvalidApplicantIdIs400() throws Exception {
        mvc.perform(get("/api/tracking/students/not-a-number/details")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid Applicant ID."));
    }

    @Test
    void allInterviewRoundsReturnsBothRoundsAscending() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/interviews/all").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].interview_round").value(1))
           .andExpect(jsonPath("$[0].life_goals_and_zeal").value("4.5")) // fractional numeric preserved as string
           .andExpect(jsonPath("$[1].interview_round").value(2));
    }

    @Test
    void allHomeVerificationRoundsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/home/all").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].home_verification_status").value("ACCEPTED"));
    }
}
