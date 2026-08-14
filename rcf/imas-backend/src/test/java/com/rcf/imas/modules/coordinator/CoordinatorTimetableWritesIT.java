package com.rcf.imas.modules.coordinator;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorTimetableWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        // Permanent, idempotent fixture: createSlot always writes created_by=1/updated_by=1 (Firm Decision 3),
        // which has a real FK to pp."user"(user_id) -- seed once, never delete.
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (1,'systemUser1','x','N') ON CONFLICT (user_id) DO NOTHING").update();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966201,'ttwUser966201','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966201,'TTW Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966201,'TTW Batch',966201)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966201,'TTW Room')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966201,966201)").update();

        // Two existing slots for conflict tests: MONDAY 09-10 (966201) and TUESDAY 09-10 (966202).
        jdbc.sql("""
                INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
                VALUES (966201,966201,'MONDAY','09:00:00','10:00:00'),
                       (966202,966201,'TUESDAY','09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("966201", "ttwUser966201", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id = 966201").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966201").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966201").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966201").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966201").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966201").update();
        // NOTE: user_id=1 is a permanent shared fixture, deliberately never deleted here.
    }

    @Test
    void createSlotSuccessSyncsClassroomLinkAndUsesLiteralCreatedBy() throws Exception {
        String body = """
                {"batch_id":"966201","classroom_id":"966201","day":"WEDNESDAY",
                 "start_time":"14:00:00","end_time":"15:00:00","class_link":"https://zoom.example/ww1"}
                """;
        MvcResult result = mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.classroom_id").value(966201))
           .andExpect(jsonPath("$.data.day_of_week").value("WEDNESDAY"))
           .andExpect(jsonPath("$.data.start_time").value("14:00:00"))
           .andExpect(jsonPath("$.data.end_time").value("15:00:00"))
           .andExpect(jsonPath("$.data.created_by").value("1"))
           .andExpect(jsonPath("$.data.updated_by").value("1"))
           .andReturn();

        int newId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.timetable_id");
        try {
            String classLink = jdbc.sql("SELECT class_link FROM pp.classroom WHERE classroom_id = 966201")
                    .query(String.class).single();
            org.junit.jupiter.api.Assertions.assertEquals("https://zoom.example/ww1", classLink);
        } finally {
            jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = :id").param("id", newId).update();
        }
    }

    @Test
    void createSlotConflictReturns400WithMessageKeyNotErrorKey() throws Exception {
        String body = """
                {"batch_id":"966201","classroom_id":"966201","day":"MONDAY",
                 "start_time":"09:30:00","end_time":"10:30:00"}
                """;
        mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.message").value("Conflict detected with existing schedule."))
           .andExpect(jsonPath("$.error").doesNotExist());

        Integer count = jdbc.sql("SELECT COUNT(*)::int FROM pp.timetable WHERE classroom_id = 966201")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(2, count); // no new row inserted
    }

    @Test
    void createSlotMissingRequiredFieldReturns400WithErrorKey() throws Exception {
        String body = """
                {"batch_id":"966201","day":"WEDNESDAY","start_time":"14:00:00","end_time":"15:00:00"}
                """;
        mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields"));
    }

    @Test
    void updateSlotSuccessSyncsClassroomLink() throws Exception {
        String body = """
                {"classroom_id":"966201","day":"THURSDAY",
                 "start_time":"11:00:00","end_time":"12:00:00","class_link":"https://zoom.example/ww2"}
                """;
        mvc.perform(put("/api/coordinator/timetable/966201")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.timetable_id").value(966201))
           .andExpect(jsonPath("$.data.day_of_week").value("THURSDAY"))
           .andExpect(jsonPath("$.data.start_time").value("11:00:00"));

        String classLink = jdbc.sql("SELECT class_link FROM pp.classroom WHERE classroom_id = 966201")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("https://zoom.example/ww2", classLink);
    }

    @Test
    void updateSlotConflictWithOtherExistingSlotReturns400() throws Exception {
        // 966201 is currently MONDAY 09-10; moving it to overlap 966202 (TUESDAY 09-10) must conflict --
        // exclude_id only excludes 966201 itself, not the other row.
        String body = """
                {"classroom_id":"966201","day":"TUESDAY","start_time":"09:30:00","end_time":"10:30:00"}
                """;
        mvc.perform(put("/api/coordinator/timetable/966201")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.message").value("Conflict detected with existing schedule."));
    }
}
