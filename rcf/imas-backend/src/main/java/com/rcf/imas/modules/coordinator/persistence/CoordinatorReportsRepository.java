package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

/**
 * Backs the 7 report endpoints (#23-29, ground truth phase4e-coordinator-ground-truth.md). Every method is
 * a single read-only SQL statement (or the two-query pair #26 needs) -- no writes, no @Transactional.
 * Reuses CoordinatorReadRepository.genericRow (LOCKED CONVENTIONS #3): numeric(x,0) -> String,
 * integer/bigint COUNT(...) -> String via BIGINT branch, plain integer columns pass through natively.
 */
@Repository
public class CoordinatorReportsRepository {

    private final JdbcClient jdbc;

    public CoordinatorReportsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** reportsController.js getTeacherLoad -- bare rows, {teacherClassCounts} wrapping is the controller's job. */
    public List<Map<String, Object>> teacherLoad(String fromDate, String toDate) {
        String sql = """
                SELECT
                    t.teacher_name AS teacher,
                    b.cohort_number AS cohort,
                    c.classroom_name AS classroom,
                    s.subject_code AS subject,
                    COUNT(DISTINCT cs.session_id) AS total_classes_taken
                FROM pp.class_session cs
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                """
                + (fromDate != null && toDate != null ? " WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date " : "")
                + """
                GROUP BY t.teacher_name, b.cohort_number, c.classroom_name, s.subject_code
                ORDER BY t.teacher_name, b.cohort_number, c.classroom_name
                """;
        var spec = jdbc.sql(sql);
        if (fromDate != null && toDate != null) {
            spec = spec.param("fromDate", fromDate).param("toDate", toDate);
        }
        return spec.query((rs, i) -> genericRow(rs)).list();
    }

