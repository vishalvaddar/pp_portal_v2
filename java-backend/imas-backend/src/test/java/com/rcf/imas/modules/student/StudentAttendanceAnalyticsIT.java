package com.rcf.imas.modules.student;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class StudentAttendanceAnalyticsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    // 910001: has attendance data (1 present, 1 absent) across 2 sessions in different weeks, same month;
    //         exam_results.pp_exam_score = 0 -- pins Quirk B (falsy-zero exam_score despite real attendance data).
    // 910002: student_master row exists but batch_id IS NULL -- attendance queries INNER-JOIN through batch_id,
    //         so this yields ZERO rows from the aggregate query -- pins Quirk A ({exam_score:"-"} ONLY).
    String withAttendanceToken;
    String noBatchToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910001,'aa1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910002,'aa2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (910001,'Cohort AA1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910001,'Batch AA1',910001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (910001, 24091000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (910002, 24091000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, user_id, active_yn)
            VALUES (910001, 910001, 24010001, 'Attendance Student', 'F', 910001, 910001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, user_id, active_yn)
            VALUES (910002, 910002, 24010002, 'No Batch Student2', 'M', 910002, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (910001,'AA1','AA Subject')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id) VALUES (910001,'AA Classroom',910001)
            """).update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (910001,910001)").update();

        // Session 1: 2025-06-02 (week starting Monday 2025-06-02), status PRESENT.
        jdbc.sql("""
            INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
            VALUES (910001, 910001, '2025-06-02', '09:00:00', '10:00:00')
            """).update();
        // Session 2: 2025-06-16 (different week, same month), status ABSENT (no attendance row -- but we insert
        // an explicit ABSENT row to distinguish "attended but marked absent" from "no attendance row at all").
        jdbc.sql("""
            INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
            VALUES (910002, 910001, '2025-06-16', '09:00:00', '10:00:00')
            """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (910001, 910001, 'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (910002, 910001, 'ABSENT')").update();

        // pp_exam_score = 0 for student 910001 -- node-pg returns numeric "0" (truthy) so exam_score stays "0", not "-".
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score) VALUES (910001, 0)").update();

        withAttendanceToken = jwt.issueFinalToken("910001", "aa1", "STUDENT");
        noBatchToken = jwt.issueFinalToken("910002", "aa2", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 910001 AND batch_id = 910001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 910001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 910001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 910001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 910001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (910001,910002)").update();
    }

    @Test
    void subjectPerformanceAggregatesBothSessions() throws Exception {
        mvc.perform(get("/api/student/subjects").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].subject_name").value("AA Subject"))
           .andExpect(jsonPath("$[0].total_classes").value("2"))
           .andExpect(jsonPath("$[0].attended_classes").value("1"))
           .andExpect(jsonPath("$[0].attendance_percent").value("50.00"));
    }

    @Test
    void performanceAliasReturnsIdenticalShapeToSubjects() throws Exception {
        mvc.perform(get("/api/student/performance").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].subject_name").value("AA Subject"))
           .andExpect(jsonPath("$[0].attendance_percent").value("50.00"));
    }

    @Test
    void summaryIncludesAttendanceAndZeroExamScoreStaysZero() throws Exception {
        mvc.perform(get("/api/student/summary").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total_classes").value("2"))
           .andExpect(jsonPath("$.attended_classes").value("1"))
           .andExpect(jsonPath("$.attendance_percent").value("50.00"))
           // pp_exam_score numeric(3,0)=0 -> node-pg string "0" -> JS `"0" || "-"` === "0" (NOT "-"); only NULL/no-row -> "-".
           .andExpect(jsonPath("$.exam_score").value("0"));
    }

    // NOTE: the plan's "Quirk A" (response degrades to bare {exam_score:"-"} when the attendance query
    // returns zero rows) is NOT reachable for this SQL: it is a plain aggregate with NO GROUP BY, and a
    // no-GROUP-BY aggregate in Postgres always returns exactly one row (zeros/nulls), even when the
    // sm.batch_id -> classroom_batch INNER JOIN matches nothing (e.g. batch_id IS NULL). Verified against
    // live Node source (studentModel.js's getStudentSummaryModel): `const { rows } = await pool.query(sql,
    // [user_id]); ... return { ...rows[0], exam_score: examRes.rows[0]?.pp_exam_score || "-" };` -- rows[0]
    // is always defined for this query, so `{...rows[0]}` always spreads total_classes/attended_classes/
    // attendance_percent (0/0/null here) into the response. Test corrected to the reachable behavior.
    @Test
    void summaryZeroesOutWhenNoBatchAssigned() throws Exception {
        mvc.perform(get("/api/student/summary").header("Authorization", "Bearer " + noBatchToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.exam_score").value("-"))
           .andExpect(jsonPath("$.total_classes").value("0"))
           .andExpect(jsonPath("$.attended_classes").value("0"))
           .andExpect(jsonPath("$.attendance_percent").value(nullValue()));
    }

    @Test
    void monthlyGroupsBothSessionsIntoOneMonth() throws Exception {
        mvc.perform(get("/api/student/monthly").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].month").value("2025-06"))
           .andExpect(jsonPath("$[0].percent").value("50.00"));
    }

    @Test
    void weeklySplitsSessionsIntoTwoWeeks() throws Exception {
        mvc.perform(get("/api/student/weekly").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].week_start").value("2025-06-02"))
           .andExpect(jsonPath("$[0].percent").value("100.00"))
           .andExpect(jsonPath("$[1].week_start").value("2025-06-16"))
           .andExpect(jsonPath("$[1].percent").value("0.00"));
    }

    @Test
    void customRangeFiltersToOneSession() throws Exception {
        mvc.perform(get("/api/student/custom?fromDate=2025-06-01&toDate=2025-06-10")
                .header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].total_classes").value("1"))
           .andExpect(jsonPath("$[0].attended_classes").value("1"))
           .andExpect(jsonPath("$[0].attendance_percent").value("100.00"));
    }

    @Test
    void customRangeMissingDatesIs400() throws Exception {
        mvc.perform(get("/api/student/custom").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Date range required"));
    }

    @Test
    void customRangeMalformedDateIs500WithCustomMessage() throws Exception {
        mvc.perform(get("/api/student/custom?fromDate=not-a-date&toDate=2025-06-10")
                .header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch custom data"));
    }

    @Test
    void analyticsEndpointsRequireAuth() throws Exception {
        mvc.perform(get("/api/student/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/monthly")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/weekly")).andExpect(status().isUnauthorized());
    }
}
