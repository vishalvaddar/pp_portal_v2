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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorTimetableReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966101,'ttUser966101','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966101,'TT Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966101,'TT Batch',966101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966101,'MTH','Math')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966101,'TT Teacher')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        // Room A has the existing timetable slot; Room B shares the SAME batch (via classroom_batch) but
        // has no timetable row of its own -- this is what exercises the EXISTS cross-batch-share branch.
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966101,'Room A',966101,966101)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966102,'Room B')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966101,966101),(966102,966101)").update();

        jdbc.sql("""
                INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
                VALUES (966101,966101,'MONDAY','09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("966101", "ttUser966101", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966101").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966101").update();
    }

    @Test
    void getTimetableReturnsRowsForBatch() throws Exception {
        mvc.perform(get("/api/coordinator/timetable").param("batchId", "966101")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].classroom_name").value("Room A"))
           .andExpect(jsonPath("$[0].subject_code").value("MTH"))
           .andExpect(jsonPath("$[0].teacher_name").value("TT Teacher"))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"))
           .andExpect(jsonPath("$[0].end_time").value("10:00:00"));
    }

    @Test
    void getTimetableMissingBatchIdReturns400() throws Exception {
        mvc.perform(get("/api/coordinator/timetable").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("batchId is required"));
    }

    @Test
    void checkConflictDirectClassroomOverlapReturnsTrue() throws Exception {
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966101").param("day", "MONDAY")
                .param("startTime", "09:30:00").param("endTime", "10:30:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", hasSize(1)))
           .andExpect(jsonPath("$.conflicts[0].timetable_id").value(966101));
    }

    @Test
    void checkConflictNoOverlapReturnsFalseWithNoConflictsKey() throws Exception {
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966101").param("day", "MONDAY")
                .param("startTime", "11:00:00").param("endTime", "12:00:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(false))
           .andExpect(jsonPath("$.conflicts").doesNotExist());
    }

    @Test
    void checkConflictCrossBatchShareExistsBranchReturnsTrue() throws Exception {
        // Room B (966102) has no timetable row of its own, but shares batch 966101 with Room A (966101),
        // which DOES have an overlapping slot -- the EXISTS(classroom_batch cb1 JOIN cb2) branch must fire.
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966102").param("day", "MONDAY")
                .param("startTime", "09:30:00").param("endTime", "10:30:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", hasSize(1)));
    }

    @Test
    void deleteSlotRemovesRowAndReturnsSuccess() throws Exception {
        mvc.perform(delete("/api/coordinator/timetable/966101").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));

        Integer remaining = jdbc.sql("SELECT COUNT(*)::int FROM pp.timetable WHERE timetable_id = 966101")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
    }
}
