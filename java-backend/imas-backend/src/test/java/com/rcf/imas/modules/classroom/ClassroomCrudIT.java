package com.rcf.imas.modules.classroom;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ClassroomCrudIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;
    String studentToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910001,'crAdmin910','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (910001,'CR1','Classroom Subject 910')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910002,'crTeacherLogin910','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (910001,910002,'Classroom Teacher 910')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id) VALUES (910001,910001)").update();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (910001,'Classroom Platform 910')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (910001,'Cohort CR910')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910001,'CR Batch A',910001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910002,'CR Batch B',910001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("910001", "crAdmin910", "ADMIN");
        studentToken = jwt.issueFinalToken("910099", "crStudent910", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (SELECT classroom_id FROM pp.classroom WHERE classroom_name LIKE 'CR %')").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_name LIKE 'CR %'").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 910001").update();
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 910001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 910001").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 910001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 910001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (910001,910002)").update();
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/classrooms/subjects")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminTokenIs403() throws Exception {
        mvc.perform(get("/api/classrooms/subjects").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void subjectsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/classrooms/subjects").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.subject_id==910001)].subject_name").value("Classroom Subject 910"));
    }

    @Test
    void platformsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/classrooms/platforms").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.platform_id==910001)].platform_name").value("Classroom Platform 910"));
    }

    @Test
    void teachersBySubjectJoinsThroughUserForLoginName() throws Exception {
        mvc.perform(get("/api/classrooms/teachers/910001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].teacher_id").value(910001))
           .andExpect(jsonPath("$[0].teacher_name").value("crTeacherLogin910")); // u.user_name, NOT t.teacher_name
    }

    @Test
    void batchesByCohortClassroomSideReturnsIdAndNameOnly() throws Exception {
        mvc.perform(get("/api/classrooms/batches/910001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
           .andExpect(jsonPath("$[0].batch_id").exists())
           .andExpect(jsonPath("$[0].batch_name").exists())
           .andExpect(jsonPath("$[0].cohort_number").doesNotExist()); // classroom-side shape: batch_id+batch_name ONLY
    }

    @Test
    void createUpdateGetListDeleteRoundTripWithBatchIdsResyncSemantics() throws Exception {
        // create with batch_ids = [910001, 910002]
        String createBody = """
            {"classroom_name":"CR Full Classroom","subject_id":910001,"teacher_id":910001,"platform_id":910001,
             "class_link":"https://x/y","active_yn":"Y","created_by":910001,"batch_ids":[910001,910002]}
            """;
        String createResp = mvc.perform(post("/api/classrooms").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("Classroom created successfully"))
            .andExpect(jsonPath("$.classroom_id").exists())
            .andReturn().getResponse().getContentAsString();
        int classroomId = com.jayway.jsonpath.JsonPath.read(createResp, "$.classroom_id");

        // list: batch_ids aggregated, both linked
        mvc.perform(get("/api/classrooms").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.classroom_id==" + classroomId + ")].batch_ids[0]").exists());

        // update WITHOUT batch_ids key at all -> links untouched (partial update branch)
        String partialUpdate = """
            {"classroom_name":"CR Full Classroom Renamed","subject_id":910001,"teacher_id":910001,
             "platform_id":910001,"class_link":"https://x/y2","active_yn":"Y","updated_by":910001}
            """;
        mvc.perform(put("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(partialUpdate))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Classroom updated"));

        Integer linkCountAfterPartial = jdbc.sql("SELECT COUNT(*) FROM pp.classroom_batch WHERE classroom_id = :id")
            .param("id", classroomId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(linkCountAfterPartial).isEqualTo(2); // untouched

        // update WITH batch_ids = [] -> full resync, unlinks everything
        String emptyResync = """
            {"classroom_name":"CR Full Classroom Renamed","subject_id":910001,"teacher_id":910001,
             "platform_id":910001,"class_link":"https://x/y2","active_yn":"Y","updated_by":910001,"batch_ids":[]}
            """;
        mvc.perform(put("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(emptyResync))
           .andExpect(status().isOk());

        Integer linkCountAfterResync = jdbc.sql("SELECT COUNT(*) FROM pp.classroom_batch WHERE classroom_id = :id")
            .param("id", classroomId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(linkCountAfterResync).isEqualTo(0); // fully unlinked

        // delete
        mvc.perform(delete("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Classroom deleted successfully"));

        // delete again -> 404
        mvc.perform(delete("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Classroom not found"));
    }

    @Test
    void updateUnknownIdIs404() throws Exception {
        String body = """
            {"classroom_name":"X","subject_id":910001,"teacher_id":910001,"platform_id":910001,
             "class_link":"x","active_yn":"Y","updated_by":910001}
            """;
        mvc.perform(put("/api/classrooms/999999999").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Classroom not found"));
    }

    @Test
    void createMissingRequiredColumnSurfacesRawDbErrorAs500() throws Exception {
        // classroom_name is NOT NULL at the DB level; Node never pre-validates it -- the raw PG error message
        // is what the classroom-side catch(err) => {error: err.message} pattern would surface (convention #7).
        String body = """
            {"subject_id":910001,"teacher_id":910001,"platform_id":910001,"active_yn":"Y","created_by":910001}
            """;
        mvc.perform(post("/api/classrooms").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").exists());
    }
}
