package com.rcf.imas.modules.shortlist.persistence;

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
public class ShortlistReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public ShortlistReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity for simple lists: integer numerics + bigint → String; timestamp → ISO-Z; boolean/text native.
     *  NOTE: not for fractional numerics (e.g. weighted_score) — those use an explicit toPlainString mapper. */
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
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    public List<Map<String, Object>> allStates() {
        return jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> divisionsByState(String stateName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction AS division
                WHERE division.parent_juris IN (
                    SELECT state.juris_code FROM pp.jurisdiction AS state
                    WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:name)))
                  AND LOWER(division.juris_type) = 'division'
                """).param("name", stateName).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> districtsByDivision(String divisionName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction AS district
                WHERE district.parent_juris IN (
                    SELECT division.juris_code FROM pp.jurisdiction AS division
                    WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:name)))
                  AND LOWER(district.juris_type) = 'education district'
                """).param("name", divisionName).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String stateName, String divisionName, String districtName, String year) {
        return jdbc.sql("""
                SELECT j.juris_code, j.juris_name,
                    CASE WHEN j.juris_code IN (
                        SELECT sbj.juris_code FROM pp.shortlist_batch_jurisdiction AS sbj
                        JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                        WHERE sb.frozen_yn = 'Y' AND sb.shortlisted_year = :year::numeric)
                    THEN TRUE ELSE FALSE END AS is_frozen_block
                FROM pp.jurisdiction AS j
                WHERE LOWER(j.juris_type) = 'block' AND j.parent_juris IN (
                    SELECT district.juris_code FROM pp.jurisdiction AS district
                    WHERE LOWER(TRIM(district.juris_name)) = LOWER(TRIM(:district))
                      AND LOWER(district.juris_type) = 'education district'
                      AND district.parent_juris IN (
                        SELECT division.juris_code FROM pp.jurisdiction AS division
                        WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:division))
                          AND LOWER(division.juris_type) = 'division'
                          AND division.parent_juris IN (
                            SELECT state.juris_code FROM pp.jurisdiction AS state
                            WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:state))
                              AND LOWER(state.juris_type) = 'state')))
                """).param("state", stateName).param("division", divisionName)
                .param("district", districtName).param("year", year)
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> criteria() {
        return jdbc.sql("SELECT criteria_id, criteria FROM pp.shortlist_criteria")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public String criteriaText(String criteriaId) {
        return jdbc.sql("SELECT criteria FROM pp.shortlist_criteria WHERE criteria_id = :id::numeric")
                .param("id", criteriaId).query(String.class).optional().orElse(null);
    }

    /** Node totalPopRes: COUNT over the year's applicants in the named blocks (returns String, parity). */
    public String totalApplicantsInBlocks(List<String> blockNamesLower, String year) {
        return jdbc.sql("""
                SELECT COUNT(api.applicant_id) FROM pp.applicant_primary_info api
                WHERE api.nmms_year = :year::numeric AND api.nmms_block IN (
                    SELECT j.juris_code FROM pp.jurisdiction j
                    WHERE LOWER(TRIM(j.juris_name)) = ANY(:blocks) AND LOWER(j.juris_type) = 'block')
                """).param("year", year).param("blocks", blockNamesLower.toArray(new String[0]))
                .query(Long.class).single().toString();
    }

    /** Node getShortlistedCountForBlocksAndYear (returns String, parity). */
    public String shortlistedCountForBlocks(List<String> blockNamesLower, String year) {
        return jdbc.sql("""
                SELECT COUNT(asi.applicant_id) FROM pp.applicant_shortlist_info asi
                WHERE asi.shortlisted_yn = 'Y' AND asi.applicant_id IN (
                    SELECT api.applicant_id FROM pp.applicant_primary_info api
                    WHERE api.nmms_year = :year::numeric AND api.nmms_block IN (
                        SELECT j.juris_code FROM pp.jurisdiction j
                        WHERE LOWER(TRIM(j.juris_name)) = ANY(:blocks) AND LOWER(j.juris_type) = 'block'))
                """).param("year", year).param("blocks", blockNamesLower.toArray(new String[0]))
                .query(Long.class).single().toString();
    }

    public List<String> shortlistNames(String year) {
        return jdbc.sql("SELECT shortlist_batch_name FROM pp.shortlist_batch WHERE shortlisted_year = :year::numeric")
                .param("year", year).query(String.class).list();
    }

    public List<Map<String, Object>> nonFrozenNames(String year) {
        return jdbc.sql("SELECT shortlist_batch_name, shortlist_batch_id FROM pp.shortlist_batch WHERE shortlisted_year = :year::numeric AND frozen_yn = 'N'")
                .param("year", year).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("shortlist_batch_name"));
                    m.put("id", rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString());
                    return m;
                }).list();
    }

    public int totalApplicantCount(String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric")
                .param("year", year).query(Integer.class).single();
    }

    public int totalShortlistedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info asi
                JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                JOIN pp.shortlist_batch sb ON asi.shortlist_batch_id = sb.shortlist_batch_id
                WHERE api.nmms_year = :year::numeric AND asi.shortlisted_yn = 'Y' AND sb.frozen_yn = 'Y'
                """).param("year", year).query(Integer.class).single();
    }

    /** getShortlistInfo parity. Returns null if the batch name+year doesn't exist. id kept as String (node-pg numeric). */
    public Map<String, Object> shortlistInfo(String name, String year) {
        Map<String, Object> head = jdbc.sql("""
                SELECT shortlist_batch_id, description, criteria_id, shortlist_batch_name, frozen_yn
                FROM pp.shortlist_batch WHERE shortlist_batch_name = :name AND shortlisted_year = :year::numeric
                """).param("name", name).param("year", year).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString());
                    m.put("description", rs.getString("description"));
                    m.put("criteria_id", rs.getBigDecimal("criteria_id") == null ? null : rs.getBigDecimal("criteria_id").toBigInteger().toString());
                    m.put("name", rs.getString("shortlist_batch_name"));
                    m.put("frozen_yn", rs.getString("frozen_yn"));
                    return m;
                }).optional().orElse(null);
        if (head == null) return null;

        String id = (String) head.get("id");
        String criteriaId = (String) head.get("criteria_id");
        String criteria = criteriaId == null ? "N/A" :
                jdbc.sql("SELECT criteria FROM pp.shortlist_criteria WHERE criteria_id = :id::numeric")
                        .param("id", criteriaId).query(String.class).optional().orElse("N/A");
        List<String> blocks = jdbc.sql("""
                SELECT j.juris_name FROM pp.jurisdiction j
                JOIN pp.shortlist_batch_jurisdiction sbj ON j.juris_code = sbj.juris_code
                WHERE sbj.shortlist_batch_id = :id::numeric
                """).param("id", id).query(String.class).list();
        int totalStudents = jdbc.sql("""
                SELECT COUNT(*) FROM pp.applicant_primary_info
                WHERE nmms_year = :year::numeric AND nmms_block IN (SELECT juris_code FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = :id::numeric)
                """).param("year", year).param("id", id).query(Integer.class).single();
        int shortlistedCount = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric")
                .param("id", id).query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", head.get("name"));
        out.put("description", head.get("description"));
        out.put("criteria", criteria);
        out.put("blocks", blocks);
        out.put("totalStudents", totalStudents);
        out.put("shortlistedCount", shortlistedCount);
        out.put("isFrozen", "Y".equals(head.get("frozen_yn")) ? "Yes" : "No");
        return out;
    }

    public List<Map<String, Object>> showData(String batchId) {
        return jdbc.sql("""
                SELECT api.applicant_id, api.nmms_reg_number, api.nmms_block, api.student_name,
                       api.gmat_score, api.sat_score, api.medium,
                       (api.gmat_score * 0.70 + api.sat_score * 0.30) AS weighted_score
                FROM pp.applicant_primary_info api
                WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric)
                ORDER BY api.student_name ASC
                """).param("id", batchId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("applicant_id", numStr(rs.getBigDecimal("applicant_id")));
                    m.put("nmms_reg_number", numStr(rs.getBigDecimal("nmms_reg_number")));
                    m.put("nmms_block", numStr(rs.getBigDecimal("nmms_block")));
                    m.put("student_name", rs.getString("student_name"));
                    m.put("gmat_score", numStr(rs.getBigDecimal("gmat_score")));
                    m.put("sat_score", numStr(rs.getBigDecimal("sat_score")));
                    m.put("medium", rs.getString("medium"));
                    java.math.BigDecimal ws = rs.getBigDecimal("weighted_score");
                    m.put("weighted_score", ws == null ? null : ws.toPlainString());   // decimal preserved
                    return m;
                }).list();
    }

    private static String numStr(java.math.BigDecimal bd) { return bd == null ? null : bd.toBigInteger().toString(); }

    public List<Map<String, Object>> invalidMediumStudents(String batchId, List<String> allowedMediums) {
        return jdbc.sql("""
                SELECT api.applicant_id, api.student_name, inst.institute_name, inst.dise_code,
                       api.contact_no1, api.contact_no2, api.medium AS selected_medium,
                       (SELECT ARRAY_AGG(DISTINCT m.medium) FROM pp.institute_medium m WHERE m.dise_code = inst.dise_code) AS supported_mediums
                FROM pp.applicant_primary_info api
                JOIN pp.applicant_shortlist_info asi ON api.applicant_id = asi.applicant_id
                JOIN pp.institute inst ON api.current_institute_dise_code = inst.dise_code
                WHERE asi.shortlist_batch_id = :batch::numeric
                  AND ( (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) > 1
                        OR (api.medium IS NULL OR api.medium = '' OR api.medium != ANY(:allowed)) )
                  AND NOT ( (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) = 1
                            AND api.medium = ANY(:allowed) )
                GROUP BY api.applicant_id, api.student_name, inst.institute_name, inst.dise_code, api.contact_no1, api.contact_no2, api.medium
                ORDER BY inst.institute_name
                """).param("batch", batchId).param("allowed", allowedMediums.toArray(new String[0]))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("applicant_id", rs.getBigDecimal("applicant_id").toBigInteger().toString());
                    m.put("student_name", rs.getString("student_name"));
                    m.put("institute_name", rs.getString("institute_name"));
                    m.put("dise_code", rs.getString("dise_code"));
                    m.put("contact_no1", rs.getString("contact_no1"));
                    m.put("contact_no2", rs.getString("contact_no2"));
                    m.put("selected_medium", rs.getString("selected_medium"));
                    java.sql.Array arr = rs.getArray("supported_mediums");
                    m.put("supported_mediums", arr == null ? null : java.util.Arrays.asList((Object[]) arr.getArray()));
                    return m;
                }).list();
    }

    public int shortlistedCountInBatch(String batchId) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :id::numeric AND shortlisted_yn = 'Y'")
                .param("id", batchId).query(Integer.class).single();
    }

    /** Download rows in the exact column order (values only; the controller/XlsxSupport supplies headers incl. "S. No."). */
    public List<Map<String, Object>> downloadRows(String batchId) {
        return jdbc.sql("""
                SELECT api.nmms_reg_number AS "NMMS Registration No", api.student_name AS "Student Name",
                       api.contact_no1 AS "Contact No 1", cur_inst.institute_name AS "Current School Name",
                       api.medium AS "Medium", d.juris_name AS "District", b.juris_name AS "Block",
                       api.gmat_score AS "GMAT Score", api.sat_score AS "SAT Score"
                FROM pp.applicant_primary_info api
                LEFT JOIN pp.institute cur_inst ON api.current_institute_dise_code = cur_inst.dise_code
                LEFT JOIN pp.jurisdiction d ON api.district = d.juris_code
                LEFT JOIN pp.jurisdiction b ON api.nmms_block = b.juris_code
                WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric)
                ORDER BY api.student_name ASC
                """).param("id", batchId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("NMMS Registration No", numStr(rs.getBigDecimal("NMMS Registration No")));
                    m.put("Student Name", rs.getString("Student Name"));
                    m.put("Contact No 1", rs.getString("Contact No 1"));
                    m.put("Current School Name", rs.getString("Current School Name"));
                    m.put("Medium", rs.getString("Medium"));
                    m.put("District", rs.getString("District"));
                    m.put("Block", rs.getString("Block"));
                    m.put("GMAT Score", numStr(rs.getBigDecimal("GMAT Score")));
                    m.put("SAT Score", numStr(rs.getBigDecimal("SAT Score")));
                    return m;
                }).list();
    }
}
