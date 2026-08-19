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
class InterviewAssignIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('asseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='asseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "asseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (92501,'Alpha','Y'),(92502,'Beta','Y') ON CONFLICT (interviewer_id) DO NOTHING").update();

        // applicants: 101 fresh(no rounds); 102 eligible-next-round; 103 cancelled; 104 max-rounds; 105 already-scheduled(ineligible); 106 dup-interviewer
        for (long id = 920101; id <= 920106; id++) {
            jdbc.sql("""
                INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, created_by, updated_by)
                VALUES (:id, 2027, :reg, :nm, :u, :u) ON CONFLICT (applicant_id) DO NOTHING
                """).param("id", id).param("reg", 27920000000L + id).param("nm", "Cand" + id).param("u", uid).update();
        }
        // 102 -> round 1 RESCHEDULED + ANOTHER INTERVIEW REQUIRED (branch B)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result) VALUES (920102, 92501, 1, 'RESCHEDULED', 'ANOTHER INTERVIEW REQUIRED')").update();
        // 103 -> round 1 CANCELLED, interviewer NULL (branch C)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (920103, NULL, 1, 'CANCELLED')").update();
        // 104 -> round 3 (branch A max rounds)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result) VALUES (920104, 92502, 3, 'RESCHEDULED', 'ANOTHER INTERVIEW REQUIRED')").update();
        // 105 -> round 1 SCHEDULED, no result (branch D ineligible)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (920105, 92502, 1, 'SCHEDULED')").update();
        // 106 -> round 1 RESCHEDULED+AIR but already with Alpha(92501) -> dup guard blocks re-assigning to Alpha
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result) VALUES (920106, 92501, 1, 'RESCHEDULED', 'ANOTHER INTERVIEW REQUIRED')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id BETWEEN 920101 AND 920106").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id BETWEEN 920101 AND 920106").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id IN (92501,92502)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'asseed'").update();
    }

    private String body(String ids, long interviewerId) {
        return "{\"applicantIds\":[" + ids + "],\"interviewerId\":" + interviewerId + ",\"nmmsYear\":2027}";
    }

    @Test
    void freshApplicantInsertsRound1Assigned() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920101", 92502)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Assignment process completed."))
           .andExpect(jsonPath("$.results[0].applicantId").value(920101))
           .andExpect(jsonPath("$.results[0].status").value("Assigned"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(1));
        Integer round = jdbc.sql("SELECT interview_round FROM pp.student_interview WHERE applicant_id=920101").query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, round);
    }

    @Test
    void branchB_nextRoundEligibleInsertsRound2() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920102", 92502))) // new interviewer Beta, not the round-1 Alpha
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Assigned"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(2));
        Integer rounds = jdbc.sql("SELECT COUNT(*) FROM pp.student_interview WHERE applicant_id=920102").query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(2, rounds); // a NEW row was inserted
    }

    @Test
    void branchC_cancelledRowIsReusedViaUpdateNotInsert() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920103", 92501)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Assigned"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(1)); // pre-existing round, NOT nextRound
        Integer rowCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_interview WHERE applicant_id=920103").query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, rowCount); // still ONE row (reused, not a new insert)
        String status = jdbc.sql("SELECT status FROM pp.student_interview WHERE applicant_id=920103").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("SCHEDULED", status);
        String iid = jdbc.sql("SELECT interviewer_id::text FROM pp.student_interview WHERE applicant_id=920103").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("92501", iid);
    }

    @Test
    void branchA_maxRoundsSkipped() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920104", 92501)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Max rounds reached (3 rounds completed)."));
    }

    @Test
    void branchD_ineligibleScheduledSkipped() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920105", 92501)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Current status (SCHEDULED) or result (NONE) does not allow reassignment."));
    }

    @Test
    void crossRoundDuplicateInterviewerGuardSkips() throws Exception {
        // 920106 is eligible (branch B) but already assigned to Alpha(92501) in round 1 -> re-assigning to Alpha is blocked
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920106", 92501)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Already assigned to this interviewer in a previous round."));
        // but assigning 920106 to a DIFFERENT interviewer (Beta) succeeds as round 2
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920106", 92502)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Assigned"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(2));
    }

    @Test
    void insertGuardSkipsWhenYearMismatch() throws Exception {
        // fresh applicant 920101 exists only for nmms_year 2027; assigning against 2099 -> INSERT...SELECT matches 0 rows
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[920101],\"interviewerId\":92502,\"nmmsYear\":2099}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Student data not found for the specified year."));
    }

    @Test
    void batchPreservesInputOrderAndPerApplicantOutcomes() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("920104,920101,920105", 92502)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].applicantId").value(920104)).andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[1].applicantId").value(920101)).andExpect(jsonPath("$.results[1].status").value("Assigned"))
           .andExpect(jsonPath("$.results[2].applicantId").value(920105)).andExpect(jsonPath("$.results[2].status").value("Skipped"));
    }

    @Test
    void missingBodyFieldsIs400() throws Exception {
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"interviewerId\":92501,\"nmmsYear\":2027}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing applicantIds, interviewerId, or nmmsYear in request body."));
    }

    @Test
    void emptyApplicantIdsArrayIsAllowedReturnsEmptyResults() throws Exception {
        // Node: ![] is false, so an empty array PASSES validation and returns results:[]
        mvc.perform(post("/api/interview/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[],\"interviewerId\":92501,\"nmmsYear\":2027}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Assignment process completed."))
           .andExpect(jsonPath("$.results").isArray())
           .andExpect(jsonPath("$.results").isEmpty());
    }
}
