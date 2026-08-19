package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClassroomReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ClassroomReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the whole classroom module (convention #3): EXAMS-style
     * bd.toBigInteger().toString() for NUMERIC/DECIMAL -- this module has NO genuinely fractional numeric
     * output anywhere (unlike Plan 4a's student module), so do NOT copy Plan 4a's toPlainString() deviation.
     * BIGINT -> String. DATE -> "yyyy-MM-dd". TIME -> "HH:mm:ss". TIMESTAMP -> ISO-Z. ARRAY (only
     * COALESCE(array_agg(cb.batch_id)...) in getClassrooms) -> List, elements passthrough (integer array,
     * never numeric here, but the same element-conversion rule as ExamsReadRepository is applied for
     * forward-consistency). Else passthrough via rs.getObject(i) (native JSON number for integer columns).
     * Package-private static so the other three repositories in this module call it directly (same package),
     * matching the ExamsWriteRepository-reuses-ExamsReadRepository.genericRow precedent.
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
                    val = bd == null ? null : bd.toBigInteger().toString();
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
                case java.sql.Types.ARRAY -> {
                    Array arr = rs.getArray(i);
                    val = arr == null ? null : arrayToList(arr);
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    private static List<Object> arrayToList(Array arr) throws SQLException {
        Object raw = arr.getArray();
        List<Object> out = new ArrayList<>();
        int len = java.lang.reflect.Array.getLength(raw);
        for (int i = 0; i < len; i++) {
            Object el = java.lang.reflect.Array.get(raw, i);
            if (el instanceof BigDecimal bd) { out.add(bd.toBigInteger().toString()); }
            else { out.add(el); }
        }
        return out;
    }

    public List<Map<String, Object>> subjects() {
        return jdbc.sql("SELECT subject_id, subject_name, subject_code FROM pp.subject ORDER BY subject_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> teachingPlatforms() {
        return jdbc.sql("SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> teachersBySubject(String subjectId) {
        return jdbc.sql("""
                SELECT
                    t.teacher_id,
                    u.user_name AS teacher_name
                 FROM pp.teacher t
                 JOIN pp.user u ON t.user_id = u.user_id
                 JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
                 WHERE ts.subject_id = :subjectId::integer
                 ORDER BY u.user_name
                """).param("subjectId", subjectId).query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getBatchesByCohort -- {batch_id,batch_name} ONLY. Distinct from BatchReadRepository's
     *  batch-side implementation (SELECT *) -- ground truth §7 quirk 5, do not unify. */
    public List<Map<String, Object>> batchesByCohortClassroomSide(String cohortNumber) {
        return jdbc.sql("SELECT batch_id, batch_name FROM pp.batch WHERE cohort_number = :cohortNumber::integer ORDER BY batch_name")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> classrooms() {
        return jdbc.sql("""
                SELECT
                    c.classroom_id, c.classroom_name, c.class_link, c.active_yn, c.description, c.created_at,
                    c.subject_id, c.teacher_id, c.platform_id,
                    s.subject_name, s.subject_code,
                    u.user_name AS teacher_name,
                    p.platform_name,
                    COALESCE(array_agg(cb.batch_id) FILTER (WHERE cb.batch_id IS NOT NULL), '{}') AS batch_ids,
                    MAX(b.cohort_number) AS cohort_number
                 FROM pp.classroom c
                 LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                 LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                 LEFT JOIN pp.user u ON t.user_id = u.user_id
                 LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
                 LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                 LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                 GROUP BY
                    c.classroom_id, s.subject_name, s.subject_code,
                    u.user_name, p.platform_name
                 ORDER BY c.created_at DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }
}
