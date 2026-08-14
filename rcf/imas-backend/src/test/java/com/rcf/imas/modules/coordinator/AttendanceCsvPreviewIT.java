package com.rcf.imas.modules.coordinator;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AttendanceCsvPreviewIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965701,'coordUser965701','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965701,'CSV Preview Cohort 965701')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965701,'CSV Preview Batch 965701',965701)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965701,965701)").update();

        // ACTIVE, matches CSV row "Asha Rani K" at 80% -> PRESENT
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965711,'Asha Rani',101,965701,'F','ACTIVE')
                """).update();
        // ACTIVE, matches CSV row "Kiran Kumar M" at 37.5% -> ABSENT (below 40)
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965712,'Kiran Kumar',102,965701,'M','ACTIVE')
                """).update();
        // ACTIVE, matches CSV row at 50% -> LATE JOINED
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965713,'Meena S',103,965701,'F','ACTIVE')
                """).update();
        // ACTIVE, no CSV match at all -> ABSENT, duration 0
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965714,'Zoya Not In Csv',104,965701,'F','ACTIVE')
                """).update();
        // INACTIVE, matches CSV row "Ravi Teja" -> inactiveStudents[], excluded from previewData
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                VALUES (965715,'Ravi Teja',105,965701,'M','INACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965701", "coordUser965701", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965701").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965701").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965701").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965701").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965701").update();
    }

    /**
     * Total meeting duration (row index 1, col D) = 80 minutes ("1 hr 20 min").
     * Data rows (index >= 2), column A=name, D=duration, E=time_joined, F=time_exited:
     *  - "Asha Rani K"   80 min column D duplicated across 2 rows (64 min, then 80 min) -> keep LARGEST (80) -> 80/80=100% -> PRESENT
     *  - "Kiran Kumar M" 30 min -> 30/80=37.5% -> ABSENT (below 40)
     *  - "Meena S Extra" 40 min -> 40/80=50% -> LATE JOINED
     *  - "Ravi Teja X"   80 min -> matches INACTIVE student Ravi Teja -> inactiveStudents[]
     *  - "Unmatched Guy" 10 min -> no db student's name is a substring of this csv key -> unmatchedStudents[]
     * "Zoya Not In Csv" has no CSV row at all -> previewData ABSENT, duration 0.
     */
    private String csvContent() {
        return "Name,User Email,User Type,Duration (Total Meeting),Time Joined,Time Left\n"
             + ",,,1 hr 20 min,,\n"
             + "Asha Rani K,a@example.com,Participant,64 min,09:00 AM,10:04 AM\n"
             + "Asha Rani K,a@example.com,Participant,1 hr 20 min,09:00 AM,10:20 AM\n"
             + "Kiran Kumar M,k@example.com,Participant,30 min,09:05 AM,09:35 AM\n"
             + "Meena S Extra,m@example.com,Participant,40 min,09:10 AM,09:50 AM\n"
             + "Ravi Teja X,r@example.com,Participant,1 hr 20 min,09:00 AM,10:20 AM\n"
             + "Unmatched Guy,u@example.com,Participant,10 min,09:00 AM,09:10 AM\n";
    }

    private MockMultipartFile csv() {
        return new MockMultipartFile("file", "attendance.csv", "text/csv", csvContent().getBytes());
    }

    @Test
    void previewPartitionsAndScoresCorrectly() throws Exception {
        mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                .file(csv())
                .param("batch_id", "965701")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.previewData", org.hamcrest.Matchers.hasSize(4)))
           // previewData sorted by student_name (locale compare): Asha Rani, Kiran Kumar, Meena S, Zoya Not In Csv
           .andExpect(jsonPath("$.previewData[0].student_name").value("Asha Rani"))
           .andExpect(jsonPath("$.previewData[0].student_id").value("965711"))
           .andExpect(jsonPath("$.previewData[0].duration_minutes").value(80))
           .andExpect(jsonPath("$.previewData[0].status").value("PRESENT"))
           .andExpect(jsonPath("$.previewData[1].student_name").value("Kiran Kumar"))
           .andExpect(jsonPath("$.previewData[1].duration_minutes").value(30))
           .andExpect(jsonPath("$.previewData[1].status").value("ABSENT"))
           .andExpect(jsonPath("$.previewData[2].student_name").value("Meena S"))
           .andExpect(jsonPath("$.previewData[2].duration_minutes").value(40))
           .andExpect(jsonPath("$.previewData[2].status").value("LATE JOINED"))
           .andExpect(jsonPath("$.previewData[3].student_name").value("Zoya Not In Csv"))
           .andExpect(jsonPath("$.previewData[3].duration_minutes").value(0))
           .andExpect(jsonPath("$.previewData[3].status").value("ABSENT"))
           .andExpect(jsonPath("$.previewData[3].time_joined").value("N/A"))
           .andExpect(jsonPath("$.inactiveStudents", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.inactiveStudents[0].student_name").value("Ravi Teja"))
           .andExpect(jsonPath("$.inactiveStudents[0].duration_minutes").value(80))
           .andExpect(jsonPath("$.unmatchedStudents", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.unmatchedStudents[0].student_name").value("Unmatched Guy"))
           .andExpect(jsonPath("$.unmatchedStudents[0].duration_minutes").value(10));
    }

    @Test
    void noFileIs400() throws Exception {
        mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                .param("batch_id", "965701")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("No file uploaded"));
    }

    @Test
    void tooFewRowsIs400() throws Exception {
        MockMultipartFile tiny = new MockMultipartFile("file", "tiny.csv", "text/csv",
                "Name,User Email,User Type,Duration (Total Meeting),Time Joined,Time Left\n".getBytes());
        mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                .file(tiny)
                .param("batch_id", "965701")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("CSV missing data rows."));
    }
}
