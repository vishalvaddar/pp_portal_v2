package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorClassroomCreateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965301,'coordUser965301','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965301,'CC1','Create Classroom Subject 965301')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965301,'Create Classroom Teacher 965301')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (965301,'Create Classroom Platform 965301')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965301", "coordUser965301", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (SELECT classroom_id FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301')").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301'").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 965301").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965301").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965301").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965301").update();
    }

    @Test
    void createClassroomReturnsClassroomIdOnlyAnd201() throws Exception {
        String body = """
            {"classroom_name":"Created Classroom 965301","subject_id":965301,"teacher_id":965301,
             "platform_id":965301,"class_link":"https://x/created","active_yn":"Y",
             "created_by":965301,"updated_by":965301}
            """;
        String resp = mvc.perform(post("/api/coordinator/classrooms")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.classroom_id").exists())
           .andExpect(jsonPath("$.classroom_name").doesNotExist()) // RETURNING classroom_id ONLY
           .andReturn().getResponse().getContentAsString();

        Integer newId = jdbc.sql("SELECT classroom_id FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301'")
                .query(Integer.class).single();
        String activeYn = jdbc.sql("SELECT active_yn FROM pp.classroom WHERE classroom_id = :id")
                .param("id", newId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Y", activeYn);
        org.junit.jupiter.api.Assertions.assertTrue(resp.contains(String.valueOf(newId)));
    }
}
