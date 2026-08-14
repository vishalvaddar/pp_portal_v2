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

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamHallTicketIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('htseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='htseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "htseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, address, village, pincode, latitude, longitude, active_yn)
            VALUES (85001,'HT Centre','123 Main Rd','HT Village','590001',15.85,74.50,'Y') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (850201,'HT Exam','2027-06-01','09:00:00','11:00:00','2027',85001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (850001,'HT DIST','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, student_name, father_name, created_by, updated_by)
            VALUES (850101,2027,24085000001,850001,'HallTicketKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (850101, 850201, '27HT0001')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = 850201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 850101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 850201").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 85001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 850001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'htseed'").update();
    }

    @Test
    void hallTicketSingleWorksWithNoAuthorizationHeaderAtAll() throws Exception {
        // The critical parity assertion: this endpoint must be reachable with ZERO Authorization header --
        // not 401, not 403 -- matching the public, unauthenticated StudentHallticketPage.js call.
        byte[] pdf = mvc.perform(get("/api/exams/hallticket/27HT0001"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/pdf"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"27HT0001.pdf\""))
           .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void hallTicketSingleMissingIs404() throws Exception {
        mvc.perform(get("/api/exams/hallticket/DOES-NOT-EXIST"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Hall ticket not found"));
    }

    @Test
    void downloadAllHallTicketsReturnsZipWithOneEntryPerStudent() throws Exception {
        byte[] zip = mvc.perform(get("/api/exams/850201/HT Exam/download-all-hall-tickets").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/zip"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=All_Hall_Tickets_850201_HT_Exam.zip"))
           .andReturn().getResponse().getContentAsByteArray();

        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                assertThat(entry.getName()).isEqualTo("HallTicketKid_27HT0001.pdf");
                entryCount++;
            }
        }
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    void downloadAllHallTicketsMissingExamIs404() throws Exception {
        mvc.perform(get("/api/exams/999999999/NoSuchExam/download-all-hall-tickets").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No hall tickets found"));
    }

    @Test
    void downloadAllHallTicketsRequiresAdmin() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/850201/HT Exam/download-all-hall-tickets").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
