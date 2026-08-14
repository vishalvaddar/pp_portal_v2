package com.rcf.imas.modules.results;

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
class ResultsCascadeAndFilterOptionsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800002,'BELGAUM DIV','DIVISION',800001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800003,'BELAGAVI','EDUCATION DISTRICT',800002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800004,'GOKAK','BLOCK',800003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (890001, 'NMMS Aptitude 2025', '2025-06-15', '09:00:00', '11:00:00')
            ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        // exam_results/student_interview/home_verification all FK applicant_id -> applicant_primary_info,
        // so the referenced applicant MUST exist first (999999). nmms_reg_number is NOT NULL UNIQUE; other cols nullable.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (999999, 24099999999) ON CONFLICT (applicant_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (890101, 999999, 'COMPLETED', 'SELECTED') ON CONFLICT (interview_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.home_verification(verification_id, applicant_id, status) VALUES (890201, 999999, 'ACCEPTED') ON CONFLICT (verification_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_cleared) VALUES (999999, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id = 999999").update();
        jdbc.sql("DELETE FROM pp.home_verification WHERE verification_id = 890201").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE interview_id = 890101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 999999").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 890001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (800001,800002,800003,800004)").update();
    }

    @Test
    void divisionsByState() throws Exception {
        mvc.perform(get("/api/results/divisions-by-state/800001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800002"))
           .andExpect(jsonPath("$[0].name").value("BELGAUM DIV"));
    }

    @Test
    void educationDistrictsByDivision() throws Exception {
        mvc.perform(get("/api/results/education-districts-by-division/800002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800003"))
           .andExpect(jsonPath("$[0].name").value("BELAGAVI"));
    }

    @Test
    void blocksByDistrict() throws Exception {
        mvc.perform(get("/api/results/blocks-by-district/800003").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800004"))
           .andExpect(jsonPath("$[0].name").value("GOKAK"));
    }

    @Test
    void allExamsReturnsDateAndTimeAsStrings() throws Exception {
        mvc.perform(get("/api/results/all-exams").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_name").value(org.hamcrest.Matchers.hasItem("NMMS Aptitude 2025")))
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_date").value(org.hamcrest.Matchers.hasItem("2025-06-15")))
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_start_time").value(org.hamcrest.Matchers.hasItem("09:00:00")));
    }

    @Test
    void filterOptionsKnownFields() throws Exception {
        mvc.perform(get("/api/results/filter-options/interview_status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("COMPLETED")));
        mvc.perform(get("/api/results/filter-options/interview_result").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("SELECTED")));
        mvc.perform(get("/api/results/filter-options/verification_status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("ACCEPTED")));
        mvc.perform(get("/api/results/filter-options/pp_exam_cleared").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("Y")));
    }

    @Test
    void filterOptionsUnknownFieldIsEmpty200NotError() throws Exception {
        mvc.perform(get("/api/results/filter-options/not_a_real_field").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    @Test
    void allEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/results/divisions-by-state/800001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/results/all-exams").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/results/filter-options/interview_status").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
