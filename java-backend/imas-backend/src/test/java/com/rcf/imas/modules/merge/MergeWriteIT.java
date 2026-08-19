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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergeWriteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (29,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (980001,'BELAGAVI','EDUCATION DISTRICT',29) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (980010,'GOKAK','BLOCK',980001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mwseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mwseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "mwseed", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (29, 980001, 980010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mwseed'").update();
    }

    private void stageUnique() {
        // one unique 1:1 name-key pair
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, students_sats_id, student_name, father_name, contact_no1, contact_no2, current_institute_dise_code, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',980001,29,980010,'111111','Asha Rani','Ravi','9876543210','9000000000',NULL,'asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'),'2025',980001,980010,'24010000001','Asha Rani','55','60','asharani')
            """).update();
    }

    @Test
    void bulkAutoMapMovesUnique1to1IntoDraftAndMarksMatched() throws Exception {
        stageUnique();
        mvc.perform(post("/api/merge/bulk-auto-map").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"980001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Bulk mapping successful. Records copied to draft."));

        Long std = jdbc.sql("SELECT COUNT(*) FROM pp.std_applicant_primary_info WHERE district = 980001 AND nmms_reg_number = 24010000001").query(Long.class).single();
        assertThat(std).isEqualTo(1);
        String status = jdbc.sql("SELECT match_status FROM pp.stg_nmms_phase2_results WHERE district = 980001").query(String.class).single();
        assertThat(status).isEqualTo("MATCHED");
    }

    @Test
    void resolveLivelyInsertsPairAndMarksMatched() throws Exception {
        stageUnique();
        Long appId = jdbc.sql("SELECT id FROM pp.stg_nmms_phase1_applications WHERE district = 980001").query(Long.class).single();
        Long resId = jdbc.sql("SELECT result_stg_id FROM pp.stg_nmms_phase2_results WHERE district = 980001").query(Long.class).single();

        mvc.perform(post("/api/merge/resolve-lively").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_id\":" + appId + ",\"res_id\":" + resId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Mapped successfully"));

        Long std = jdbc.sql("SELECT COUNT(*) FROM pp.std_applicant_primary_info WHERE district = 980001").query(Long.class).single();
        assertThat(std).isEqualTo(1);
    }

    @Test
    void resolveLivelyDominoAutoMatchesRemainingPair() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, students_sats_id, student_name, father_name, contact_no1, contact_no2, current_institute_dise_code, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',980001,29,980010,'111111','Asha Rani','Ravi','9876543210','9000000000',NULL,'asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'),'2025',980001,980010,'24010000001','Asha Rani','55','60','asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'),'2025',980001,980010,'24010000002','Asha Rani','50','50','asharani')
            """).update();

        Long appId = jdbc.sql("SELECT id FROM pp.stg_nmms_phase1_applications WHERE district = 980001").query(Long.class).single();
        Long res1Id = jdbc.sql("SELECT result_stg_id FROM pp.stg_nmms_phase2_results WHERE district = 980001 AND nmms_reg_number = '24010000001'").query(Long.class).single();

        mvc.perform(post("/api/merge/resolve-lively").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_id\":" + appId + ",\"res_id\":" + res1Id + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Mapped successfully"));

        Long std = jdbc.sql("SELECT COUNT(*) FROM pp.std_applicant_primary_info WHERE district = 980001").query(Long.class).single();
        assertThat(std).isEqualTo(2);
        Long matched = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = 980001 AND match_status = 'MATCHED'").query(Long.class).single();
        assertThat(matched).isEqualTo(2);
    }

    @Test
    void commitToPrimaryFreezesDraftIntoPrimary() throws Exception {
        // put a draft row directly, then commit
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, app_state, nmms_block, student_name, father_name, contact_no1, created_by)
            VALUES (2025, 24010000009, 980001, 29, 980010, 'Asha', 'Ravi', '9876543210', :u)
            """).param("u", uid).update();

        mvc.perform(post("/api/merge/commit-to-primary").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"980001\",\"year\":\"2025\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Successfully committed to Primary Table."));

        Long prim = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE district = 980001 AND nmms_reg_number = 24010000009").query(Long.class).single();
        assertThat(prim).isEqualTo(1);
    }

    @Test
    void writesAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(post("/api/merge/bulk-auto-map").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"980001\"}"))
           .andExpect(status().isForbidden());
    }
}
