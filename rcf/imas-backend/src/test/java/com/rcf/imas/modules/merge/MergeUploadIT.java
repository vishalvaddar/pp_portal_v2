package com.rcf.imas.modules.merge;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergeUploadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    // state 29, district 960001 (BELAGAVI), block 960010 (GOKAK), institute dise 12345678901
    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (29,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (960001,'BELAGAVI','EDUCATION DISTRICT',29) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (960010,'GOKAK','BLOCK',960001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('12345678901','Test School') ON CONFLICT (dise_code) DO NOTHING").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 960001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 960001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = '12345678901'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (29, 960001, 960010)").update();
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "u.csv", "text/csv", content.getBytes());
    }

    @Test
    void uploadP1InsertsValidRows() throws Exception {
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha Rani,Ravi,Test School,9876543210,9000000000\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.logs[0]").value("Successfully inserted 1 records."));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(1);
        String key = jdbc.sql("SELECT student_name_key FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(String.class).single();
        assertThat(key).isEqualTo("asharani");
    }

    @Test
    void uploadP1RejectsWholeBatchOnAnyValidationError() throws Exception {
        // second row has an unknown block → all-or-nothing rollback, nothing inserted
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha,Ravi,S,9876543210,9\n"
                + "2025,KARNATAKA,BELAGAVI,NOWHERE,12345678901,222222,Kiran,Suresh,S,9876543210,9\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.logs").isArray());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void uploadP1DuplicateGuard() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'), '2025', 960001, 29, 960010, 'X', 'x')").update();
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha,Ravi,S,9876543210,9\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.logs[0]").value("Upload Rejected: Data for Year 2025 already uploaded for this district."));
    }

    @Test
    void uploadP1NoFileIs400() throws Exception {
        mvc.perform(multipart("/api/merge/upload-p1")
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("No CSV file provided"));
    }

    @Test
    void uploadP2InsertsValidRowsWithPendingStatus() throws Exception {
        String content = "nmms_year,nmms_block,nmms_reg_number,student_name,gmat_score,sat_score\n"
                + "2025,GOKAK,24010000001,Asha Rani,55,60\n";
        mvc.perform(multipart("/api/merge/upload-p2").file(csv(content))
                .param("year", "2025").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.logs[0]").value("Successfully inserted 1 results."));

        String status = jdbc.sql("SELECT match_status FROM pp.stg_nmms_phase2_results WHERE district = 960001").query(String.class).single();
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void uploadP2SilentlySkipsRegNameFailuresWithNoLog() throws Exception {
        // reg too short + bad name → rowError but NO log pushed → batch commits only the valid row (Node quirk)
        String content = "nmms_year,nmms_block,nmms_reg_number,student_name,gmat_score,sat_score\n"
                + "2025,GOKAK,24010000002,Valid Name,50,50\n"
                + "2025,GOKAK,123,Bad9Name,40,40\n";
        mvc.perform(multipart("/api/merge/upload-p2").file(csv(content))
                .param("year", "2025").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(1);   // only the valid row
    }

    @Test
    void uploadP2DuplicateReturns200WithSuccessFalse() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'), '2025', 960001, 960010, '24010000001', 'X', 'x')").update();
        String content = "nmms_year,nmms_block,nmms_reg_number,student_name,gmat_score,sat_score\n"
                + "2025,GOKAK,24010000002,Asha Rani,55,60\n";
        mvc.perform(multipart("/api/merge/upload-p2").file(csv(content))
                .param("year", "2025").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.logs[0]").value("Upload Rejected: Results for Year 2025 have already been uploaded for this district."));
    }

    @Test
    void uploadsAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(multipart("/api/merge/upload-p1").file(csv("nmms_year\n2025\n"))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
