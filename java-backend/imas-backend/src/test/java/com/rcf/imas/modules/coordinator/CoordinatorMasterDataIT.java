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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorMasterDataIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965101,'coordUser965101','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(institute_id, dise_code, institute_name, institute_board, management_type) VALUES (965101,'DISE965101','Coordinator Test School 965101','STATE','GOVERNMENT')").update();
        jdbc.sql("INSERT INTO pp.institute(institute_id, dise_code, institute_name, institute_board, management_type) VALUES (965102,'DISE965102','Unrelated School Zzz','STATE','PRIVATE UNAIDED')").update();
        jdbc.sql("SELECT setval('pp.institute_id_seq', (SELECT MAX(institute_id)::bigint FROM pp.institute))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965101,'Coordinator Test Teacher 965101')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (965101,'Coordinator Test Platform 965101')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965101,'CT1','Coordinator Test Subject 965101')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965101,'Coordinator Cohort 965101')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965101,'Coordinator Test Batch 965101',965101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, description, active_yn, class_link) VALUES (965101,'Active Classroom 965101','desc-a','Y','https://x/active')").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, description, active_yn, class_link) VALUES (965102,'Inactive Classroom 965102','desc-b','N','https://x/inactive')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965101,965101)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965102,965101)").update();

        coordToken = jwt.issueFinalToken("965101", "coordUser965101", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965101").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 965101").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965101").update();
        jdbc.sql("DELETE FROM pp.institute WHERE institute_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965101").update();
    }

    @Test
    void homeIsPublicNoTokenNeeded() throws Exception {
        mvc.perform(get("/api/coordinator/"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/plain"))
           .andExpect(content().string("Coordinator Home"));
    }

    @Test
    void otherRoutesRequireAuth() throws Exception {
        mvc.perform(get("/api/coordinator/subjects")).andExpect(status().isUnauthorized());
    }

    @Test
    void instituteSearchUnderThreeCharsReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search").param("q", "DI")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void instituteSearchMissingQReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void instituteSearchThreePlusCharsMatchesDiseOrName() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search").param("q", "965101")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].dise_code").value("DISE965101"))
           .andExpect(jsonPath("$[0].institute_name").value("Coordinator Test School 965101"));
    }

    @Test
    void teachersReturnsNameOnlyNoId() throws Exception {
        mvc.perform(get("/api/coordinator/teachers").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.teacher_name=='Coordinator Test Teacher 965101')]").exists())
           .andExpect(jsonPath("$[0].teacher_id").doesNotExist());
    }

    @Test
    void platformsReturnsIdAndName() throws Exception {
        mvc.perform(get("/api/coordinator/platforms").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.platform_id==965101)].platform_name").value("Coordinator Test Platform 965101"));
    }

    @Test
    void subjectsReturnsAllColumns() throws Exception {
        mvc.perform(get("/api/coordinator/subjects").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.subject_id==965101)].subject_code").value("CT1"))
           .andExpect(jsonPath("$[?(@.subject_id==965101)].subject_name").value("Coordinator Test Subject 965101"));
    }

    @Test
    void allClassroomsReturnsBothStatuses() throws Exception {
        mvc.perform(get("/api/coordinator/classrooms").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.classroom_id==965101)].active_yn").value("Y"))
           .andExpect(jsonPath("$[?(@.classroom_id==965102)].active_yn").value("N"));
    }

    @Test
    void classroomsByBatchReturnsActiveOnly() throws Exception {
        mvc.perform(get("/api/coordinator/classrooms/965101").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].classroom_id").value(965101))
           .andExpect(jsonPath("$[0].class_link").value("https://x/active"));
    }
}
