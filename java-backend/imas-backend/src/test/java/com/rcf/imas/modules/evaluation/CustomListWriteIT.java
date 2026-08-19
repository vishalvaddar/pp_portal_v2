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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CustomListWriteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('wlseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='wlseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "wlseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (710101,2025,24071000001,'WriteKid1','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (710102,2025,24071000002,'WriteKid2','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, active_yn) VALUES (710201,710101,'WriteKid1','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, active_yn) VALUES (710202,710102,'WriteKid2','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list_fields WHERE field_id IN (SELECT field_id FROM pp.field_master WHERE col_name IN ('gender','medium'))").update();
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_name IN ('WL Save Test','WL Save Test Updated')").update();
        jdbc.sql("DELETE FROM pp.field_master WHERE col_name IN ('gender','medium')").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (710201,710202)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (710101,710102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'wlseed'").update();
    }

    @Test
    void saveListFullCreatesThenReplacesEntirely() throws Exception {
        String createBody = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        var createResult = mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(createBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andReturn();
        String listId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.list_id").toString();

        // full replace: drop the gender field, add medium field, replace student 1 with student 2
        String updateBody = """
            {"list_id":%s,"list_name":"WL Save Test Updated","student_ids":[710202],"selectedFields":[{"col_name":"medium"}]}
            """.formatted(listId);
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(updateBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.list_id").value(listId));

        Integer studentCount = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(studentCount).isEqualTo(1); // fully replaced, not merged
        Integer stillHasOldStudent = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric AND student_id = 710201")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillHasOldStudent).isEqualTo(0);

        String name = jdbc.sql("SELECT list_name FROM pp.custom_list WHERE list_id = :id::numeric").param("id", listId)
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(name).isEqualTo("WL Save Test Updated");
    }

    @Test
    void saveListFullReusesFieldMasterRowAcrossLists() throws Exception {
        String body1 = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body1)).andExpect(status().isOk());

        Integer fieldMasterRows = jdbc.sql("SELECT COUNT(*)::int FROM pp.field_master WHERE col_name = 'gender'")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(fieldMasterRows).isEqualTo(1); // exactly one, reused not duplicated
    }

    @Test
    void deleteListAlwaysReturnsSuccessTrueEvenForMissingId() throws Exception {
        mvc.perform(delete("/api/custom-list/list/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteListCascadesToFieldsAndStudentsJunctionRows() throws Exception {
        String body = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        var result = mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andReturn();
        String listId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.list_id").toString();

        mvc.perform(delete("/api/custom-list/list/" + listId).header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        Integer remaining = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(0);
    }

    @Test
    void writeEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/custom-list/list/1").header("Authorization", "Bearer " + studentTok))
                .andExpect(status().isForbidden());
    }
}
