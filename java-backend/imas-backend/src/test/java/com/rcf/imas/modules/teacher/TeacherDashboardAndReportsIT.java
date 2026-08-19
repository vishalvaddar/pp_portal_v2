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
class TeacherDashboardAndReportsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String emptyTeacherToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966301,'teacherUser966301','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966303,'emptyTeacherUser966303','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // 966301 has classrooms/sessions/attendance; 966302 has a pp.teacher row but ZERO classrooms/
        // sessions (proves the LEFT JOIN-vs-JOIN asymmetry, ground truth §7.7).
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966301,966301,'Teacher 966301')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966302,966303,'Empty Teacher 966302')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966301,'PHY1','Physics')").update();
        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966302,'CHE1','Chemistry')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966301,'Cohort 966301')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966301,'Batch 966301',966301)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966302,'Batch 966302',966301)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966301,'Physics Room',966301,966301)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966302,'Chem Room',966302,966301)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966301,966301)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966302,966302)").update();

        // 8 sessions across Jan-Aug 2025: Physics room Jan-May (5), Chem room Jun-Aug (3).
        // monthlyTrend's ORDER BY ... ASC LIMIT 6 must return Jan-Jun (the EARLIEST 6 months), excluding
        // Jul/Aug -- ground truth §7.2, the mislabeled-comment quirk.
        String[] months = {"01","02","03","04","05","06","07","08"};
        for (int idx = 0; idx < months.length; idx++) {
            int sessionId = 966301 + idx;
            int classroomId = idx < 5 ? 966301 : 966302;
            jdbc.sql("INSERT INTO pp.class_session(session_id, classroom_id, teacher_id, session_date, start_time, end_time) " +
                    "VALUES (:sid,:cid,966301,:date,'09:00:00','10:00:00')")
                    .param("sid", sessionId).param("cid", classroomId)
                    .param("date", java.sql.Date.valueOf("2025-" + months[idx] + "-15")).update();
        }
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966311,'Student 966311',966301,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(attendance_id, session_id, student_id, status, attendance_percent) " +
                "VALUES (966301,966301,966311,'PRESENT',90.00)").update();
        jdbc.sql("INSERT INTO pp.student_attendance(attendance_id, session_id, student_id, status, attendance_percent) " +
                "VALUES (966302,966302,966311,'PRESENT',80.00)").update();
        jdbc.sql("SELECT setval('pp.attendance_id_seq', (SELECT MAX(attendance_id)::bigint FROM pp.student_attendance))").query(Long.class).single();

        teacherToken = jwt.issueFinalToken("966301", "teacherUser966301", "TEACHER");
        emptyTeacherToken = jwt.issueFinalToken("966303", "emptyTeacherUser966303", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE attendance_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id BETWEEN 966301 AND 966308").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 966311").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966301").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966301,966303)").update();
    }

    @Test
    void dashboardOverviewSubjectAnalysisAndMonthlyTrendEarliestSix() throws Exception {
        mvc.perform(get("/api/teacher/dashboard").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overview.total_conducted").value("8"))
           .andExpect(jsonPath("$.overview.avg_attendance").value("85.00"))
           .andExpect(jsonPath("$.overview.total_batches").value("2"))
           .andExpect(jsonPath("$.subjectAnalysis", hasSize(2)))
           .andExpect(jsonPath("$.subjectAnalysis[0].subject_name").value("Physics"))
           .andExpect(jsonPath("$.subjectAnalysis[0].classes_taken").value("5"))
           .andExpect(jsonPath("$.subjectAnalysis[1].subject_name").value("Chemistry"))
           .andExpect(jsonPath("$.subjectAnalysis[1].classes_taken").value("3"))
           // monthlyTrend quirk (ground truth §7.2): earliest 6 months (Jan-Jun 2025), NOT the most recent 6.
           .andExpect(jsonPath("$.monthlyTrend", hasSize(6)))
           .andExpect(jsonPath("$.monthlyTrend[0].month_label").value("Jan 2025"))
           .andExpect(jsonPath("$.monthlyTrend[5].month_label").value("Jun 2025"));
    }

    @Test
    void dashboardZeroedForTeacherWithNoSessions() throws Exception {
        // LEFT JOIN (query 1) vs JOIN+GROUP BY (queries 2-4) asymmetry, ground truth §7.7: overview is
        // zeroed, subjectAnalysis/monthlyTrend are empty arrays, total_batches is "0" (plain aggregate,
        // no GROUP BY, always returns one row even over zero matches).
        mvc.perform(get("/api/teacher/dashboard").header("Authorization", "Bearer " + emptyTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overview.total_conducted").value("0"))
           .andExpect(jsonPath("$.overview.avg_attendance").value("0"))
           .andExpect(jsonPath("$.overview.total_batches").value("0"))
           .andExpect(jsonPath("$.subjectAnalysis", hasSize(0)))
           .andExpect(jsonPath("$.monthlyTrend", hasSize(0)));
    }

    @Test
    void reportsMissingDatesReturns400() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("fromDate and toDate are required"));
    }

    @Test
    void reportsReturnsSessionsInRangeOrderedByDateWithAttendanceFlag() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes")
                .param("fromDate", "2025-01-01").param("toDate", "2025-03-31")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.classes", hasSize(3)))
           .andExpect(jsonPath("$.classes[0].date").value("2025-01-15"))
           .andExpect(jsonPath("$.classes[0].subject_name").value("Physics"))
           .andExpect(jsonPath("$.classes[0].cohort_name").value("Cohort 966301"))
           .andExpect(jsonPath("$.classes[0].attendance_marked").value(true))
           .andExpect(jsonPath("$.classes[2].date").value("2025-03-15"))
           .andExpect(jsonPath("$.classes[2].attendance_marked").value(false));
    }

    @Test
    void reportsScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes")
                .param("fromDate", "2025-01-01").param("toDate", "2025-12-31")
                .header("Authorization", "Bearer " + emptyTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.classes", hasSize(0)));
    }
}
