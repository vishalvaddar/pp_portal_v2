package com.rcf.imas.modules.results.persistence;

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
public class ResultsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The 4 closed filter-options branches. Values are hardcoded, parameterless SQL — never derived from `field`. */
    private static final Map<String, String> FILTER_OPTION_SQL = Map.of(
        "interview_status",    "SELECT DISTINCT status as value FROM pp.student_interview WHERE status IS NOT NULL",
        "interview_result",    "SELECT DISTINCT interview_result as value FROM pp.student_interview WHERE interview_result IS NOT NULL",
        "verification_status", "SELECT DISTINCT status as value FROM pp.home_verification WHERE status IS NOT NULL",
        "pp_exam_cleared",     "SELECT DISTINCT pp_exam_cleared as value FROM pp.exam_results WHERE pp_exam_cleared IS NOT NULL"
    );

    private final JdbcClient jdbc;

    public ResultsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * node-pg parity for this module's generic lists: integer numerics + bigint -> String; DATE -> "yyyy-MM-dd";
     * TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z; else native passthrough. Map keys are the column label as-is.
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
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    public List<Map<String, Object>> divisionsByState(String stateId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'DIVISION' AND parent_juris = :stateId::numeric
                ORDER BY juris_name
                """).param("stateId", stateId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> educationDistrictsByDivision(String divisionId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' AND parent_juris = :divisionId::numeric
                ORDER BY juris_name
                """).param("divisionId", divisionId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String districtId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'BLOCK' AND parent_juris = :districtId::numeric
                ORDER BY juris_name
                """).param("districtId", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> allExams() {
        return jdbc.sql("""
                SELECT exam_id, exam_name, exam_date, exam_start_time, exam_end_time
                FROM pp.examination ORDER BY exam_date DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** Closed dispatch: unknown field -> empty list, no query executed, 200 (matches Node's default: branch). */
    public List<String> filterOptions(String field) {
        String sql = FILTER_OPTION_SQL.get(field);
        if (sql == null) return List.of();
        return jdbc.sql(sql).query(String.class).list();
    }

    private static final String SEARCH_BASE_SELECT = """
            SELECT
              api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
              api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
              si.institute_name as school_name,
              er.pp_exam_score, er.pp_exam_cleared,
              si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
              hv.status as verification_status, hv.remarks as verification_remarks,
              rr.rejection_reason as rejection_reasons,
              div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name
            FROM pp.applicant_primary_info api
            LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
            LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
            LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
            LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
            LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
            LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
            LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
            LEFT JOIN pp.jurisdiction div ON div.juris_code = dist.parent_juris
            WHERE api.app_state = :appState::numeric
            """;

    /**
     * searchStudentsByBlocks parity: base WHERE always binds app_state (defaults to 1 at the controller);
     * the three optional predicates are appended in this exact order, only when present — built with a
     * StringBuilder + named params (never positional ?) to avoid off-by-one errors across the 8 filter
     * combinations. No DISTINCT/dedup: exam_results/student_interview/home_verification can fan out per applicant.
     */
    public List<Map<String, Object>> searchByBlocks(String division, String educationDistrict,
                                                     List<Object> blocks, String appState) {
        boolean hasDivision = division != null && !division.isBlank();
        boolean hasEducationDistrict = educationDistrict != null && !educationDistrict.isBlank();
        boolean hasBlocks = blocks != null && !blocks.isEmpty();

        StringBuilder sql = new StringBuilder(SEARCH_BASE_SELECT);
        if (hasDivision) sql.append(" AND dist.parent_juris = :division::numeric");
        if (hasEducationDistrict) sql.append(" AND api.district = :educationDistrict::numeric");
        if (hasBlocks) sql.append(" AND api.nmms_block = ANY(:blocks)");
        sql.append(" ORDER BY COALESCE(blk.juris_name, 'Unknown'), api.student_name");

        var query = jdbc.sql(sql.toString()).param("appState", appState);
        if (hasDivision) query = query.param("division", division);
        if (hasEducationDistrict) query = query.param("educationDistrict", educationDistrict);
        if (hasBlocks) {
            BigDecimal[] arr = blocks.stream().map(b -> new BigDecimal(String.valueOf(b))).toArray(BigDecimal[]::new);
            query = query.param("blocks", arr);
        }
        return query.query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * searchStudentsByExam parity. BUG PRESERVED VERBATIM (Node resultandrankingModel.js:152-153): both `div` and
     * `dist` join ON juris_code = api.district, so `division_name` here is actually the district's own name, not
     * the true parent division (unlike searchByBlocks, which correctly walks dist.parent_juris). Do NOT fix.
     */
    public List<Map<String, Object>> searchByExam(String examId) {
        return jdbc.sql("""
                SELECT
                  api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
                  api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
                  si.institute_name as school_name,
                  er.pp_exam_score, er.pp_exam_cleared,
                  si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
                  hv.status as verification_status, hv.remarks as verification_remarks,
                  rr.rejection_reason as rejection_reasons,
                  div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name,
                  e.exam_name, e.exam_date
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
                LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
                LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
                LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
                LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
                LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
                LEFT JOIN pp.jurisdiction div ON div.juris_code = api.district
                LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
                LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
                LEFT JOIN pp.examination e ON ae.exam_id = e.exam_id
                WHERE ae.exam_id = :examId::numeric
                ORDER BY api.student_name
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }
}
