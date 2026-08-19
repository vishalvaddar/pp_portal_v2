package com.rcf.imas.modules.student.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class StudentPortalReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public StudentPortalReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * node-pg parity, WITH ONE MODULE-SPECIFIC DEVIATION: NUMERIC/DECIMAL -> bd.toPlainString() (NOT
     * toBigInteger()). This module has genuinely fractional numeric output (attendance_percent, percent --
     * both ROUND(x::numeric,2)) that must not be truncated to an integer string. toPlainString() is a safe
     * superset for scale-0 numerics too (ids, nmms_year, etc. -- identical output to toBigInteger()).
     * BIGINT -> String (COUNT() results). DATE -> "yyyy-MM-dd". TIME -> "HH:mm:ss". TIMESTAMP -> ISO-Z.
     * Else passthrough via rs.getObject(i) (native JSON number for integer/int columns). Map keys are the
     * column label verbatim (snake_case).
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
                    java.sql.Time t = rs.getTime(i);
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

    private static final String PROFILE_SELECT = """
            SELECT
              sm.student_id, sm.applicant_id, sm.enr_id, sm.student_name, sm.gender,
              sm.father_name, sm.father_occupation, sm.mother_name, sm.mother_occupation,
              sm.student_email, sm.student_email_password, sm.parent_email,
              sm.contact_no1, sm.contact_no2, sm.home_address,
              sm.current_institute_dise_code, sm.previous_institute_dise_code,
              ci.institute_name AS current_institute, pi.institute_name AS previous_institute,
              sm.sim_name, sm.teacher_name, sm.teacher_mobile_number,
              sm.active_yn, sm.recharge_status, sm.sponsor, sm.photo_link,
              sm.batch_id, b.batch_name, c.cohort_number, c.cohort_name,
              ins.inactive_reason, sm.created_at, sm.updated_at
            FROM pp.student_master sm
            JOIN pp.batch b ON sm.batch_id = b.batch_id
            JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
            LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
            LEFT JOIN pp.inactive_students ins
              ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
            WHERE sm.user_id = :userId::numeric
            LIMIT 1
            """;

    /** Own-profile lookup, reused internally by /timetable (batch_id null -> "No batch assigned."). */
    public Optional<Map<String, Object>> profileByUserId(String userId) {
        return jdbc.sql(PROFILE_SELECT).param("userId", userId).query((rs, i) -> genericRow(rs)).optional();
    }

    public List<Map<String, Object>> timetableByBatchId(Object batchId) {
        return jdbc.sql("""
                SELECT
                    tt.timetable_id, tt.day_of_week, tt.start_time, tt.end_time,
                    c.classroom_name, c.class_link,
                    s.subject_name, t.teacher_name, p.platform_name
                FROM pp.timetable tt
                JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
                WHERE cb.batch_id = :batchId
                ORDER BY
                    CASE tt.day_of_week
                        WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END,
                    tt.start_time ASC
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> inactiveHistory(String studentId) {
        return jdbc.sql("""
                SELECT inactive_reason, inactive_date, created_by, updated_by
                FROM pp.inactive_students
                WHERE student_id = :studentId::numeric
                ORDER BY inactive_date DESC
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).list();
    }

    private static final String ATTENDANCE_FILTER =
            "sa.status IN ('PRESENT','LATE JOINED','LEAVE')";

    private static final String ATTENDANCE_AGG_SQL = """
            SELECT
              COUNT(cs.session_id) AS total_classes,
              COUNT(sa.session_id) FILTER (WHERE %s) AS attended_classes,
              ROUND(
                COUNT(sa.session_id) FILTER (WHERE %s)::numeric
                / NULLIF(COUNT(cs.session_id),0) * 100
              ,2) AS attendance_percent
            FROM pp.student_master sm
            JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
            JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
            JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
            LEFT JOIN pp.student_attendance sa
              ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
            WHERE sm.user_id = :userId::numeric
            """.formatted(ATTENDANCE_FILTER, ATTENDANCE_FILTER);

    private static final String EXAM_SCORE_SQL = """
            SELECT er.pp_exam_score
            FROM pp.exam_results er
            JOIN pp.student_master sm ON sm.applicant_id = er.applicant_id
            WHERE sm.user_id = :userId::numeric
            """;

    /**
     * Two round-trips, no transaction (Node parity). `exam_score = pp_exam_score || "-"` (studentModel.js:791):
     * pp_exam_score is numeric(3,0), which node-pg returns as a STRING, so a zero score is "0" (truthy) -> stays
     * "0"; only a NULL pp_exam_score or a missing exam_results row -> "-". (The earlier "0-as-falsy" reading was
     * wrong: JS `"0" || "-"` === "0", not "-".)
     */
    public Map<String, Object> summary(String userId) {
        Map<String, Object> agg = jdbc.sql(ATTENDANCE_AGG_SQL).param("userId", userId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        BigDecimal examScoreBd = jdbc.sql(EXAM_SCORE_SQL).param("userId", userId)
                .query((rs, i) -> rs.getBigDecimal(1)).optional().orElse(null);
        String examScore = examScoreBd == null ? "-" : examScoreBd.toPlainString();

        Map<String, Object> result = new LinkedHashMap<>();
        if (agg != null) result.putAll(agg);
        result.put("exam_score", examScore);
        return result;
    }

    public List<Map<String, Object>> subjectPerformance(String userId) {
        return jdbc.sql("""
                SELECT
                  subj.subject_name,
                  COUNT(cs.session_id) AS total_classes,
                  COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_classes,
                  ROUND(
                    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
                    / NULLIF(COUNT(cs.session_id),0) * 100
                  ,2) AS attendance_percent
                FROM pp.student_master sm
                JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
                JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
                JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
                JOIN pp.subject subj ON subj.subject_id = c.subject_id
                LEFT JOIN pp.student_attendance sa
                  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
                WHERE sm.user_id = :userId::numeric
                GROUP BY subj.subject_name
                ORDER BY subj.subject_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** No NULLIF guard, unlike summary/subjects/custom -- preserved (see plan's SQL section for why it's safe). */
    public List<Map<String, Object>> monthlyAttendance(String userId) {
        return jdbc.sql("""
                SELECT
                  TO_CHAR(cs.session_date, 'YYYY-MM') AS month,
                  ROUND(
                    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
                    / COUNT(cs.session_id) * 100
                  ,2) AS percent
                FROM pp.student_master sm
                JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
                JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
                JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
                LEFT JOIN pp.student_attendance sa
                  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
                WHERE sm.user_id = :userId::numeric
                GROUP BY month
                ORDER BY month
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> weeklyAttendance(String userId) {
        return jdbc.sql("""
                SELECT
                  TO_CHAR(DATE_TRUNC('week', cs.session_date), 'YYYY-MM-DD') AS week_start,
                  ROUND(
                    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
                    / COUNT(cs.session_id) * 100
                  ,2) AS percent
                FROM pp.student_master sm
                JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
                JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
                JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
                LEFT JOIN pp.student_attendance sa
                  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
                WHERE sm.user_id = :userId::numeric
                GROUP BY week_start
                ORDER BY week_start
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> customAttendance(String userId, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT
                  subj.subject_name,
                  COUNT(cs.session_id) AS total_classes,
                  COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_classes,
                  ROUND(
                    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
                    / NULLIF(COUNT(cs.session_id),0) * 100
                  ,2) AS attendance_percent
                FROM pp.student_master sm
                JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
                JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
                JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
                JOIN pp.subject subj ON subj.subject_id = c.subject_id
                LEFT JOIN pp.student_attendance sa
                  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
                WHERE sm.user_id = :userId::numeric
                  AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                GROUP BY subj.subject_name
                ORDER BY subj.subject_name
                """).param("userId", userId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
