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
public class StudentSearchReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public StudentSearchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as StudentPortalReadRepository (NUMERIC/DECIMAL -> toPlainString(), see Plan 4a
     *  convention #3) -- duplicated per this module's established per-repository house style. */
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

    private static final String DATA_SELECT = """
            SELECT
              sm.student_id, sm.student_name, sm.enr_id, sm.gender,
              b.batch_name, c.cohort_name,
              api.nmms_year, api.nmms_reg_number,
              j_state.juris_name AS state, j_dist.juris_name AS district,
              COALESCE(asi.spl_health_cond, 'N') AS spl_health_cond,
              COALESCE(asi.spl_family_cond, 'N') AS spl_family_cond
            FROM pp.student_master sm
            JOIN pp.batch b ON sm.batch_id = b.batch_id
            JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
            LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
            LEFT JOIN pp.jurisdiction j_state ON api.app_state = j_state.juris_code
            LEFT JOIN pp.jurisdiction j_dist ON api.district = j_dist.juris_code
            WHERE 1=1
            """;

    private static final String COUNT_SELECT = """
            SELECT COUNT(*) AS total
            FROM pp.student_master sm
            JOIN pp.batch b ON sm.batch_id = b.batch_id
            JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
            LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
            WHERE 1=1
            """;

    public record SearchResult(List<Map<String, Object>> rows, long total, int limit, int offset) {}

    /**
     * studentSearchModel.js:searchStudents parity. Dynamic WHERE via StringBuilder + named params (never
     * positional ?, matching the Results module's precedent). Every filter param is JS-truthy-checked in Node
     * (present() below mirrors `if (x)` / `if (x?.trim())`); limit clamped [1,100] default 50, offset clamped
     * >=0 default 0.
     */
    public SearchResult search(String batchId, String cohortNumber, String name, String enrId, String gender,
                                String stateId, String districtId, String blockId,
                                String splHealthCond, String splFamilyCond, Integer limitReq, Integer offsetReq) {
        int limit = Math.min(Math.max(limitReq == null ? 50 : limitReq, 1), 100);
        int offset = Math.max(offsetReq == null ? 0 : offsetReq, 0);

        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (present(batchId))       { where.append(" AND sm.batch_id = :batchId::numeric"); params.put("batchId", batchId); }
        if (present(cohortNumber))  { where.append(" AND c.cohort_number = :cohortNumber::numeric"); params.put("cohortNumber", cohortNumber); }
        if (present(name))          { where.append(" AND sm.student_name ILIKE :name"); params.put("name", "%" + name.trim() + "%"); }
        if (present(enrId))         { where.append(" AND CAST(sm.enr_id AS TEXT) ILIKE :enrId"); params.put("enrId", "%" + enrId.trim() + "%"); }
        if (present(gender))        { where.append(" AND UPPER(sm.gender) = :gender"); params.put("gender", gender.trim().toUpperCase()); }
        if (present(stateId))       { where.append(" AND api.app_state = :stateId::numeric"); params.put("stateId", stateId); }
        if (present(districtId))    { where.append(" AND api.district = :districtId::numeric"); params.put("districtId", districtId); }
        if (present(blockId))       { where.append(" AND api.nmms_block = :blockId::numeric"); params.put("blockId", blockId); }
        if (present(splHealthCond)) { where.append(" AND COALESCE(asi.spl_health_cond, 'N') = :splHealthCond"); params.put("splHealthCond", splHealthCond); }
        if (present(splFamilyCond)) { where.append(" AND COALESCE(asi.spl_family_cond, 'N') = :splFamilyCond"); params.put("splFamilyCond", splFamilyCond); }

        var dataQuery = jdbc.sql(DATA_SELECT + where + " ORDER BY sm.student_name ASC LIMIT :limit OFFSET :offset");
        var countQuery = jdbc.sql(COUNT_SELECT + where);
        for (var e : params.entrySet()) {
            dataQuery = dataQuery.param(e.getKey(), e.getValue());
            countQuery = countQuery.param(e.getKey(), e.getValue());
        }
        dataQuery = dataQuery.param("limit", limit).param("offset", offset);

        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        long total = countQuery.query(Long.class).single();
        return new SearchResult(rows, total, limit, offset);
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    /**
     * Explicit column list -- EXCLUDES student_email_password (firm decision: never return credentials, even
     * to ADMIN). Node's raw `SELECT *` is intentionally NOT reproduced verbatim here.
     */
    public Optional<Map<String, Object>> byId(String studentId) {
        return jdbc.sql("""
                SELECT
                  student_id, applicant_id, enr_id, student_name, father_name, father_occupation,
                  mother_name, mother_occupation, gender, batch_id, sim_name, student_email, parent_email,
                  photo_link, home_address, contact_no1, contact_no2,
                  current_institute_dise_code, previous_institute_dise_code,
                  active_yn, recharge_status, sponsor, teacher_name, teacher_mobile_number,
                  created_at, updated_at, created_by, updated_by, user_id
                FROM pp.student_master
                WHERE student_id = :studentId::numeric
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).optional();
    }
}
