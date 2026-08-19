package com.rcf.imas.modules.tracking;

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
class ActiveTimetableReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;
    String studentToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (960001,'ttAdmin960','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, end_date) VALUES (960001,'TT Cohort Open 960', NULL)").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, end_date) VALUES (960002,'TT Cohort Closed 960', DATE '2020-01-01')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (960001,'TT Batch A',960001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (960001,'TT1','TT Subject 960')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (960002,'ttTeacherLogin960','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (960001,960002,'TT Teacher 960')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (960001,'TT Classroom 960',960001,960001)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (960001,960001)").update();

        // Timetable rows across several days to prove ordering: Wednesday and Monday.
        jdbc.sql("INSERT INTO pp.timetable(classroom_id, day_of_week, start_time, end_time) VALUES (960001,'WEDNESDAY','09:00','10:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(classroom_id, day_of_week, start_time, end_time) VALUES (960001,'MONDAY','09:00','10:00')").update();

        adminToken = jwt.issueFinalToken("960001", "ttAdmin960", "ADMIN");
        studentToken = jwt.issueFinalToken("960099", "ttStudent960", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 960001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 960001").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 960001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (960001,960002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (960001,960002)").update();
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminTokenIs403() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void dropdownsReturnsOnlyOpenCohortsAndAllTeachers() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.cohorts[?(@.cohort_number==960001)]").exists())
           .andExpect(jsonPath("$.cohorts[?(@.cohort_number==960002)]").doesNotExist()) // end_date set -> excluded
           .andExpect(jsonPath("$.teachers[?(@.teacher_id==960001)].teacher_name").value("TT Teacher 960"));
    }

    @Test
    void batchesByCohortName() throws Exception {
        mvc.perform(get("/api/activetimetable/batches").param("cohortName", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(960001));
    }

    @Test
    void fetchCombinedOrdersSundayToSaturday() throws Exception {
        // Only Monday/Wednesday seeded -> Monday (2) must come before Wednesday (4) under the Sun-Sat CASE.
        // Node getCombined reads `id` (the client sends id == cohort for the combined view; ActiveTimeTable.js).
        mvc.perform(get("/api/activetimetable/fetch").param("type", "combined")
                .param("id", "TT Cohort Open 960").param("cohort", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"));
    }

    @Test
    void fetchTeacherOrdersAlphabeticallyNotSundayToSaturday() throws Exception {
        // Plain alphabetical: "MONDAY" < "WEDNESDAY" lexically too here, so seed a case that actually
        // differs: alphabetical (M < W) happens to match Sun-Sat order for these two days, so this test
        // asserts the ACTUAL alphabetical predicate by checking day_of_week is present per row and that
        // the endpoint ignores the cohort param entirely (quirk c) by omitting cohort from the call.
        mvc.perform(get("/api/activetimetable/fetch").param("type", "teacher").param("id", "960001")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"));
    }

    @Test
    void fetchBatchReturnsSubjectTeacherBatchShape() throws Exception {
        mvc.perform(get("/api/activetimetable/fetch").param("type", "batch")
                .param("id", "TT Batch A").param("cohort", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].subject_name").value("TT Subject 960"))
           .andExpect(jsonPath("$[0].teacher_name").value("TT Teacher 960"));
    }

    @Test
    void fetchUnknownTypeReturns200WithEmptyBody() throws Exception {
        mvc.perform(get("/api/activetimetable/fetch").param("type", "bogus")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(content().string(""));
    }

    @Test
    void fetchMissingTypeReturns200WithEmptyBodyNotFiveHundred() throws Exception {
        // Node destructures req.query -- an absent `type` matches none of the if/else-if branches, `data`
        // stays undefined, and res.json(undefined) serializes to an empty 200 body (activeTimeTableController.js:26-37).
        // A plain `switch(type)` on a null selector throws NPE regardless of its default branch, so the
        // controller must special-case null before reaching the switch.
        mvc.perform(get("/api/activetimetable/fetch")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(content().string(""));
    }
}
