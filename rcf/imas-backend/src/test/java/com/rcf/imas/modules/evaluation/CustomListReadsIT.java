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
class CustomListReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('elseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='elseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "elseed", "ADMIN");
        student = jwt.issueFinalToken("999", "s", "STUDENT");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (7001,'Cohort EL') ON CONFLICT (cohort_number) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number, medium) VALUES (7001,'Batch EL',7001,'KANNADA') ON CONFLICT (batch_id) DO NOTHING").update();

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (700001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700003,'BELAGAVI DIST','EDUCATION DISTRICT',700001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700004,'GOKAK BLOCK','BLOCK',700003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("UPDATE pp.jurisdiction SET juris_type = 'BLOCK' WHERE juris_code = 700004").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, created_by, updated_by)
            VALUES (700101,2025,24070000001,700001,700003,700004,'ListKid','f','KANNADA',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.student_master (student_id, applicant_id, student_name, batch_id, active_yn)
            VALUES (700201, 700101, 'ListKid', 7001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.custom_list(list_id, list_name) VALUES (7001,'EL List') ON CONFLICT (list_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.custom_list_id_seq', (SELECT MAX(list_id)::bigint FROM pp.custom_list))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_students(list_id, student_id) VALUES (7001, 700201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = 7001").update(); // cascades custom_list_students/fields
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 700201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 700101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 7001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 7001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (700001,700003,700004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'elseed'").update();
    }

    @Test
    void listsReturnsStudentCountAsStringViaGenericRow() throws Exception {
        mvc.perform(get("/api/custom-list/lists").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.list_id=='7001')].student_count").value(org.hamcrest.Matchers.hasItem("1")));
    }

    @Test
    void listsAvailableUnderEvaluationBaseTooDualPath() throws Exception {
        mvc.perform(get("/api/evaluation/lists").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.list_id=='7001')].list_name").value(org.hamcrest.Matchers.hasItem("EL List")));
    }

    @Test
    void batchesFilteredByCohortId() throws Exception {
        mvc.perform(get("/api/custom-list/batches").param("cohortId", "7001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].batch_name").value("Batch EL"))
           .andExpect(jsonPath("$[0].cohort_name").value("Cohort EL"));
    }

    @Test
    void availableFieldsIsLiveIntrospectionNotAStaticList() throws Exception {
        mvc.perform(get("/api/custom-list/available-fields").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.col_name=='district')].display_name").value(org.hamcrest.Matchers.hasItem("District")))
           .andExpect(jsonPath("$[?(@.col_name=='applicant_id')]").isEmpty())     // excluded column
           .andExpect(jsonPath("$[?(@.col_name=='active_yn')].display_name").value(org.hamcrest.Matchers.hasItem("Active Status")));
    }

    @Test
    void studentsByListIdReturnsEnvelopeWithStudentsAndFields() throws Exception {
        mvc.perform(get("/api/custom-list/students-by-list/7001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students[0].student_name").value("ListKid"))
           .andExpect(jsonPath("$.students[0].batch_name").value("Batch EL"))
           .andExpect(jsonPath("$.fields").isArray());
    }

    @Test
    void studentsByCohortHardcodes2025AndIgnoresDivisionId() throws Exception {
        mvc.perform(get("/api/custom-list/students-by-cohort/7001")
                .param("stateId", "700001").param("districtId", "700003").param("blockId", "700004")
                .param("divisionId", "999999999")   // must be silently ignored
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("ListKid"));
    }

    @Test
    void allCustomListEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/custom-list/lists").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation/lists").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
    }
}
