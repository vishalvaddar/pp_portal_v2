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
class StudentAdminSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String studentTok;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920001,'as1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920002,'as2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("920001", "as1", "ADMIN");
        studentTok = jwt.issueFinalToken("920002", "as2", "STUDENT");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (920001,'KARNATAKA','STATE')").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (920002,'BELAGAVI','EDUCATION DISTRICT',920001)").update();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (920001,'Cohort AS1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920001,'Batch AS1',920001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, app_state, district)
            VALUES (920001, 24092000001, 920001, 920002)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, app_state, district)
            VALUES (920002, 24092000002, 920001, 920002)
            """).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, spl_health_cond) VALUES (920001, 'Y')").update();
        // 920002 has NO applicant_secondary_info row -- pins the COALESCE(...,'N') default.

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, student_email_password)
            VALUES (920001, 920001, 24030001, 'Asha Search', 'F', 920001, 'SECRET-PW-1')
            """).update();
        // Plan seed used 'Basha Search' here, but 'Basha' contains 'asha' as a substring, so
        // ILIKE '%Asha%' (Node parity: studentSearchModel.js searchStudents, `sm.student_name ILIKE $n`
        // with a %wrapped% param) matches BOTH rows, breaking searchByNameIlike's length()==1 assertion.
        // Renamed to a name with no 'asha' substring collision -- code/assertions kept as the plan wrote them.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id)
            VALUES (920002, 920002, 24030002, 'Diya Search', 'M', 920001)
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 920001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 920001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (920001,920002)").update();
    }

    @Test
    void searchNoFiltersDefaultsToLimit50() throws Exception {
        mvc.perform(get("/api/search-students").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.pagination.limit").value(50))
           .andExpect(jsonPath("$.pagination.page").value(1))
           .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    void searchByNameIlike() throws Exception {
        mvc.perform(get("/api/search-students?name=Asha").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].student_name").value("Asha Search"));
    }

    @Test
    void searchByGenderUppercasesInput() throws Exception {
        mvc.perform(get("/api/search-students?gender=f").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].gender").value("F"));
    }

    @Test
    void searchSplHealthCondDefaultsToNViaCoalesce() throws Exception {
        mvc.perform(get("/api/search-students?spl_health_cond=N").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].student_name").value("Diya Search"))
           .andExpect(jsonPath("$.data[0].spl_health_cond").value("N"));
    }

    @Test
    void searchLimitClampedToMax100OffsetClampedToZero() throws Exception {
        mvc.perform(get("/api/search-students?limit=500&offset=-5").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.pagination.limit").value(100))
           .andExpect(jsonPath("$.pagination.offset").value(0));
    }

    @Test
    void searchStudentsIsAdminOnly() throws Exception {
        mvc.perform(get("/api/search-students").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/search-students")).andExpect(status().isUnauthorized());
    }

    @Test
    void studentByIdRedactsPasswordColumn() throws Exception {
        mvc.perform(get("/api/student/920001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.student_name").value("Asha Search"))
           .andExpect(jsonPath("$.data.student_email_password").doesNotExist());
    }

    @Test
    void studentByIdNotFoundIs404() throws Exception {
        mvc.perform(get("/api/student/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Student not found"));
    }

    @Test
    void studentByIdNonNumericIsBareSuccessFalse500() throws Exception {
        mvc.perform(get("/api/student/abc").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.error").doesNotExist())
           .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void studentByIdIsAdminOnly() throws Exception {
        mvc.perform(get("/api/student/920001").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
