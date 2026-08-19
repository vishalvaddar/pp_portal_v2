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
class CoordinatorAbsenteesReportIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965901,'coordUser965901','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965901,'Absentees Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965901,'Absentees Batch',965901)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965901,965901)").update();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965901,'SCI','Science')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965901,'Absentees Teacher')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965901,'Absentees Classroom',965901,965901)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965901,965901)").update();

        // Timetable: MONDAY slot for this classroom. 2026-06-08 and 2026-06-15 are both Mondays.
        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) VALUES (965901,965901,'MONDAY','09:00:00','10:00:00')").update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965901,'Absentee Student 965901',965901,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        // Session + attendance only for the FIRST Monday (2026-06-08, PRESENT). The second scheduled Monday
        // (2026-06-15) has no session/attendance row at all -> counts as a scheduled-but-missed slot.
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965901,965901,'2026-06-08','09:00:00','10:00:00',965901)
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901,965901,'PRESENT')").update();

        coordToken = jwt.issueFinalToken("965901", "coordUser965901", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965901").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 965901").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 965901").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 965901").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965901").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965901").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965901").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965901").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965901").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965901").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965901").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965901").update();
    }

    @Test
    void absenteesReportShapeAndMissedCount() throws Exception {
        // Range covers exactly the two Mondays: 2026-06-08 (attended) and 2026-06-15 (missed, no session ever ran).
        mvc.perform(get("/api/coordinator/reports/absentees")
                .param("batch_id", "965901").param("fromDate", "2026-06-08").param("toDate", "2026-06-15")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reportId").value("ABS-965901-2026-06-08-2026-06-15"))
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].id").value("965901"))
           .andExpect(jsonPath("$.students[0].name").value("Absentee Student 965901"))
           .andExpect(jsonPath("$.students[0].totalMissed").value(1))
           .andExpect(jsonPath("$.students[0].missedClasses", hasSize(1)))
           .andExpect(jsonPath("$.students[0].missedClasses[0].subject").value("SCI"))
           .andExpect(jsonPath("$.students[0].missedClasses[0].count").value(1));
    }

    @Test
    void absenteesMissingRequiredParamsReturns400() throws Exception {
        mvc.perform(get("/api/coordinator/reports/absentees")
                .param("batch_id", "965901")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("batch_id, fromDate, and toDate required"));
    }
}
