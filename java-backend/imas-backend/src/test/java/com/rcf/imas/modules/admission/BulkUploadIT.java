package com.rcf.imas.modules.admission;

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
class BulkUploadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        // FK user_id=1 must exist (bulk hardcodes created_by=updated_by=1)
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (1,'bulk_sys','x','N') ON CONFLICT (user_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // jurisdiction_type rows are required by jurisdiction_juris_type_fkey
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT (juris_type) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT (juris_type) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT (juris_type) DO NOTHING").update();

        // jurisdiction hierarchy for NAME→CODE resolution
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800002,'BELAGAVI','EDUCATION DISTRICT',800001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800003,'GOKAK','BLOCK',800002) ON CONFLICT (juris_code) DO NOTHING").update();
    }

    // Convention 2: clean up exactly what we seed — children (applicants) before parents (jurisdiction/user).
    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (800003,800002,800001)").update();
        // Only drop the seeded types if no other jurisdiction row still references them (they are
        // shared reference data — a sibling IT may seed jurisdictions of the same type).
        jdbc.sql("""
                DELETE FROM pp.jurisdiction_type
                WHERE juris_type IN ('BLOCK','EDUCATION DISTRICT','STATE')
                  AND juris_type NOT IN (SELECT DISTINCT juris_type FROM pp.jurisdiction WHERE juris_type IS NOT NULL)
                """).update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 1").update();
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "applicants.csv", "text/csv", content.getBytes());
    }

    @Test
    void happyPathCsvInsertsAllRowsAndReturns200Success() throws Exception {
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block,gender,contact_no1
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak,F,9876543210
            2025,24010000202,Kiran,Suresh,50,55,Karnataka,Belagavi,Gokak,M,9000000000
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalRecords").value(2))
           .andExpect(jsonPath("$.insertedRecords").value(2))
           .andExpect(jsonPath("$.validationErrors").value(0))
           .andExpect(jsonPath("$.dbErrors").value(0))
           .andExpect(jsonPath("$.status").value("success"))
           .andExpect(jsonPath("$.logFile").isString());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(2);
        // jurisdiction NAME→CODE resolved
        Long code = jdbc.sql("SELECT district::bigint FROM pp.applicant_primary_info WHERE nmms_reg_number=24010000201").query(Long.class).single();
        assertThat(code).isEqualTo(800002L);
    }

    @Test
    void anyValidationErrorInsertsNothingAndReturns400() throws Exception {
        // second row missing father_name + sat_score → all-or-nothing → 0 inserted, 400
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak
            2025,24010000202,Kiran,,50,,Karnataka,Belagavi,Gokak
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.totalRecords").value(2))
           .andExpect(jsonPath("$.insertedRecords").value(0))
           .andExpect(jsonPath("$.validationErrors").value(org.hamcrest.Matchers.greaterThan(0)))
           .andExpect(jsonPath("$.dbErrors").value(0))
           .andExpect(jsonPath("$.status").value("failed"));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(0);  // nothing inserted (all-or-nothing)
    }

    @Test
    void unresolvedJurisdictionRollsBackWholeBatchAnd500() throws Exception {
        // valid rows, but "Atlantis" district resolves to nothing → row error → batch rollback → db-fail 500
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak
            2025,24010000202,Kiran,Suresh,50,55,Karnataka,Atlantis,Gokak
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.status").value("failed"))
           .andExpect(jsonPath("$.insertedRecords").value(0))
           .andExpect(jsonPath("$.dbErrors").value(org.hamcrest.Matchers.greaterThan(0)));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(0);  // rollback → even the first valid row is gone
    }

    @Test
    void noFileIs400() throws Exception {
        mvc.perform(multipart("/api/bulk-upload/upload").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("No file received")));
    }

    @Test
    void uploadIsAdminOnly() throws Exception {
        mvc.perform(multipart("/api/bulk-upload/upload")
                .file(csv("nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score\n2025,24010000201,A,B,1,2\n"))
                .header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
