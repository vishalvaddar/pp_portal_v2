package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ActiveTimetableWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (961001,'ttwAdmin961','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (961001,'TW1','TTW Existing Subject 961')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (961002,'ttwTeacherLogin961','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (961001,961002,'TTW Teacher 961')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("961001", "ttwAdmin961", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 961001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 961001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_name LIKE 'TTW %'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (961001,961002)").update();
    }

    @Test
    void addSubjectSucceedsAndUsesJwtPrincipalNotBodyForCreatedBy() throws Exception {
        String body = """
            {"subject_code":"TW2","subject_name":"TTW New Subject 961","admin_id":"999999"}
            """; // admin_id in the body must be IGNORED -- created_by comes from the JWT principal (Firm Decision 2)
        mvc.perform(post("/api/activetimetable/subject/add").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Subject added successfully"))
           .andExpect(jsonPath("$.data.subject_name").value("TTW New Subject 961"))
           .andExpect(jsonPath("$.data.created_by").value("961001")); // JWT principal userId, not "999999"
    }

    @Test
    void addSubjectDuplicateNameIs400() throws Exception {
        String body = """
            {"subject_code":"TW1","subject_name":"TTW Existing Subject 961"}
            """;
        mvc.perform(post("/api/activetimetable/subject/add").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Subject name already exists"));
    }

    @Test
    void teacherSkillsReturnsEmptySkillsAndFullSubjectList() throws Exception {
        mvc.perform(get("/api/activetimetable/teacher-skills/961001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.skills", hasSize(0)))
           .andExpect(jsonPath("$.allSubjects[?(@.subject_id==961001)]").exists());
    }

    @Test
    void manageTeacherSkillAddThenGetShowsIt() throws Exception {
        String addBody = """
            {"teacherId":"961001","subjectId":"961001","medium":"KANNADA","action":"add"}
            """;
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(addBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Skill updated successfully"));

        mvc.perform(get("/api/activetimetable/teacher-skills/961001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.skills[0].medium").value("KANNADA"));
    }

    @Test
    void manageTeacherSkillDeleteWithMismatchedCaseSilentlyDeletesNothing() throws Exception {
        // seed the skill directly as KANNADA (uppercase, per addTeacherSkill's .toUpperCase())
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (961001,961001,'KANNADA')").update();

        String deleteBodyWrongCase = """
            {"teacherId":"961001","subjectId":"961001","medium":"Kannada","action":"delete"}
            """; // lowercase-mixed medium -- deleteTeacherSkill does NOT uppercase (quirk 4d)
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(deleteBodyWrongCase))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Skill updated successfully")); // no error even though 0 rows deleted

        Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.teacher_subject WHERE teacher_id=961001 AND subject_id=961001")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillThere).isEqualTo(1); // row survives the case-mismatched delete
    }

    @Test
    void manageTeacherSkillDuplicateAddIsRaw500NotHandled() throws Exception {
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (961001,961001,'KANNADA')").update();
        String addBody = """
            {"teacherId":"961001","subjectId":"961001","medium":"KANNADA","action":"add"}
            """;
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(addBody))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Database error: ")));
    }
}
