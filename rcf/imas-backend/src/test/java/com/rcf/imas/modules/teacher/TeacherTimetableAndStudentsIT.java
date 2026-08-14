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
class TeacherTimetableAndStudentsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966201,'teacherUser966201','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966202,'otherTeacherUser966202','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966201,966201,'Teacher 966201')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966202,966202,'Other Teacher 966202')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966201,'MAT1','Maths')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966201,'Cohort 966201')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        // Two batches under the same cohort: batch 966201 fed by classroom 966201, batch 966202 fed by
        // classroom 966202 -- lets the timetable batchId filter and the students cohort+batch filter
        // both discriminate cleanly.
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966201,'Batch 966201',966201)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966202,'Batch 966202',966201)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966201,'Classroom 966201',966201,966201)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966202,'Classroom 966202',966201,966201)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966201,966201)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966202,966202)").update();

        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966201,966201,'MONDAY','09:00:00','10:00:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966202,966201,'WEDNESDAY','11:00:00','12:00:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966203,966202,'FRIDAY','08:00:00','09:00:00')").update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        // Students: 966211 ACTIVE in batch 966201; 966212 INACTIVE in batch 966201 with TWO
        // pp.inactive_students rows (fan-out quirk, ground truth §7.3); 966213 ACTIVE in batch 966202.
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966211,'Student 966211',966201,'F','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966212,'Student 966212',966201,'M','INACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966213,'Student 966213',966202,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) " +
                "VALUES (966212,'Reason A','2025-01-01')").update();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) " +
                "VALUES (966212,'Reason B','2025-02-01')").update();

        teacherToken = jwt.issueFinalToken("966201", "teacherUser966201", "TEACHER");
        otherTeacherToken = jwt.issueFinalToken("966202", "otherTeacherUser966202", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 966212").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966211,966212,966213)").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id IN (966201,966202,966203)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966201").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966201").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966201,966202)").update();
    }

    @Test
    void timetableNoFilterReturnsAllRowsOrderedByDayThenTime() throws Exception {
        mvc.perform(get("/api/teacher/timetable").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(3)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"))
           .andExpect(jsonPath("$[2].day_of_week").value("FRIDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"));
    }

    @Test
    void timetableFilteredByBatchIdReturnsOnlyThatClassroomsRows() throws Exception {
        mvc.perform(get("/api/teacher/timetable").param("batchId", "966202")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].day_of_week").value("FRIDAY"));
    }

    @Test
    void timetableScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/timetable").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void studentsNoFilterFansOutOnInactiveJoin() throws Exception {
        // 966211 (1 row) + 966212 (2 rows, one per inactive_students entry) + 966213 (1 row) = 4 rows.
        mvc.perform(get("/api/teacher/students").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(4)))
           .andExpect(jsonPath("$[?(@.student_id=='966212')]", hasSize(2)));
    }

    @Test
    void studentsCohortAndBatchFilterScopesToOneBatch() throws Exception {
        mvc.perform(get("/api/teacher/students")
                .param("cohortNumber", "966201").param("batchId", "966202")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].student_id").value("966213"));
    }

    @Test
    void studentsScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/students").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void inactiveHistoryOrderedDescByDateNoOwnershipCheck() throws Exception {
        // IDOR preserved (ground truth §7.1): otherTeacherToken -- who does NOT teach this student -- can
        // still read the history. That's the documented, deliberately-ported behavior, not a test bug.
        mvc.perform(get("/api/teacher/students/966212/inactive-history")
                .header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].inactive_reason").value("Reason B"))
           .andExpect(jsonPath("$[1].inactive_reason").value("Reason A"));
    }
}
