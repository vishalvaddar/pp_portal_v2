package com.rcf.imas.modules.student;

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
class ApplicantSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String studentTok;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (930001,'as1seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("930001", "as1", "ADMIN");
        studentTok = jwt.issueFinalToken("930002", "as2", "STUDENT");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (930001,'KARNATAKA','STATE')").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, nmms_year, student_name, medium, app_state)
            VALUES (930001, 24093000001, 2025, 'Applicant Alpha', 'KANNADA', 930001)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, nmms_year, student_name, medium, app_state)
            VALUES (930002, 24093000002, 2025, 'Applicant Beta', 'ENGLISH', 930001)
            """).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930001,'Cohort ASR1')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930002,'Cohort ASR2')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (930001,'Batch ASR1',930001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 930001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (930001,930002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (930001,930002)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 930001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 930001").update();
    }

    @Test
    void searchByNmmsRegNumberIgnoresAllOtherFilters() throws Exception {
        // student_name filter deliberately mismatches applicant 930001's own name -- Node ignores it entirely
        // once nmms_reg_number is present.
        mvc.perform(get("/api/search?nmms_reg_number=24093000001&student_name=Applicant Beta")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].student_name").value("Applicant Alpha"));
    }

    @Test
    void searchDefaultLimitIsTenNotFifty() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.pagination.limit").value(10))
           .andExpect(jsonPath("$.sort.sortBy").value("applicant_id"))
           .andExpect(jsonPath("$.sort.sortOrder").value("ASC"));
    }

    @Test
    void searchSortByStudentNameDescending() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=student_name&sort_order=desc")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sort.sortBy").value("student_name"))
           .andExpect(jsonPath("$.sort.sortOrder").value("DESC"))
           .andExpect(jsonPath("$.data[0].student_name").value("Applicant Beta"))
           .andExpect(jsonPath("$.data[1].student_name").value("Applicant Alpha"));
    }

    @Test
    void searchInvalidSortByFallsBackToApplicantId() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=not_a_real_column")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sort.sortBy").value("applicant_id"));
    }

    @Test
    void searchSortBySplHealthCondCrashesWithDetails500() throws Exception {
        // BUG PRESERVED: spl_health_cond is in Node's sort whitelist but the column lives on
        // applicant_secondary_info, not applicant_primary_info (aliased `a`) -- ORDER BY a.spl_health_cond
        // throws at the database. Only reachable when totalCount > 0 (data query is skipped when total=0).
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=spl_health_cond")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void searchNoMatchAtOffsetZeroIs404() throws Exception {
        mvc.perform(get("/api/search?nmms_year=1900").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No applications found matching the criteria."));
    }

    @Test
    void searchNoMatchAtNonzeroOffsetIs200EmptyData() throws Exception {
        mvc.perform(get("/api/search?nmms_year=1900&offset=50").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void cohortsReturnsAllOrderedByCohortNumberAscending() throws Exception {
        mvc.perform(get("/api/cohorts").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].cohort_number").value("930001"))
           .andExpect(jsonPath("$.data[1].cohort_number").value("930002"));
    }

    @Test
    void batchesByCohortReturnsOrderedByBatchId() throws Exception {
        mvc.perform(get("/api/batches/cohort/930001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].batch_name").value("Batch ASR1"));
    }

    @Test
    void batchesByCohortNonNumericIs500WithDetails() throws Exception {
        mvc.perform(get("/api/batches/cohort/abc").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void applicantSearchEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/search").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cohorts").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/batches/cohort/930001").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/search")).andExpect(status().isUnauthorized());
    }
}
