package com.rcf.imas.modules.merge.persistence;

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
public class MergeReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public MergeReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: numeric/decimal AND bigint → String; timestamp → ISO-Z; text/boolean native. */
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
                    long v = rs.getLong(i);
                    val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i);
                    val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    // ---- 1) /jurisdiction ----
    public List<Map<String, Object>> jurisdictions(String type, String parent) {
        var spec = (parent == null || parent.isBlank())
                ? jdbc.sql("SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = :type ORDER BY juris_name ASC")
                       .param("type", type)
                : jdbc.sql("SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = :type AND parent_juris = :parent::numeric ORDER BY juris_name ASC")
                       .param("type", type).param("parent", parent);
        return spec.query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 6/7) /applications, /results (paginated 50) ----
    public Map<String, Object> stagedPage(String table, String alias, String year, String district, String search, int page) {
        int limit = 50;
        int offset = (page - 1) * limit;
        StringBuilder q = new StringBuilder("SELECT ").append(alias).append(".*, d.juris_name AS district_name, b.juris_name AS nmms_block_name FROM pp.")
                .append(table).append(' ').append(alias)
                .append(" LEFT JOIN pp.jurisdiction d ON ").append(alias).append(".district = d.juris_code")
                .append(" LEFT JOIN pp.jurisdiction b ON ").append(alias).append(".nmms_block = b.juris_code")
                .append(" WHERE ").append(alias).append(".nmms_year = :year AND ").append(alias).append(".district = :district::numeric");
        boolean hasSearch = search != null && !search.isBlank();
        if (hasSearch) q.append(" AND ").append(alias).append(".student_name ILIKE :search");
        q.append(" LIMIT :limit OFFSET :offset");

        var spec = jdbc.sql(q.toString()).param("year", year).param("district", district)
                .param("limit", limit).param("offset", offset);
        if (hasSearch) spec = spec.param("search", "%" + search + "%");
        List<Map<String, Object>> rows = spec.query((rs, i) -> genericRow(rs)).list();

        long count = jdbc.sql("SELECT COUNT(*) FROM pp." + table + " WHERE nmms_year = :year AND district = :district::numeric")
                .param("year", year).param("district", district).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totalPages", (int) Math.ceil((double) count / limit));
        return out;
    }

    // ---- 13) /draft-district-students ----
    public List<Map<String, Object>> draftDistrictStudents(String district, String year) {
        return jdbc.sql("""
                SELECT ROW_NUMBER() OVER (ORDER BY s.student_name) AS sl_no, s.student_name,
                       j1.juris_name AS district_name, j2.juris_name AS block_name,
                       s.current_institute_dise_code, s.nmms_reg_number, s.gmat_score, s.sat_score
                FROM pp.std_applicant_primary_info s
                LEFT JOIN pp.jurisdiction j1 ON s.district = j1.juris_code
                LEFT JOIN pp.jurisdiction j2 ON s.nmms_block = j2.juris_code
                WHERE s.district = :district::numeric AND s.nmms_year = :year::numeric
                ORDER BY s.student_name
                """).param("district", district).param("year", year)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 12) /draft-districts (Number()-coerced ids + counts) ----
    public List<Map<String, Object>> draftDistricts() {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants,
                       COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants
                FROM pp.stg_nmms_phase1_applications s
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m
                  ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged
                ORDER BY j.juris_name
                """).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));
                    d.put("year", rs.getLong("year"));
                    d.put("total_applicants", rs.getLong("total_applicants"));
                    d.put("total_merged_applicants", rs.getLong("total_merged_applicants"));
                    d.put("remaining_applicants", rs.getLong("remaining_applicants"));
                    return d;
                }).list();
    }

    // ---- 17) /merge-status (draftDistricts + ismerged) ----
    public List<Map<String, Object>> mergeStatus(String year) {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants,
                       COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants,
                       CASE WHEN COALESCE(m.total_merged, 0) = COUNT(*) THEN true ELSE false END AS ismerged
                FROM pp.stg_nmms_phase1_applications s
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m
                  ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                WHERE s.nmms_year = :year
                GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged
                ORDER BY j.juris_name
                """).param("year", year).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));
                    d.put("year", rs.getLong("year"));
                    d.put("total_applicants", rs.getLong("total_applicants"));
                    d.put("total_merged_applicants", rs.getLong("total_merged_applicants"));
                    d.put("remaining_applicants", rs.getLong("remaining_applicants"));
                    d.put("ismerged", rs.getBoolean("ismerged"));
                    return d;
                }).list();
    }

    // ---- 16) /commit-status (raw rows → generic mapper) ----
    public List<Map<String, Object>> commitStatus(String year) {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(c.total_committed, 0) AS total_committed,
                       COALESCE(c.total_committed, 0) = COUNT(*) AS is_committed
                FROM pp.stg_nmms_phase1_applications s
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_committed FROM pp.applicant_primary_info GROUP BY district, nmms_year) c
                  ON s.district::numeric = c.district::numeric AND s.nmms_year::text = c.nmms_year::text
                WHERE s.nmms_year = :year
                GROUP BY j.juris_name, s.district, s.nmms_year, c.total_committed
                ORDER BY j.juris_name
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 2) /merged-status (Node bug preserved: SQL selects only student_count; the three totals map to null) ----
    public List<Map<String, Object>> mergedStatus() {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, a.district AS district_id, a.nmms_year AS year,
                       COUNT(a.applicant_id) AS student_count
                FROM pp.applicant_primary_info a
                JOIN pp.jurisdiction j ON a.district = j.juris_code
                GROUP BY j.juris_name, a.district, a.nmms_year
                ORDER BY j.juris_name
                """).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));       // Number(d.district_id)
                    d.put("year", rs.getLong("year"));                     // Number(d.year)
                    // Node maps total_applicants/total_merged_applicants/remaining_applicants from columns the
                    // SQL never selects → Number(undefined) = NaN → JSON null. Preserve exactly.
                    d.put("total_applicants", null);
                    d.put("total_merged_applicants", null);
                    d.put("remaining_applicants", null);
                    return d;
                }).list();
    }

    // ---- 8) /preview-merge raw joined rows (grouping happens in the service) ----
    public List<Map<String, Object>> previewRows(String year, String district) {
        return jdbc.sql("""
                SELECT a.id AS phase1_id, a.student_name, a.father_name, a.students_sats_id,
                       a.contact_no1, a.institute_name, a.nmms_block, j.juris_name AS block_name,
                       r.result_stg_id, r.nmms_reg_number, r.gmat_score, r.sat_score,
                       r.student_name AS result_student_name
                FROM pp.stg_nmms_phase1_applications a
                LEFT JOIN pp.jurisdiction j ON a.nmms_block = j.juris_code
                LEFT JOIN pp.stg_nmms_phase2_results r
                  ON a.student_name_key = r.student_name_key
                  AND a.nmms_block = r.nmms_block
                  AND a.district = r.district
                  AND a.nmms_year = r.nmms_year
                  AND r.match_status IS DISTINCT FROM 'MATCHED'
                WHERE a.nmms_year = :year AND a.district = :district::numeric
                ORDER BY a.student_name ASC
                """).param("year", year).param("district", district)
                .query((rs, i) -> MergeReadRepository.genericRow(rs)).list();
    }

    // ---- 3) district CSV data ----
    public List<Map<String, Object>> districtMergedData(String districtId) {
        return jdbc.sql("""
                SELECT s.student_name, s.father_name, s.nmms_reg_number, s.students_sats_id,
                       d.juris_name AS district_name, b.juris_name AS block_name,
                       s.gmat_score, s.sat_score, s.contact_no1
                FROM pp.std_applicant_primary_info s
                LEFT JOIN pp.jurisdiction d ON s.district = d.juris_code
                LEFT JOIN pp.jurisdiction b ON s.nmms_block = b.juris_code
                WHERE s.district = :district::numeric
                ORDER BY s.student_name
                """).param("district", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 14) delete guards ----
    public boolean stdPrimaryExists(String districtId, String year) {
        return !jdbc.sql("SELECT 1 FROM pp.std_applicant_primary_info WHERE district = :d::numeric AND nmms_year = :y::numeric LIMIT 1")
                .param("d", districtId).param("y", year).query(Integer.class).list().isEmpty();
    }

    public boolean applicantPrimaryExists(String districtId, String year) {
        return !jdbc.sql("SELECT 1 FROM pp.applicant_primary_info WHERE district = :d::numeric AND nmms_year = :y::numeric LIMIT 1")
                .param("d", districtId).param("y", year).query(Integer.class).list().isEmpty();
    }
}
