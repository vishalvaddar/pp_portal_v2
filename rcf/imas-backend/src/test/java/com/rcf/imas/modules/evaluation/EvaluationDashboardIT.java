package com.rcf.imas.modules.evaluation;

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
class EvaluationDashboardIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dbseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dbseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "dbseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (760001,'DASH BLOCK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (760101,2027,24076000001,760001,'DashKid1','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (760102,2027,24076000002,760001,'DashKid2','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (760101,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (760102,'N')").update(); // NOT shortlisted_yn='Y'

        jdbc.sql("""
            INSERT INTO pp.applicant_secondary_info(applicant_id, village) VALUES (760101,'V1')
            """).update(); // only applicant 1 has been "Evaluated"

        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, interview_required_yn) VALUES (760101,'Y')").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status) VALUES (760201,760101,'COMPLETED')").update(); // upper-case, never matches 'Completed'
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 760001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'dbseed'").update();
    }

    @Test
    void overallCountsReturnsExactLabelsAsRealInts() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$['Total Students']").value(2))
           .andExpect(jsonPath("$['Shortlisted']").value(1))          // shortlisted_yn='Y' filter -> only applicant 1
           .andExpect(jsonPath("$['Evaluated']").value(1))
           .andExpect(jsonPath("$['Interview Required']").value(1));
    }

    @Test
    void jurisdictionsPreservesTopLevelStringsVsCountsRealIntsSplit() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/jurisdictions/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("DASH BLOCK"))
           .andExpect(jsonPath("$[0].totalShortlisted").value("2"))     // top-level: raw COUNT bigint AS STRING (not parsed)
           .andExpect(jsonPath("$[0].evaluated").value("1"))
           .andExpect(jsonPath("$[0].progress").value(50))              // computed int: round(1/2*100)
           .andExpect(jsonPath("$[0].counts.pendingEvaluation").value(1))       // sub-object: real int
           .andExpect(jsonPath("$[0].counts.totalInterviewRequired").value(1))
           .andExpect(jsonPath("$[0].counts.completedInterview").value(0));      // 'Completed' vs 'COMPLETED' bug -> always 0
    }

    @Test
    void overallProgressComputesRoundedPercentAsInt() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall-progress/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overallProgress").value(50)); // totalReq=2 (no shortlisted_yn filter here), totalDone=1
    }

    @Test
    void nonNumericYearIs500WithDistinctMessage() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall/not-a-year").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch overall counts."));
    }

    @Test
    void dashboardEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/evaluation-dashboard/overall/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation-dashboard/jurisdictions/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation-dashboard/overall-progress/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
