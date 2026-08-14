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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AttendanceSessionOverlapUndoIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965601,'coordUser965601','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965601,'Attendance Cohort 965601')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965601,'Attendance Batch 965601',965601)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965601,965601)").update();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965601,'Attendance Teacher 965601')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965601,'Attendance Classroom 965601',965601)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965601,965601)").update();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (965601, 965601, DATE '2026-07-06', '09:00:00', '10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965602,'Attendance Student 965602',965602,965601,'F','ACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965601", "coordUser965601", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965601").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965601").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965601").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965601").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965601").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965601").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965601").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965601").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965601").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965601").update();
    }

    @Test
    void getOrFindSession_findsExistingSessionByHHmmStartTime() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/session")
                .param("classroom_id", "965601").param("session_date", "2026-07-06").param("start_time", "09:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session_id").value(965601))
           .andExpect(jsonPath("$.start_time").value("09:00:00"))
           .andExpect(jsonPath("$.end_time").value("10:00:00"));
    }

    @Test
    void getOrFindSession_noMatchReturns200WithNullSessionId() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/session")
                .param("classroom_id", "965601").param("session_date", "2026-07-07").param("start_time", "09:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session_id").doesNotExist())
           .andExpect(jsonPath("$.session_id").value((Object) null));
    }

    @Test
    void checkOverlap_trueWhenRangesOverlap() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/check-overlap")
                .param("classroomId", "965601").param("date", "2026-07-06")
                .param("startTime", "09:30:00").param("endTime", "10:30:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts").doesNotExist());
    }

    @Test
    void getOrFindSession_missingParamsReturns200WithNullSessionId() throws Exception {
        // Node destructures req.query -- a missing classroom_id/session_date/start_time binds SQL NULL,
        // finds nothing, and returns {session_id:null} 200 (attendanceController.js:311-334), NOT a 500.
        mvc.perform(get("/api/coordinator/attendance/session")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session_id").value((Object) null));
    }

    @Test
    void checkOverlap_missingParamsReturns200WithOverlapFalse() throws Exception {
        // Node destructures req.query -- a missing classroomId/date/startTime/endTime binds SQL NULL, the
        // OVERLAPS query matches nothing, and returns {overlap:false} 200 (attendanceController.js:703-716).
        mvc.perform(get("/api/coordinator/attendance/check-overlap")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(false));
    }

    @Test
    void checkOverlap_falseWhenNoOverlap() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/check-overlap")
                .param("classroomId", "965601").param("date", "2026-07-06")
                .param("startTime", "11:00:00").param("endTime", "12:00:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(false));
    }

    @Test
    void undo_deletesSessionAndAttendanceRows() throws Exception {
        jdbc.sql("""
                INSERT INTO pp.student_attendance(session_id, student_id, status)
                VALUES (965601, 965602, 'PRESENT')
                """).update();

        mvc.perform(post("/api/coordinator/attendance/undo")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":965601}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Undo Successful"));

        Long sessions = jdbc.sql("SELECT COUNT(*) FROM pp.class_session WHERE session_id = 965601").query(Long.class).single();
        Long attendance = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = 965601").query(Long.class).single();
        assertThat(sessions).isZero();
        assertThat(attendance).isZero();
    }

    @Test
    void undo_withMissingSessionIdReturns200NoOp() throws Exception {
        // Node binds a missing session_id as NULL -> DELETEs match nothing -> {message} 200.
        // Must NOT feed 'null'::integer and 500.
        mvc.perform(post("/api/coordinator/attendance/undo")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Undo Successful"));
    }

    @Test
    void bulk_isANoOpStub() throws Exception {
        mvc.perform(post("/api/coordinator/attendance/bulk")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anything\":\"ignored\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Bulk submission logic active"));
    }

    @Test
    void sampleCsvReference_downloadsAttachment() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/csv/reference")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sample_attendance.csv")))
           .andExpect(content().contentType("text/csv"));
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/session")
                .param("classroom_id", "965601").param("session_date", "2026-07-06").param("start_time", "09:00"))
           .andExpect(status().isUnauthorized());
    }
}
