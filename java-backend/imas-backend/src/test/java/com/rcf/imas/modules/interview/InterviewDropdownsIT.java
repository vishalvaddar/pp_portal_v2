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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class InterviewDropdownsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('ivseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='ivseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "ivseed", "ADMIN");
        student = jwt.issueFinalToken("999", "s", "STUDENT");

        // jurisdiction tree: state -> division -> education district -> block
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('state'),('division'),('education district'),('block') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (900001,'Karnataka','state') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (900002,'Belagavi Div','division',900001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (900003,'Belagavi Edu Dist','education district',900002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (900004,'Gokak Block','block',900003) ON CONFLICT (juris_code) DO NOTHING").update();

        // exam centre + interviewer (inactive rows must still appear -> Firm Decision 5/8)
        jdbc.sql("INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn) VALUES (90001,'IVC1','Zeta Centre','N') ON CONFLICT (pp_exam_centre_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (90501,'Zoe Interviewer','N') ON CONFLICT (interviewer_id) DO NOTHING").update();

        // one applicant needing home verification (student_interview.home_verification_req_yn='Y', NOT yet in home_verification)
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, created_by, updated_by)
            VALUES (900101, 2027, 27090000001, 'Verify Me', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, home_verification_req_yn)
            VALUES (900101, 90501, 1, 'SCHEDULED', 'Y')
            """).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id = 900101").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 900101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 900101").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id = 90501").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 90001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (900001,900002,900003,900004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'ivseed'").update();
    }

    @Test
    void examCentersIncludesInactiveIdAsString() throws Exception {
        mvc.perform(get("/api/interview/exam-centers").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='90001')].pp_exam_centre_name").value(org.hamcrest.Matchers.hasItem("Zeta Centre")));
    }

    @Test
    void statesReturnsStateRows() throws Exception {
        mvc.perform(get("/api/interview/states").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.juris_code=='900001')].juris_name").value(org.hamcrest.Matchers.hasItem("Karnataka")));
    }

    @Test
    void divisionsByStateName() throws Exception {
        mvc.perform(get("/api/interview/divisions").param("stateName", "Karnataka").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("Belagavi Div"));
    }

    @Test
    void divisionsMissingStateNameIs400() throws Exception {
        mvc.perform(get("/api/interview/divisions").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing stateName query parameter."));
    }

    @Test
    void districtsByDivisionName() throws Exception {
        mvc.perform(get("/api/interview/districts").param("divisionName", "Belagavi Div").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("Belagavi Edu Dist"));
    }

    @Test
    void districtsMissingDivisionNameIs400() throws Exception {
        mvc.perform(get("/api/interview/districts").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing divisionName parameter."));
    }

    @Test
    void blocksByDistrictWithFrozenFlagBoolean() throws Exception {
        mvc.perform(get("/api/interview/blocks")
                .param("stateName", "Karnataka").param("divisionName", "Belagavi Div").param("districtName", "Belagavi Edu Dist")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("Gokak Block"))
           .andExpect(jsonPath("$[0].is_frozen_block").value(false)); // native boolean, not a string
    }

    @Test
    void blocksMissingParamsIs400() throws Exception {
        mvc.perform(get("/api/interview/blocks").param("stateName", "Karnataka").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing one or more required parameters: stateName, divisionName, or districtName."));
    }

    @Test
    void interviewersIncludesInactiveIdAsString() throws Exception {
        mvc.perform(get("/api/interview/interviewers").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.interviewer_id=='90501')].interviewer_name").value(org.hamcrest.Matchers.hasItem("Zoe Interviewer")));
    }

    @Test
    void studentsForVerificationReturnsUnverifiedApplicant() throws Exception {
        mvc.perform(get("/api/interview/students-for-verification").param("nmmsYear", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.applicant_id=='900101')].student_name").value(org.hamcrest.Matchers.hasItem("Verify Me")));
    }

    @Test
    void studentsForVerificationRejectsLiteralUndefined() throws Exception {
        mvc.perform(get("/api/interview/students-for-verification").param("nmmsYear", "undefined").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing or invalid nmmsYear. Received: undefined"));
    }

    @Test
    void studentsForVerificationExcludesAlreadyVerified() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.home_verification(applicant_id, status, verification_type, verified_by)
            VALUES (900101, 'ACCEPTED', 'PHYSICAL', 'tester')
            """).update();
        mvc.perform(get("/api/interview/students-for-verification").param("nmmsYear", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.applicant_id=='900101')]").isEmpty());
    }

    @Test
    void dropdownsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/interview/states").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/interview/interviewers").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
    }
}
