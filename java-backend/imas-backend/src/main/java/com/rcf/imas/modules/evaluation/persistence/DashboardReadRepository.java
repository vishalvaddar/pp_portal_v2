package com.rcf.imas.modules.evaluation.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DashboardReadRepository {

    private final JdbcClient jdbc;

    public DashboardReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as EvaluationReadRepository (NUMERIC/BIGINT -> String) -- used ONLY for
     *  getJurisdictionStatus's top-level spread fields, which Node genuinely leaves as raw pg strings. */
    private static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
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
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** getOverallCounts parity: 8 sequential COUNT(*) queries, EACH explicitly parseInt'd to a real int in Node --
     *  ported as real ints via a typed query, insertion order preserved with the 9 exact label strings as keys. */
    public Map<String, Object> overallCounts(String year) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Total Students", count("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric", year));
        out.put("Shortlisted", count("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric and a.shortlisted_yn='Y'
                """, year));
        out.put("Evaluated", count("""
                SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year));
        out.put("Pending Evaluation/Marks Entry", count("""
                SELECT COUNT(*) FROM pp.applicant_primary_info a
                WHERE a.applicant_id NOT IN (SELECT asi.applicant_id FROM pp.applicant_secondary_info asi)
                  AND a.applicant_id IN (SELECT s.applicant_id FROM pp.applicant_shortlist_info s)
                  AND a.nmms_year = :year::numeric
                """, year));
        out.put("Interview Required", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                """, year));
        out.put("Pending Interviews Assignment", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND NOT EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id)
                """, year));
        out.put("Pending Interview Result Upload", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.interview_result IS NULL)
                """, year));
        out.put("Home Verification Required", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn='Y')
                """, year));
        out.put("Pending Home Verification Result Upload", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn = 'Y')
                  AND NOT EXISTS (SELECT 1 FROM pp.home_verification hv WHERE hv.applicant_id = er.applicant_id AND hv.status IS NOT NULL)
                """, year));
        return out;
    }

    private int count(String sql, String year) {
        return jdbc.sql(sql).param("year", year).query(Integer.class).single();
    }

    /**
     * getJurisdictionStatus parity (evaluationDashboardModel.js:31-81). Per-row shape, read from the ACTUAL Node
     * source (not just the ground-truth summary, which over-generalizes): the top-level fields returned by the
     * spread `...row` are the RAW pg query result -- Strings for juris_code/totalShortlisted/evaluated/
     * pendingEvaluation/totalInterviewRequired/completedInterview (all NUMERIC/BIGINT) -- NOT reassigned/parsed.
     * Only `progress` (computed) and the `counts` sub-object (freshly parseInt'd) are real ints. Bug preserved:
     * si.status = 'Completed' (mixed case) never matches the upper-case-only CHECK constraint -> completedInterview
     * (both the raw top-level string AND the parsed counts.completedInterview) is always 0.
     */
    public List<Map<String, Object>> jurisdictionStatus(String year) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT
                  j.juris_name, j.juris_code,
                  COUNT(asi.applicant_id) AS "totalShortlisted",
                  COUNT(sec.applicant_id) AS "evaluated",
                  COUNT(CASE WHEN sec.applicant_id IS NULL THEN 1 END) AS "pendingEvaluation",
                  COUNT(CASE WHEN er.interview_required_yn = 'Y' THEN 1 END) AS "totalInterviewRequired",
                  COUNT(CASE WHEN si.status = 'Completed' THEN 1 END) AS "completedInterview"
                FROM pp.jurisdiction j
                JOIN pp.applicant_primary_info a ON j.juris_code = a.nmms_block
                JOIN pp.applicant_shortlist_info asi ON a.applicant_id = asi.applicant_id
                LEFT JOIN pp.applicant_secondary_info sec ON a.applicant_id = sec.applicant_id
                LEFT JOIN pp.exam_results er ON a.applicant_id = er.applicant_id
                LEFT JOIN pp.student_interview si ON a.applicant_id = si.applicant_id
                WHERE a.nmms_year = :year::numeric
                GROUP BY j.juris_code, j.juris_name
                ORDER BY j.juris_name ASC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            int total = Integer.parseInt((String) row.get("totalShortlisted"));
            int done = Integer.parseInt((String) row.get("evaluated"));
            int progress = total > 0 ? Math.round((done * 100f) / total) : 0;

            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("pendingEvaluation", Integer.parseInt((String) row.get("pendingEvaluation")));
            counts.put("totalInterviewRequired", Integer.parseInt((String) row.get("totalInterviewRequired")));
            counts.put("completedInterview", Integer.parseInt((String) row.get("completedInterview")));

            Map<String, Object> merged = new LinkedHashMap<>(row); // preserves the raw-string top-level fields verbatim
            merged.put("progress", progress);
            merged.put("counts", counts);
            out.add(merged);
        }
        return out;
    }

    /** getOverallProgress parity: q1 has NO shortlisted_yn filter (unlike getOverallCounts's "Shortlisted") -- a
     *  second, deliberately different definition of "shortlisted" (Firm Decision 5c). Only `overallProgress` (a
     *  real int) is returned; the two intermediate counts are never surfaced. */
    public int overallProgress(String year) {
        int totalReq = count("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year);
        int totalDone = count("""
                SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year);
        return totalReq > 0 ? Math.round((totalDone * 100f) / totalReq) : 0;
    }
}
