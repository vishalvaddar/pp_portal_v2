package com.rcf.imas.modules.coordinator.persistence;

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
public class CoordinatorReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcClient jdbc;

    public CoordinatorReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the coordinator module (LOCKED CONVENTIONS #3): EXAMS/classroom-style
     * bd.toBigInteger().toString() for NUMERIC/DECIMAL -- every numeric column in this 14-endpoint slice
     * (student_id, applicant_id, enr_id, created_by, updated_by) is a whole-number id, no fractional output.
     * integer columns (batch_id, classroom_id, subject_id, teacher_id, platform_id, cohort_number) pass
     * through natively via rs.getObject(i). Package-private static so CoordinatorWriteRepository reuses it.
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

    /** searchInstitutesModel -- ILIKE on dise_code OR institute_name, LIMIT 15. Caller (controller) applies
     *  the min-3-chars guard and returns [] without calling this method at all. */
    public List<Map<String, Object>> instituteSearch(String term) {
        return jdbc.sql("""
                SELECT dise_code, institute_name, institute_board, management_type
                FROM pp.institute
                WHERE dise_code ILIKE :term OR institute_name ILIKE :term
                ORDER BY institute_name ASC
                LIMIT 15
                """).param("term", "%" + term + "%").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getTeachers -- names only, no id, no ORDER BY (ported literally, ground truth §8.8). */
    public List<Map<String, Object>> teachers() {
        return jdbc.sql("SELECT teacher_name FROM pp.teacher").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getPlatforms. */
    public List<Map<String, Object>> platforms() {
        return jdbc.sql("SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** subjectModel.getAllSubjects -- SELECT *. */
    public List<Map<String, Object>> subjects() {
        return jdbc.sql("SELECT * FROM pp.subject ORDER BY subject_name").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getAllClassrooms -- all statuses. */
    public List<Map<String, Object>> allClassrooms() {
        return jdbc.sql("""
                SELECT classroom_id, classroom_name, description, active_yn
                FROM pp.classroom
                ORDER BY classroom_name
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getClassroomsByBatch -- active_yn='Y' only. */
    public List<Map<String, Object>> classroomsByBatch(String batchId) {
        return jdbc.sql("""
                SELECT c.classroom_id, c.classroom_name, c.class_link
                FROM pp.classroom c
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE cb.batch_id = :batchId::integer AND c.active_yn = 'Y'
                ORDER BY c.classroom_name
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** cohortModel.getCohortsByUser -- DISTINCT, scoped via pp.batch_coordinator_batches. */
    public List<Map<String, Object>> cohortsByUser(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT c.cohort_number, c.cohort_name
                FROM pp.cohort c
                JOIN pp.batch b ON c.cohort_number = b.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE bcb.user_id = :userId::numeric
                ORDER BY c.cohort_number
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** batchModel.getBatchesByCohort -- scoped by cohort AND coordinator. */
    public List<Map<String, Object>> batchesByCohort(String cohortNumber, String coordinatorId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, b.cohort_number, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE b.cohort_number = :cohortNumber::integer AND bcb.user_id = :coordinatorId::numeric
                ORDER BY b.batch_id DESC
                """).param("cohortNumber", cohortNumber).param("coordinatorId", coordinatorId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** batchModel.getAllBatchesForCoordinator. */
    public List<Map<String, Object>> allBatchesForCoordinator(String userId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, b.cohort_number, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE bcb.user_id = :userId::numeric
                ORDER BY b.batch_id DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    private static final String STUDENT_SELECT = """
            sm.student_id, sm.applicant_id, sm.enr_id, sm.student_name, sm.gender,
            sm.father_name, sm.father_occupation, sm.mother_name, sm.mother_occupation,
            sm.student_email, sm.student_email_password, sm.parent_email,
            sm.contact_no1, sm.contact_no2, sm.home_address,
            sm.current_institute_dise_code, sm.previous_institute_dise_code,
            ci.institute_name AS current_institute, pi.institute_name AS previous_institute,
            sm.sim_name, sm.teacher_name, sm.teacher_mobile_number,
            sm.active_yn, sm.recharge_status, sm.sponsor, sm.photo_link,
            sm.batch_id, b.batch_name, c.cohort_number, c.cohort_name,
            ins.inactive_reason, sm.created_at, sm.updated_at
            """;

    /** studentModel.getStudentsByCohortAndBatch. NOTE: the LEFT JOIN inactive_students has no de-dup --
     *  a student with >1 inactive_students row (append-only, no unique constraint) fans out into duplicate
     *  rows here, matching Node's own behavior verbatim (see plan's Deferred section). */
    public List<Map<String, Object>> studentsByCohortAndBatch(String cohortNumber, String batchId) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE c.cohort_number = :cohortNumber::integer AND b.batch_id = :batchId::integer
                ORDER BY sm.student_name
                """).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getStudentsByCoordinator -- ALL of the coordinator's students, any status. */
    public List<Map<String, Object>> studentsByCoordinator(String userId) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE bcb.user_id = :userId::numeric
                ORDER BY sm.student_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** Firm Decision 8 (ground truth §8.13): direct SQL filter, not Node's fetch-all-then-JS-filter. */
    public List<Map<String, Object>> studentsByCoordinatorAndCohort(String userId, String cohortNumber) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE bcb.user_id = :userId::numeric AND c.cohort_number = :cohortNumber::integer
                ORDER BY sm.student_name
                """).param("userId", userId).param("cohortNumber", cohortNumber)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getActiveStudentsForAttendance -- STRICTLY active_yn='ACTIVE', narrow column set. */
    public List<Map<String, Object>> activeStudentsForAttendance(String cohortNumber, String batchId) {
        return jdbc.sql("""
                SELECT sm.student_id, sm.enr_id, sm.student_name,
                       sm.contact_no1, sm.student_email, sm.batch_id, sm.active_yn
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE c.cohort_number = :cohortNumber::integer
                  AND b.batch_id = :batchId::integer
                  AND sm.active_yn = 'ACTIVE'
                ORDER BY sm.student_name
                """).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getInactiveHistoryByStudentId. */
    public List<Map<String, Object>> inactiveHistory(String studentId) {
        return jdbc.sql("""
                SELECT inactive_reason, inactive_date, created_by, updated_by
                FROM pp.inactive_students
                WHERE student_id = :studentId::numeric
                ORDER BY inactive_date DESC
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).list();
    }
}
