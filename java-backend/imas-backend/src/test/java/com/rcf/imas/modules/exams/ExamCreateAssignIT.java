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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamCreateAssignIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('caseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='caseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "caseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, active_yn) VALUES (83001,'CA Centre','Y')
            ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (830001,'CA DIVISION','DIVISION') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830002,'CA EDU DIST','EDUCATION DISTRICT',830001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830003,'CA BLOCK','BLOCK',830002) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (830201,'CA Exam','2027-06-01','09:00:00','11:00:00','2027',83001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (830101,2027,24083000001,830003,'AssignKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_id, shortlist_batch_name, shortlisted_year) VALUES (8301, 'CA Batch', 2027) ON CONFLICT (shortlist_batch_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlist_batch_id, shortlisted_yn) VALUES (830101, 8301, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.hall_ticket_sequence WHERE juris_code = '830002'").update();
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 830101 OR exam_id IN (830201, 830202, 830203)").update();
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 830101").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = 8301").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 830101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id IN (830201, 830202, 830203)").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 83001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (830001,830002,830003)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'caseed'").update();
    }

    @Test
    void createExamOnlyMissingFieldsIs400WithErrorKey() throws Exception {
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"examName\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields."));
    }

    @Test
    void createExamOnlyAllowsOmittedAcademicYearOrphanQuirk() throws Exception {
        var result = mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"Orphan Exam\",\"date\":\"2027-08-01\",\"startTime\":\"09:00\",\"endTime\":\"11:00\"}"))
           .andExpect(status().isCreated()).andReturn();
        String examId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.examId").toString();
        try {
            // NOTE: Optional<String>.orElse() cannot distinguish "row exists with NULL column" from "no row" --
            // both collapse to Optional.empty() (DataAccessUtils.optionalResult wraps via Optional.ofNullable).
            // Query into a Map instead so a genuine SQL NULL is observable as a real Java null.
            java.util.Map<String, Object> row = jdbc.sql("SELECT exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                    .param("id", examId)
                    .query((rs, i) -> { var m = new java.util.HashMap<String, Object>(); m.put("exam_year", rs.getString("exam_year")); return m; })
                    .single();
            org.assertj.core.api.Assertions.assertThat(row.get("exam_year")).isNull(); // Firm Decision 11d: NULL exam_year, no error
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
        }
    }

    @Test
    void createExamOnlyDetectsTimeConflict() throws Exception {
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"Conflicting\",\"date\":\"2027-06-01\",\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"academic_year\":\"2027-28\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Time conflict"))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("09:00")));
    }

    @Test
    void createExamOnlySuccessDerivesExamYearFromAcademicYear() throws Exception {
        var result = mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"CA Exam 2\",\"date\":\"2027-09-01\",\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"academic_year\":\"2027-28\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Exam created successfully")).andReturn();
        String examId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.examId").toString();
        try {
            String examYear = jdbc.sql("SELECT exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                    .param("id", examId).query(String.class).single();
            org.assertj.core.api.Assertions.assertThat(examYear).isEqualTo("2027"); // split("-")[0]
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
        }
    }

    @Test
    void assignStudentsMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"division\":830001}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields: examId, division, educationDistrict, blocks[]"));
    }

    @Test
    void assignStudentsNonexistentExamIs404WithErrorKey() throws Exception {
        mvc.perform(post("/api/exams/999999999/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Exam does not exist."));
    }

    @Test
    void assignStudentsNoShortlistedApplicantsIs404WithMessageKey() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[999999],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No shortlisted applicants found for the selected region."));
    }

    @Test
    void assignStudentsSuccessGeneratesHallTicketNumberFromRequestBodyAcademicYearNotExamYear() throws Exception {
        // exam.exam_year='2027' (drives shortlist eligibility) vs academicYear='2028-29' in the request body
        // (drives hall-ticket numbering) -- Firm Decision 11f, two distinct years, never cross-validated.
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.totalAssigned").value(1))
           .andExpect(jsonPath("$.applicants[0].applicant_id").value("830101"))
           .andExpect(jsonPath("$.applicants[0].hall_ticket_no").value("28020001")); // "28"+"02"(last2 of edu-district 830002, per Node)+"0001"
    }

    @Test
    void assignStudentsRerunBurnsSequenceGapButDoesNotDuplicateRow() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated());

        // Re-run against the SAME already-assigned cohort: ON CONFLICT (applicant_id, exam_id) DO NOTHING means no
        // new applicant_exam row, but hall_ticket_sequence.last_sequence is still bumped (gap, not collision).
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.applicants[0].hall_ticket_no").value("28020002")); // sequence bumped to 2...

        Integer rows = jdbc.sql("SELECT COUNT(*)::int FROM pp.applicant_exam WHERE applicant_id = 830101 AND exam_id = 830201")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1); // ...but still only ONE applicant_exam row
        String storedTicket = jdbc.sql("SELECT pp_hall_ticket_no FROM pp.applicant_exam WHERE applicant_id = 830101 AND exam_id = 830201")
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(storedTicket).isEqualTo("28020001"); // first-assignment ticket kept (DO NOTHING)
    }

    @Test
    void createAndAssignEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
