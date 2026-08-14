package com.rcf.imas.modules.admission;

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
class ApplicantReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        // clean applicant tables (children first)
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();

        // a creating user for the FK (created_by/updated_by → pp.user)
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('seed_admin','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='seed_admin'").query(Long.class).single();

        // jurisdiction rows so district_name resolves and the bulk/reg joins have something to hit
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (900001,'BELAGAVI','EDUCATION DISTRICT',NULL) ON CONFLICT (juris_code) DO NOTHING").update();

        // two applicants with explicit ids → then advance the sequence (LOCKED rule)
        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, district, student_name, father_name, gender, medium, contact_no1, dob, created_by, updated_by, created_at)
                VALUES
                  (500001, 2025, 24010000001, 900001, 'Asha', 'Ravi', 'F', 'Kannada', '9876543210', DATE '2011-06-15', :u, :u, TIMESTAMP '2025-01-01 10:00:00'),
                  (500002, 2025, 24010000002, 900001, 'Kiran', 'Suresh', 'M', 'English', '9000000000', NULL, :u, :u, TIMESTAMP '2025-02-01 10:00:00')
                """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, village, created_by, updated_by) VALUES (500001,'Hubli',:u,:u)")
                .param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))")
                .query(Long.class).single();
    }

    // applicant rows FK-reference pp.jurisdiction and pp."user"; clear them so sibling ITs
    // (JurisdictionIT / AuthFlowIT / UserRoleAdminIT) can DELETE those parent tables (shared DB).
    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
    }

    @Test
    void listReturnsSummaryOrderedByCreatedAtDescWithGenderMapped() throws Exception {
        mvc.perform(get("/api/applicants/").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.data[0].applicant_id").value("500002"))     // created_at DESC → 500002 first
           .andExpect(jsonPath("$.data[0].gender").value("Male"))
           .andExpect(jsonPath("$.data[1].applicant_id").value("500001"))
           .andExpect(jsonPath("$.data[1].gender").value("Female"))
           .andExpect(jsonPath("$.data[1].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.data[1].nmms_reg_number").value("24010000001"));
    }

    @Test
    void getByIdReturnsJoinedRowWithDobAndGenderMapped() throws Exception {
        mvc.perform(get("/api/applicants/500001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.applicant_id").value("500001"))
           .andExpect(jsonPath("$.data.gender").value("Female"))
           .andExpect(jsonPath("$.data.dob").value("2011-06-15"))
           .andExpect(jsonPath("$.data.village").value("Hubli"));
    }

    @Test
    void getByIdMissingIs404() throws Exception {
        mvc.perform(get("/api/applicants/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void getByRegReturnsRowWithJurisdictionNames() throws Exception {
        mvc.perform(get("/api/applicants/reg/24010000001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.student_name").value("Asha"))
           .andExpect(jsonPath("$.data.district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.data.gender").value("Female"));
    }

    @Test
    void getByRegMissingIs404() throws Exception {
        mvc.perform(get("/api/applicants/reg/99999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void countByYear() throws Exception {
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/applicants/count?year=2099").header("Authorization", "Bearer " + admin))
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void shortlistedAndSelectedCountsAreZeroWithNoRelatedRows() throws Exception {
        mvc.perform(get("/api/applicants/shortlisted/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/applicants/selected/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void cohortStudentCountShape() throws Exception {
        mvc.perform(get("/api/applicants/cohortstudentcount?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.currentYear").value(2025))
           .andExpect(jsonPath("$.data.previousYear").value(2024))
           .andExpect(jsonPath("$.data.counts.current_count").value(0))   // no student_master rows
           .andExpect(jsonPath("$.data.counts.previous_count").value(0));
    }

    @Test
    void todayClassesCountIsArray() throws Exception {
        mvc.perform(get("/api/applicants/today-classes-count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").isArray());  // empty array when no timetable rows today
    }

    @Test
    void readsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/applicants/").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/applicants/500001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }

    @Test
    void countPathIsNotSwallowedByApplicantIdRoute() throws Exception {
        // "/count" must map to the count handler, not GET /{applicantId} with id="count"
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").exists());
    }
}
