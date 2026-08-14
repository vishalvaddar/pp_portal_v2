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
class CoordinatorReportsLoadAndTeachersIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965701,'coordUser965701','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965701,'Reports Cohort 965701')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        // Assigned batch (coordinator 965701 IS assigned) and an unassigned batch, to prove scoping on #27.
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965701,'Assigned Batch 965701',965701)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965702,'Unassigned Batch 965702',965701)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965701,965701)").update();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965701,'MATH','Mathematics')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        // Two teachers: one taught a session in the ASSIGNED batch (must appear in #27), one only in the
        // unassigned batch (must NOT appear).
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name, contact_no) VALUES (965701,'Assigned Teacher 965701','9000000001')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name, contact_no) VALUES (965702,'Unassigned Teacher 965702','9000000002')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965701,'Assigned Classroom 965701',965701,965701)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965702,'Unassigned Classroom 965702',965701,965702)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965701,965701)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965702,965702)").update();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965701,965701,'2026-06-01','09:00:00','10:00:00',965701)
                """).update();
        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (965702,965702,'2026-06-01','09:00:00','10:00:00',965702)
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965701", "coordUser965701", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965701").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (965701,965702)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965701").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965701").update();
    }

    @Test
    void teacherLoadReturnsCountsGroupedByTeacherCohortClassroomSubject() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-load")
                .param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.teacherClassCounts", hasSize(2)))
           .andExpect(jsonPath("$.teacherClassCounts[?(@.teacher=='Assigned Teacher 965701')].total_classes_taken")
                   .value(org.hamcrest.Matchers.contains("1")));
    }

    @Test
    void teacherLoadNoDateFilterReturnsAllRows() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-load")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.teacherClassCounts", hasSize(2)));
    }

    @Test
    void coordinatorTeachersScopedToPrincipalNotClientParam() throws Exception {
        // Even if a malicious client passes ?user_id= for a DIFFERENT coordinator, the principal's own
        // batches govern -- proves Firm Decision 8 / Disagreements #1.
        mvc.perform(get("/api/coordinator/reports/coordinator-teachers")
                .param("user_id", "999999")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].teacher_id").value(965701))
           .andExpect(jsonPath("$[0].teacher_name").value("Assigned Teacher 965701"))
           .andExpect(jsonPath("$[0].subject_name").value("Mathematics"))
           .andExpect(jsonPath("$[0].batch_name").value("Assigned Batch 965701"));
    }
}
