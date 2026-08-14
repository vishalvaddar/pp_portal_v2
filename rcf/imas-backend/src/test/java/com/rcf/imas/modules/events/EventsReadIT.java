package com.rcf.imas.modules.events;

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
class EventsReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    Integer eventTypeId;
    Integer eventId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970201,'evAdmin972','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("970201", "evAdmin972", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970201, 'Cohort970')").update();

        eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType970') RETURNING event_type_id")
                .query(Integer.class).single();

        eventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_description, event_start_date,
                    event_end_date, event_location, cohort_number, boys_attended, girls_attended, parents_attended,
                    created_by, updated_by)
                VALUES (:t,'Sammelan Event 970','desc970',DATE '2026-01-10',DATE '2026-01-11','Hall970',970201,5,7,3,970201,970201)
                RETURNING event_id
                """).param("t", eventTypeId).query(Integer.class).single();

        jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name, uploaded_by) VALUES (:e,'/tmp/p1.jpg','p1.jpg',970201)")
                .param("e", eventId).update();
        jdbc.sql("INSERT INTO pp.event_reports(event_id, report_type, file_path, file_name, generated_by) VALUES (:e,'SAMMELAN_REPORT','/tmp/r1.pdf','r1.pdf',970201)")
                .param("e", eventId).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970201)").update();
        jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970201)").update();
        jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970201").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'EvType970'").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970201").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970201").update();
    }

    @Test
    void getAllEventsIncludesCoverPhotoAndCounts() throws Exception {
        mvc.perform(get("/api/events").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].event_title").value("Sammelan Event 970"))
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].cover_photo").value("/tmp/p1.jpg"))
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].boys_attended").value(5))
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].girls_attended").value(7))
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].event_type").value("EvType970"))
            .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].start_date").value("2026-01-10"));
    }

    @Test
    void getEventByIdIncludesPhotosAndReports() throws Exception {
        mvc.perform(get("/api/events/" + eventId).header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_title").value("Sammelan Event 970"))
            .andExpect(jsonPath("$.event_type_name").value("EvType970"))
            .andExpect(jsonPath("$.created_by").value("970201"))
            .andExpect(jsonPath("$.photos", hasSize(1)))
            .andExpect(jsonPath("$.photos[0].file_name").value("p1.jpg"))
            .andExpect(jsonPath("$.reports", hasSize(1)))
            .andExpect(jsonPath("$.reports[0].report_type").value("SAMMELAN_REPORT"));
    }

    @Test
    void getEventByIdNotFoundIs404() throws Exception {
        mvc.perform(get("/api/events/999999999").header("Authorization", "Bearer " + admin))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void getEventByIdInvalidIdIs400() throws Exception {
        mvc.perform(get("/api/events/abc").header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid event ID"));
    }
}
