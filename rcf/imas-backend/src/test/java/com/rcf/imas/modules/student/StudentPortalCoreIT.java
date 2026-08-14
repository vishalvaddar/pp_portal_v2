package com.rcf.imas.modules.student;

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
class StudentPortalCoreIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String studentToken;      // user_id 900001, has batch + timetable + inactive-history row
    String noBatchStudentToken; // user_id 900002, student_master row exists but batch_id IS NULL

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (900001,'sp1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (900002,'sp2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (900001,'Cohort SP1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (900001,'Batch SP1',900001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('SP100000000001','SP Institute')").update();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (900001, 24090000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (900002, 24090000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, user_id,
                current_institute_dise_code, active_yn)
            VALUES (900001, 900001, 24000001, 'Portal Student', 'F', 900001, 900001, 'SP100000000001', 'ACTIVE')
            """).update();
        // 900002: no batch_id -- pins the "No batch assigned." 400 for /timetable.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, user_id, active_yn)
            VALUES (900002, 900002, 24000002, 'No Batch Student', 'M', 900002, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (900001,'SP1','SP Subject')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (900001,'SP Teacher')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (900001,'SP Platform 900001')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id, platform_id)
            VALUES (900001,'SP Classroom',900001,900001,900001)
            """).update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (900001,900001)").update();
        jdbc.sql("""
            INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
            VALUES (900001,900001,'MONDAY','09:00:00','10:00:00')
            """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (900001,'Relocated','2025-01-10')").update();

        studentToken = jwt.issueFinalToken("900001", "sp1", "STUDENT");
        noBatchStudentToken = jwt.issueFinalToken("900002", "sp2", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 900001").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 900001 AND batch_id = 900001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 900001").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 900001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 900001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 900001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'SP100000000001'").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 900001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 900001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (900001,900002)").update();
    }

    @Test
    void healthCheckIsPublicPlainText() throws Exception {
        mvc.perform(get("/api/student/"))
           .andExpect(status().isOk())
           .andExpect(content().string("Student API Working"));
    }

    @Test
    void profileNoTokenIs401() throws Exception {
        mvc.perform(get("/api/student/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void profileReturnsOwnRecordWhenAuthenticated() throws Exception {
        mvc.perform(get("/api/student/profile").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.student_name").value("Portal Student"))
           .andExpect(jsonPath("$.batch_name").value("Batch SP1"))
           .andExpect(jsonPath("$.cohort_name").value("Cohort SP1"))
           .andExpect(jsonPath("$.student_id").value("900001"));
    }

    @Test
    void profileNotFoundIs404() throws Exception {
        String unknownToken = jwt.issueFinalToken("999999", "nobody", "STUDENT");
        mvc.perform(get("/api/student/profile").header("Authorization", "Bearer " + unknownToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student profile not found"));
    }

    @Test
    void timetableReturnsOrderedRows() throws Exception {
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"))
           .andExpect(jsonPath("$[0].subject_name").value("SP Subject"));
    }

    // NOTE (plan defect, verified against live Node source): the plan's Step-1 seed comment claims
    // student 900002 (batch_id IS NULL) "pins" the 400 "No batch assigned." branch, but the plan's OWN
    // SQL note at line 120 states the batch/cohort joins are INNER -- a student with batch_id IS NULL
    // gets NO profile row at all (404, not a partial object with a null batch_id). Verified directly
    // against server/models/coordinator/studentModel.js:676-703 (getStudentProfileByUserId, INNER JOIN
    // pp.batch/pp.cohort) and server/controllers/coordinator/studentController.js:338-358 (getMySchedule):
    // `if (!profile) return 404 "Student profile not found."` fires first because `profile` (rows[0]) is
    // undefined when the INNER JOIN eliminates the row -- `if (!profile.batch_id) return 400` is
    // unreachable dead code in Node itself. This test therefore pins the actually-reachable 404 behavior;
    // the Java controller still retains the batch_id-null 400 check for Node parity (dead code both sides).
    @Test
    void timetableNoBatchAssignedProfileRowIsAbsentSoIs404() throws Exception {
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + noBatchStudentToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student profile not found."));
    }

    @Test
    void timetableProfileNotFoundHasTrailingPeriod() throws Exception {
        String unknownToken = jwt.issueFinalToken("999999", "nobody", "STUDENT");
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + unknownToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student profile not found."));
    }

    @Test
    void inactiveHistoryReturnsRows() throws Exception {
        mvc.perform(get("/api/student/900001/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].inactive_reason").value("Relocated"));
    }

    @Test
    void inactiveHistoryEmptyIsEmptyArrayNot404() throws Exception {
        mvc.perform(get("/api/student/999999/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    @Test
    void inactiveHistoryNonNumericIdIs500() throws Exception {
        mvc.perform(get("/api/student/abc/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch inactive history"));
    }
}
