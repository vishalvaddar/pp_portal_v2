package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TrackingStudentsAndDocumentIT extends PgIntegrationTest {

    @TempDir static Path storageRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("imas.file-storage-path", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() throws IOException {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (964001,'tsAdmin964','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Student A: latest round SELECTED, status SCHEDULED (distinct from B so a status filter excludes A),
        // no home-verification-required flag.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name, nmms_reg_number) VALUES (964001,2025,'Zed Student 964',96400100001)").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn, doc_name, doc_type)
                VALUES (964001,1,'SCHEDULED','SELECTED','N','report964a.pdf','application/pdf')
                """).update();

        // Student B: latest round REJECTED but home_verification_req_yn='Y' (persistent across rounds via MAX OVER).
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name, nmms_reg_number) VALUES (964002,2025,'Amy Student 964',96400200001)").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (964002,1,'COMPLETED','REJECTED','Y')
                """).update();

        // Student C: different nmms_year -- must be excluded by the year filter.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name, nmms_reg_number) VALUES (964003,2024,'Other Year Student 964',96400300001)").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (964003,1,'COMPLETED','SELECTED','N')
                """).update();

        jdbc.sql("""
                INSERT INTO pp.home_verification(applicant_id, date_of_verification, status, verification_type, doc_name, doc_type)
                VALUES (964001, DATE '2025-06-01', 'ACCEPTED', 'PHYSICAL', 'homeverif964.pdf', 'application/pdf')
                """).update();

        Path folder = storageRoot.resolve("Interview-data").resolve("cohort-2025");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("report964a.pdf"), "fake-pdf-bytes");

        Path homeFolder = storageRoot.resolve("home-verification-data").resolve("cohort-2025");
        Files.createDirectories(homeFolder);
        Files.writeString(homeFolder.resolve("homeverif964.pdf"), "fake-pdf-bytes");

        adminToken = jwt.issueFinalToken("964001", "tsAdmin964", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 964001").update();
    }

    @Test
    void studentsNoFiltersReturnsBothCurrentYearStudentsOrderedByName() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964")) // alphabetical
           .andExpect(jsonPath("$.students[1].student_name").value("Zed Student 964"))
           .andExpect(jsonPath("$.totalStudents").value(2));
    }

    @Test
    void studentsFilteredByStatusInList() throws Exception {
        // Client sends the param as `statuses` (EvaluationTracking.js:90); the controller MUST bind that name.
        // Only Amy is COMPLETED (Zed is SCHEDULED), so a wired status filter returns exactly 1 -- if the param
        // name regresses, `statuses` binds null, the filter is skipped, and both students come back (hasSize 2).
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("statuses", "COMPLETED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964"));
    }

    @Test
    void studentsFilteredByResultOnly() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("results", "SELECTED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Zed Student 964"));
    }

    @Test
    void studentsFilteredByHomeVerificationSyntheticValue() throws Exception {
        // 'HOME VERIFICATION REQUIRED' is NOT a real interview_result -- it maps to
        // persistent_verification_req = 'Y' (Firm Decision 4g). Student B qualifies (Y), Student A does not.
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("results", "HOME VERIFICATION REQUIRED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964"));
    }

    @Test
    void studentsFilteredByResultsPlusHomeVerificationCombinedOr() throws Exception {
        // results=SELECTED OR home-verification-required -> both students qualify (A via SELECTED, B via Y flag).
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .param("results", "SELECTED").param("results", "HOME VERIFICATION REQUIRED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2)));
    }

    @Test
    void studentsFilteredByStatusAndResultCombinedAnd() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .param("statuses", "COMPLETED").param("results", "REJECTED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964"));
    }

    @Test
    void downloadInterviewDocumentRedirectsToDataPath() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "interview")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isFound())
           .andExpect(header().string("Location", "/Data/Interview-data/cohort-2025/report964a.pdf"));
    }

    @Test
    void downloadHomeDocumentRedirectsToDataPath() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "home")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isFound())
           .andExpect(header().string("Location", "/Data/home-verification-data/cohort-2025/homeverif964.pdf"));
    }

    @Test
    void downloadDocumentBadTypeIs400() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "bogus")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(content().string("Invalid parameters."));
    }

    @Test
    void downloadDocumentMissingTypeIs400PlainTextNotFiveHundred() throws Exception {
        // Node's `!['interview','home'].includes(docType)` guard treats a missing `type` the same as a
        // bogus one -> 400 "Invalid parameters." plain text (trackingController.js:154-161). A required
        // @RequestParam would MissingServletRequestParameterException -> a JSON 500 instead.
        mvc.perform(get("/api/tracking/document/964001/cohort-2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(content().string("Invalid parameters."));
    }

    @Test
    void downloadDocumentNoMetadataIs404() throws Exception {
        mvc.perform(get("/api/tracking/document/964002/cohort-2025").param("type", "interview")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(content().string("Document metadata not found."));
    }
}
