package com.rcf.imas.modules.merge;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergeDownloadDeleteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (990001,'BELAGAVI','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (990010,'GOKAK','BLOCK',990001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mdseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mdseed'").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (990001, 990010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mdseed'").update();
    }

    @Test
    void downloadTemplateP1IsHeaderOnlyCsv() throws Exception {
        String body = mvc.perform(get("/api/merge/download-template?phase=p1").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("nmms_year").contains("current_institute_dise_code").contains("date_of_application");
    }

    @Test
    void downloadTemplateInvalidPhaseIs400() throws Exception {
        mvc.perform(get("/api/merge/download-template?phase=zzz").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid phase"));
    }

    @Test
    void districtCsvEmptyIs404() throws Exception {
        mvc.perform(get("/api/merge/district/990001/download-csv").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found for this district."));
    }

    @Test
    void districtCsvReturnsRows() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, contact_no1, created_by)
            VALUES (2025, 24010000001, 990001, 990010, 'Asha', 'Ravi', '9876543210', :u)
            """).param("u", uid).update();
        String body = mvc.perform(get("/api/merge/district/990001/download-csv").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("student_name").contains("Asha");
    }

    @Test
    void deleteP1DataWhenNotYetDrafted() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',990001,990010,'X','x')").update();
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("1 Phase 1 application records deleted for district 990001"));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 990001").query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void deleteBlockedWhenAlreadyDrafted() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',990001,990010,'X','x')").update();
        jdbc.sql("INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, created_by) VALUES (2025, 24010000002, 990001, 990010, 'X', :u)").param("u", uid).update();
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Deletion not allowed!! Data already merged. To continue with the deletion you need to delete the merged data"));
    }

    @Test
    void deleteRequiresDistrictAndYear() throws Exception {
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("District is required"));
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Year is required"));
    }

    @Test
    void deleteDistrictDataMissingBodyReturns400NotFiveHundred() throws Exception {
        // Node's req.body defaults to {} when no body is sent -> destructures to undefined -> its own
        // `if (!district)` 400 runs (mergeController.js:86-90). A required @RequestBody would MissingServletRequestBody 500 instead.
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("District is required"));
    }

    @Test
    void downloadsAndDeleteAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(get("/api/merge/download-template?phase=p1").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
