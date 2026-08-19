package com.rcf.imas.modules.selectionreports.persistence;

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

/**
 * selectionReportModel.js port (Firm Decision 2). All SQL is verbatim from the ground truth / live
 * Node source. genericRow uses BigDecimal.toPlainString() (NOT toBigInteger()) for NUMERIC/DECIMAL
 * because turnout_percentage/selection_percentage are ROUND(x,2) fractional values -- truncating to
 * an integer would silently corrupt every percentage on every printed report.
 */
@Repository
public class SelectionReportsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public SelectionReportsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

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

    /** getAcademicYears (selectionReportModel.js:5). No phase filter -- DISTINCT collapses phase duplicates. */
    public List<Map<String, Object>> academicYears() {
        return jdbc.sql("SELECT DISTINCT academic_year FROM pp.system_config ORDER BY academic_year DESC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getNMMSReport(year,type) (selectionReportModel.js:10-35). Firm Decision 4: type!='district' -> block branch. */
    public List<Map<String, Object>> nmmsReport(String year, String type) {
        if ("district".equals(type)) {
            return jdbc.sql("""
                    SELECT d.juris_name AS label, COUNT(a.applicant_id) AS applicant_count
                    FROM pp.applicant_primary_info a
                    JOIN pp.jurisdiction d ON a.district = d.juris_code
                    WHERE a.nmms_year = :year::numeric
                    GROUP BY d.juris_name ORDER BY d.juris_name
                    """).param("year", year).query((rs, i) -> genericRow(rs)).list();
        }
        return jdbc.sql("""
                SELECT
                    d.juris_name AS district_name,
                    b.juris_name AS label,
                    COUNT(a.applicant_id) AS applicant_count
                FROM pp.applicant_primary_info a
                JOIN pp.jurisdiction d ON a.district = d.juris_code
                JOIN pp.jurisdiction b ON a.nmms_block = b.juris_code
                WHERE a.nmms_year = :year::numeric
                GROUP BY d.juris_name, b.juris_name
                ORDER BY d.juris_name, b.juris_name
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getTurnOutReport(year,type) (selectionReportModel.js:38-100). Deferred quirk: called_count =
     * COUNT(DISTINCT s.applicant_id) over ALL applicant_shortlist_info rows for the year -- NO
     * shortlisted_yn filter. Do not add one; it changes the printed "called" number.
     */
    public List<Map<String, Object>> turnOutReport(String year, String type) {
        if ("district".equals(type)) {
            return jdbc.sql("""
                    SELECT
                        j.juris_name AS label,
                        COUNT(DISTINCT s.applicant_id) AS called_count,
                        COUNT(DISTINCT CASE
                            WHEN a.pp_exam_appeared_yn = 'Y'
                            THEN s.applicant_id
                        END) AS appeared_count,
                        ROUND(
                            COUNT(DISTINCT CASE
                                WHEN a.pp_exam_appeared_yn = 'Y'
                                THEN s.applicant_id
                            END) * 100.0
                            / NULLIF(COUNT(DISTINCT s.applicant_id), 0),
                            2
                        ) AS turnout_percentage
                    FROM pp.applicant_shortlist_info s
                    JOIN pp.applicant_primary_info ap ON ap.applicant_id = s.applicant_id
                    LEFT JOIN pp.applicant_exam_attendance a ON a.applicant_id = s.applicant_id
                    JOIN pp.jurisdiction j ON ap.district = j.juris_code
                    WHERE ap.nmms_year = :year::numeric
                    GROUP BY ap.district, j.juris_name
                    ORDER BY j.juris_name
                    """).param("year", year).query((rs, i) -> genericRow(rs)).list();
        }
        return jdbc.sql("""
                SELECT
                    d.juris_name AS district_name,
                    b.juris_name AS label,
                    COUNT(DISTINCT s.applicant_id) AS called_count,
                    COUNT(DISTINCT CASE
                        WHEN a.pp_exam_appeared_yn = 'Y'
                        THEN s.applicant_id
                    END) AS appeared_count,
                    ROUND(
                        COUNT(DISTINCT CASE
                            WHEN a.pp_exam_appeared_yn = 'Y'
                            THEN s.applicant_id
                        END) * 100.0
                        / NULLIF(COUNT(DISTINCT s.applicant_id), 0),
                        2
                    ) AS turnout_percentage
                FROM pp.applicant_shortlist_info s
                JOIN pp.applicant_primary_info ap ON ap.applicant_id = s.applicant_id
                LEFT JOIN pp.applicant_exam_attendance a ON a.applicant_id = s.applicant_id
                JOIN pp.jurisdiction d ON ap.district = d.juris_code
                JOIN pp.jurisdiction b ON ap.nmms_block = b.juris_code
                WHERE ap.nmms_year = :year::numeric
                GROUP BY ap.district, ap.nmms_block, d.juris_name, b.juris_name
                ORDER BY d.juris_name, b.juris_name
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /** getSelectionReport(year,type) (selectionReportModel.js:101-133). */
    public List<Map<String, Object>> selectionReport(String year, String type) {
        if ("district".equals(type)) {
            return jdbc.sql("""
                    SELECT j.juris_name AS label,
                        COUNT(DISTINCT a.applicant_id) AS appeared_count,
                        COUNT(DISTINCT sm.applicant_id) AS selected_count,
                        ROUND(COUNT(DISTINCT sm.applicant_id) * 100.0 / NULLIF(COUNT(DISTINCT a.applicant_id), 0), 2) AS selection_percentage
                    FROM pp.applicant_exam_attendance a
                    JOIN pp.applicant_primary_info ap ON ap.applicant_id = a.applicant_id
                    JOIN pp.jurisdiction j ON ap.district = j.juris_code
                    LEFT JOIN pp.student_master sm ON sm.applicant_id = a.applicant_id
                    WHERE a.pp_exam_appeared_yn = 'Y' AND ap.nmms_year = :year::numeric
                    GROUP BY ap.district, j.juris_name ORDER BY j.juris_name
                    """).param("year", year).query((rs, i) -> genericRow(rs)).list();
        }
        return jdbc.sql("""
                SELECT d.juris_name AS district_name, b.juris_name AS label,
                    COUNT(DISTINCT a.applicant_id) AS appeared_count,
                    COUNT(DISTINCT sm.applicant_id) AS selected_count,
                    ROUND(COUNT(DISTINCT sm.applicant_id) * 100.0 / NULLIF(COUNT(DISTINCT a.applicant_id), 0), 2) AS selection_percentage
                FROM pp.applicant_exam_attendance a
                JOIN pp.applicant_primary_info ap ON ap.applicant_id = a.applicant_id
                JOIN pp.jurisdiction d ON ap.district = d.juris_code
                JOIN pp.jurisdiction b ON ap.nmms_block = b.juris_code
                LEFT JOIN pp.student_master sm ON sm.applicant_id = a.applicant_id
                WHERE a.pp_exam_appeared_yn = 'Y' AND ap.nmms_year = :year::numeric
                GROUP BY ap.district, ap.nmms_block, d.juris_name, b.juris_name
                ORDER BY d.juris_name, b.juris_name
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getSelectsReport(year,type) (selectionReportModel.js:134-168). Firm Decision 9: returns raw
     * {label,gender,student_count} rows -- the M/F -> {boys_sel,girls_sel} pivot happens on the
     * FRONTEND, never here.
     */
    public List<Map<String, Object>> selectsReport(String year, String type) {
        if ("district".equals(type)) {
            return jdbc.sql("""
                    SELECT
                        d.juris_name AS label,
                        ap.gender,
                        COUNT(sm.applicant_id) AS student_count
                    FROM pp.applicant_primary_info ap
                    JOIN pp.jurisdiction d ON ap.district = d.juris_code
                    LEFT JOIN pp.student_master sm ON sm.applicant_id = ap.applicant_id
                    WHERE ap.nmms_year = :year::numeric
                    GROUP BY ap.district, d.juris_name, ap.gender
                    ORDER BY d.juris_name, ap.gender
                    """).param("year", year).query((rs, i) -> genericRow(rs)).list();
        }
        return jdbc.sql("""
                SELECT
                    d.juris_name AS district_name,
                    b.juris_name AS label,
                    ap.gender,
                    COUNT(sm.applicant_id) AS student_count
                FROM pp.applicant_primary_info ap
                JOIN pp.jurisdiction d ON ap.district = d.juris_code
                JOIN pp.jurisdiction b ON ap.nmms_block = b.juris_code
                LEFT JOIN pp.student_master sm ON sm.applicant_id = ap.applicant_id
                WHERE ap.nmms_year = :year::numeric
                GROUP BY ap.district, d.juris_name, ap.nmms_block, b.juris_name, ap.gender
                ORDER BY d.juris_name, b.juris_name, ap.gender
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /** getCohorts (selectionReportModel.js:169-173). */
    public List<Map<String, Object>> cohorts() {
        return jdbc.sql("SELECT cohort_name FROM pp.cohort ORDER BY cohort_number ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getSammelanData(cohort,fromDate,toDate) (selectionReportModel.js:176-202). Deferred quirk: the
     * overlap-range test (event_start_date <= toDate AND event_end_date >= fromDate) and the
     * hard-coded event_type_name='Sammelan' literal are both preserved -- bound here by named/semantic
     * params rather than by copying Node's confusing $2/$3 positional swap (Firm Decision 7).
     */
    public List<Map<String, Object>> sammelanData(String cohort, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT
                    c.cohort_name,
                    em.event_title AS label,
                    d.juris_name AS district_name,
                    b.juris_name AS block_name,
                    em.event_location,
                    em.event_start_date AS from_date,
                    em.event_end_date AS to_date,
                    COALESCE(em.boys_attended, 0) AS boys_sel,
                    COALESCE(em.girls_attended, 0) AS girls_sel
                FROM pp.cohort c
                JOIN pp.event_master em ON em.cohort_number = c.cohort_number
                JOIN pp.event_type et ON et.event_type_id = em.event_type_id
                LEFT JOIN pp.jurisdiction d ON em.event_district = d.juris_code
                LEFT JOIN pp.jurisdiction b ON em.event_block = b.juris_code
                WHERE et.event_type_name = 'Sammelan'
                    AND em.event_start_date <= :toDate::date
                    AND em.event_end_date >= :fromDate::date
                    AND c.cohort_name = :cohort
                ORDER BY em.event_start_date
                """).param("cohort", cohort).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
