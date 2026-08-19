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
class InterviewFilteringIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('flseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='flseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "flseed", "ADMIN");

        // geography
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('state'),('education district'),('block') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'Karnataka','state') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (910003,'Belagavi Edu Dist','education district',910001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (910004,'Gokak Block','block',910003) ON CONFLICT (juris_code) DO NOTHING").update();

        // exam centre + examination + interviewer + institute
        jdbc.sql("INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn) VALUES (91001,'FLC1','Gokak Centre','Y') ON CONFLICT (pp_exam_centre_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, pp_exam_centre_id) VALUES (91201,'FL Exam','2027-06-01','09:00:00','11:00:00',91001) ON CONFLICT (exam_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (91501,'Ivy Interviewer','Y') ON CONFLICT (interviewer_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('DISE910','Gokak High') ON CONFLICT (dise_code) DO NOTHING").update();

        // applicant U = never interviewed (unassigned); applicant R = SCHEDULED/no-result (reassignable, also 'by interviewer')
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block, current_institute_dise_code, created_by, updated_by)
            VALUES (910101, 2027, 27091000001, 'Uma Unassigned', 910001, 910003, 910004, 'DISE910', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block, current_institute_dise_code, created_by, updated_by)
            VALUES (910102, 2027, 27091000002, 'Ravi Reassign', 910001, 910003, 910004, 'DISE910', :u, :u) ON CONFLICT (applicant_id) DO NOTHING
            """).param("u", uid).update();

        // exam_results: both cleared + interview-required
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score, pp_exam_cleared, interview_required_yn) VALUES (910101, 55, 'Y', 'Y')").update();
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score, pp_exam_cleared, interview_required_yn) VALUES (910102, 60, 'Y', 'Y')").update();

        // applicant_exam links to the centre-linked examination (needed by by-centre queries)
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (910101, 91201)").update();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (910102, 91201)").update();

        // R has a SCHEDULED round with no result => reassignable + shows under getStudentsByInterviewer
        jdbc.sql("""
            INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status)
            VALUES (910102, 91501, 1, 'SCHEDULED')
            """).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (910101,910102)").update();
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id IN (910101,910102)").update();
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id IN (910101,910102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (910101,910102)").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DISE910'").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id = 91501").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 91201").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 91001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (910001,910003,910004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'flseed'").update();
    }

    @Test
    void studentsByInterviewerReturnsRoundAsNumber() throws Exception {
        mvc.perform(get("/api/interview/students/Ivy Interviewer").param("nmmsYear", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].applicant_id").value("910102"))
           .andExpect(jsonPath("$[0].interview_round").value(1)); // integer column -> JSON number
    }

    @Test
    void studentsByInterviewerMissingYearIs400() throws Exception {
        mvc.perform(get("/api/interview/students/Ivy Interviewer").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing interviewerName in parameters or nmmsYear in query."));
    }

    @Test
    void unassignedStudentsByCentreFindsNeverInterviewed() throws Exception {
        mvc.perform(get("/api/interview/unassigned-students").param("centerName", "Gokak Centre").param("nmmsYear", "2027")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.applicant_id=='910101')].student_name").value(org.hamcrest.Matchers.hasItem("Uma Unassigned")))
           .andExpect(jsonPath("$[?(@.applicant_id=='910102')]").isEmpty()); // R already scheduled -> not unassigned
    }

    @Test
    void unassignedStudentsMissingParamsIs400() throws Exception {
        mvc.perform(get("/api/interview/unassigned-students").param("centerName", "Gokak Centre").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing centerName or nmmsYear query parameter."));
    }

    @Test
    void unassignedStudentsByBlockFindsNeverInterviewed() throws Exception {
        mvc.perform(get("/api/interview/unassigned-students-by-block")
                .param("stateName", "Karnataka").param("districtName", "Belagavi Edu Dist").param("blockName", "Gokak Block").param("nmmsYear", "2027")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.applicant_id=='910101')].student_name").value(org.hamcrest.Matchers.hasItem("Uma Unassigned")));
    }

    @Test
    void unassignedByBlockMissingParamsIs400() throws Exception {
        mvc.perform(get("/api/interview/unassigned-students-by-block").param("stateName", "Karnataka").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing required query parameters."));
    }

    @Test
    void reassignableStudentsByCentreIncludesCentreName() throws Exception {
        mvc.perform(get("/api/interview/reassignable-students").param("centerName", "Gokak Centre").param("nmmsYear", "2027")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].applicant_id").value("910102"))
           .andExpect(jsonPath("$[0].pp_exam_centre_name").value("Gokak Centre"))
           .andExpect(jsonPath("$[0].current_interviewer").value("Ivy Interviewer"))
           .andExpect(jsonPath("$[0].current_interviewer_id").value("91501"))
           .andExpect(jsonPath("$[0].interview_round").value(1));
    }

    @Test
    void reassignableStudentsMissingParamsIs400() throws Exception {
        mvc.perform(get("/api/interview/reassignable-students").param("centerName", "Gokak Centre").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing centerName or nmmsYear query parameter."));
    }

    @Test
    void reassignableStudentsByBlockHasNoCentreNameField() throws Exception {
        mvc.perform(get("/api/interview/reassignable-students-by-block")
                .param("stateName", "Karnataka").param("districtName", "Belagavi Edu Dist").param("blockName", "Gokak Block").param("nmmsYear", "2027")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].applicant_id").value("910102"))
           .andExpect(jsonPath("$[0].institute_name").value("Gokak High"))
           .andExpect(jsonPath("$[0].pp_exam_centre_name").doesNotExist()); // by-block variant omits centre name
    }

    @Test
    void reassignableByBlockHasNoValidationReturnsEmptyOnMissingParams() throws Exception {
        // Firm Decision 8: the by-block reassignable endpoint has NO 400 guard in Node -> 200 [] on missing params.
        mvc.perform(get("/api/interview/reassignable-students-by-block").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray())
           .andExpect(jsonPath("$").isEmpty());
    }
}
