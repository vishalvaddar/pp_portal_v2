package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorDashboardGlobalAttendanceIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966401,'gaUser966401','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966401,'GA Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966401,'GA Batch',966401)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966401,'GA Room')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966401,966401)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966401,'GA Student 1',966401,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966402,'GA Student 2',966401,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966401,966401, date_trunc('month', CURRENT_DATE)::date, '09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        // 966401=PRESENT (counted), 966402='LATE JOINED' (must be EXCLUDED -- pins the quirk). s_count=2,
        // sess_count=1, p_count=1 -> cohort_avg/batch avg = (1/(1*2))*100 = 50.00, classes_held=1.
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966401,966401,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966401,966402,'LATE JOINED')").update();

        coordToken = jwt.issueFinalToken("966401", "gaUser966401", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 966401").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 966401").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966401,966402)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966401").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966401").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966401").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966401").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966401").update();
    }

    @Test
    void globalAttendanceStatsExcludesLateJoinedAndParsesJsonbBatchesArray() throws Exception {
        mvc.perform(get("/api/coordinator/reports/global-attendance")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==966401)]", hasSize(1)))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].cohort_name", contains("GA Cohort")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].cohort_avg", contains("50.00")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].batch_name", contains("GA Batch")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].avg", contains(50)))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].classes_held", contains(1)));
    }
}