    /** teacherController.js getCoordinatorTeachers -- scoped by principal userId (Firm Decision 8,
     *  Disagreements #1: live Node scopes by a client-supplied query param instead, closed here). */
    public List<Map<String, Object>> coordinatorTeachers(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT
                    t.teacher_id,
                    t.teacher_name,
                    t.contact_no,
                    s.subject_name,
                    b.batch_name
                FROM pp.batch_coordinator_batches bcb
                JOIN pp.batch b ON bcb.batch_id = b.batch_id
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cls ON cb.classroom_id = cls.classroom_id
                JOIN pp.class_session cs ON cs.classroom_id = cls.classroom_id
                JOIN pp.teacher t ON t.teacher_id = cs.teacher_id
                LEFT JOIN pp.subject s ON cls.subject_id = s.subject_id
                WHERE bcb.user_id = :userId::numeric
                ORDER BY t.teacher_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    public record AttendanceBatchInfo(String batchName, String cohortName) {}

    /** getAttendanceReport step 1 -- batch/cohort names. Empty rows -> caller substitutes "" (Node's `?.`
     *  optional-chaining default), so this returns Optional-style null-safe access via the list being empty. */
    public List<Map<String, Object>> attendanceBatchInfo(String batchId) {
        return jdbc.sql("""
                SELECT b.batch_name, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                WHERE b.batch_id = :batchId::integer
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAttendanceReport step 2 -- conducted counts per subject_code x teacher_name, ported verbatim. */
    public List<Map<String, Object>> attendanceConducted(String batchId, String fromDate, String toDate) {
        return jdbc.sql("""
                WITH batch_classrooms AS (
                    SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = :batchId::integer
                )
                SELECT
                    subj.subject_code,
                    t.teacher_name,
                    COUNT(DISTINCT cs.session_id) AS conducted
                FROM pp.class_session cs
                JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
                JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                JOIN pp.subject subj ON subj.subject_id = c.subject_id
                LEFT JOIN pp.teacher t ON t.teacher_id = cs.teacher_id
                WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                GROUP BY subj.subject_code, t.teacher_name
                """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAttendanceReport step 3 -- student attendance matrix. Firm Decision 2: the LEFT JOIN
     *  pp.inactive_students has NO dedup, ported verbatim (append-only table, no unique constraint --
     *  a double-inactivated student fans out here exactly as it does in Node). */
    public List<Map<String, Object>> attendanceByStudent(String batchId, String fromDate, String toDate) {
        return jdbc.sql("""
                WITH batch_classrooms AS (
                    SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = :batchId::integer
                ),
                batch_students AS (
                    SELECT sm.student_id, sm.student_name, ins.inactive_date
                    FROM pp.student_master sm
                    LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id
                    WHERE sm.batch_id = :batchId::integer
                      AND (ins.student_id IS NULL OR ins.inactive_date > :fromDate::date)
                ),
                sessions AS (
                    SELECT
                        cs.session_id,
                        cs.teacher_id,
                        subj.subject_code,
                        cs.session_date
                    FROM pp.class_session cs
                    JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
                    JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                    JOIN pp.subject subj ON subj.subject_id = c.subject_id
                    WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                ),
                student_sessions AS (
                    SELECT
                        bs.student_id,
                        bs.student_name,
                        bs.inactive_date,
                        s.session_id,
                        s.subject_code,
                        s.teacher_id,
                        s.session_date
                    FROM sessions s
                    JOIN batch_students bs
                        ON (bs.inactive_date IS NULL OR s.session_date < bs.inactive_date)
                )
                SELECT
                    ss.student_id,
                    ss.student_name,
                    ss.subject_code,
                    t.teacher_name,
                    COUNT(DISTINCT ss.session_id) FILTER (
                        WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
                    ) AS attended
                FROM student_sessions ss
                LEFT JOIN pp.student_attendance sa
                    ON sa.session_id = ss.session_id
                    AND sa.student_id = ss.student_id
                LEFT JOIN pp.teacher t ON t.teacher_id = ss.teacher_id
                GROUP BY ss.student_id, ss.student_name, ss.subject_code, t.teacher_name
                ORDER BY ss.student_name
                """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAbsenteesReport -- generate_series + timetable day-name match. Firm Decision 3: ported verbatim,
     *  including scheduled_count counting timetabled slots (not actual class_session rows) and the
     *  subject_code LIMIT-1 lookup (assumes subject_code uniqueness, unenforced by schema). */
    public List<Map<String, Object>> absentees(String batchId, String fromDate, String toDate) {
        return jdbc.sql("""
                WITH dates AS (
                    SELECT generate_series(:fromDate::date, :toDate::date, interval '1 day')::date AS dt
                ),
                batch_classrooms AS (
                    SELECT cb.classroom_id FROM pp.classroom_batch cb WHERE cb.batch_id = :batchId::integer
                ),
                scheduled AS (
                    SELECT c.classroom_id, s.subject_code, d.dt
                    FROM pp.classroom c
                    JOIN batch_classrooms bc ON bc.classroom_id = c.classroom_id
                    JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                    JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                    JOIN pp.subject s ON s.subject_id = c.subject_id
                ),
                attended AS (
                    SELECT sa.student_id, c.subject_id, cs.session_date AS date, sa.status
                    FROM pp.student_attendance sa
                    JOIN pp.class_session cs ON cs.session_id = sa.session_id
                    JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                    WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                ),
                compare AS (
                    SELECT bs.student_id, bs.student_name, sch.subject_code,
                           COUNT(*) AS scheduled_count,
                           COUNT(att.*) FILTER (WHERE att.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_count,
                           ARRAY_AGG(CASE WHEN att.status = 'ABSENT' THEN att.date END)
                             FILTER (WHERE att.status = 'ABSENT') AS absent_dates
                    FROM (SELECT sm.student_id, sm.student_name FROM pp.student_master sm WHERE sm.batch_id = :batchId::integer) bs
                    JOIN scheduled sch ON TRUE
                    LEFT JOIN attended att
                      ON att.student_id = bs.student_id
                      AND att.subject_id = (SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)
                      AND att.date = sch.dt
                    GROUP BY bs.student_id, bs.student_name, sch.subject_code
                )
                SELECT student_id, student_name, subject_code AS subject, scheduled_count, attended_count,
                       (scheduled_count - attended_count) AS missed_count,
                       COALESCE(absent_dates, '{}') AS missed_dates
                FROM compare
                WHERE (scheduled_count - attended_count) > 0
                ORDER BY missed_count DESC
                """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeacherPerformance step 1 -- scheduled slots via generate_series + timetable day-name match,
     *  filtered by classroom.teacher_id (the DEFAULT/classroom-level teacher, not the session-level one --
     *  ported exactly as reportsController.js:326-330 has it). */
    public List<Map<String, Object>> teacherPerformanceScheduled(String teacherId, String fromDate, String toDate) {
        return jdbc.sql("""
                WITH dates AS (SELECT generate_series(:fromDate::date, :toDate::date, interval '1 day')::date AS dt),
                scheduled AS (
                    SELECT s.subject_code, d.dt FROM pp.classroom c
                    JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                    JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                    JOIN pp.subject s ON s.subject_id = c.subject_id
                    WHERE c.teacher_id = :teacherId::integer
                )
                SELECT subject_code AS subject, COUNT(*) AS scheduled FROM scheduled GROUP BY subject_code
                """).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeacherPerformance step 2 -- conducted sessions, also filtered by classroom.teacher_id (NOT
     *  cs.teacher_id -- ported exactly as reportsController.js:339 has it). */
    public List<Map<String, Object>> teacherPerformanceConducted(String teacherId, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT subj.subject_code AS subject, COUNT(DISTINCT cs.session_id) AS conducted
                FROM pp.class_session cs
                JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                JOIN pp.subject subj ON subj.subject_id = c.subject_id
                WHERE c.teacher_id = :teacherId::integer AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                GROUP BY subj.subject_code
                """).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getBatchClassDetails -- session list for a batch w/ attendance_marked EXISTS-flag. */
    public List<Map<String, Object>> batchClassDetails(String batchId, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT
                    cs.session_id,
                    cs.session_date AS date,
                    t.teacher_name,
                    co.cohort_name,
                    c.classroom_name,
                    EXISTS (
                        SELECT 1
                        FROM pp.student_attendance sa
                        JOIN pp.student_master sm ON sa.student_id = sm.student_id
                        WHERE sa.session_id = cs.session_id
                          AND sm.batch_id = :batchId::integer
                    ) AS attendance_marked
                FROM pp.class_session cs
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                WHERE b.batch_id = :batchId::integer
                  AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                ORDER BY cs.session_date DESC
                """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeacherClassDetails -- Firm Decision 4: closed 2-way switch on filterColumn, never string-
     *  interpolating the request VALUE (only the column name, chosen from two hardcoded literals, differs). */
    public List<Map<String, Object>> teacherClassDetails(String teacherId, String fromDate, String toDate) {
        boolean numeric = teacherId != null && teacherId.matches("\\d+");
        String sql = numeric
                ? """
                    SELECT DISTINCT ON (cs.session_id)
                           cs.session_date AS date, t.teacher_name, co.cohort_name,
                           b.batch_name, c.classroom_name
                    FROM pp.class_session cs
                    JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                    JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                    JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                    JOIN pp.batch b ON cb.batch_id = b.batch_id
                    JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                    WHERE t.teacher_id = :teacherId::integer
                      AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                    ORDER BY cs.session_id, cs.session_date DESC
                    """
                : """
                    SELECT DISTINCT ON (cs.session_id)
                           cs.session_date AS date, t.teacher_name, co.cohort_name,
                           b.batch_name, c.classroom_name
                    FROM pp.class_session cs
                    JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                    JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                    JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                    JOIN pp.batch b ON cb.batch_id = b.batch_id
                    JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                    WHERE t.teacher_name = :teacherId
                      AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                    ORDER BY cs.session_id, cs.session_date DESC
                    """;
        return jdbc.sql(sql).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
