package com.rcf.imas.modules.teacher;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TeacherScopedReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;
    String noTeacherRowToken;

    @BeforeEach
    void seed() {
        cleanup();

        // pp."user" rows: the teacher whose portal we're testing, a second teacher with a teacher row but
        // NO classroom (to prove scoping returns empty, not a leak), and the coordinator who will surface
        // in /coordinators.
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966101,'teacherUser966101','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966102,'otherTeacherUser966102','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn, full_name, user_email, contact_no, active_yn) " +
                "VALUES (966103,'coordUser966103','x','N','Coord Full Name 966103','coord966103@example.com','9000000003','Y')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name, qualification, experience_yrs, doj, contact_no) " +
                "VALUES (966101,966101,'Teacher 966101','B.Ed',5,'2020-01-01','9000000001')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966102,966102,'Other Teacher 966102')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966101,'ENG1','English')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (966101,966101,'ENGLISH')").update();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966101,'Cohort 966101')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966101,'Batch 966101',966101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id, class_link) " +
                "VALUES (966101,'Classroom 966101',966101,966101,'http://class.link/966101')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966101,966101)").update();

        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966103,966101)").update();

        teacherToken = jwt.issueFinalToken("966101", "teacherUser966101", "TEACHER");
        otherTeacherToken = jwt.issueFinalToken("966102", "otherTeacherUser966102", "TEACHER");
        noTeacherRowToken = jwt.issueFinalToken("999999", "noSuchTeacher999999", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966101").update();
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 966101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966101").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966101,966102,966103)").update();
    }

    @Test
    void cohortsReturnsOnlyReachableCohort() throws Exception {
        mvc.perform(get("/api/teacher/cohorts").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].cohort_number").value(966101))
           .andExpect(jsonPath("$[0].cohort_name").value("Cohort 966101"));
    }

    @Test
    void cohortsScopedToTeacherReturnsEmptyForTeacherWithNoClassroom() throws Exception {
        mvc.perform(get("/api/teacher/cohorts").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void batchesNoFilterReturnsReachableBatch() throws Exception {
        mvc.perform(get("/api/teacher/batches").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(966101))
           .andExpect(jsonPath("$[0].batch_name").value("Batch 966101"));
    }

    @Test
    void batchesFilteredByCohortNumber() throws Exception {
        mvc.perform(get("/api/teacher/batches").param("cohort_number", "966101")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(966101));
    }

    @Test
    void profileReturnsOwnProfileWithSubjectsClassroomsAndPhotoLink() throws Exception {
        mvc.perform(get("/api/teacher/profile").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.teacher_id").value(966101))
           .andExpect(jsonPath("$.teacher_name").value("Teacher 966101"))
           .andExpect(jsonPath("$.username").value("teacherUser966101"))
           .andExpect(jsonPath("$.subjects_taught").value("English (ENGLISH)"))
           .andExpect(jsonPath("$.assigned_classrooms").value("Classroom 966101"))
           .andExpect(jsonPath("$.photo_link").value("user-photos/966101.jpg"));
    }

    @Test
    void profileReturns404WhenNoTeacherRowForUser() throws Exception {
        mvc.perform(get("/api/teacher/profile").header("Authorization", "Bearer " + noTeacherRowToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Teacher profile not found"));
    }

    @Test
    void coordinatorsReturnsSharedCoordinatorWithPhotoLink() throws Exception {
        mvc.perform(get("/api/teacher/coordinators").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].user_id").value("966103"))
           .andExpect(jsonPath("$[0].full_name").value("Coord Full Name 966103"))
           .andExpect(jsonPath("$[0].shared_batches").value("Batch 966101"))
           .andExpect(jsonPath("$[0].photo_link").value("user-photos/966103.jpg"));
    }

    @Test
    void unauthenticatedRequestRejected() throws Exception {
        mvc.perform(get("/api/teacher/cohorts")).andExpect(status().isUnauthorized());
    }
}
