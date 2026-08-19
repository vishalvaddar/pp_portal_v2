package com.rcf.imas.modules.tracking.persistence;

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

@Repository
public class ActiveTimetableReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ActiveTimetableReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the whole tracking module (convention #3 -- MODULE-SPECIFIC
     * DEVIATION from Plan 4b's toBigInteger() shortcut): uses bd.toPlainString() for NUMERIC/DECIMAL
     * because pp.student_interview has genuinely fractional numeric(3,1) score columns
     * (life_goals_and_zeal, commitment_to_learning, integrity, communication_skills) -- toBigInteger()
     * would silently truncate e.g. 4.5 -> "4", a real data-loss bug, not a style choice. Whole-number
     * numeric columns (interviewer_id, applicant_id, interview_id, verification_id, created_by/updated_by)
     * still render correctly ("5", no trailing ".0", since their scale is 0).
     * Package-private static so the other two repositories in this module call it directly (same package).
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

    /** getCohorts -- "open" cohorts only (end_date IS NULL). */
    public List<Map<String, Object>> openCohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name FROM pp.cohort WHERE end_date IS NULL ORDER BY cohort_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeachers -- all teachers, no filter. */
    public List<Map<String, Object>> allTeachers() {
        return jdbc.sql("SELECT teacher_id, teacher_name FROM pp.teacher ORDER BY teacher_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getBatches($1=cohortName). */
    public List<Map<String, Object>> batchesByCohortName(String cohortName) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE c.cohort_name = :cohortName
                """).param("cohortName", cohortName).query((rs, i) -> genericRow(rs)).list();
    }

    /** getCombined($1=cohortName) [type=combined] -- Sun->Sat CASE ordering (quirk 4a). */
    public List<Map<String, Object>> combinedByCohort(String cohortName) {
        return jdbc.sql("""
                SELECT
                  t.teacher_name, s.subject_name, b.batch_name,
                  tt.day_of_week, tt.start_time, tt.end_time
                FROM pp.timetable tt
                LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
                WHERE ch.cohort_name = :cohortName
                ORDER BY
                  CASE
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'sunday' THEN 1
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'monday' THEN 2
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'tuesday' THEN 3
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'wednesday' THEN 4
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'thursday' THEN 5
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'friday' THEN 6
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'saturday' THEN 7
                  END, tt.start_time
                """).param("cohortName", cohortName).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getTeacherWise($1=teacherId) [type=teacher]. QUIRKS (preserve, do NOT "fix"): (a) NO cohort filter
     * at all, even though the frontend always sends one -- shows a teacher's classes across ALL cohorts;
     * (b) ORDER BY tt.day_of_week is plain ALPHABETICAL text order (Friday, Monday, Saturday, Sunday,
     * Thursday, Tuesday, Wednesday), NOT the Sun-Sat CASE combinedByCohort uses.
     */
    public List<Map<String, Object>> teacherWise(String teacherId) {
        return jdbc.sql("""
                SELECT t.teacher_name, s.subject_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
                FROM pp.timetable tt
                LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                WHERE t.teacher_id = :teacherId::integer
                ORDER BY tt.day_of_week, tt.start_time
                """).param("teacherId", teacherId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getBatchWise($1=batchName,$2=cohortName) [type=batch] -- same alphabetical day-order quirk as teacherWise. */
    public List<Map<String, Object>> batchWise(String batchName, String cohortName) {
        return jdbc.sql("""
                SELECT s.subject_name, t.teacher_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
                FROM pp.timetable tt
                LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
                WHERE b.batch_name = :batchName AND ch.cohort_name = :cohortName
                ORDER BY tt.day_of_week, tt.start_time
                """).param("batchName", batchName).param("cohortName", cohortName)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeacherSkills part 1 -- current skills for one teacher. */
    public List<Map<String, Object>> teacherSkills(String teacherId) {
        return jdbc.sql("""
                SELECT ts.subject_id, s.subject_name, ts.medium
                FROM pp.teacher_subject ts
                JOIN pp.subject s ON ts.subject_id = s.subject_id
                WHERE ts.teacher_id = :teacherId::integer
                """).param("teacherId", teacherId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getSubjects -- full subject list, used both standalone and as getTeacherSkills part 2 (allSubjects). */
    public List<Map<String, Object>> allSubjects() {
        return jdbc.sql("SELECT subject_id, subject_name FROM pp.subject ORDER BY subject_name")
                .query((rs, i) -> genericRow(rs)).list();
    }
}
