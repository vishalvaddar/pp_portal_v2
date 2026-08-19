package com.rcf.imas.modules.evaluation.persistence;

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
public class EvaluationReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public EvaluationReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: NUMERIC/BIGINT -> String; DATE -> "yyyy-MM-dd"; TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z; else passthrough.
     *  Map keys are the column label verbatim (handles sm.* dynamic column sets and camelCase SQL aliases unchanged). */
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

    public List<Map<String, Object>> allLists() {
        return jdbc.sql("""
                SELECT cl.list_id, cl.list_name, COUNT(cls.student_id) AS student_count
                FROM pp.custom_list cl
                LEFT JOIN pp.custom_list_students cls ON cl.list_id = cls.list_id
                GROUP BY cl.list_id, cl.list_name
                ORDER BY cl.list_id DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** cohortId: skip filter if null/blank/"null"/"undefined" (Node's addFilter-equivalent check for this one param). */
    public List<Map<String, Object>> allBatches(String cohortId) {
        boolean has = cohortId != null && !cohortId.isBlank() && !"null".equals(cohortId) && !"undefined".equals(cohortId);
        String sql = "SELECT b.batch_id, b.batch_name, c.cohort_name FROM pp.batch b JOIN pp.cohort c ON b.cohort_number = c.cohort_number"
                + (has ? " WHERE b.cohort_number = :cohortId::numeric" : "") + " ORDER BY b.batch_name";
        var q = jdbc.sql(sql);
        if (has) q = q.param("cohortId", cohortId);
        return q.query((rs, i) -> genericRow(rs)).list();
    }

    /** getAvailableFields parity: LIVE information_schema introspection, not a static field list (Firm Decision 2). */
    public List<Map<String, Object>> availableFields() {
        return jdbc.sql("""
                SELECT
                    column_name AS col_name,
                    CASE
                        WHEN column_name = 'batch_id' THEN 'Batch Name'
                        WHEN column_name = 'current_institute_dise_code' THEN 'Current School Name'
                        WHEN column_name = 'previous_institute_dise_code' THEN 'Previous School Name'
                        WHEN column_name = 'active_yn' THEN 'Active Status'
                        WHEN column_name = 'contact_no1' THEN 'Contact Number 1'
                        WHEN column_name = 'contact_no2' THEN 'Contact Number 2'
                        WHEN column_name = 'enr_id' THEN 'Enrollment Id'
                        ELSE INITCAP(REPLACE(column_name, '_', ' '))
                    END AS display_name
                FROM information_schema.columns
                WHERE table_schema = 'pp' AND table_name = 'student_master'
                  AND column_name NOT IN ('created_at','updated_at','created_by','updated_by','applicant_id','photo_link','student_id')
                UNION ALL
                SELECT
                    column_name AS col_name,
                    CASE
                        WHEN column_name = 'district' THEN 'District'
                        WHEN column_name = 'nmms_block' THEN 'Block'
                    END AS display_name
                FROM information_schema.columns
                WHERE table_schema = 'pp' AND table_name = 'applicant_primary_info'
                  AND column_name IN ('district','nmms_block')
                ORDER BY display_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> studentsByList(String listId) {
        return jdbc.sql("""
                SELECT
                    sm.*,
                    batch.batch_name,
                    inst_curr.institute_name AS current_institute_name,
                    inst_prev.institute_name AS previous_institute_name,
                    dist.juris_name AS district,
                    blk.juris_name AS block
                FROM pp.custom_list_students cls
                JOIN pp.student_master sm ON cls.student_id = sm.student_id
                LEFT JOIN pp.batch batch ON batch.batch_id = sm.batch_id
                LEFT JOIN pp.institute inst_curr ON sm.current_institute_dise_code = inst_curr.dise_code
                LEFT JOIN pp.institute inst_prev ON sm.previous_institute_dise_code = inst_prev.dise_code
                LEFT JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                LEFT JOIN pp.jurisdiction dist ON api.district = dist.juris_code
                LEFT JOIN pp.jurisdiction blk ON api.nmms_block = blk.juris_code
                WHERE cls.list_id = :listId::numeric
                ORDER BY sm.student_name
                """).param("listId", listId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> fieldsForList(String listId) {
        return jdbc.sql("""
                SELECT
                    fm.col_name, fm.field_id,
                    CASE
                        WHEN fm.col_name = 'batch_id' THEN 'Batch Name'
                        WHEN fm.col_name = 'current_institute_dise_code' THEN 'Current School Name'
                        WHEN fm.col_name = 'previous_institute_dise_code' THEN 'Previous School Name'
                        WHEN fm.col_name = 'active_yn' THEN 'Active Status'
                        WHEN fm.col_name = 'contact_no1' THEN 'Contact Number 1'
                        WHEN fm.col_name = 'contact_no2' THEN 'Contact Number 2'
                        WHEN fm.col_name = 'enr_id' THEN 'Enrollment Id'
                        ELSE INITCAP(REPLACE(fm.col_name, '_', ' '))
                    END as display_name
                FROM pp.custom_list_fields clf
                JOIN pp.field_master fm ON clf.field_id = fm.field_id
                WHERE clf.list_id = :listId::numeric
                """).param("listId", listId).query((rs, i) -> genericRow(rs)).list();
    }

    public String listName(String listId) {
        return jdbc.sql("SELECT list_name FROM pp.custom_list WHERE list_id = :listId::numeric")
                .param("listId", listId).query(String.class).optional().orElse(null);
    }

    /**
     * getStudentsByCohort parity. `nmms_year = 2025` is a hard-coded SQL literal (Firm Decision 5d), NOT parameterized.
     * `divisionId` is intentionally not a method parameter here at all (Firm Decision 5e) -- the controller reads it
     * off the request but never passes it through, matching Node's silent no-op.
     */
    public List<Map<String, Object>> studentsByCohort(String cohortId, String batchId, String stateId,
                                                       String districtId, String blockId) {
        StringBuilder sql = new StringBuilder("""
                SELECT sm.student_id, sm.student_name, b.batch_name, api.gender
                FROM pp.student_master sm
                JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
                WHERE sm.active_yn = 'ACTIVE'
                  AND api.nmms_year = 2025
                """);
        java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
        addFilter(filters, "cohortId", cohortId);
        addFilter(filters, "batchId", batchId);
        addFilter(filters, "stateId", stateId);
        addFilter(filters, "districtId", districtId);
        addFilter(filters, "blockId", blockId);
        if (filters.containsKey("cohortId")) sql.append(" AND b.cohort_number = :cohortId::numeric");
        if (filters.containsKey("batchId")) sql.append(" AND sm.batch_id = :batchId::numeric");
        if (filters.containsKey("stateId")) sql.append(" AND api.app_state = :stateId::numeric");
        if (filters.containsKey("districtId")) sql.append(" AND api.district = :districtId::numeric");
        if (filters.containsKey("blockId")) sql.append(" AND api.nmms_block = :blockId::numeric");
        sql.append(" ORDER BY sm.student_name");

        var query = jdbc.sql(sql.toString());
        for (var e : filters.entrySet()) query = query.param(e.getKey(), e.getValue());
        return query.query((rs, i) -> genericRow(rs)).list();
    }

    /** Node's addFilter: skip null/blank/"all"/"null"/"undefined". */
    private static void addFilter(Map<String, String> out, String key, String val) {
        if (val != null && !val.isBlank() && !"all".equals(val) && !"null".equals(val) && !"undefined".equals(val)) {
            out.put(key, val);
        }
    }

    public List<Map<String, Object>> examNames(String yearPrefixLike) {
        return jdbc.sql("SELECT exam_name FROM pp.examination WHERE exam_year LIKE :prefix ORDER BY exam_id ASC")
                .param("prefix", yearPrefixLike).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getStudents parity (evaluationModels.js). BUG PRESERVED VERBATIM: er/aea are LEFT-JOINed on asi.applicant_id
     * (applicant_secondary_info), NOT api.applicant_id (applicant_primary_info) -- an applicant with exam results
     * but no secondary-info row shows NULL exam columns even though matching rows exist elsewhere. Do NOT fix.
     */
    public List<Map<String, Object>> studentsForExam(String examName) {
        return jdbc.sql("""
                SELECT
                    api.applicant_id, api.student_name, api.father_name, api.mother_name,
                    asi.village, api.gender, api.aadhaar, api.dob, api.medium, api.home_address,
                    api.family_income_total,
                    asi.father_occupation, asi.mother_occupation, asi.father_education, asi.mother_education,
                    asi.household_size, asi.own_house, asi.smart_phone_home, asi.internet_facility_home,
                    asi.career_goals, asi.subjects_of_interest, asi.transportation_mode, asi.distance_to_school,
                    asi.num_two_wheelers, asi.num_four_wheelers, asi.irrigation_land,
                    asi.neighbor_name, asi.neighbor_phone, asi.favorite_teacher_name, asi.favorite_teacher_phone,
                    aea.pp_exam_appeared_yn,
                    er.pp_exam_score, er.pp_exam_cleared, er.interview_required_yn
                FROM pp.examination ex
                LEFT JOIN pp.applicant_exam ae ON ae.exam_id = ex.exam_id
                LEFT JOIN pp.applicant_primary_info api ON api.applicant_id = ae.applicant_id
                LEFT JOIN pp.applicant_secondary_info asi ON api.applicant_id = asi.applicant_id
                LEFT JOIN pp.exam_results er ON asi.applicant_id = er.applicant_id
                LEFT JOIN pp.applicant_exam_attendance aea ON aea.applicant_id = asi.applicant_id
                WHERE ex.exam_name = :examName
                """).param("examName", examName).query((rs, i) -> genericRow(rs)).list();
    }
}
