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

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorAttendanceReportIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965801,'coordUser965801','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965801,'Attendance Report Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965801,'Attendance Report Batch',965801)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965801,965801)").update();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965801,'MATH','Mathematics')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965801,'Report Teacher 965801')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965801,'Attendance Report Classroom',965801,965801)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965801,965801)").update();

        // Student A: stays ACTIVE the whole range -- present in both sessions.
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965801,'Active Student 965801',965801,'F','ACTIVE')").update();
        // Student B: goes inactive mid-range (2026-06-10) -- only counted for sessions BEFORE that date.
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965802,'Mid Range Inactive Student 965802',965801,'F','INACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965802,'Left program','2026-06-10')").update();

        // Session 1 (before inactivation): both students attend.
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965801,965801,'2026-06-05','09:00:00','10:00:00',965801)
                """).update();
        // Session 2 (after inactivation): only student A is eligible (student B excluded by the
        // `s.session_date < bs.inactive_date` join condition in student_sessions).
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965802,965801,'2026-06-15','09:00:00','10:00:00',965801)
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965801,965801,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965801,965802,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965802,965801,'PRESENT')").update();

        coordToken = jwt.issueFinalToken("965801", "coordUser965801", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (965801,965802,965803)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (965801,965802,965803)").update();
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 965802").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965801,965802)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965801").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965801").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965801").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965801").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965801").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965801").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965801").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965801").update();
    }

    @Test
    void attendanceReportShapeAndCounts() throws Exception {
        mvc.perform(get("/api/coordinator/reports/attendance")
                .param("batchId", "965801").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reportId").value("ATT-965801-2026-06-01-2026-06-30"))
           .andExpect(jsonPath("$.batch_name").value("Attendance Report Batch"))
           .andExpect(jsonPath("$.cohort_name").value("Attendance Report Cohort"))
           .andExpect(jsonPath("$.subjects.MATH[0].teacher_name").value("Report Teacher 965801"))
           .andExpect(jsonPath("$.subjects.MATH[0].conducted").value(2))
           // Student A (965801): attended both sessions -> attended=2 under MATH/Report Teacher.
           // NOTE: comparing directly against java.util.List.of(2) trips a Spring JsonPathExpectationsHelper
           // quirk -- when actual and expected are both java.util.List but of different concrete classes
           // (net.minidev.json.JSONArray vs the JDK's immutable List12), it re-evaluates the JSON path
           // reflectively against the expected value's exact runtime class and silently yields null. Using a
           // Hamcrest contains() matcher (same strength: single-element, value-equal) avoids that path.
           .andExpect(jsonPath("$.students[?(@.id=='965801')].subjects['MATH']['Report Teacher 965801']").value(contains(2)))
           // Student B (965802): only eligible for the pre-inactivation session -> attended=1.
           .andExpect(jsonPath("$.students[?(@.id=='965802')].subjects['MATH']['Report Teacher 965801']").value(contains(1)));
    }

    @Test
    void attendanceReportToleratesNullTeacherName() throws Exception {
        // A conducted session whose classroom/session teacher is unresolved -> LEFT JOIN teacher yields a
        // null teacher_name. Node serializes teacher_name:null and returns 200; Java must NOT 500 (the old
        // Map.of(...) NPE'd on the null value).
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965803,965801,'2026-06-20','09:00:00','10:00:00',NULL)
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965803,965801,'PRESENT')").update();

        mvc.perform(get("/api/coordinator/reports/attendance")
                .param("batchId", "965801").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(content().string(org.hamcrest.Matchers.containsString("\"teacher_name\":null")));

        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965803").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 965803").update();
    }

    @Test
    void attendanceReportMissingParamsReturns200NotFiveHundred() throws Exception {
        // Node destructures req.query with no guard -- a missing batchId/fromDate/toDate runs the query with
        // undefined, binds SQL NULL, and returns an empty-shaped 200 (reportsController.js:13-14), not a 500.
        mvc.perform(get("/api/coordinator/reports/attendance")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.batch_name").value(""))
           .andExpect(jsonPath("$.cohort_name").value(""))
           .andExpect(jsonPath("$.students", org.hamcrest.Matchers.hasSize(0)));
    }
}
