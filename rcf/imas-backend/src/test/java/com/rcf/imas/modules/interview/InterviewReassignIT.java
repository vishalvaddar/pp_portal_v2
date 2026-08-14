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
class InterviewReassignIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('rsseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='rsseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "rsseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (93501,'Old','Y'),(93502,'New','Y') ON CONFLICT (interviewer_id) DO NOTHING").update();

        for (long id = 930101; id <= 930104; id++) {
            jdbc.sql("""
                INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, created_by, updated_by)
                VALUES (:id, 2027, :reg, :nm, :u, :u) ON CONFLICT (applicant_id) DO NOTHING
                """).param("id", id).param("reg", 27930000000L + id).param("nm", "Cand" + id).param("u", uid).update();
        }
        // 101 SCHEDULED w/ Old -> reassign to New succeeds (status becomes RESCHEDULED)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (930101, 93501, 1, 'SCHEDULED')").update();
        // 102 SCHEDULED w/ Old -> cancel (NO_ONE)
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (930102, 93501, 1, 'SCHEDULED')").update();
        // 103 SCHEDULED w/ New -> reassign to New is a no-op (IS DISTINCT FROM guard) -> Skipped
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (930103, 93502, 1, 'SCHEDULED')").update();
        // 104 already CANCELLED -> cancel again is a no-op -> Skipped
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (930104, NULL, 1, 'CANCELLED')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id BETWEEN 930101 AND 930104").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id BETWEEN 930101 AND 930104").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id IN (93501,93502)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'rsseed'").update();
    }

    @Test
    void reassignToNewInterviewerSetsRescheduled() throws Exception {
        mvc.perform(post("/api/interview/reassign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[930101],\"newInterviewerId\":93502,\"nmmsYear\":2027}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Reassignment process completed."))
           .andExpect(jsonPath("$.results[0].applicantId").value(930101))
           .andExpect(jsonPath("$.results[0].status").value("RESCHEDULED"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(1));
        String iid = jdbc.sql("SELECT interviewer_id::text FROM pp.student_interview WHERE applicant_id=930101").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("93502", iid);
    }

    @Test
    void cancellationSetsCancelledAndNullInterviewer() throws Exception {
        mvc.perform(post("/api/interview/reassign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[930102],\"newInterviewerId\":\"NO_ONE\",\"nmmsYear\":2027}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("CANCELLED"))
           .andExpect(jsonPath("$.results[0].interviewRound").value(1));
        String iid = jdbc.sql("SELECT interviewer_id FROM pp.student_interview WHERE applicant_id=930102").query((rs, i) -> rs.getObject("interviewer_id")).single() == null ? "null" : "notnull";
        org.junit.jupiter.api.Assertions.assertEquals("null", iid);
    }

    @Test
    void reassignToSameInterviewerIsNoOpSkipped() throws Exception {
        mvc.perform(post("/api/interview/reassign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[930103],\"newInterviewerId\":93502,\"nmmsYear\":2027}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Student is already assigned to this interviewer or has a finalized result"));
    }

    @Test
    void cancelAlreadyCancelledIsNoOpSkipped() throws Exception {
        mvc.perform(post("/api/interview/reassign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[930104],\"newInterviewerId\":\"NO_ONE\",\"nmmsYear\":2027}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.results[0].status").value("Skipped"))
           .andExpect(jsonPath("$.results[0].reason").value("Already unassigned or not in a cancellable state"));
    }

    @Test
    void missingBodyFieldsIs400() throws Exception {
        mvc.perform(post("/api/interview/reassign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"applicantIds\":[930101],\"nmmsYear\":2027}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing applicantIds, newInterviewerId, or nmmsYear in request body."));
    }
}
