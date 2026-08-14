package com.rcf.imas.modules.coordinator.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the 3 dashboard/analytics endpoints (#35-37, ground truth §4.8/§4.9). Deliberately does NOT reuse
 * CoordinatorReadRepository.genericRow -- these queries return DECIMAL numeric columns (a 2-decimal
 * percentage/average), and genericRow's NUMERIC branch truncates to a whole-number id string
 * (bd.toBigInteger()), which would silently drop the fractional part. Bespoke row mappers here instead.
 */
@Repository
public class CoordinatorDashboardRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CoordinatorDashboardRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Mirrors JS Number(x) + JSON.stringify semantics: a numerically-whole double serializes without a
     *  trailing ".0" (JS has no int/float distinction), a genuine fraction keeps full double precision.
     *  Same trick as CoordinatorReportsService.teacherPerformanceReport's "completion" field. */
    public static Object jsNumber(double d) {
        return (d == Math.rint(d) && !Double.isInfinite(d)) ? (Object) (long) d : (Object) d;
    }

    /** getBatchWeeklyAverage's own batch list (attendanceAnalyticsController.js:9-19) -- NO ORDER BY, so the
     *  bare-array result keeps Postgres natural order matching Node (do NOT reuse allBatchesForCoordinator,
     *  which appends ORDER BY batch_id DESC and would reorder this wire-visible array). */
    public List<Map<String, Object>> weeklyAvgBatchList(String userId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE bcb.user_id = :userId::numeric
                """)
                .param("userId", userId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batch_id", rs.getObject("batch_id"));       // integer -> JS number
                    m.put("batch_name", rs.getString("batch_name"));
                    m.put("cohort_name", rs.getString("cohort_name"));
                    return m;
                }).list();
    }

    /** attendanceModel.getWeeklyBatchAverage -- PRESENT=100, LATE JOINED=50, else 0, unweighted AVG over
     *  only rows that exist (no attendance row at all contributes nothing). Returns BigDecimal.ZERO when
     *  no matching rows exist at all (Node: `Number(rows[0].avg_attendance || 0)`). */
    public BigDecimal weeklyBatchAverage(String batchId, String fromDate, String toDate) {
        BigDecimal avg = jdbc.sql("""
                SELECT AVG(CASE WHEN sa.status = 'PRESENT' THEN 100 WHEN sa.status = 'LATE JOINED' THEN 50 ELSE 0 END) AS avg_attendance
                FROM pp.student_attendance sa
                JOIN pp.student_master sm ON sa.student_id = sm.student_id
                JOIN pp.class_session cs ON sa.session_id = cs.session_id
                WHERE sm.batch_id = :batchId::integer
                  AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                """)
                .param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query(BigDecimal.class).single();
        return avg == null ? BigDecimal.ZERO : avg;
    }

    /** reportsController.getTeacherSubjectMonthlyStats -- current-month per-subject/teacher percentage for
     *  one batch. Shares the 'PRESENT','LEAVE' (LATE JOINED excluded) numerator with #36 -- see plan's
     *  "findings" section, this is NOT called out in the ground truth doc for #37 but is present verbatim
     *  in the live source (reportsController.js:432-434). percentage stays a String (Firm Decision 9). */
    public List<Map<String, Object>> teacherSubjectStats(String batchId) {
        return jdbc.sql("""
                WITH current_month AS (
                    SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                           (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
                ),
                student_pop AS (
                    SELECT COUNT(*) as active_students FROM pp.student_master
                    WHERE batch_id = :batchId::integer AND active_yn = 'ACTIVE'
                )
                SELECT
                    s.subject_code, t.teacher_name,
                    ROUND(CASE WHEN (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop)) > 0
                          THEN (COUNT(sa.attendance_id) FILTER (WHERE sa.status IN ('PRESENT', 'LEAVE'))::float
                                / (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop))) * 100
                          ELSE 0 END::numeric, 2) as percentage
                FROM pp.class_session cs
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.student_attendance sa ON sa.session_id = cs.session_id
                CROSS JOIN current_month cm
                WHERE cb.batch_id = :batchId::integer AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt
                GROUP BY s.subject_code, t.teacher_name ORDER BY percentage DESC
                """)
                .param("batchId", batchId)
                .query((rs, i) -> teacherSubjectRow(rs)).list();
    }

    private static Map<String, Object> teacherSubjectRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject_code", rs.getString("subject_code"));
        m.put("teacher_name", rs.getString("teacher_name"));
        BigDecimal pct = rs.getBigDecimal("percentage");
        m.put("percentage", pct == null ? null : pct.toPlainString());
        return m;
    }

    /** reportsController.getGlobalAttendanceStats -- current-month rainbow gauge, one row per cohort. Uses
     *  sa.status IN ('PRESENT','LEAVE') -- excludes 'LATE JOINED' -- ported verbatim (Firm Decision 5/9,
     *  ground truth §4.8/§8.6). The jsonb `batches` column is parsed to a real List<Map> (Firm Decision 7),
     *  and its nested `avg` field goes through jsNumber() (Firm Decision 8) so a whole-number percentage
     *  serializes as `50`, not `50.0`, matching JS JSON.parse/JSON.stringify round-tripping a jsonb numeric. */
    public List<Map<String, Object>> globalAttendanceStats() {
        return jdbc.sql("""
                WITH current_month AS (
                    SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                           (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
                ),
                metrics AS (
                    SELECT
                        b.batch_id, b.batch_name, b.cohort_number,
                        (SELECT COUNT(*) FROM pp.student_master WHERE batch_id = b.batch_id AND active_yn = 'ACTIVE') as s_count,
                        (SELECT COUNT(DISTINCT cs.session_id) FROM pp.classroom_batch cb
                         JOIN pp.class_session cs ON cs.classroom_id = cb.classroom_id
                         CROSS JOIN current_month cm
                         WHERE cb.batch_id = b.batch_id AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as sess_count,
                        (SELECT COUNT(sa.attendance_id) FROM pp.student_attendance sa
                         JOIN pp.class_session cs ON sa.session_id = cs.session_id
                         JOIN pp.student_master sm ON sm.student_id = sa.student_id
                         CROSS JOIN current_month cm
                         WHERE sm.batch_id = b.batch_id AND sm.active_yn = 'ACTIVE'
                         AND sa.status IN ('PRESENT', 'LEAVE')
                         AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as p_count
                    FROM pp.batch b
                )
                SELECT
                    c.cohort_name, c.cohort_number,
                    ROUND(AVG(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END)::numeric, 2) as cohort_avg,
                    jsonb_agg(jsonb_build_object(
                        'batch_name', m.batch_name,
                        'avg', ROUND(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END::numeric, 2),
                        'classes_held', m.sess_count
                    ) ORDER BY m.batch_name) as batches
                FROM pp.cohort c
                JOIN metrics m ON m.cohort_number = c.cohort_number
                GROUP BY c.cohort_name, c.cohort_number ORDER BY c.cohort_number
                """)
                .query((rs, i) -> globalAttendanceRow(rs)).list();
    }

    private Map<String, Object> globalAttendanceRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohort_name", rs.getString("cohort_name"));
        m.put("cohort_number", rs.getObject("cohort_number"));
        BigDecimal avg = rs.getBigDecimal("cohort_avg");
        m.put("cohort_avg", avg == null ? null : avg.toPlainString());

        String batchesJson = rs.getString("batches"); // pgjdbc getString() on jsonb returns the raw JSON text
        List<Map<String, Object>> batches;
        try {
            batches = batchesJson == null ? List.of()
                    : objectMapper.readValue(batchesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        for (Map<String, Object> batch : batches) {
            Object rawAvg = batch.get("avg");
            if (rawAvg instanceof Number n) {
                batch.put("avg", jsNumber(n.doubleValue()));
            }
        }
        m.put("batches", batches);
        return m;
    }
}
