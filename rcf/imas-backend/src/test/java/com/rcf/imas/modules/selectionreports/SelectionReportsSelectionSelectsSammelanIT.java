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
class SelectionReportsSelectionSelectsSammelanIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970301,'srAdmin970b','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Jurisdiction: 1 district, 1 block, for selection-data / selects-data.
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (970302,'Dharwad','DISTRICT')").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970312,'Dharwad Block A','BLOCK',970302)").update();

        // 3 applicants: 970401/970402 appeared ('Y'), 970403 did not ('N', excluded from selection-data).
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, gender, student_name) VALUES (970401,2025,97040100001,970302,970312,'M','Sel Applicant M1')").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, gender, student_name) VALUES (970402,2025,97040200001,970302,970312,'F','Sel Applicant F1')").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, gender, student_name) VALUES (970403,2025,97040300001,970302,970312,'M','Sel Applicant M2')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (970401,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (970402,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (970403,'N')").update();
        // Only 970401 (M) is in student_master -- i.e. selected.
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender) VALUES (970401,970401,'Sel Applicant M1','M')").update();

        // Cohorts for /cohorts (insertion order == cohort_number ASC, matching the sequence default).
        jdbc.sql("INSERT INTO pp.cohort(cohort_name) VALUES ('Cohort Beta')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_name) VALUES ('Cohort Alpha')").update();

        // Sammelan fixtures: 1 cohort, 1 real 'Sammelan' event_type + 1 decoy 'Training' event_type,
        // 4 events to exercise the overlap-range filter and the hard-coded event_type_name literal.
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970501,'Sammelan Test Cohort')").update();
        jdbc.sql("INSERT INTO pp.event_type(event_type_id, event_type_name) VALUES (970501,'Sammelan')").update();
        jdbc.sql("INSERT INTO pp.event_type(event_type_id, event_type_name) VALUES (970502,'Training')").update();

        // Event W: Sammelan, straddles the range start (starts before fromDate, ends exactly on fromDate) -> INCLUDED.
        jdbc.sql("""
                INSERT INTO pp.event_master(event_type_id, event_title, event_start_date, event_end_date,
                    event_district, event_block, event_location, cohort_number, boys_attended, girls_attended)
                VALUES (970501,'Sammelan W', DATE '2026-02-25', DATE '2026-03-02', 970302, 970312, 'Hall W', 970501, 4, 3)
                """).update();
        // Event X: Sammelan, fully inside the range -> INCLUDED.
        jdbc.sql("""
                INSERT INTO pp.event_master(event_type_id, event_title, event_start_date, event_end_date,
                    event_district, event_block, event_location, cohort_number, boys_attended, girls_attended)
                VALUES (970501,'Sammelan X', DATE '2026-03-01', DATE '2026-03-03', 970302, 970312, 'Hall X', 970501, 10, 8)
                """).update();
        // Event Y: same dates as X but event_type = Training (decoy) -> EXCLUDED (quirk 9).
        jdbc.sql("""
                INSERT INTO pp.event_master(event_type_id, event_title, event_start_date, event_end_date,
                    event_district, event_block, event_location, cohort_number, boys_attended, girls_attended)
                VALUES (970502,'Training Y (decoy)', DATE '2026-03-01', DATE '2026-03-03', 970302, 970312, 'Hall Y', 970501, 99, 99)
                """).update();
        // Event Z: Sammelan, entirely after the range -> EXCLUDED.
        jdbc.sql("""
                INSERT INTO pp.event_master(event_type_id, event_title, event_start_date, event_end_date,
                    event_district, event_block, event_location, cohort_number, boys_attended, girls_attended)
                VALUES (970501,'Sammelan Z (out of range)', DATE '2026-04-01', DATE '2026-04-05', 970302, 970312, 'Hall Z', 970501, 5, 5)
                """).update();

        adminToken = jwt.issueFinalToken("970301", "srAdmin970b", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970501").update();
        jdbc.sql("DELETE FROM pp.event_type WHERE event_type_id IN (970501,970502)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970501 OR cohort_name IN ('Cohort Alpha','Cohort Beta')").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE applicant_id = 970401").update();
        jdbc.sql("DELETE FROM pp.applicant_exam_attendance WHERE applicant_id IN (970401,970402,970403)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (970401,970402,970403)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (970312,970302)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970301").update();
    }

    @Test
    void selectionDataDistrictComputesAppearedSelectedAndPercentage() throws Exception {
        mvc.perform(get("/api/selection-reports/selection-data").param("year", "2025").param("type", "district")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].label").value("Dharwad"))
           .andExpect(jsonPath("$[0].appeared_count").value("2"))
           .andExpect(jsonPath("$[0].selected_count").value("1"))
           .andExpect(jsonPath("$[0].selection_percentage").value("50.00"));
    }

    @Test
    void selectsDataDistrictReturnsUnpivotedGenderRows() throws Exception {
        // Firm Decision 9: raw {label,gender,student_count} rows, no boys_sel/girls_sel pivot here.
        mvc.perform(get("/api/selection-reports/selects-data").param("year", "2025").param("type", "district")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].label").value("Dharwad"))
           .andExpect(jsonPath("$[0].gender").value("F"))
           .andExpect(jsonPath("$[0].student_count").value("0"))
           .andExpect(jsonPath("$[1].gender").value("M"))
           .andExpect(jsonPath("$[1].student_count").value("1"));
    }

    @Test
    void cohortsReturnsNamesOrderedByCohortNumber() throws Exception {
        mvc.perform(get("/api/selection-reports/cohorts").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].cohort_name").value("Cohort Beta"))
           .andExpect(jsonPath("$[1].cohort_name").value("Cohort Alpha"));
    }

    @Test
    void sammelanDataMissingParamsReturns400() throws Exception {
        mvc.perform(get("/api/selection-reports/sammelan-data")
                .param("cohort", "Sammelan Test Cohort").param("fromDate", "2026-03-02")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required parameters"));
    }

    @Test
    void sammelanDataAppliesOverlapRangeAndHardcodedEventTypeFilter() throws Exception {
        // Range [2026-03-02, 2026-03-10]: Event W straddles the start (included, overlap semantics --
        // quirk 8), Event X is fully inside (included), Event Y is same dates as X but wrong event_type
        // (excluded -- quirk 9), Event Z is fully after the range (excluded).
        mvc.perform(get("/api/selection-reports/sammelan-data")
                .param("cohort", "Sammelan Test Cohort").param("fromDate", "2026-03-02").param("toDate", "2026-03-10")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].label").value("Sammelan W"))
           .andExpect(jsonPath("$[0].district_name").value("Dharwad"))
           .andExpect(jsonPath("$[0].block_name").value("Dharwad Block A"))
           .andExpect(jsonPath("$[0].boys_sel").value("4"))
           .andExpect(jsonPath("$[0].girls_sel").value("3"))
           .andExpect(jsonPath("$[1].label").value("Sammelan X"))
           .andExpect(jsonPath("$[1].boys_sel").value("10"))
           .andExpect(jsonPath("$[1].girls_sel").value("8"));
    }
}
