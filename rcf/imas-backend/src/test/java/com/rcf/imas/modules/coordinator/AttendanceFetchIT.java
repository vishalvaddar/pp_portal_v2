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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AttendanceFetchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965901,'coordUser965901','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965901,'Fetch Cohort 965901')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965901,'Fetch Batch 965901',965901)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965901,965901)").update();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965901,'Fetch Teacher 965901')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965901,'Fetch Classroom 965901',965901)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965901,965901)").update();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (965901, 965901, DATE '2026-07-09', '09:00:00', '10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        // ACTIVE student, marked PRESENT for this session -> appears with db_status
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                VALUES (965911,'Fetch Student Active Marked','9000000001','a@example.com',965901,'F','ACTIVE')
                """).update();
        // ACTIVE student, not yet marked -> appears with db_status null
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                VALUES (965912,'Fetch Student Active Unmarked','9000000002','b@example.com',965901,'M','ACTIVE')
                """).update();
        // INACTIVE student, NOT marked for this session -> excluded entirely
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                VALUES (965913,'Fetch Student Inactive Unmarked','9000000003','c@example.com',965901,'F','INACTIVE')
                """).update();
        // INACTIVE student, but WAS marked for this session -> still appears (Node's OR sa.session_id IS NOT NULL)
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                VALUES (965914,'Fetch Student Inactive Marked','9000000004','d@example.com',965901,'M','INACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901, 965911, 'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901, 965914, 'LATE JOINED')").update();

        coordToken = jwt.issueFinalToken("965901", "coordUser965901", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965901").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965901").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965901").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965901").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965901").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965901").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965901").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965901").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965901").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965901").update();
    }

    @Test
    void fetchReturnsActivePlusPreviouslyMarkedStudentsOrderedByName() throws Exception {
        mvc.perform(get("/api/coordinator/attendance")
                .param("session_id", "965901").param("batchId", "965901")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
           // ORDER BY sm.student_name -- alphabetical
           .andExpect(jsonPath("$[0].student_name").value("Fetch Student Active Marked"))
           .andExpect(jsonPath("$[0].db_status").value("PRESENT"))
           .andExpect(jsonPath("$[0].student_id").value("965911"))
           .andExpect(jsonPath("$[1].student_name").value("Fetch Student Active Unmarked"))
           .andExpect(jsonPath("$[1].db_status").doesNotExist())
           .andExpect(jsonPath("$[2].student_name").value("Fetch Student Inactive Marked"))
           .andExpect(jsonPath("$[2].db_status").value("LATE JOINED"));
    }

    @Test
    void fetchWithoutSessionIdStillReturnsActiveStudentsWithNullStatus() throws Exception {
        mvc.perform(get("/api/coordinator/attendance")
                .param("batchId", "965901")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
           .andExpect(jsonPath("$[0].student_name").value("Fetch Student Active Marked"))
           .andExpect(jsonPath("$[0].db_status").doesNotExist())
           .andExpect(jsonPath("$[1].student_name").value("Fetch Student Active Unmarked"));
    }

    @Test
    void fetchWithoutBatchIdReturns200EmptyList() throws Exception {
        // Node destructures req.query -- a missing batchId binds SQL NULL, matches no student_master rows,
        // and returns 200 [] (attendanceController.js:338-366), NOT a 500.
        mvc.perform(get("/api/coordinator/attendance")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
