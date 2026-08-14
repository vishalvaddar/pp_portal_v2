package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryStatsAndBrandsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        // Users: 951001 = admin/token issuer; 951002 = holds an open official_issue (excluded from /users);
        // 951003 = locked_yn left NULL (excluded from /users, quirk 11); 951004 = free staff (included).
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951001,'saAdmin951','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951002,'saStaffHeld951','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password) VALUES (951003,'saStaffNullLocked951','x')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951004,'saStaffFree951','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (951001,'Cohort TI951')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (951001,'TI Batch',951001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (951001, 24951000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (951002, 24951000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (951001)").update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (951002)").update();

        // student 951001: active, no open student_issue -> eligible. student 951002: active, HAS an open
        // student_issue -> not eligible.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (951001, 951001, 95100001, 'Eligible Student 951', 'F', 951001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (951002, 951002, 95100002, 'Held Student 951', 'M', 951001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (951001,'BrandA951','ModelA951',951001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab_inventory: 951001 IN_OFFICE, 951002 DAMAGED (linked to student 951002's open issue),
        // 951003 ASSIGNED (linked to user 951002's open official_issue).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951001,'SN-TI-951001',951001,'IN_OFFICE',951001)").update();
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951002,'SN-TI-951002',951001,'DAMAGED',951001)").update();
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951003,'SN-TI-951003',951001,'ASSIGNED',951001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (951002,951002,CURRENT_DATE,951001)").update();
        jdbc.sql("INSERT INTO pp.official_issue(tab_id, user_id, assignment_date, created_by) VALUES (951003,951002,CURRENT_DATE,951001)").update();

        adminToken = jwt.issueFinalToken("951001", "saAdmin951", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 951001 OR (brand_name = 'TestBrand951')").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 951001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 951001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (951001,951002,951003,951004)").update();
    }

    @Test
    void statsReturnsFilterCountsAsStrings() throws Exception {
        mvc.perform(get("/api/tabs/stats").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.total").value("3"))
           .andExpect(jsonPath("$.data.in_office").value("1"))
           .andExpect(jsonPath("$.data.damaged").value("1"))
           .andExpect(jsonPath("$.data.lost").value("0"))
           .andExpect(jsonPath("$.data.returned_awaiting").value("0"))
           .andExpect(jsonPath("$.data.student_assigned").value("1"))
           .andExpect(jsonPath("$.data.official_assigned").value("1"));
    }

    @Test
    void eligibleStudentsExcludesHeldStudent() throws Exception {
        mvc.perform(get("/api/tabs/eligible-students").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[?(@.student_id=='951001')]").exists())
           .andExpect(jsonPath("$.data[?(@.student_id=='951002')]").doesNotExist());
    }

    @Test
    void usersExcludesHeldStaffAndNullLockedYn() throws Exception {
        mvc.perform(get("/api/tabs/users").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.user_id=='951004')]").exists())
           .andExpect(jsonPath("$.data[?(@.user_id=='951002')]").doesNotExist())
           .andExpect(jsonPath("$.data[?(@.user_id=='951003')]").doesNotExist());
    }

    @Test
    void cohortsListsSeededCohort() throws Exception {
        mvc.perform(get("/api/tabs/cohorts").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.cohort_number==951001)]").exists());
    }

    @Test
    void brandsListsSeededBrand() throws Exception {
        mvc.perform(get("/api/tabs/brands").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.brand_id==951001)]").exists());
    }

    @Test
    void createBrandUpsertsOnConflictAndReturnsSameBrandId() throws Exception {
        String body = """
            {"brand_name":"TestBrand951","model_name":"TestModel951","created_by":951001}
            """;
        String firstResponse = mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.brand_name").value("TestBrand951"))
           .andReturn().getResponse().getContentAsString();

        // Second identical create hits ON CONFLICT DO UPDATE -- same (brand_name, model_name), so it must
        // return the SAME brand_id (upsert, not a duplicate row), still 201.
        // NOTE: JsonPath.read(...)'s inferred generic return type resolves javac's overload pick to
        // jsonPath(...).value(Matcher) instead of .value(Object), causing a runtime ClassCastException --
        // binding to an explicit Object first forces the correct overload (test-only fix, not a behavior change).
        Object firstBrandId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.data.brand_id");
        mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.brand_id").value(firstBrandId));

        Long count = jdbc.sql("SELECT COUNT(*) FROM pp.tab_brand WHERE brand_name = 'TestBrand951'")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1L);
    }

    @Test
    void createBrandMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("brand_name, model_name, and created_by are required."));
    }
}
