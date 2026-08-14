package com.rcf.imas.modules.tabinventory.persistence;

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
public class TabInventoryReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcClient jdbc;

    public TabInventoryReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow for this module (house convention, ground truth §5/§7-16, matching
     * CoordinatorReadRepository.genericRow): numeric(x,0) columns (tab_id, student_id, user_id,
     * applicant_id, enr_id) -> String via BigDecimal.toBigInteger().toString(); integer columns
     * (brand_id, cohort_number) pass through natively; bigint COUNT(*) results -> String; DATE ->
     * "yyyy-MM-dd"; TIMESTAMP -> ISO-Z. Package-private static so TabInventoryWriteRepository reuses it.
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
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** getTabStats (ground truth §2.12). All 7 result columns are bigint COUNT(...)/COUNT(...) FILTER
     *  results -> String via genericRow's BIGINT case. */
    public Map<String, Object> tabStats() {
        return jdbc.sql("""
                SELECT
                  COUNT(*) as total,
                  COUNT(*) FILTER (WHERE status = 'IN_OFFICE') as in_office,
                  COUNT(*) FILTER (WHERE status = 'DAMAGED') as damaged,
                  COUNT(*) FILTER (WHERE status = 'LOST') as lost,
                  COUNT(*) FILTER (WHERE status = 'RETURNED') as returned_awaiting,
                  (SELECT COUNT(*) FROM pp.student_issue WHERE return_date IS NULL) as student_assigned,
                  (SELECT COUNT(*) FROM pp.official_issue WHERE return_date IS NULL) as official_assigned
                FROM pp.tab_inventory
                """).query((rs, i) -> genericRow(rs)).single();
    }

    /** getEligibleStudents (ground truth §2.11). */
    public List<Map<String, Object>> eligibleStudents() {
        return jdbc.sql("""
                SELECT s.student_id, s.applicant_id, s.student_name, s.enr_id
                FROM pp.student_master s
                WHERE s.active_yn = 'ACTIVE'
                AND NOT EXISTS (
                    SELECT 1 FROM pp.student_issue si
                    WHERE si.student_id = s.student_id AND si.return_date IS NULL
                )
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllUsers (ground truth §2.5). locked_yn is CHAR(1), nullable, no DEFAULT -- the exact `= 'N'`
     *  comparison is preserved verbatim (three-valued SQL logic silently excludes NULL rows, quirk 11). */
    public List<Map<String, Object>> usersWithoutTab() {
        return jdbc.sql("""
                SELECT user_id, user_name
                FROM pp."user" u
                WHERE locked_yn = 'N'
                AND NOT EXISTS (
                    SELECT 1 FROM pp.official_issue oi
                    WHERE oi.user_id = u.user_id AND oi.return_date IS NULL
                )
                ORDER BY user_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllCohorts (ground truth §2.13). */
    public List<Map<String, Object>> cohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name FROM pp.cohort ORDER BY cohort_name ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllBrands (ground truth §2.1). */
    public List<Map<String, Object>> brands() {
        return jdbc.sql("SELECT brand_id, brand_name, model_name FROM pp.tab_brand ORDER BY brand_name, model_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    private static final String ALL_TABS_SQL = """
            WITH latest_student_assignment AS (
              SELECT
                si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, sm.enr_id, b.batch_name, c.cohort_name,
                ROW_NUMBER() OVER(PARTITION BY si.tab_id ORDER BY si.assignment_date DESC, si.created_at DESC) as rn
              FROM pp.student_issue si
              JOIN pp.student_master sm ON si.student_id = sm.student_id
              LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
              LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            ),
            latest_official_assignment AS (
              SELECT
                oi.tab_id, oi.user_id, oi.assignment_date, oi.return_date, u.user_name as staff_name,
                ROW_NUMBER() OVER(PARTITION BY oi.tab_id ORDER BY oi.assignment_date DESC, oi.created_at DESC) as rn
              FROM pp.official_issue oi
              JOIN pp."user" u ON oi.user_id = u.user_id
            )
            SELECT
              t.tab_id, t.serial_number, t.imei, t.inventory_id, tb.brand_name, tb.model_name AS model,
              t.tab_purchase_date, t.status, t.remarks, t.updated_at,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE COALESCE(sa.student_name, oa.staff_name) END AS assigned_to,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.enr_id END AS enr_id,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.student_name END AS student_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE oa.staff_name END AS staff_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.cohort_name END as cohort_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.batch_name END as batch_name,
              CASE
                WHEN t.status = 'IN_OFFICE' THEN NULL
                WHEN sa.student_id IS NOT NULL AND (sa.assignment_date >= COALESCE(oa.assignment_date, '1970-01-01')) THEN 'STUDENT'
                WHEN oa.user_id IS NOT NULL THEN 'OFFICIAL'
                ELSE NULL
              END AS assignment_category
            FROM pp.tab_inventory t
            LEFT JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
            LEFT JOIN latest_student_assignment sa ON t.tab_id = sa.tab_id AND sa.rn = 1
            LEFT JOIN latest_official_assignment oa ON t.tab_id = oa.tab_id AND oa.rn = 1
            ORDER BY t.created_at DESC
            """;

    /** getAllTabs (ground truth §2.8). NOTE: this CTE's batch/cohort joins are LEFT (unlike
     *  getTabMovementReport's INNER, ground truth §7 quirk 12) -- the two are preserved as-is, not
     *  harmonized. */
    public List<Map<String, Object>> allTabs() {
        return jdbc.sql(ALL_TABS_SQL).query((rs, i) -> genericRow(rs)).list();
    }

    /** getTabById (ground truth §2.7). */
    public Optional<Map<String, Object>> tabById(String tabId) {
        return jdbc.sql("SELECT * FROM pp.tab_inventory WHERE tab_id = :tabId::numeric")
                .param("tabId", tabId).query((rs, i) -> genericRow(rs)).optional();
    }

    /** getTabHistory (ground truth §2.9). student_issue has no remark column (unlike official_issue) --
     *  the literal NULL as staff_remark for the student branch is correct/required, not an oversight
     *  (ground truth §3 note). */
    public List<Map<String, Object>> tabHistory(String tabId) {
        return jdbc.sql("""
                SELECT
                  assignment_date, return_date, sm.student_name as name, sm.enr_id, 'Student' as category, NULL as staff_remark
                FROM pp.student_issue si
                JOIN pp.student_master sm ON si.student_id = sm.student_id
                WHERE si.tab_id = :tabId::numeric
                UNION ALL
                SELECT
                  assignment_date, return_date, u.user_name as name, NULL as enr_id, 'Staff' as category, remark as staff_remark
                FROM pp.official_issue oi
                JOIN pp."user" u ON oi.user_id = u.user_id
                WHERE oi.tab_id = :tabId::numeric
                ORDER BY assignment_date DESC
                """).param("tabId", tabId).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getTabMovementReport (ground truth §2.14, §7 quirk 13). Dynamic WHERE clauses via StringBuilder,
     * values always bound as NAMED params -- never interpolated. "ALL" (or absent/blank) is the
     * Node-equivalent sentinel for "no filter" (model.js:567 `fromCohort && fromCohort !== "ALL"`,
     * :572 same for toCohort). Because JdbcClient uses named params (not node-pg's positional $1/$2),
     * there is no index-shift arithmetic to replicate -- only the conditional clause presence matters.
     * sequential_issues uses INNER JOIN batch/cohort (unlike getAllTabs' LEFT, quirk 12) -- rows with an
     * unresolvable batch/cohort are silently dropped, preserved verbatim.
     */
    public List<Map<String, Object>> movementReport(String fromCohort, String toCohort) {
        StringBuilder sql = new StringBuilder("""
                WITH sequential_issues AS (
                  SELECT
                    si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, c.cohort_name,
                    LEAD(sm.student_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_holder,
                    LEAD(c.cohort_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_cohort,
                    LEAD(si.assignment_date) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as transfer_date
                  FROM pp.student_issue si
                  JOIN pp.student_master sm ON si.student_id = sm.student_id
                  JOIN pp.batch b ON sm.batch_id = b.batch_id
                  JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                )
                SELECT
                  t.serial_number, t.inventory_id, tb.brand_name, tb.model_name as model,
                  si.student_name AS previous_holder, si.cohort_name AS from_cohort,
                  si.next_holder AS new_holder, si.next_cohort AS to_cohort, si.transfer_date AS moved_at
                FROM sequential_issues si
                JOIN pp.tab_inventory t ON si.tab_id = t.tab_id
                JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
                WHERE si.next_cohort IS NOT NULL
                """);

        boolean hasFrom = fromCohort != null && !fromCohort.isEmpty() && !fromCohort.equals("ALL");
        boolean hasTo = toCohort != null && !toCohort.isEmpty() && !toCohort.equals("ALL");
        if (hasFrom) sql.append(" AND si.cohort_name = :fromCohort");
        if (hasTo) sql.append(" AND si.next_cohort = :toCohort");
        sql.append(" ORDER BY si.transfer_date DESC");

        var spec = jdbc.sql(sql.toString());
        if (hasFrom) spec = spec.param("fromCohort", fromCohort);
        if (hasTo) spec = spec.param("toCohort", toCohort);
        return spec.query((rs, i) -> genericRow(rs)).list();
    }
}
