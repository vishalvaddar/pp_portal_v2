package com.rcf.imas.modules.events;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EventsUpdateIT extends PgIntegrationTest {

    @TempDir static Path storageRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("imas.event-storage-path", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    Integer eventTypeId;
    Integer sammelanTypeId;
    Integer eventId;
    Integer otherEventId;
    Integer keepPhotoId;
    Integer deletePhotoId;
    Integer otherEventPhotoId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970401,'evAdmin974','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("970401", "evAdmin974", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970401, 'Cohort974')").update();
        eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType974') RETURNING event_type_id").query(Integer.class).single();
        sammelanTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Sammelan') RETURNING event_type_id").query(Integer.class).single();

        eventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                VALUES (:t,'Update Target 974',DATE '2026-05-01',DATE '2026-05-02',970401) RETURNING event_id
                """).param("t", sammelanTypeId).query(Integer.class).single();
        otherEventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                VALUES (:t,'Other Event 974',DATE '2026-05-01',DATE '2026-05-02',970401) RETURNING event_id
                """).param("t", eventTypeId).query(Integer.class).single();

        keepPhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/keep.jpg','keep.jpg') RETURNING photo_id")
                .param("e", eventId).query(Integer.class).single();
        deletePhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/del.jpg','del.jpg') RETURNING photo_id")
                .param("e", eventId).query(Integer.class).single();
        otherEventPhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/other.jpg','other.jpg') RETURNING photo_id")
                .param("e", otherEventId).query(Integer.class).single();

        // A student marked present on the sammelan event, for the count-sync assertion
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name) VALUES (970401,2026,97040100001,'Sync Student')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, active_yn) VALUES (970401,970401,'Sync Student','F','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.event_students(event_id, student_id) VALUES (:e,970401)").param("e", eventId).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.event_students WHERE student_id = 970401").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 970401").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 970401").update();
        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970401)").update();
        jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970401)").update();
        jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970401").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name IN ('EvType974','Sammelan')").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970401").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970401").update();
    }

    @Test
    void updateEventMissingTitleIs400NotMissingParam500() throws Exception {
        // A required @RequestParam would 500 (MissingServletRequestParameterException) when event_title is
        // absent; Node runs validateEventBody on a plain body and returns a 400 {message}. required=false +
        // validateEventBody must reproduce that 400 (createEvent already did; updateEvent must match).
        mvc.perform(multipart("/api/events/" + eventId)
                .with(req -> { req.setMethod("PUT"); return req; })
                .param("event_type_id", String.valueOf(sammelanTypeId))
                .param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-03")
                .param("cohort_number", "970401")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void updateEventMasterFieldsPhotoDeleteScopedAndSammelanSync() throws Exception {
        MockMultipartFile newPhoto = new MockMultipartFile("photos", "IMG_NEW.jpg", "image/jpeg", "bytes".getBytes());
        MockMultipartFile report = new MockMultipartFile("reports", "final.pdf", "application/pdf", "pdf-bytes".getBytes());

        mvc.perform(multipart("/api/events/" + eventId).file(newPhoto).file(report)
                .param("eventTitle", "Updated Title 974")
                .with(req -> { req.setMethod("PUT"); return req; })
                .param("event_type_id", String.valueOf(sammelanTypeId))
                .param("event_title", "Updated Title 974")
                .param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-03")
                .param("event_type_name", "Sammelan")
                .param("cohort_number", "970401")
                .param("photos_to_delete", "[" + deletePhotoId + "]")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Updated successfully"));

        String title = jdbc.sql("SELECT event_title FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(String.class).single();
        assertEquals("Updated Title 974", title);

        // scoped delete removed only the targeted photo for THIS event
        Integer remaining = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id").param("id", deletePhotoId).query(Integer.class).single();
        assertEquals(0, remaining);
        Integer keptCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id").param("id", keepPhotoId).query(Integer.class).single();
        assertEquals(1, keptCount);

        // Sammelan count sync: 1 student, gender F -> girls_attended=1, boys_attended=0
        Integer boys = jdbc.sql("SELECT boys_attended FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
        Integer girls = jdbc.sql("SELECT girls_attended FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
        assertEquals(0, boys);
        assertEquals(1, girls);

        // new photo stored with the SERVER-GENERATED filename (unlike createEvent's original-filename choice)
        String newPhotoName = jdbc.sql("SELECT file_name FROM pp.event_photos WHERE event_id = :id AND file_name LIKE 'updated_title_974%'")
                .param("id", eventId).query(String.class).single();
        assertTrue(newPhotoName.startsWith("updated_title_974-"));

        // report row written with hard-coded SAMMELAN_REPORT type
        String reportType = jdbc.sql("SELECT report_type FROM pp.event_reports WHERE event_id = :id").param("id", eventId).query(String.class).single();
        assertEquals("SAMMELAN_REPORT", reportType);
    }

    @Test
    void updateEventPhotoDeleteCannotTouchOtherEventsPhoto() throws Exception {
        mvc.perform(multipart("/api/events/" + eventId)
                .with(req -> { req.setMethod("PUT"); return req; })
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_title", "Cross Event Attempt 974")
                .param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-02")
                .param("cohort_number", "970401")
                .param("photos_to_delete", "[" + otherEventPhotoId + "]")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());

        // otherEventPhotoId belongs to otherEventId, NOT eventId -- must survive (Locked Decision 5 hardening)
        Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id")
                .param("id", otherEventPhotoId).query(Integer.class).single();
        assertEquals(1, stillThere);
    }

    @Test
    void updateEventInvalidIdIs400() throws Exception {
        mvc.perform(multipart("/api/events/abc")
                .with(req -> { req.setMethod("PUT"); return req; })
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_title", "x").param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-02")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid event ID"));
    }
}
