package com.rcf.imas.modules.exams.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ExamsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ExamsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: NUMERIC/BIGINT -> String; DATE -> "yyyy-MM-dd"; TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z;
     *  Postgres ARRAY (e.g. ARRAY_AGG(juris_code)/ARRAY_AGG(juris_name) in /assigned) -> List&lt;String&gt;, each
     *  element following the same NUMERIC->String / text->passthrough rule; else passthrough. Map keys are the
     *  column label verbatim (handles the query's own AS aliases unchanged). */
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
                case java.sql.Types.ARRAY -> {
                    Array arr = rs.getArray(i);
                    val = arr == null ? null : arrayToStringList(arr);
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    private static List<String> arrayToStringList(Array arr) throws SQLException {
        Object raw = arr.getArray();
        List<String> out = new ArrayList<>();
        int len = java.lang.reflect.Array.getLength(raw);
        for (int i = 0; i < len; i++) {
            Object el = java.lang.reflect.Array.get(raw, i);
            if (el == null) { out.add(null); }
            else if (el instanceof BigDecimal bd) { out.add(bd.toBigInteger().toString()); }
            else { out.add(String.valueOf(el)); }
        }
        return out;
    }

    /** getExamCentres() parity: active-only, id+name projection. */
    public List<Map<String, Object>> activeCentres() {
        return jdbc.sql("""
                SELECT pp_exam_centre_id, pp_exam_centre_name
                FROM pp.pp_exam_centre
                WHERE active_yn = 'Y'
                ORDER BY pp_exam_centre_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getexamcentresview() parity: every column, every row (active + inactive). Firm Decision 2: unlike Node,
     *  a DB failure here surfaces as a real 500 via GlobalExceptionHandler, not a hang. */
    public List<Map<String, Object>> allCentresAllColumns() {
        return jdbc.sql("SELECT * FROM pp.pp_exam_centre").query((rs, i) -> genericRow(rs)).list();
    }

    /** checkExistingCentre() parity: pre-insert dup guard, code OR name OR phone OR email match. */
    public Map<String, Object> findExistingCentre(String code, String name, String phone, String email) {
        return jdbc.sql("""
                SELECT * FROM pp.pp_exam_centre
                WHERE pp_exam_centre_code = :code
                   OR pp_exam_centre_name = :name
                   OR contact_phone = :phone
                   OR contact_email = :email
                LIMIT 1
                """).param("code", code).param("name", name).param("phone", phone).param("email", email)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** deleteExamCentre's usage guard: any exam already referencing this centre. */
    public String examNameUsingCentre(String centreId) {
        return jdbc.sql("SELECT exam_name FROM pp.examination WHERE pp_exam_centre_id = :id::numeric LIMIT 1")
                .param("id", centreId).query(String.class).optional().orElse(null);
    }

    public List<Map<String, Object>> divisionsByState(String stateId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'DIVISION' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", stateId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> educationDistrictsByDivision(String divisionId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", divisionId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String districtId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'BLOCK' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> clustersByBlock(String blockId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'CLUSTER' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", blockId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getUsedBlocks() parity: e.exam_year is varchar, no cast. Firm Decision 6: returns real Long values (JSON
     *  numbers), NOT the genericRow String convention -- the one deliberate exception in this module because the
     *  frontend does `usedBlocks.includes(Number(b.id))`. */
    public List<Long> usedBlocks(String year) {
        return jdbc.sql("""
                SELECT DISTINCT api.nmms_block
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
                INNER JOIN pp.examination e ON ae.exam_id = e.exam_id
                WHERE e.exam_year = :year
                """).param("year", year).query(Long.class).list();
    }

    /** getAllExams(year) parity: exam_year is varchar, plain string equality, no cast. INNER JOINs on
     *  applicant_exam/applicant_primary_info mean a zero-applicant exam is excluded entirely -- this is how
     *  /assigned and /notassigned partition. ARRAY_AGG columns come back via genericRow's new ARRAY case. */
    public List<Map<String, Object>> assignedExams(String year) {
        return jdbc.sql("""
                SELECT
                  e.exam_id, e.exam_name, e.exam_date, e.frozen_yn, e.pp_exam_centre_id,
                  c.pp_exam_centre_name, e.exam_start_time, e.exam_end_time,
                  ARRAY_AGG(DISTINCT jd.juris_code) AS district_ids,
                  ARRAY_AGG(DISTINCT jd.juris_name) AS district_names,
                  ARRAY_AGG(DISTINCT jb.juris_code) AS block_ids,
                  ARRAY_AGG(DISTINCT jb.juris_name) AS block_names
                FROM pp.examination e
                LEFT JOIN pp.pp_exam_centre c ON e.pp_exam_centre_id = c.pp_exam_centre_id
                JOIN pp.applicant_exam ae ON ae.exam_id = e.exam_id
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                LEFT JOIN pp.jurisdiction jd ON api.district = jd.juris_code
                LEFT JOIN pp.jurisdiction jb ON api.nmms_block = jb.juris_code
                WHERE e.exam_year = :year
                GROUP BY e.exam_id, e.exam_name, e.exam_date, e.pp_exam_centre_id, c.pp_exam_centre_name
                ORDER BY e.exam_date DESC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllExamsnotassigned(year) parity: NOT EXISTS complement of assignedExams. */
    public List<Map<String, Object>> notAssignedExams(String year) {
        return jdbc.sql("""
                SELECT e.exam_id, e.exam_name, e.exam_date, e.frozen_yn, e.pp_exam_centre_id,
                       c.pp_exam_centre_name, e.exam_start_time, e.exam_end_time
                FROM pp.examination e
                LEFT JOIN pp.pp_exam_centre c ON e.pp_exam_centre_id = c.pp_exam_centre_id
                WHERE e.exam_year = :year
                  AND NOT EXISTS (SELECT 1 FROM pp.applicant_exam ae WHERE ae.exam_id = e.exam_id)
                ORDER BY e.exam_date DESC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /** generateStudentList's query parity. sl_no (ROW_NUMBER) is fetched but NOT used for the "Sl. No." column --
     *  Node re-derives it from the JS array index during mapping (`result.rows.map((row, index) => [index+1, ...])`),
     *  which is equivalent here since the query is already ORDER BY api.student_name (same order the index walks). */
    public List<Map<String, Object>> studentListRows(String examId) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, i.dise_code, i.institute_name,
                  api.contact_no1, api.contact_no2, ee.exam_name, ee.exam_date,
                  api.gmat_score, api.sat_score, ee.exam_start_time, ee.exam_end_time,
                  ec.pp_exam_centre_name, api.nmms_reg_number, ec.contact_person,
                  j.juris_name AS block_name,
                  ROW_NUMBER() OVER (ORDER BY api.student_name) AS sl_no
                FROM pp.examination ee
                JOIN pp.applicant_exam ae ON ee.exam_id = ae.exam_id
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.pp_exam_centre ec ON ee.pp_exam_centre_id = ec.pp_exam_centre_id
                LEFT JOIN pp.institute i ON api.current_institute_dise_code = i.dise_code
                LEFT JOIN pp.jurisdiction j ON api.nmms_block = j.juris_code
                WHERE ae.exam_id = :examId::numeric
                ORDER BY api.student_name
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }

    /** singlestudentdownloadhallticket() parity. */
    public Map<String, Object> hallTicketByNumber(String hallTicketNo) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, api.district AS juris_code,
                  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
                  ec.latitude, ec.longitude, ec.address, ec.village, ec.pincode, api.nmms_reg_number
                FROM pp.applicant_exam ae
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.examination e ON ae.exam_id = e.exam_id
                JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
                WHERE ae.pp_hall_ticket_no = :ticket
                """).param("ticket", hallTicketNo).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** downloadAllHallTickets() parity. */
    public List<Map<String, Object>> hallTicketsForExam(String examId) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, api.nmms_reg_number,
                  api.district AS juris_code,
                  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
                  ec.latitude, ec.address, ec.village, ec.pincode, ec.longitude
                FROM pp.applicant_exam ae
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.examination e ON ae.exam_id = e.exam_id
                JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
                WHERE ae.exam_id = :examId::numeric
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }
}
