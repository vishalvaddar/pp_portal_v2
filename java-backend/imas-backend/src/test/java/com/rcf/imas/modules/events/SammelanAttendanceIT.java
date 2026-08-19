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

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SammelanAttendanceIT extends PgIntegrationTest {

    @TempDir static Path storageRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("imas.event-storage-path", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    Integer sammelanTypeId;
    Integer otherTypeId;
    Integer sammelanEventId;
    Integer nonSammelanEventId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970501,'evAdmin975','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("970501", "evAdmin975", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970501, 'Cohort975')").update();
        // sammelanEvents() filters literally on event_type_name = 'Sammelan' (case-sensitive, no
        // ILIKE/trim -- ground truth §2, §7.8), so the seed must use that exact literal, not a
        // '975'-suffixed variant, for sammelanListOnlyReturnsSammelanTypeEvents to be meaningful.
        sammelanTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Sammelan') RETURNING event_type_id").query(Integer.class).single();
        otherTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Workshop975') RETURNING event_type_id").query(Integer.class).single();

        sammelanEventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                VALUES (:t,'Sammelan Attendance 975',DATE '2026-06-01',DATE '2026-06-02',970501) RETURNING event_id
                """).param("t", sammelanTypeId).query(Integer.class).single();
        nonSammelanEventId = jdbc.sql("""
                INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                VALUES (:t,'Workshop Not Sammelan 975',DATE '2026-06-01',DATE '2026-06-02',970501) RETURNING event_id
                """).param("t", otherTypeId).query(Integer.class).single();

        // jurisdiction_type rows are required by jurisdiction_juris_type_fkey
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT (juris_type) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970510,'StateX975','STATE',NULL)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970511,'DistrictX975','EDUCATION DISTRICT',970510)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970512,'BlockX975','BLOCK',970511)").update();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (970501,'Batch975',970501)").update();

        // Student A: present already (event_students row exists) -> is_marked=true
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block) VALUES (970501,2026,97050100001,'Amy Attend975',970510,970511,970512)").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, batch_id, active_yn) VALUES (970501,970501,'Amy Attend975','F',970501,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.event_students(event_id, student_id) VALUES (:e,970501)").param("e", sammelanEventId).update();

        // Student B: not yet marked -> is_marked=false
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block) VALUES (970502,2026,97050200001,'Bob Attend975',970510,970511,970512)").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, batch_id, active_yn) VALUES (970502,970502,'Bob Attend975','M',970501,'ACTIVE')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.event_students WHERE student_id IN (970501,970502)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (970501,970502)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (970501,970502)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 970501").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (970510,970511,970512)").update();
        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970501)").update();
        jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970501)").update();
        jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970501").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name IN ('Sammelan','Workshop975')").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970501").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970501").update();
    }

    @Test
    void sammelanListOnlyReturnsSammelanTypeEvents() throws Exception {
        mvc.perform(get("/api/attendance/sammelan-list").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[?(@.event_title=='Sammelan Attendance 975')]", hasSize(1)))
            .andExpect(jsonPath("$.data[?(@.event_title=='Workshop Not Sammelan 975')]", hasSize(0)));
    }

    @Test
    void studentsListMarksExistingAttendeeAndOmitsUnmarked() throws Exception {
        String body = "{\"eventTitle\":\"Sammelan Attendance 975\",\"stateName\":\"StateX975\",\"page\":1}";
        mvc.perform(post("/api/attendance/students-list").contentType("application/json").content(body)
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[?(@.student_name=='Amy Attend975')].is_marked").value(true))
            .andExpect(jsonPath("$.data[?(@.student_name=='Bob Attend975')].is_marked").value(false));
    }

    @Test
    void studentsListUnknownEventTitleIs404() throws Exception {
        String body = "{\"eventTitle\":\"Does Not Exist 975\"}";
        mvc.perform(post("/api/attendance/students-list").contentType("application/json").content(body)
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.msg").value("Event not found"));
    }

    @Test
    void studentsListMissingBodyIs404NotFiveHundred() throws Exception {
        // Node's req.body defaults to {} with no body sent -> eventTitle undefined -> binds SQL NULL ->
        // 0 rows -> 404 {success:false,msg:"Event not found"} (eventController.js:221-233), NOT a 500
        // from a required @RequestBody.
        mvc.perform(post("/api/attendance/students-list").header("Authorization", "Bearer " + admin))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.msg").value("Event not found"));
    }

    @Test
    void studentsListStringPageIsParsedNotCastToFiveHundred() throws Exception {
        // Node's `(page||1)` accepts a JSON string page value via JS coercion; a hard (Number) cast on the
        // Java side would ClassCastException. Must parse "2" and still 200.
        String body = "{\"eventTitle\":\"Sammelan Attendance 975\",\"page\":\"2\"}";
        mvc.perform(post("/api/attendance/students-list").contentType("application/json").content(body)
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void submitAttendancePersistsEventStudentsAndCountsAndIsIdempotent() throws Exception {
        String studentIdsJson = "[970501,970502]";

        mvc.perform(multipart("/api/attendance/save")
                .param("eventId", String.valueOf(sammelanEventId))
                .param("studentIds", studentIdsJson)
                .param("parents_attended", "4")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.msg").value("Attendance updated successfully!"));

        Integer linkedCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_students WHERE event_id = :id")
                .param("id", sammelanEventId).query(Integer.class).single();
        assertEquals(2, linkedCount); // student A already linked (seed) + student B newly linked

        Integer boys = jdbc.sql("SELECT boys_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
        Integer girls = jdbc.sql("SELECT girls_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
        Integer parents = jdbc.sql("SELECT parents_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
        assertEquals(1, boys);   // Bob = M
        assertEquals(1, girls);  // Amy = F
        assertEquals(4, parents);

        // idempotent replay: ON CONFLICT DO NOTHING, still exactly 2 rows, no DELETE ever happens
        // (Disagreements #1 -- this endpoint can only ADD attendees, never remove them)
        mvc.perform(multipart("/api/attendance/save")
                .param("eventId", String.valueOf(sammelanEventId))
                .param("studentIds", "[970501]")   // dropping 970502 from the list does NOT remove it
                .param("parents_attended", "4")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());

        Integer stillLinked = jdbc.sql("SELECT COUNT(*) FROM pp.event_students WHERE event_id = :id")
                .param("id", sammelanEventId).query(Integer.class).single();
        assertEquals(2, stillLinked); // 970502 NOT removed -- verbatim INSERT-only port (Disagreements #1)
    }

    @Test
    void submitAttendanceMissingStudentIdsStillReturns200() throws Exception {
        // Node has no validation here: a missing studentIds binds null -> unnest(null) -> 0 rows -> 200.
        // (Must NOT 500 the way a required @RequestParam would.)
        mvc.perform(multipart("/api/attendance/save")
                .param("eventId", String.valueOf(sammelanEventId))
                .param("parents_attended", "2")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void submitAttendanceMissingEventIdIs500WithSuccessFalse() throws Exception {
        // Node: a missing eventId flows into the SQL and fails -> 500 {success:false, msg:"Server Error..."}.
        // Java reproduces the shape (not a generic {error} 500) after eventId parse throws.
        mvc.perform(multipart("/api/attendance/save")
                .param("studentIds", "[970501]")
                .param("parents_attended", "1")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().is5xxServerError())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void submitAttendanceWithReportPersistsRow() throws Exception {
        MockMultipartFile report = new MockMultipartFile("reports", "attendance-report.pdf", "application/pdf", "pdf-bytes".getBytes());
        mvc.perform(multipart("/api/attendance/save").file(report)
                .param("eventId", String.valueOf(sammelanEventId))
                .param("studentIds", "[970501]")
                .param("parents_attended", "1")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());

        Integer reportCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id AND report_type = 'SAMMELAN_REPORT'")
                .param("id", sammelanEventId).query(Integer.class).single();
        assertEquals(1, reportCount);
    }
}
