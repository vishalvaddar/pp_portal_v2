package com.rcf.imas.modules.teacher.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TeacherReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public TeacherReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the teacher module (LOCKED CONVENTIONS #3): deliberately DIFFERENT
     * from CoordinatorReadRepository.genericRow's NUMERIC/DECIMAL branch. That branch uses
     * bd.toBigInteger().toString() safely ONLY because every NUMERIC column in the coordinator module's
     * 14-endpoint slice happens to be a whole-number id. This module's /dashboard avg_attendance is a
     * genuinely fractional NUMERIC (e.g. "85.00", or "0" via COALESCE when there's no attendance data) --
     * toBigInteger() would silently truncate "85.00" to "85". toPlainString() is correct for BOTH
     * whole-number ids (student_id, enr_id, applicant_id, user_id -- stored scale 0, so toPlainString()
     * prints with no decimal point) AND the one fractional column, matching node-pg's numeric-is-always-
     * a-string behavior exactly.
     */
    static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            int type = md.getColumnType(i);
            Object val;
            switch (type) {
                case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                    BigDecimal bd = rs.getBigDecimal(i);
                    val = bd == null ? null : bd.toPlainString();
                }
                case java.sql.Types.BIGINT -> {
                    long v = rs.getLong(i); val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.DATE -> {
                    java.sql.Date d = rs.getDate(i);
                    val = d == null ? null : DATE_FMT.format(d.toLocalDate());
                }
                case java.sql.Types.TIME -> {
                    Time t = rs.getTime(i);
                    val = t == null ? null : TIME_FMT.format(t.toLocalTime());
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** #1 getCohortsController -- TeacherStudentController.js:16-25. */
    public List<Map<String, Object>> cohorts(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT c.cohort_number, c.cohort_name
                FROM pp.cohort c
                JOIN pp.batch b ON c.cohort_number = b.cohort_number
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric
                ORDER BY c.cohort_number DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #2 getBatchesController, unfiltered branch -- TeacherStudentController.js:43-59. */
    public List<Map<String, Object>> batches(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric
                ORDER BY b.batch_name ASC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #2 getBatchesController, cohort_number-filtered branch -- TeacherStudentController.js:43-59. */
    public List<Map<String, Object>> batchesByCohort(String userId, String cohortNumber) {
        return jdbc.sql("""
                SELECT DISTINCT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric AND b.cohort_number = :cohortNumber::integer
                ORDER BY b.batch_name ASC
                """).param("userId", userId).param("cohortNumber", cohortNumber)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** #6 getTeacherProfileByUserId -- TeacherProfileModel.js:3-33. Returns null if no pp.teacher row
     *  matches (controller maps that to 404, matching Node's rows[0] === undefined check). */
    public Map<String, Object> profile(String userId) {
        return jdbc.sql("""
                SELECT
                    t.teacher_id,
                    t.teacher_name,
                    t.qualification,
                    t.experience_yrs,
                    t.doj,
                    t.contact_no,
                    u.user_name AS username,
                    (
                        SELECT string_agg(DISTINCT s.subject_name || ' (' || ts.medium || ')', ', ')
                        FROM pp.teacher_subject ts
                        JOIN pp.subject s ON ts.subject_id = s.subject_id
                        WHERE ts.teacher_id = t.teacher_id
                    ) AS subjects_taught,
                    (
                        SELECT string_agg(DISTINCT c.classroom_name, ', ')
                        FROM pp.classroom c
                        WHERE c.teacher_id = t.teacher_id
                    ) AS assigned_classrooms
                FROM pp.teacher t
                JOIN pp."user" u ON t.user_id = u.user_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** #7 getCoordinatorsForTeacher -- TeacherCoordinatorModel.js:3-32. */
    public List<Map<String, Object>> coordinators(String userId) {
        return jdbc.sql("""
                SELECT
                    u.user_id,
                    u.full_name,
                    u.user_email,
                    u.contact_no,
                    u.active_yn,
                    string_agg(DISTINCT b.batch_name, ', ') AS shared_batches
                FROM pp.teacher t
                JOIN pp.classroom cl ON t.teacher_id = cl.teacher_id
                JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                JOIN pp."user" u ON bcb.user_id = u.user_id
                WHERE t.user_id = :userId::numeric
                  AND u.active_yn = 'Y'
                GROUP BY
                    u.user_id,
                    u.full_name,
                    u.user_email,
                    u.contact_no,
                    u.active_yn
                ORDER BY u.full_name ASC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #3 getTimetableByBatchAndTeacher, unfiltered branch -- TeacherTimetableModel.js:3-53. */
    public List<Map<String, Object>> timetable(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT
                    t.timetable_id,
                    t.day_of_week,
                    t.start_time,
                    t.end_time,
                    c.classroom_id,
                    c.classroom_name,
                    c.class_link,
                    s.subject_name,
                    s.subject_code,
                    tch.teacher_name,
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1
                        WHEN 'MONDAY' THEN 2
                        WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4
                        WHEN 'THURSDAY' THEN 5
                        WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END as day_order
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
                WHERE tch.user_id = :userId::numeric
                ORDER BY day_order, t.start_time
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #3 getTimetableByBatchAndTeacher, batchId-filtered branch -- TeacherTimetableModel.js:3-53. */
    public List<Map<String, Object>> timetableByBatch(String userId, String batchId) {
        return jdbc.sql("""
                SELECT DISTINCT
                    t.timetable_id,
                    t.day_of_week,
                    t.start_time,
                    t.end_time,
                    c.classroom_id,
                    c.classroom_name,
                    c.class_link,
                    s.subject_name,
                    s.subject_code,
                    tch.teacher_name,
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1
                        WHEN 'MONDAY' THEN 2
                        WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4
                        WHEN 'THURSDAY' THEN 5
                        WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END as day_order
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE tch.user_id = :userId::numeric AND cb.batch_id = :batchId::integer
                ORDER BY day_order, t.start_time
                """).param("userId", userId).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    private static final String STUDENT_SELECT = """
            sm.student_id,
            sm.applicant_id,
            sm.enr_id,
            sm.student_name,
            sm.gender,
            sm.father_name,
            sm.father_occupation,
            sm.mother_name,
            sm.mother_occupation,
            sm.student_email,
            sm.student_email_password,
            sm.parent_email,
            sm.contact_no1,
            sm.contact_no2,
            sm.home_address,
            sm.current_institute_dise_code,
            sm.previous_institute_dise_code,
            ci.institute_name AS current_institute,
            pi.institute_name AS previous_institute,
            sm.sim_name,
            sm.teacher_name,
            sm.teacher_mobile_number,
            sm.active_yn,
            sm.recharge_status,
            sm.sponsor,
            sm.photo_link,
            sm.batch_id,
            b.batch_name,
            c.cohort_number,
            c.cohort_name,
            ins.inactive_reason,
            sm.created_at,
            sm.updated_at
            """;

    /** #4 getStudentsByTeacher -- TeacherStudentModel.js:46-91. FAN-OUT QUIRK (ground truth §7.3): the
     *  LEFT JOIN pp.inactive_students below has no ORDER BY/dedup -- a student with >1 inactive_students
     *  row appears once per row. Do NOT add DISTINCT ON here; the client dedups (last-wins) client-side. */
    public List<Map<String, Object>> studentsByTeacher(String userId) {
        return jdbc.sql("SELECT DISTINCT " + STUDENT_SELECT + """
                FROM pp.teacher t
                JOIN pp.classroom cr ON cr.teacher_id = t.teacher_id
                JOIN pp.classroom_batch cb ON cb.classroom_id = cr.classroom_id
                JOIN pp.batch b ON b.batch_id = cb.batch_id
                JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                JOIN pp.student_master sm ON sm.batch_id = b.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins
                    ON ins.student_id = sm.student_id
                   AND sm.active_yn = 'INACTIVE'
                WHERE t.user_id = :userId::numeric
                ORDER BY c.cohort_number, b.batch_name, sm.student_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #4 getStudentsByTeacherBatch -- TeacherStudentModel.js:97-150. Same fan-out quirk as above. */
    public List<Map<String, Object>> studentsByTeacherAndBatch(String userId, String cohortNumber, String batchId) {
        return jdbc.sql("SELECT DISTINCT " + STUDENT_SELECT + """
                FROM pp.teacher t
                JOIN pp.classroom cr ON cr.teacher_id = t.teacher_id
                JOIN pp.classroom_batch cb ON cb.classroom_id = cr.classroom_id
                JOIN pp.batch b ON b.batch_id = cb.batch_id
                JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                JOIN pp.student_master sm ON sm.batch_id = b.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins
                    ON ins.student_id = sm.student_id
                   AND sm.active_yn = 'INACTIVE'
                WHERE
                    t.user_id = :userId::numeric
                    AND c.cohort_number = :cohortNumber::integer
                    AND b.batch_id = :batchId::integer
                ORDER BY sm.student_name
                """).param("userId", userId).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** #5 getInactiveHistoryByStudentId -- TeacherStudentModel.js:156-174. NO teacher-ownership check
     *  (ground truth §7.1, IDOR) -- ported as-is, flagged in the plan's Deferred section, not fixed here. */
    public List<Map<String, Object>> inactiveHistory(String studentId) {
        return jdbc.sql("""
                SELECT
                    inactive_reason,
                    inactive_date,
                    created_by,
                    updated_by
                FROM pp.inactive_students
                WHERE student_id = :studentId::numeric
                ORDER BY inactive_date DESC
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #8 getTeacherDashboardStats -- TeacherDashboardModel.js:3-67. Node runs these 4 as Promise.all
     *  (no transaction, no cross-query consistency guarantee to preserve, ground truth §6) -- 4 sequential
     *  JdbcClient calls here are observably equivalent for this read-only self-service screen. */
    public Map<String, Object> dashboardOverview(String userId) {
        Map<String, Object> stats = jdbc.sql("""
                SELECT
                    COUNT(DISTINCT cs.session_id) as total_conducted,
                    COALESCE(ROUND(AVG(sa.attendance_percent), 2), 0) as avg_attendance
                FROM pp.teacher t
                LEFT JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                LEFT JOIN pp.student_attendance sa ON cs.session_id = sa.session_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).single();
        Object totalBatches = jdbc.sql("""
                SELECT COUNT(DISTINCT cb.batch_id) as total_batches
                FROM pp.teacher t
                JOIN pp.classroom c ON t.teacher_id = c.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).single().get("total_batches");
        Map<String, Object> overview = new java.util.LinkedHashMap<>(stats);
        overview.put("total_batches", totalBatches);
        return overview;
    }

    public List<Map<String, Object>> dashboardSubjectAnalysis(String userId) {
        return jdbc.sql("""
                SELECT
                    s.subject_name,
                    COUNT(cs.session_id) as classes_taken
                FROM pp.teacher t
                JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                WHERE t.user_id = :userId::numeric
                GROUP BY s.subject_name
                ORDER BY classes_taken DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** QUIRK PRESERVED (ground truth §7.2): ORDER BY ... ASC LIMIT 6 returns the EARLIEST 6 months of
     *  session data, not the most recent 6, despite Node's own code comment claiming "Last 6 months". Do
     *  NOT "fix" to DESC + reverse -- that would be a behavior change requiring product sign-off. */
    public List<Map<String, Object>> dashboardMonthlyTrend(String userId) {
        return jdbc.sql("""
                SELECT
                    TO_CHAR(cs.session_date, 'Mon YYYY') as month_label,
                    COUNT(cs.session_id) as classes_taken
                FROM pp.teacher t
                JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                WHERE t.user_id = :userId::numeric
                GROUP BY TO_CHAR(cs.session_date, 'Mon YYYY'), DATE_TRUNC('month', cs.session_date)
                ORDER BY DATE_TRUNC('month', cs.session_date) ASC
                LIMIT 6
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #9 getMyClassReports -- TeacherReportModel.js:3-39. */
    public List<Map<String, Object>> myClassReports(String userId, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT
                    cs.session_id,
                    cs.session_date AS date,
                    co.cohort_name,
                    string_agg(DISTINCT b.batch_name, ', ') AS batch_name,
                    c.classroom_name,
                    s.subject_name,
                    EXISTS (
                        SELECT 1
                        FROM pp.student_attendance sa
                        WHERE sa.session_id = cs.session_id
                    ) AS attendance_marked
                FROM pp.class_session cs
                JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                WHERE t.user_id = :userId::numeric
                  AND cs.session_date >= :fromDate::date
                  AND cs.session_date <= :toDate::date
                GROUP BY
                    cs.session_id,
                    cs.session_date,
                    co.cohort_name,
                    c.classroom_name,
                    s.subject_name
                ORDER BY cs.session_date ASC
                """).param("userId", userId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
