package com.rcf.imas.modules.interview;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class InterviewSubmitIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('sbseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='sbseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "sbseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (94501,'Iv','Y') ON CONFLICT (interviewer_id) DO NOTHING").update();

        // applicant 101 (interview submit, ACCEPTED->SELECTED path) with primary + secondary rows for the master upsert copy
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, father_name, mother_name, gender, contact_no1, created_by, updated_by)
            VALUES (940101, 2027, 27940000101, 'Sel Ected', 'F', 'M', 'M', '9000000001', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, father_occupation, mother_occupation) VALUES (940101,'Farmer','Teacher') ON CONFLICT (applicant_id) DO NOTHING").update();
        // 101 has a SCHEDULED round w/ no result -> the UPDATE target
        jdbc.sql("INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status) VALUES (940101, 94501, 1, 'SCHEDULED')").update();

        // applicant 102 (home verification, ACCEPTED path)
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, gender, created_by, updated_by)
            VALUES (940102, 2027, 27940000102, 'Home Verified', 'F', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE applicant_id IN (940101,940102)").update();
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id IN (940101,940102)").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (940101,940102)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (940101,940102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (940101,940102)").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id = 94501").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'sbseed'").update();
    }

    @Test
    void submitInterviewAcceptedMapsToSelectedAndEnrolls() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "x".getBytes());
        mvc.perform(multipart("/api/interview/submit-interview").file(file)
                .param("applicantId", "940101").param("nmmsYear", "2027").param("remarks", "Great")
                .param("interviewDate", "2027-06-10").param("interviewTime", "10:00:00")
                .param("interviewMode", "online").param("interviewStatus", "completed")
                .param("lifeGoalsAndZeal", "8.5").param("commitmentToLearning", "9.0")
                .param("integrity", "8.0").param("communicationSkills", "7.5")
                .param("homeVerificationRequired", "Not Required").param("interviewResult", "ACCEPTED")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Interview details submitted successfully. Enrollment ID: 2027")))
           .andExpect(jsonPath("$.data.enr_id").value("20270001"))
           .andExpect(jsonPath("$.data.interview_result").value("SELECTED"))
           .andExpect(jsonPath("$.data.doc_type").value("PDF"))
           .andExpect(jsonPath("$.data.doc_name").value("INTERVIEW-940101-2027.pdf"));
        // student_master row created with the enr_id
        String enr = jdbc.sql("SELECT enr_id::text FROM pp.student_master WHERE applicant_id=940101").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("20270001", enr);
        // the scheduled interview row transitioned
        String st = jdbc.sql("SELECT status FROM pp.student_interview WHERE applicant_id=940101").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", st);
    }

    @Test
    void submitInterviewMissingFileIs400() throws Exception {
        mvc.perform(multipart("/api/interview/submit-interview")
                .param("applicantId", "940101").param("nmmsYear", "2027").param("remarks", "Great")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing applicantId, remarks, interview file, or nmmsYear."));
    }

    @Test
    void submitInterviewNoMatchingScheduledRowIs500WithErrorTrue() throws Exception {
        // applicant 940102 has NO scheduled student_interview row -> the UPDATE matches 0 rows -> Node throws -> 500 {error:true,...}
        MockMultipartFile file = new MockMultipartFile("file", "r.pdf", "application/pdf", "x".getBytes());
        mvc.perform(multipart("/api/interview/submit-interview").file(file)
                .param("applicantId", "940102").param("nmmsYear", "2027").param("remarks", "x")
                .param("interviewMode", "online").param("interviewStatus", "completed").param("interviewResult", "REJECTED")
                .param("lifeGoalsAndZeal", "5").param("commitmentToLearning", "5").param("integrity", "5").param("communicationSkills", "5")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().is(500))
           .andExpect(jsonPath("$.error").value(true))
           .andExpect(jsonPath("$.message").value("Update failed. No matching scheduled interview found."));
    }

    @Test
    void submitHomeVerificationAcceptedEnrolls() throws Exception {
        MockMultipartFile file = new MockMultipartFile("verificationDocument", "proof.jpg", "image/jpeg", "y".getBytes());
        mvc.perform(multipart("/api/interview/submit-home-verification").file(file)
                .param("applicantId", "940102").param("status", "ACCEPTED").param("verifiedBy", "Officer")
                .param("verificationType", "physical").param("dateOfVerification", "2027-07-01").param("nmmsYear", "2027")
                .param("remarks", "ok")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Home verification submitted successfully. Student Enrolled as: 2027")))
           .andExpect(jsonPath("$.data.enr_id").value("20270001"))
           .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
           .andExpect(jsonPath("$.data.verification_type").value("PHYSICAL"))
           .andExpect(jsonPath("$.data.doc_type").value("JPG"))
           .andExpect(jsonPath("$.data.doc_name").value("HOME-VERI-940102-2027.jpg"));
        String enr = jdbc.sql("SELECT enr_id::text FROM pp.student_master WHERE applicant_id=940102").query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("20270001", enr);
    }

    @Test
    void submitHomeVerificationRejectedDoesNotEnroll() throws Exception {
        mvc.perform(multipart("/api/interview/submit-home-verification")
                .param("applicantId", "940102").param("status", "REJECTED").param("verifiedBy", "Officer")
                .param("verificationType", "virtual").param("dateOfVerification", "2027-07-01").param("nmmsYear", "2027")
                .param("remarks", "no")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Home verification submitted successfully.")) // no enr suffix
           .andExpect(jsonPath("$.data.enr_id").value(org.hamcrest.Matchers.nullValue())) // enr_id serializes as JSON null (ObjectMapper is not NON_NULL)
           .andExpect(jsonPath("$.data.status").value("REJECTED"));
        Integer masters = jdbc.sql("SELECT COUNT(*) FROM pp.student_master WHERE applicant_id=940102").query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, masters);
    }

    @Test
    void submitHomeVerificationMissingFieldsIs400() throws Exception {
        mvc.perform(multipart("/api/interview/submit-home-verification")
                .param("applicantId", "940102").param("status", "ACCEPTED")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing required fields including nmmsYear."));
    }
}
