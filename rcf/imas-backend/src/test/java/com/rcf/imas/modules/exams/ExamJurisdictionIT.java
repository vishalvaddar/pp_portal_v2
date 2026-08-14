package com.rcf.imas.modules.exams;

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
class ExamJurisdictionIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('juseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='juseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "juseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK'),('CLUSTER') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (810001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810002,'BELAGAVI DIV','DIVISION',810001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810003,'BELAGAVI EDU DIST','EDUCATION DISTRICT',810002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810004,'GOKAK BLOCK','BLOCK',810003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810005,'GOKAK CLUSTER','CLUSTER',810004) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (810101,2027,24081000001,810004,'JurisKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year)
            VALUES (810201,'UB Exam','2027-06-01','09:00:00','11:00:00','2027')
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (810101, 810201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 810101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 810201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 810101").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (810001,810002,810003,810004,810005)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'juseed'").update();
    }

    @Test
    void divisionsByStateReturnsIdAndNameAsStrings() throws Exception {
        mvc.perform(get("/api/exams/divisions-by-state/810001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("810002"))
           .andExpect(jsonPath("$[0].name").value("BELAGAVI DIV"));
    }

    @Test
    void educationDistrictsByDivision() throws Exception {
        mvc.perform(get("/api/exams/education-districts-by-division/810002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("BELAGAVI EDU DIST"));
    }

    @Test
    void blocksByDistrict() throws Exception {
        mvc.perform(get("/api/exams/blocks-by-district/810003").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("GOKAK BLOCK"));
    }

    @Test
    void clustersByBlock() throws Exception {
        mvc.perform(get("/api/exams/clusters-by-block/810004").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("GOKAK CLUSTER"));
    }

    @Test
    void usedBlocksReturnsJsonNumbersNotStrings() throws Exception {
        mvc.perform(get("/api/exams/used-blocks").param("year", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(content().json("[810004]")) // bare numeric array; would fail as "\"810004\"" if wrongly stringified
           .andExpect(jsonPath("$[0]").isNumber());
    }
}
