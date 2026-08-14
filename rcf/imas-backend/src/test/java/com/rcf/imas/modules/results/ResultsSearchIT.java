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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ResultsSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (810001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810003,'BELAGAVI','EDUCATION DISTRICT',810001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810004,'GOKAK','BLOCK',810003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('SR100000000001','SearchSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('rsseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='rsseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "rsseed", "ADMIN");

        // applicant 1: fans out to 2 interview rows (2 rounds) -> assert search-by-blocks returns 2 rows for it.
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES (820001,2025,24020000001,810001,810003,810004,'Fanout','f','KANNADA','9000000001','SR100000000001',70,80,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (820101,820001,'COMPLETED','ANOTHER INTERVIEW REQUIRED')").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (820102,820001,'SCHEDULED','SELECTED')").update();

        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% search-exam') ON CONFLICT (criteria) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (820201, 'SearchExam', '2025-05-01', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (820001, 820201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 820201").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria = 'Top 6% search-exam'").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'SR100000000001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (810001,810003,810004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'rsseed'").update();
    }

    @Test
    void searchByBlocksFiltersByDivisionDistrictAndBlocksAndDoesNotDedupFanOut() throws Exception {
        String body = """
            {"division":810001,"education_district":810003,"blocks":[810004],"app_state":810001}
            """;
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))   // 2 student_interview rows -> 2 result rows, no DISTINCT
           .andExpect(jsonPath("$[0].applicant_id").value("820001"))
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"));
    }

    @Test
    void searchByBlocksWithNoFiltersReturnsAllForAppState() throws Exception {
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_state\":810001}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchByExamReproducesDivisionNameEqualsDistrictNameBug() throws Exception {
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":820201}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].division_name").value("BELAGAVI"))   // BUG: should be parent division, is district
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$[0].exam_name").value("SearchExam"));
    }

    @Test
    void searchByExamMissingIdIs400() throws Exception {
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Exam ID is required"));
    }

    @Test
    void searchEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":1}")).andExpect(status().isForbidden());
    }
}
