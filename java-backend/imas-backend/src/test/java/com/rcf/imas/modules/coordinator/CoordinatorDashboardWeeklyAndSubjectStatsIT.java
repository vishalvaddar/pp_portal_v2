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

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorDashboardWeeklyAndSubjectStatsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    /** Reproduces JS `Date#getDay()` (Sunday=0) math from attendanceAnalyticsController.js, independently
     *  of the production Java, so the test seeds the SAME window the endpoint will query no matter which
     *  day this test actually runs on. */
    private static LocalDate[] lastMondayToSundayWindow(LocalDate today) {
        int day = today.getDayOfWeek().getValue() % 7; // MONDAY=1..SATURDAY=6, SUNDAY=7 -> 0 (Sunday=0 parity)
        LocalDate lastSunday = today.minusDays(day);
        LocalDate lastMonday = lastSunday.minusDays(6);
        return new LocalDate[]{lastMonday, lastSunday};
    }

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966301,'dashUser966301','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // --- Fixture A: weekly-avg (batch 966301) ---
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966301,'Dash Cohort A')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966311,'Dash Cohort B')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966301,'Dash Batch A',966301)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966311,'Dash Batch B',966311)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966301,966301),(966301,966311)").update();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966301,'Dash Room A')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966301,966301)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966301,'Weekly Student 1',966301,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966302,'Weekly Student 2',966301,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966303,'Weekly Student 3',966301,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        LocalDate[] window = lastMondayToSundayWindow(LocalDate.now());
        String sessionDate = window[0].toString(); // lastMonday, guaranteed inside [fromDate,toDate]

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966301,966301, :sessionDate::date, '09:00:00','10:00:00')
                """).param("sessionDate", sessionDate).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        // PRESENT=100, LATE JOINED=50, ABSENT=0 -> avg = (100+50+0)/3 = 50.0
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966301,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966302,'LATE JOINED')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966303,'ABSENT')").update();

        // --- Fixture B: teacher-subject-stats, current month (batch 966311) ---
        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966311,'SCI','Science')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966311,'Dash Teacher B')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966311,'Dash Room B',966311,966311)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966311,966311)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966311,'Subj Student 1',966311,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966312,'Subj Student 2',966311,'ACTIVE')").update();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966311,966311, date_trunc('month', CURRENT_DATE)::date, '09:00:00','10:00:00')
                """).update();

        // 1 PRESENT (counted), 1 LATE JOINED (must be EXCLUDED per the quirk shared with #36) -> 2 active
        // students, 1 session held, numerator=1 -> percentage = (1/(1*2))*100 = 50.00
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966311,966311,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966311,966312,'LATE JOINED')").update();

        coordToken = jwt.issueFinalToken("966301", "dashUser966301", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966301,966302,966303,966311,966312)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966311").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966311").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE user_id = 966301").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966301").update();
    }

    @Test
    void batchWeeklyAverageReturnsWeightedAverageAsNumber() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/batch-weekly-avg")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.batch_id==966301)]", hasSize(1)))
           .andExpect(jsonPath("$[?(@.batch_id==966301)].batch_name", org.hamcrest.Matchers.contains("Dash Batch A")))
           .andExpect(jsonPath("$[?(@.batch_id==966301)].avg_attendance", org.hamcrest.Matchers.contains(50)));
    }

    @Test
    void teacherSubjectStatsExcludesLateJoinedFromNumerator() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-subject-stats").param("batchId", "966311")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].subject_code").value("SCI"))
           .andExpect(jsonPath("$[0].teacher_name").value("Dash Teacher B"))
           .andExpect(jsonPath("$[0].percentage").value("50.00"));
    }
}
