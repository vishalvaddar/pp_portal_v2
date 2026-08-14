package com.rcf.imas.modules.coordinator;

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
class CoordinatorTeacherPerformanceAndClassDetailsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966001,'coordUser966001','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966001,'Perf Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966001,'Perf Batch',966001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966001,966001)").update();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966001,'ENG','English')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966001,'Perf Teacher 966001')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        // classroom.teacher_id = the "default" teacher (used by teacher-performance's scheduled query and
        // batch-class-details' classroom-batch join).
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966001,'Perf Classroom',966001,966001)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966001,966001)").update();

        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) VALUES (966001,966001,'MONDAY','09:00:00','10:00:00')").update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        // One session actually run (session-level teacher_id = 966001, same as classroom's default), on the
        // one scheduled Monday in range (2026-06-08) -- scheduled=1, conducted=1, completion=100.0.
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (966001,966001,'2026-06-08','09:00:00','10:00:00',966001)
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("966001", "coordUser966001", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 966001").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 966001").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 966001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966001").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 966001").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966001").update();
    }

    @Test
    void teacherPerformanceShapeAndCompletion() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-performance")
                .param("teacherId", "966001").param("fromDate", "2026-06-08").param("toDate", "2026-06-08")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reportId").value("TP-966001-2026-06-08-2026-06-08"))
           .andExpect(jsonPath("$.subjects", hasSize(1)))
           .andExpect(jsonPath("$.subjects[0].subject").value("ENG"))
           .andExpect(jsonPath("$.subjects[0].scheduled").value(1))
           .andExpect(jsonPath("$.subjects[0].conducted").value(1))
           // Node emits an integral completion as 100 (JS Number), NOT 100.0 -- must be a bare integer on the wire.
           .andExpect(jsonPath("$.subjects[0].completion").value(100))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("\"completion\":100")))
           .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"completion\":100.0"))));
    }

    @Test
    void teacherPerformanceMissingParamsReturns400() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-performance")
                .param("teacherId", "966001")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("teacherId, fromDate, and toDate required"));
    }

    @Test
    void batchClassDetailsShapeAndAttendanceMarkedFlag() throws Exception {
        mvc.perform(get("/api/coordinator/reports/batch-class-details")
                .param("batchId", "966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(1))
           .andExpect(jsonPath("$.classes", hasSize(1)))
           .andExpect(jsonPath("$.classes[0].session_id").value(966001))
           .andExpect(jsonPath("$.classes[0].teacher_name").value("Perf Teacher 966001"))
           .andExpect(jsonPath("$.classes[0].cohort_name").value("Perf Cohort"))
           .andExpect(jsonPath("$.classes[0].classroom_name").value("Perf Classroom"))
           .andExpect(jsonPath("$.classes[0].attendance_marked").value(false));
    }

    @Test
    void teacherClassDetailsNumericIdFilterUsesTeacherIdColumn() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-class-details")
                .param("teacherId", "966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(1))
           .andExpect(jsonPath("$.classes[0].teacher_name").value("Perf Teacher 966001"))
           .andExpect(jsonPath("$.classes[0].batch_name").value("Perf Batch"));
    }

    @Test
    void teacherClassDetailsNonNumericFilterUsesTeacherNameColumn() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-class-details")
                .param("teacherId", "Perf Teacher 966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(1))
           .andExpect(jsonPath("$.classes[0].classroom_name").value("Perf Classroom"));
    }

    @Test
    void batchClassDetailsMissingParamsReturns200EmptyNotFiveHundred() throws Exception {
        // Node's getBatchClassDetails runs the query unguarded with undefined query params -> empty 200
        // (reportsController.js:487-488), not a 500.
        mvc.perform(get("/api/coordinator/reports/batch-class-details")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(0))
           .andExpect(jsonPath("$.classes", hasSize(0)));
    }

    @Test
    void teacherClassDetailsMissingParamsReturns200EmptyNotFiveHundred() throws Exception {
        // Node's getTeacherClassDetails runs the query unguarded with undefined query params -> empty 200
        // (reportsController.js:575-576), not a 500.
        mvc.perform(get("/api/coordinator/reports/teacher-class-details")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(0))
           .andExpect(jsonPath("$.classes", hasSize(0)));
    }
}
