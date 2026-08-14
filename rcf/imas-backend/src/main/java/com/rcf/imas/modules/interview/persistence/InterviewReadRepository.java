package com.rcf.imas.modules.interview.persistence;

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
public class InterviewReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public InterviewReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: NUMERIC/BIGINT -> String; DATE -> "yyyy-MM-dd"; TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z;
     *  ARRAY -> List&lt;String&gt; (pattern-parity, unused here); INTEGER (interview_round) -> Number (passthrough);
     *  BOOLEAN (is_frozen_block) -> boolean (passthrough). Map keys are the column label verbatim (preserves
     *  the report's `AS "Student Name"` aliases unchanged). */
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
            if (el == null) out.add(null);
            else if (el instanceof BigDecimal bd) out.add(bd.toBigInteger().toString());
            else out.add(String.valueOf(el));
        }
        return out;
    }

    // ---- getExamCenters() interviewModel.js:10-22 ----
    public List<Map<String, Object>> examCenters() {
        return jdbc.sql("""
                SELECT pp_exam_centre_id, pp_exam_centre_name
                FROM pp.pp_exam_centre
                ORDER BY pp_exam_centre_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getAllStates() interviewModel.js:24-36 ----
    public List<Map<String, Object>> states() {
        return jdbc.sql("""
                SELECT juris_code, juris_name
                FROM pp.jurisdiction
                WHERE LOWER(juris_type) = 'state'
                """).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getDivisionsByState(stateName) interviewModel.js:38-58 ----
    public List<Map<String, Object>> divisionsByState(String stateName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name
                FROM pp.jurisdiction AS division
                WHERE division.parent_juris IN (
                  SELECT state.juris_code
                  FROM pp.jurisdiction AS state
                  WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:stateName))
                )
                AND LOWER(division.juris_type) = 'division'
                """).param("stateName", stateName).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getDistrictsByDivision(divisionName) interviewModel.js:60-80 ----
    public List<Map<String, Object>> districtsByDivision(String divisionName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name
                FROM pp.jurisdiction AS district
                WHERE district.parent_juris IN (
                  SELECT division.juris_code
                  FROM pp.jurisdiction AS division
                  WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:divisionName))
                )
                AND LOWER(district.juris_type) = 'education district'
                """).param("divisionName", divisionName).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getBlocksByDistrict(stateName, divisionName, districtName) interviewModel.js:82-129 ----
    // NOTE param order: state=:stateName ($1), division=:divisionName ($2), district=:districtName ($3).
    public List<Map<String, Object>> blocksByDistrict(String stateName, String divisionName, String districtName) {
        return jdbc.sql("""
                SELECT
                    j.juris_code,
                    j.juris_name,
                    CASE
                        WHEN j.juris_code IN (
                            SELECT sbj.juris_code
                            FROM pp.shortlist_batch_jurisdiction AS sbj
                            JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                            WHERE sb.frozen_yn = 'Y'
                        )
                        THEN TRUE ELSE FALSE
                    END AS is_frozen_block
                FROM pp.jurisdiction AS j
                WHERE LOWER(j.juris_type) = 'block'
                    AND j.parent_juris IN (
                        SELECT district.juris_code
                        FROM pp.jurisdiction AS district
                        WHERE LOWER(TRIM(district.juris_name)) = LOWER(TRIM(:districtName))
                          AND LOWER(district.juris_type) = 'education district'
                          AND district.parent_juris IN (
                            SELECT division.juris_code
                            FROM pp.jurisdiction AS division
                            WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:divisionName))
                              AND LOWER(division.juris_type) = 'division'
                              AND division.parent_juris IN (
                                SELECT state.juris_code
                                FROM pp.jurisdiction AS state
                                WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:stateName))
                                  AND LOWER(state.juris_type) = 'state'
                              )
                          )
                    )
                """).param("stateName", stateName).param("divisionName", divisionName).param("districtName", districtName)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getInterviewers() interviewModel.js:163-175 (NO active_status filter) ----
    public List<Map<String, Object>> interviewers() {
        return jdbc.sql("""
                SELECT interviewer_id, interviewer_name
                FROM pp.interviewer
                ORDER BY interviewer_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getStudentsForVerification(nmmsYear) interviewModel.js:746-773 ----
    public List<Map<String, Object>> studentsForVerification(String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    a.student_name,
                    a.applicant_id
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a
                    ON a.applicant_id = s.applicant_id
                WHERE
                    UPPER(TRIM(s.home_verification_req_yn)) = 'Y'
                    AND a.nmms_year = :nmmsYear::numeric
                    AND a.applicant_id NOT IN (
                        SELECT applicant_id
                        FROM pp.home_verification
                    )
                """).param("nmmsYear", nmmsYear).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getStudentsByInterviewer(interviewerName, nmmsYear) interviewModel.js:133-161 ----
    public List<Map<String, Object>> studentsByInterviewer(String interviewerName, String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    a.student_name,
                    a.applicant_id,
                    s.interview_round
                FROM pp.student_interview s
                JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                WHERE
                    LOWER(TRIM(i.interviewer_name)) = LOWER(TRIM(:interviewerName))
                    AND a.nmms_year = :nmmsYear::numeric
                    AND UPPER(TRIM(s.status)) = 'SCHEDULED'
                    AND s.interview_result IS NULL
                """).param("interviewerName", interviewerName).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getUnassignedStudents(centerName, nmmsYear) interviewModel.js:177-233 ----
    // by-CENTRE: LatestInterview CTE is year-scoped; requires applicant_exam -> examination -> pp_exam_centre join.
    public List<Map<String, Object>> unassignedStudents(String centerName, String nmmsYear) {
        return jdbc.sql("""
                WITH LatestInterview AS (
                    SELECT
                        si.applicant_id,
                        si.interview_round,
                        si.status,
                        si.interview_result,
                        ROW_NUMBER() OVER (
                            PARTITION BY si.applicant_id
                            ORDER BY si.interview_round DESC,
                                     si.interview_date DESC NULLS LAST
                        ) AS rn
                    FROM pp.student_interview si
                    JOIN pp.applicant_primary_info api_sub
                        ON si.applicant_id = api_sub.applicant_id
                    WHERE api_sub.nmms_year = :nmmsYear::numeric
                )
                SELECT
                    api.applicant_id,
                    api.student_name,
                    exam.pp_exam_score
                FROM pp.applicant_primary_info api
                JOIN pp.exam_results exam
                    ON api.applicant_id = exam.applicant_id
                    AND exam.pp_exam_cleared = 'Y'
                    AND exam.interview_required_yn = 'Y'
                JOIN pp.applicant_exam ap
                    ON ap.applicant_id = exam.applicant_id
                JOIN pp.examination e
                    ON e.exam_id = ap.exam_id
                JOIN pp.pp_exam_centre centre
                    ON e.pp_exam_centre_id = centre.pp_exam_centre_id
                LEFT JOIN LatestInterview li
                    ON api.applicant_id = li.applicant_id
                    AND li.rn = 1
                WHERE
                    LOWER(TRIM(centre.pp_exam_centre_name)) = LOWER(TRIM(:centerName))
                    AND api.nmms_year = :nmmsYear::numeric
                    AND (
                        li.applicant_id IS NULL
                        OR (
                            TRIM(UPPER(li.status)) = 'RESCHEDULED'
                            AND TRIM(UPPER(li.interview_result)) = 'ANOTHER INTERVIEW REQUIRED'
                            AND li.interview_round < 3
                        )
                        OR TRIM(UPPER(li.status)) = 'CANCELLED'
                    )
                """).param("centerName", centerName).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getUnassignedStudentsByBlock(stateName, districtName, blockName, nmmsYear) interviewModel.js:235-292 ----
    // by-BLOCK: LatestInterview CTE spans ALL years (no nmms_year filter inside CTE); LEFT JOIN jurisdiction; NO exam/centre join.
    public List<Map<String, Object>> unassignedStudentsByBlock(String stateName, String districtName, String blockName, String nmmsYear) {
        return jdbc.sql("""
                WITH LatestInterview AS (
                    SELECT
                        si.applicant_id,
                        si.interview_round,
                        si.status,
                        si.interview_result,
                        ROW_NUMBER() OVER (
                            PARTITION BY si.applicant_id
                            ORDER BY si.interview_round DESC,
                                     si.interview_date DESC NULLS LAST
                        ) AS rn
                    FROM pp.student_interview si
                )
                SELECT
                     api.applicant_id,
                     api.student_name,
                     exam.pp_exam_score
                FROM pp.applicant_primary_info api
                JOIN pp.exam_results exam
                    ON api.applicant_id = exam.applicant_id
                    AND exam.pp_exam_cleared = 'Y'
                    AND exam.interview_required_yn = 'Y'
                LEFT JOIN LatestInterview li
                    ON api.applicant_id = li.applicant_id
                    AND li.rn = 1
                LEFT JOIN pp.jurisdiction sj ON api.app_state = sj.juris_code
                LEFT JOIN pp.jurisdiction dj ON api.district = dj.juris_code
                LEFT JOIN pp.jurisdiction bj ON api.nmms_block = bj.juris_code
                WHERE
                    LOWER(TRIM(sj.juris_name)) = LOWER(TRIM(:stateName))
                    AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM(:districtName))
                    AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM(:blockName))
                    AND api.nmms_year = :nmmsYear::numeric
                    AND (
                        li.applicant_id IS NULL
                        OR (
                            UPPER(TRIM(li.status)) = 'RESCHEDULED'
                            AND UPPER(TRIM(li.interview_result)) = 'ANOTHER INTERVIEW REQUIRED'
                            AND li.interview_round < 3
                        )
                        OR (
                            UPPER(TRIM(li.status)) = 'CANCELLED'
                        )
                    )
                """).param("stateName", stateName).param("districtName", districtName).param("blockName", blockName).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getReassignableStudents(centerName, nmmsYear) interviewModel.js:566-618 ----
    // by-CENTRE: pp_exam_centre join; returns pp_exam_centre_name; LEFT JOIN interviewer/institute.
    public List<Map<String, Object>> reassignableStudents(String centerName, String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    api.applicant_id,
                    api.student_name,
                    inst.institute_name,
                    exam.pp_exam_score,
                    centre.pp_exam_centre_name,
                    si.interview_round,
                    i.interviewer_name AS current_interviewer,
                    si.interviewer_id AS current_interviewer_id
                FROM pp.applicant_primary_info api
                JOIN pp.exam_results exam
                    ON api.applicant_id = exam.applicant_id
                JOIN pp.applicant_exam ap
                    ON ap.applicant_id = exam.applicant_id
                JOIN pp.examination e
                    ON e.exam_id = ap.exam_id
                JOIN pp.pp_exam_centre centre
                    ON e.pp_exam_centre_id = centre.pp_exam_centre_id
                LEFT JOIN pp.institute inst
                    ON api.current_institute_dise_code = inst.dise_code
                JOIN pp.student_interview si
                    ON api.applicant_id = si.applicant_id
                LEFT JOIN pp.interviewer i
                    ON si.interviewer_id = i.interviewer_id
                WHERE
                    LOWER(TRIM(centre.pp_exam_centre_name)) = LOWER(TRIM(:centerName))
                    AND api.nmms_year = :nmmsYear::numeric
                    AND exam.pp_exam_cleared = 'Y'
                    AND exam.interview_required_yn = 'Y'
                    AND (UPPER(TRIM(si.status)) = 'SCHEDULED' OR UPPER(TRIM(si.status)) = 'RESCHEDULED')
                    AND (UPPER(TRIM(si.interview_result)) = 'ANOTHER INTERVIEW REQUIRED' OR si.interview_result IS NULL)
                    AND si.interview_round = (
                        SELECT MAX(sub_si.interview_round)
                        FROM pp.student_interview sub_si
                        JOIN pp.applicant_primary_info sub_api
                            ON sub_si.applicant_id = sub_api.applicant_id
                        WHERE sub_si.applicant_id = si.applicant_id
                            AND sub_api.nmms_year = :nmmsYear::numeric
                    )
                ORDER BY api.student_name ASC
                """).param("centerName", centerName).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- getReassignableStudentsByBlock(stateName, districtName, blockName, nmmsYear) interviewModel.js:620-664 ----
    // by-BLOCK: INNER JOIN all three jurisdiction tables; NO pp_exam_centre join; no pp_exam_centre_name column.
    public List<Map<String, Object>> reassignableStudentsByBlock(String stateName, String districtName, String blockName, String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    api.applicant_id,
                    api.student_name,
                    inst.institute_name,
                    exam.pp_exam_score,
                    si.interview_round,
                    i.interviewer_name AS current_interviewer,
                    si.interviewer_id AS current_interviewer_id
                FROM pp.applicant_primary_info api
                JOIN pp.exam_results exam ON api.applicant_id = exam.applicant_id
                LEFT JOIN pp.institute inst ON api.current_institute_dise_code = inst.dise_code
                JOIN pp.student_interview si ON api.applicant_id = si.applicant_id
                LEFT JOIN pp.interviewer i ON si.interviewer_id = i.interviewer_id
                JOIN pp.jurisdiction sj ON api.app_state = sj.juris_code
                JOIN pp.jurisdiction dj ON api.district = dj.juris_code
                JOIN pp.jurisdiction bj ON api.nmms_block = bj.juris_code
                WHERE
                    api.nmms_year = :nmmsYear::numeric
                    AND LOWER(TRIM(sj.juris_name)) = LOWER(TRIM(:stateName))
                    AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM(:districtName))
                    AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM(:blockName))
                    AND exam.pp_exam_cleared = 'Y'
                    AND exam.interview_required_yn = 'Y'
                    AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
                    AND (UPPER(TRIM(si.interview_result)) = 'ANOTHER INTERVIEW REQUIRED' OR si.interview_result IS NULL)
                    AND si.interview_round = (
                        SELECT MAX(sub_si.interview_round)
                        FROM pp.student_interview sub_si
                        JOIN pp.applicant_primary_info sub_api ON sub_si.applicant_id = sub_api.applicant_id
                        WHERE sub_si.applicant_id = si.applicant_id
                            AND sub_api.nmms_year = :nmmsYear::numeric
                    )
                ORDER BY api.student_name ASC
                """).param("stateName", stateName).param("districtName", districtName).param("blockName", blockName).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getAssignmentReportData(interviewerId, nmmsYear, applicantIds) — interviewModel.js:1049-1226.
     * pg-format %s/%L converted to named binds (Firm Decision 6). NOTE interviewerId is NOT used in either SQL
     * (Node ignores it in the queries too — the applicantIds list already scopes the report). The interview-history
     * query keeps its INNER JOIN to pp.interviewer, so cancelled rounds (NULL interviewer) are dropped (quirk PRESERVED).
     */
    public List<Map<String, Object>> assignmentReportData(String nmmsYear, List<Object> applicantIds) {
        if (applicantIds == null || applicantIds.isEmpty()) return List.of();

        // QUERY 1 — profile rows (%s -> :year::numeric, %L -> IN (:ids))
        List<Map<String, Object>> profileRows = jdbc.sql("""
                SELECT
                    API.applicant_id,
                    API.nmms_reg_number,
                    API.student_name AS "Student Name",
                    API.contact_no1 AS "Contact No 1",
                    API.contact_no2 AS "Contact No 2",
                    CUR_INST.institute_name AS "Current School Name",
                    PREV_INST.institute_name AS "Previous School Name",
                    API.gmat_score,
                    API.sat_score,
                    E.pp_exam_score,
                    SJ.juris_name AS "State Name",
                    DJ.juris_name AS "District Name",
                    BJ.juris_name AS "Block Name",
                    S.village, S.father_occupation, S.mother_occupation, S.father_education, S.mother_education,
                    S.household_size, S.own_house, S.smart_phone_home, S.internet_facility_home,
                    S.career_goals, S.subjects_of_interest, S.transportation_mode, S.distance_to_school,
                    S.num_two_wheelers, S.num_four_wheelers, S.irrigation_land,
                    S.neighbor_name, S.neighbor_phone, S.favorite_teacher_name, S.favorite_teacher_phone
                FROM pp.applicant_primary_info API
                LEFT JOIN pp.applicant_secondary_info S ON S.applicant_id = API.applicant_id
                LEFT JOIN pp.exam_results E ON E.applicant_id = API.applicant_id
                LEFT JOIN pp.institute CUR_INST ON API.current_institute_dise_code = CUR_INST.dise_code
                LEFT JOIN pp.institute PREV_INST ON API.previous_institute_dise_code = PREV_INST.dise_code
                LEFT JOIN pp.jurisdiction SJ ON API.app_state = SJ.juris_code
                LEFT JOIN pp.jurisdiction DJ ON API.district = DJ.juris_code
                LEFT JOIN pp.jurisdiction BJ ON API.nmms_block = BJ.juris_code
                WHERE API.nmms_year = :year::numeric
                  AND API.applicant_id IN (:ids)
                ORDER BY API.student_name ASC
                """).param("year", nmmsYear).param("ids", applicantIds)
                .query((rs, i) -> genericRow(rs)).list();

        if (profileRows.isEmpty()) return List.of();

        List<Object> studentIds = new ArrayList<>();
        for (Map<String, Object> r : profileRows) studentIds.add(r.get("applicant_id"));

        // QUERY 2 — interview history (%L -> IN (:ids)); INNER JOIN interviewer (cancelled rounds dropped, PRESERVED)
        List<Map<String, Object>> interviewRows = jdbc.sql("""
                SELECT
                    S.applicant_id,
                    I.interviewer_name,
                    S.interview_round AS "Interview Round",
                    S.interview_date AS "Interview Date",
                    S.interview_time AS "Interview Time",
                    S.interview_mode AS "Interview Mode",
                    S.status AS "Assignment Status",
                    S.life_goals_and_zeal AS "Life Goals and Zeal",
                    S.commitment_to_learning AS "Commitment to Learning",
                    S.integrity AS "Integrity",
                    S.communication_skills AS "Communication Skills",
                    S.interview_result AS "Interview Result",
                    I.interviewer_name AS "Assigned Interviewer Name"
                FROM pp.student_interview S
                JOIN pp.interviewer I ON I.interviewer_id = S.interviewer_id
                WHERE S.applicant_id::text IN (:ids)
                ORDER BY S.applicant_id ASC, S.interview_round DESC
                """).param("ids", studentIds).query((rs, i) -> genericRow(rs)).list();

        Map<Object, List<Map<String, Object>>> byApplicant = new LinkedHashMap<>();
        for (Map<String, Object> row : interviewRows) {
            byApplicant.computeIfAbsent(row.get("applicant_id"), k -> new ArrayList<>()).add(row);
        }

        // in-memory merge/categorize (interviewModel.js:1194-1223)
        List<Map<String, Object>> finalReport = new ArrayList<>();
        for (Map<String, Object> student : profileRows) {
            List<Map<String, Object>> records = byApplicant.getOrDefault(student.get("applicant_id"), List.of());
            Map<String, Object> pendingAssignment = null;
            List<Map<String, Object>> completedRounds = new ArrayList<>();
            for (Map<String, Object> record : records) { // already round-DESC
                String result = upperTrim(record.get("Interview Result"));
                String status = upperTrim(record.get("Assignment Status"));
                if (result != null && !"PENDING".equals(result) && !"CANCELLED".equals(status) && !"SKIPPED".equals(status)) {
                    completedRounds.add(record);
                } else if (pendingAssignment == null) {
                    pendingAssignment = record;
                }
            }
            Map<String, Object> merged = new LinkedHashMap<>(student);
            merged.put("Pending Assignment", pendingAssignment);
            merged.put("Completed Rounds", completedRounds);
            finalReport.add(merged);
        }
        return finalReport;
    }

    private static String upperTrim(Object o) { return o == null ? null : String.valueOf(o).trim().toUpperCase(); }
}
