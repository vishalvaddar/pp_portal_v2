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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EventsCreateDeleteIT extends PgIntegrationTest {

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

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970301,'evAdmin973','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("970301", "evAdmin973", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970301, 'Cohort973')").update();
        eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType973') RETURNING event_type_id")
                .query(Integer.class).single();
    }

    @AfterEach
    void tearDown() throws Exception { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.event_students WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
        jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
        jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970301").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'EvType973'").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970301").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970301").update();
    }

    @Test
    void createEventWithPhotosPersistsMasterAndPhotosAndFiles() throws Exception {
        MockMultipartFile photo1 = new MockMultipartFile("photos", "IMG_001.jpg", "image/jpeg", "fake-jpg-bytes".getBytes());
        MockMultipartFile photo2 = new MockMultipartFile("photos", "IMG_002.png", "image/png", "fake-png-bytes".getBytes());

        String resp = mvc.perform(multipart("/api/events").file(photo1).file(photo2)
                .param("eventTitle", "Sammelan 970 Launch")
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_title", "Sammelan 970 Launch")
                .param("event_start_date", "2026-02-01")
                .param("event_end_date", "2026-02-02")
                .param("cohort_number", "970301")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Event created"))
            .andReturn().getResponse().getContentAsString();

        Integer eventId = com.jayway.jsonpath.JsonPath.read(resp, "$.event_id");

        Integer photoCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single();
        assertEquals(2, photoCount);

        List<String> fileNames = jdbc.sql("SELECT file_name FROM pp.event_photos WHERE event_id = :id ORDER BY file_name")
                .param("id", eventId).query(String.class).list();
        // createEvent stores the ORIGINAL uploaded filename (eventController.js:89), not the server-generated one
        assertEquals(List.of("IMG_001.jpg", "IMG_002.png"), fileNames);

        // server-generated disk filenames follow <clean(eventTitle)>-<n><ext>
        assertTrue(Files.exists(storageRoot.resolve("photos").resolve("sammelan_970_launch-1.jpg")));
        assertTrue(Files.exists(storageRoot.resolve("photos").resolve("sammelan_970_launch-2.png")));

        // served publicly, no auth header
        mvc.perform(get("/uploads/events/photos/sammelan_970_launch-1.jpg"))
            .andExpect(status().isOk());

        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id = :id").param("id", eventId).update();
        jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
    }

    @Test
    void createEventReportFileIsIgnoredNoDbRowWritten() throws Exception {
        MockMultipartFile report = new MockMultipartFile("reports", "report.pdf", "application/pdf", "fake-pdf".getBytes());

        String resp = mvc.perform(multipart("/api/events").file(report)
                .param("eventTitle", "No Report On Create 970")
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_title", "No Report On Create 970")
                .param("event_start_date", "2026-03-01")
                .param("event_end_date", "2026-03-02")
                .param("cohort_number", "970301")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        Integer eventId = com.jayway.jsonpath.JsonPath.read(resp, "$.event_id");
        Integer reportCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single();
        assertEquals(0, reportCount); // Firm Decision 3: createEvent never persists reports

        jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
    }

    @Test
    void createEventMissingTitleIs400() throws Exception {
        mvc.perform(multipart("/api/events")
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_start_date", "2026-02-01").param("event_end_date", "2026-02-02")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Event title must be at least 3 characters"));
    }

    @Test
    void createEventBadPhotoMimeIs400() throws Exception {
        MockMultipartFile badPhoto = new MockMultipartFile("photos", "note.txt", "text/plain", "hi".getBytes());
        mvc.perform(multipart("/api/events").file(badPhoto)
                .param("event_type_id", String.valueOf(eventTypeId))
                .param("event_title", "Bad Photo Event 970")
                .param("event_start_date", "2026-02-01").param("event_end_date", "2026-02-02")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Photos must be JPG, PNG, or WEBP"));
    }

    @Test
    void deleteEventCascadesStudentsPhotosReportsMaster() throws Exception {
        Integer eventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                VALUES (:t,'To Delete 970',DATE '2026-04-01',DATE '2026-04-02',970301) RETURNING event_id
                """).param("t", eventTypeId).query(Integer.class).single();
        jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/x.jpg','x.jpg')")
                .param("e", eventId).update();
        jdbc.sql("INSERT INTO pp.event_reports(event_id, report_type, file_path, file_name) VALUES (:e,'SAMMELAN_REPORT','/tmp/x.pdf','x.pdf')")
                .param("e", eventId).update();

        mvc.perform(delete("/api/events/" + eventId).header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Deleted successfully"));

        Integer remainingMaster = jdbc.sql("SELECT COUNT(*) FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
        Integer remainingPhotos = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
        Integer remainingReports = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
        assertEquals(0, remainingMaster);
        assertEquals(0, remainingPhotos);
        assertEquals(0, remainingReports);
    }

    @Test
    void deleteEventInvalidIdIs400() throws Exception {
        mvc.perform(delete("/api/events/abc").header("Authorization", "Bearer " + admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid event ID"));
    }
}
