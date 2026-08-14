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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergeReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();

        // jurisdiction: district 950001 (EDUCATION DISTRICT) + block 950010 (BLOCK, parent=district)
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (950001,'BELAGAVI','EDUCATION DISTRICT',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (950010,'GOKAK','BLOCK',950001) ON CONFLICT (juris_code) DO NOTHING").update();

        // one phase-1 staged row and one phase-2 staged row (year 2025, district 950001, block 950010)
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, student_name, father_name, student_name_key)
            VALUES (7001, '2025', 950001, 29, 950010, 'Asha Rani', 'Ravi', 'asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (8001, '2025', 950001, 950010, '24010000001', 'Asha Rani', '55', '60', 'asharani')
            """).update();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase1_applications_id_seq', (SELECT MAX(id)::bigint FROM pp.stg_nmms_phase1_applications))").query(Long.class).single();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase2_results_result_stg_id_seq', (SELECT MAX(result_stg_id)::bigint FROM pp.stg_nmms_phase2_results))").query(Long.class).single();

        // one draft (std) row so draft-districts / merge-status show a merged count
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mseed'").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, created_by)
            VALUES (2025, 24010000001, 950001, 950010, 'Asha Rani', 'Ravi', :u)
            """).param("u", uid).update();
        // and one committed row for commit-status
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, medium, contact_no1, created_by, updated_by)
            VALUES (2025, 24010000001, 950001, 950010, 'Asha Rani', 'Ravi', 'Kannada', '9876543210', :u, :u)
            """).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (950001, 950010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mseed'").update();
    }

    @Test
    void jurisdictionListByType() throws Exception {
        mvc.perform(get("/api/merge/jurisdiction?type=BLOCK&parent=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].juris_code").value("950010"))
           .andExpect(jsonPath("$[0].juris_name").value("GOKAK"));
    }

    @Test
    void applicationsPaginated() throws Exception {
        mvc.perform(get("/api/merge/applications?year=2025&district=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows.length()").value(1))
           .andExpect(jsonPath("$.rows[0].id").value("7001"))
           .andExpect(jsonPath("$.rows[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.rows[0].nmms_block_name").value("GOKAK"))
           .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void resultsPaginated() throws Exception {
        mvc.perform(get("/api/merge/results?year=2025&district=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows.length()").value(1))
           .andExpect(jsonPath("$.rows[0].nmms_reg_number").value("24010000001"))
           .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void draftDistrictsHasNumericIdsAndCounts() throws Exception {
        mvc.perform(get("/api/merge/draft-districts").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$[0].district_id").value(950001))          // JSON number
           .andExpect(jsonPath("$[0].year").value(2025))
           .andExpect(jsonPath("$[0].total_applicants").value(1))
           .andExpect(jsonPath("$[0].total_merged_applicants").value(1))
           .andExpect(jsonPath("$[0].remaining_applicants").value(0));
    }

    @Test
    void draftDistrictStudents() throws Exception {
        mvc.perform(get("/api/merge/draft-district-students?district=950001&year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("Asha Rani"))
           .andExpect(jsonPath("$[0].block_name").value("GOKAK"))
           .andExpect(jsonPath("$[0].nmms_reg_number").value("24010000001"));
    }

    @Test
    void mergeStatusHasIsmergedBoolean() throws Exception {
        mvc.perform(get("/api/merge/merge-status?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].district_id").value(950001))
           .andExpect(jsonPath("$.data[0].total_applicants").value(1))
           .andExpect(jsonPath("$.data[0].total_merged_applicants").value(1))
           .andExpect(jsonPath("$.data[0].ismerged").value(true));
    }

    @Test
    void mergeStatusRequiresYear() throws Exception {
        mvc.perform(get("/api/merge/merge-status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Year is required"));
    }

    @Test
    void commitStatusWrapsDataArray() throws Exception {
        mvc.perform(get("/api/merge/commit-status?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].district_id").value("950001"))     // raw row → String
           .andExpect(jsonPath("$.data[0].total_applicants").value("1"))
           .andExpect(jsonPath("$.data[0].total_committed").value("1"))
           .andExpect(jsonPath("$.data[0].is_committed").value(true));
    }

    @Test
    void mergedStatusPreservesNodeNullCountsBug() throws Exception {
        mvc.perform(get("/api/merge/merged-status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].district_id").value(950001))
           .andExpect(jsonPath("$[0].year").value(2025))
           .andExpect(jsonPath("$[0].total_applicants").doesNotExist())     // null (field present, value null)
           .andExpect(jsonPath("$[0].total_applicants").isEmpty());
    }

    @Test
    void readsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/merge/applications?year=2025&district=950001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/merge/draft-districts").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
