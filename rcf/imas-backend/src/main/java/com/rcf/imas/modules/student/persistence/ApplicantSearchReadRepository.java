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

@Repository
public class ApplicantSearchReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ApplicantSearchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as the other two repositories in this module (see Plan 4a convention #3). */
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

    private static final String BASE_FROM = """
            FROM pp.applicant_primary_info a
            LEFT JOIN pp.institute i ON a.current_institute_dise_code = i.dise_code
            LEFT JOIN pp.jurisdiction js ON a.app_state = js.juris_code
            LEFT JOIN pp.jurisdiction jd ON a.district = jd.juris_code
            LEFT JOIN pp.jurisdiction jb ON a.nmms_block = jb.juris_code
            WHERE 1=1
            """;

    private static final String COUNT_SELECT = "SELECT COUNT(*) " + BASE_FROM;

    private static final String DATA_SELECT = """
            SELECT a.*, i.institute_name,
              js.juris_name AS state_name, jd.juris_name AS district_name, jb.juris_name AS block_name
            """ + BASE_FROM;

    public record SearchResult(List<Map<String, Object>> rows, long totalCount) {}

    /** nmms_reg_number branch: ignores every other filter, matching searchModel.js's if/else split verbatim. */
    public SearchResult searchByRegNumber(String regNumber, ApplicantSortField sortField, String sortOrder,
                                           int limit, int offset) {
        String where = " AND a.nmms_reg_number = :regNumber::numeric";
        long total = jdbc.sql(COUNT_SELECT + where).param("regNumber", regNumber).query(Long.class).single();
        if (total == 0) return new SearchResult(List.of(), 0);   // Node skips the data query entirely when total=0

        List<Map<String, Object>> rows = jdbc.sql(DATA_SELECT + where + orderByLimitOffset(sortField, sortOrder))
                .param("regNumber", regNumber).param("limit", limit).param("offset", offset)
                .query((rs, i) -> genericRow(rs)).list();
        return new SearchResult(rows, total);
    }

    public SearchResult search(String studentName, String nmmsYear, String medium, String appState, String district,
                                String nmmsBlock, String diseCode, ApplicantSortField sortField, String sortOrder,
                                int limit, int offset) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (present(studentName)) { where.append(" AND a.student_name ILIKE :studentName"); params.put("studentName", "%" + studentName + "%"); }
        if (present(nmmsYear))    { where.append(" AND a.nmms_year = :nmmsYear::numeric"); params.put("nmmsYear", nmmsYear); }
        if (present(medium))      { where.append(" AND UPPER(a.medium) = :medium"); params.put("medium", medium.trim().toUpperCase()); }
        if (present(appState))    { where.append(" AND a.app_state = :appState::numeric"); params.put("appState", appState.trim()); }
        if (present(district))    { where.append(" AND a.district = :district::numeric"); params.put("district", district.trim()); }
        if (present(nmmsBlock))   { where.append(" AND a.nmms_block = :nmmsBlock::numeric"); params.put("nmmsBlock", nmmsBlock.trim()); }
        if (present(diseCode))    { where.append(" AND a.current_institute_dise_code = :diseCode"); params.put("diseCode", diseCode.trim()); }

        var countQuery = jdbc.sql(COUNT_SELECT + where);
        for (var e : params.entrySet()) countQuery = countQuery.param(e.getKey(), e.getValue());
        long total = countQuery.query(Long.class).single();
        if (total == 0) return new SearchResult(List.of(), 0);   // Node skips the data query entirely when total=0

        var dataQuery = jdbc.sql(DATA_SELECT + where + orderByLimitOffset(sortField, sortOrder));
        for (var e : params.entrySet()) dataQuery = dataQuery.param(e.getKey(), e.getValue());
        dataQuery = dataQuery.param("limit", limit).param("offset", offset);
        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        return new SearchResult(rows, total);
    }

    /** sortField.column comes ONLY from the closed enum (never request-concatenated); sortOrder is
     *  pre-validated by the controller to exactly "ASC"/"DESC" -- safe to concatenate both. */
    private static String orderByLimitOffset(ApplicantSortField sortField, String sortOrder) {
        return " ORDER BY a." + sortField.column + " " + sortOrder + " LIMIT :limit OFFSET :offset";
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    public List<Map<String, Object>> allCohorts() {
        return jdbc.sql("SELECT * FROM pp.cohort ORDER BY cohort_number ASC").query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> batchesByCohort(String cohortNumber) {
        return jdbc.sql("SELECT * FROM pp.batch WHERE cohort_number = :cohortNumber::integer ORDER BY batch_id ASC")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }
}
