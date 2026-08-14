package com.rcf.imas.modules.selectionreports;

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
class SelectionReportsInitNmmsTurnoutIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970001,'srAdmin970','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Jurisdiction: 2 districts, 3 blocks (Belagavi has 2 blocks, Bagalkot has 1).
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (970101,'Belagavi','DISTRICT')").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (970102,'Bagalkot','DISTRICT')").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970111,'Belagavi Block A','BLOCK',970101)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970112,'Belagavi Block B','BLOCK',970101)").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970113,'Bagalkot Block A','BLOCK',970102)").update();

        // Applicants for nmms/turnout-data (nmms_year=2025).
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name) VALUES (970201,2025,97020100001,970101,970111,'Applicant A1')").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name) VALUES (970202,2025,97020200001,970101,970111,'Applicant A2')").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name) VALUES (970203,2025,97020300001,970101,970112,'Applicant A3')").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name) VALUES (970204,2025,97020400001,970102,970113,'Applicant A4')").update();
        // Different year -- must be excluded from every nmms/turnout query below.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name) VALUES (970205,2024,97020500001,970101,970111,'Applicant A5 Old Year')").update();

        // Turn-Out quirk 7 fixture: 3 shortlist rows for the Belagavi district applicants, ONE of them 'N'.
        // called_count must count all 3 (not just the 'Y' rows).
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (970201,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (970202,'N')").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (970203,'Y')").update();
        // Only 970201 appeared; 970202 has no attendance row at all (LEFT JOIN -> not counted); 970203 explicitly 'N'.
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (970201,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (970203,'N')").update();

        // /init fixture: duplicate academic_year across phases must collapse via DISTINCT.
        jdbc.sql("INSERT INTO pp.system_config(academic_year, phase) VALUES ('2025-26','PHASE1')").update();
        jdbc.sql("INSERT INTO pp.system_config(academic_year, phase) VALUES ('2025-26','PHASE2')").update();
        jdbc.sql("INSERT INTO pp.system_config(academic_year, phase) VALUES ('2024-25','PHASE1')").update();

        adminToken = jwt.issueFinalToken("970001", "srAdmin970", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.system_config WHERE academic_year IN ('2025-26','2024-25')").update();
        jdbc.sql("DELETE FROM pp.applicant_exam_attendance WHERE applicant_id IN (970201,970202,970203)").update();
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id IN (970201,970202,970203)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (970201,970202,970203,970204,970205)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (970111,970112,970113,970101,970102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970001").update();
    }

    @Test
    void initReturnsDistinctYearsDescending() throws Exception {
        mvc.perform(get("/api/selection-reports/init").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.years", hasSize(2)))
           .andExpect(jsonPath("$.years[0].academic_year").value("2025-26"))
           .andExpect(jsonPath("$.years[1].academic_year").value("2024-25"));
    }

    @Test
    void nmmsDataDistrictReturnsCountsOrderedByDistrictName() throws Exception {
        mvc.perform(get("/api/selection-reports/nmms-data").param("year", "2025").param("type", "district")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].label").value("Bagalkot"))
           .andExpect(jsonPath("$[0].applicant_count").value("1"))
           .andExpect(jsonPath("$[1].label").value("Belagavi"))
           .andExpect(jsonPath("$[1].applicant_count").value("3"));
    }

    @Test
    void nmmsDataBlockReturnsCountsWithDistrictNameOrderedByDistrictThenBlock() throws Exception {
        mvc.perform(get("/api/selection-reports/nmms-data").param("year", "2025").param("type", "block")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(3)))
           .andExpect(jsonPath("$[0].district_name").value("Bagalkot"))
           .andExpect(jsonPath("$[0].label").value("Bagalkot Block A"))
           .andExpect(jsonPath("$[0].applicant_count").value("1"))
           .andExpect(jsonPath("$[1].district_name").value("Belagavi"))
           .andExpect(jsonPath("$[1].label").value("Belagavi Block A"))
           .andExpect(jsonPath("$[1].applicant_count").value("2"))
           .andExpect(jsonPath("$[2].label").value("Belagavi Block B"))
           .andExpect(jsonPath("$[2].applicant_count").value("1"));
    }

    @Test
    void nmmsDataUnknownTypeFallsThroughToBlockBranch() throws Exception {
        // Firm Decision 4: type=bogus (or missing) must silently use the block-mode query, not 400.
        mvc.perform(get("/api/selection-reports/nmms-data").param("year", "2025").param("type", "bogus")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].district_name").value("Bagalkot"));
    }

    @Test
    void nmmsDataAppliesYearNormalizationHelper() throws Exception {
        // "2025-26" must normalize to "2025" before hitting nmms_year=$1 (quirk 5).
        mvc.perform(get("/api/selection-reports/nmms-data").param("year", "2025-26").param("type", "district")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[1].applicant_count").value("3"));
    }

    @Test
    void turnoutDataDistrictCalledCountIncludesShortlistedNRow() throws Exception {
        // Deferred quirk: called_count must be 3 (all shortlist rows), NOT 2 (only 'Y' rows).
        mvc.perform(get("/api/selection-reports/turnout-data").param("year", "2025").param("type", "district")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].label").value("Belagavi"))
           .andExpect(jsonPath("$[0].called_count").value("3"))
           .andExpect(jsonPath("$[0].appeared_count").value("1"))
           .andExpect(jsonPath("$[0].turnout_percentage").value("33.33"));
    }
}
