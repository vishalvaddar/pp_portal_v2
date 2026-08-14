package com.rcf.imas.modules.coordinator;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AttendanceCsvCommitIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965801,'coordUser965801','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965801,'CSV Commit Cohort 965801')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965801,'CSV Commit Batch 965801',965801)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965801,965801)").update();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965801,'CSV Commit Teacher 965801')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965801,'CSV Commit Classroom 965801',965801)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965801,965801)").update();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965811,'Commit Student One',965801,'F','ACTIVE')
                """).update();
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965812,'Commit Student Two',965801,'M','ACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965801", "coordUser965801", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (SELECT session_id FROM pp.class_session WHERE classroom_id = 965801)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965801").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965801").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965801").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965801").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965801").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965801").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965801").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965801").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965801").update();
    }

    private Map<String, Object> commitBody() {
        // Session 09:00-10:00 = 60 total minutes. Student One: 45 min -> 75% capped-nowhere -> PRESENT sent as-is.
        // Student Two: 60 min -> 100%.
        return Map.of(
                "session_date", "2026-07-08",
                "classroom_id", "965801",
                "start_time", "09:00",
                "end_time", "10:00",
                "previewData", List.of(
                        Map.of("student_id", "965811", "student_name", "Commit Student One",
                                "duration_minutes", 45, "time_joined", "09:00 AM", "time_exited", "09:45 AM",
                                "status", "PRESENT"),
                        Map.of("student_id", "965812", "student_name", "Commit Student Two",
                                "duration_minutes", 60, "time_joined", "09:00 AM", "time_exited", "10:00 AM",
                                "status", "PRESENT")
                )
        );
    }

    @Test
    void commitCreatesSessionAndAttendanceRows() throws Exception {
        String body = json.writeValueAsString(commitBody());

        mvc.perform(post("/api/coordinator/attendance/csv/commit")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session_id").exists());

        Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
        assertThat(sessionId).isNotNull();

        Long teacherId = jdbc.sql("SELECT teacher_id FROM pp.class_session WHERE session_id = ?").param(sessionId).query(Long.class).single();
        assertThat(teacherId).isEqualTo(965801L);

        Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?").param(sessionId).query(Long.class).single();
        assertThat(attendanceRows).isEqualTo(2L);

        BigDecimal pctOne = jdbc.sql("SELECT attendance_percent FROM pp.student_attendance WHERE session_id = ? AND student_id = 965811").param(sessionId)
                .query(BigDecimal.class).single();
        assertThat(pctOne).isEqualByComparingTo("75.00");

        BigDecimal pctTwo = jdbc.sql("SELECT attendance_percent FROM pp.student_attendance WHERE session_id = ? AND student_id = 965812").param(sessionId)
                .query(BigDecimal.class).single();
        assertThat(pctTwo).isEqualByComparingTo("100.00");
    }

    @Test
    void commitTwiceIsIdempotentViaOnConflictUpdate() throws Exception {
        String body = json.writeValueAsString(commitBody());

        mvc.perform(post("/api/coordinator/attendance/csv/commit")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk());

        // second commit, same session window, Student One's status flips to ABSENT/duration 0
        Map<String, Object> secondBody = Map.of(
                "session_date", "2026-07-08",
                "classroom_id", "965801",
                "start_time", "09:00",
                "end_time", "10:00",
                "previewData", List.of(
                        Map.of("student_id", "965811", "student_name", "Commit Student One",
                                "duration_minutes", 0, "time_joined", "N/A", "time_exited", "N/A",
                                "status", "ABSENT"),
                        Map.of("student_id", "965812", "student_name", "Commit Student Two",
                                "duration_minutes", 60, "time_joined", "09:00 AM", "time_exited", "10:00 AM",
                                "status", "PRESENT")
                )
        );

        mvc.perform(post("/api/coordinator/attendance/csv/commit")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(secondBody)))
           .andExpect(status().isOk());

        Long sessionCount = jdbc.sql("SELECT COUNT(*) FROM pp.class_session WHERE classroom_id = 965801").query(Long.class).single();
        assertThat(sessionCount).isEqualTo(1L);   // ON CONFLICT DO UPDATE, not a second row

        Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
        Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?").param(sessionId).query(Long.class).single();
        assertThat(attendanceRows).isEqualTo(2L);   // no duplicate rows, ON CONFLICT DO UPDATE

        String statusOne = jdbc.sql("SELECT status FROM pp.student_attendance WHERE session_id = ? AND student_id = 965811").param(sessionId)
                .query(String.class).single();
        assertThat(statusOne).isEqualTo("ABSENT");
    }

    @Test
    void skipsPreviewRowsWithoutStudentId() throws Exception {
        Map<String, Object> body = Map.of(
                "session_date", "2026-07-08",
                "classroom_id", "965801",
                "start_time", "09:00",
                "end_time", "10:00",
                "previewData", List.of(
                        Map.of("student_name", "No Id Row", "duration_minutes", 10, "status", "ABSENT")
                )
        );
        mvc.perform(post("/api/coordinator/attendance/csv/commit")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
           .andExpect(status().isOk());

        Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
        Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?").param(sessionId).query(Long.class).single();
        assertThat(attendanceRows).isZero();
    }
}
