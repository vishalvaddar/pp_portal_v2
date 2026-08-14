package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamListingIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('elseed2','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='elseed2'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "elseed2", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (820003,'ELIST DIST','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (820004,'ELIST BLOCK','BLOCK',820003) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, active_yn) VALUES (82001,'ELIST Centre','Y')
            ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (820101,'Assigned Exam','2027-06-01','09:00:00','11:00:00','2027',82001)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (820102,'Unassigned Exam','2027-06-02','09:00:00','11:00:00','2027',82001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (820201,2027,24082000001,820003,820004,'ListedKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (820201, 820101)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 820201").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id IN (820101,820102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 820201").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 82001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (820003,820004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'elseed2'").update();
    }

    @Test
    void assignedRequiresYearAnd400sWithoutIt() throws Exception {
        mvc.perform(get("/api/exams/assigned").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Year is required"));
    }

    @Test
    void assignedSplitsYearYYYYDashYYAndReturnsArrayAggAsStringLists() throws Exception {
        mvc.perform(get("/api/exams/assigned").param("year", "2027-28").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].exam_id").value("820101"))
           .andExpect(jsonPath("$[0].district_ids[0]").value("820003"))
           .andExpect(jsonPath("$[0].block_names[0]").value("ELIST BLOCK"))
           .andExpect(jsonPath("$[?(@.exam_id=='820102')]").isEmpty()); // zero-applicant exam excluded (INNER JOIN)
    }

    @Test
    void notAssignedReturnsOnlyTheZeroApplicantExam() throws Exception {
        mvc.perform(get("/api/exams/notassigned").param("year", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].exam_id").value("820102"))
           .andExpect(jsonPath("$[?(@.exam_id=='820101')]").isEmpty());
    }

    @Test
    void freezeExamHasNoExistenceCheck() throws Exception {
        mvc.perform(put("/api/exams/999999999/freeze").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("✅ Exam frozen successfully")); // no existence check quirk

        mvc.perform(put("/api/exams/820102/freeze").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk());
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.examination WHERE exam_id = 820102").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(frozen).isEqualTo("Y");
    }

    @Test
    void deleteExamIsTransactionalAndRemovesChildRowsFirst() throws Exception {
        mvc.perform(delete("/api/exams/820101").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Exam and related data deleted successfully"));

        Integer remainingApplicantExam = jdbc.sql("SELECT COUNT(*)::int FROM pp.applicant_exam WHERE exam_id = 820101")
                .query(Integer.class).single();
        Integer remainingExam = jdbc.sql("SELECT COUNT(*)::int FROM pp.examination WHERE exam_id = 820101")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(remainingApplicantExam).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(remainingExam).isEqualTo(0);
    }

    @Test
    void deleteExamNoExistenceCheckStill200sForMissingId() throws Exception {
        mvc.perform(delete("/api/exams/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Exam and related data deleted successfully"));
    }

    @Test
    void listingEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/assigned").param("year", "2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(put("/api/exams/820101/freeze").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(delete("/api/exams/820101").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
