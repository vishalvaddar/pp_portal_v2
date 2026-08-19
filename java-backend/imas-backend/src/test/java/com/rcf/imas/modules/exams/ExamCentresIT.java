package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamCentresIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('ecseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='ecseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "ecseed", "ADMIN");
        student = jwt.issueFinalToken("999", "s", "STUDENT");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn, contact_phone, contact_email)
            VALUES (80001,'EC001','Active Centre','Y','9000000001','active@x.com') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn)
            VALUES (80002,'EC002','Inactive Centre','N') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id IN (80001,80002) OR pp_exam_centre_name LIKE 'New Centre%'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'ecseed'").update();
    }

    @Test
    void listActiveCentresOnlyProjectsIdAndName() throws Exception {
        mvc.perform(get("/api/exams/exam-centres").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80001')].pp_exam_centre_name").value(org.hamcrest.Matchers.hasItem("Active Centre")))
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80002')]").isEmpty()); // inactive excluded
    }

    @Test
    void viewcentresReturnsAllColumnsAllRows() throws Exception {
        mvc.perform(get("/api/exams/viewcentres").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80002')].active_yn").value(org.hamcrest.Matchers.hasItem("N"))); // inactive included
    }

    @Test
    void createCentreRejectsBlankName() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"  \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Centre name is required."));
    }

    @Test
    void createCentreRejectsInvalidPincode() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"New Centre X\",\"pincode\":\"abc\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Invalid pincode."));
    }

    @Test
    void createCentreDuplicateCodeIs409WithField() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"New Centre Dup\",\"pp_exam_centre_code\":\"EC001\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value("Centre code already exists. Please use a different value."))
           .andExpect(jsonPath("$.field").value("centre_code"));
    }

    @Test
    void createCentreSuccessReturnsFullRowIncludingGeneratedGoogleMapLink() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"New Centre Full\",\"latitude\":12.97,\"longitude\":77.59,\"sitting_capacity\":\"50\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.centre.pp_exam_centre_name").value("New Centre Full"))
           .andExpect(jsonPath("$.centre.sitting_capacity").value("50"))
           .andExpect(jsonPath("$.centre.google_map_link").value(org.hamcrest.Matchers.containsString("google.com/maps")));
    }

    @Test
    void deleteCentreBlockedWhenUsedInExam() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, pp_exam_centre_id)
            VALUES (80101,'Blocker Exam','2026-01-01','09:00:00','11:00:00',80001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        try {
            mvc.perform(delete("/api/exams/exam-centres/80001").header("Authorization", "Bearer " + admin))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message").value("Centre already used in exam: Blocker Exam"));
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 80101").update();
        }
    }

    @Test
    void deleteCentreSucceedsWhenUnused() throws Exception {
        mvc.perform(delete("/api/exams/exam-centres/80002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNoContent());
    }

    @Test
    void updateCentreFalsyActiveYnResetsToY() throws Exception {
        mvc.perform(put("/api/exams/exam-centres/80002").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"Inactive Centre Renamed\"}")) // active_yn omitted
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.centre.active_yn").value("Y")); // reset quirk (Firm Decision 11a)
    }

    @Test
    void updateCentreMissingIdIs404() throws Exception {
        mvc.perform(put("/api/exams/exam-centres/999999999").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"X\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Centre not found"));
    }

    @Test
    void examCentreEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/exams/exam-centres").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/viewcentres").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
    }
}
