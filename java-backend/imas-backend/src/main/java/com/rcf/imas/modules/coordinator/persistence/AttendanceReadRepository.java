package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

@Repository
public class AttendanceReadRepository {

    private final JdbcClient jdbc;

    public AttendanceReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** attendanceController.getOrFindSession (live, attendanceController.js:311-334). Empty Optional means
     *  "no matching session" -- the controller maps that to {session_id:null}, 200 (Node parity, not 404). */
    public Optional<Map<String, Object>> getOrFindSession(String classroomId, String sessionDate, String normalizedStartTime) {
        return jdbc.sql("""
                SELECT session_id, start_time::text AS start_time, end_time::text AS end_time, duration_minutes
                FROM pp.class_session
                WHERE classroom_id = :classroomId::integer
                  AND session_date = :sessionDate::date
                  AND to_char(start_time, 'HH24:MI:SS') = :startTime
                LIMIT 1
                """)
                .param("classroomId", classroomId)
                .param("sessionDate", sessionDate)
                .param("startTime", normalizedStartTime)
                .query((rs, i) -> genericRow(rs)).optional();
    }

    /** attendanceController.checkOverlap (live, attendanceController.js:703-716). LIVE SOURCE returns ONLY
     *  {overlap:boolean} -- no "conflicts" array (see plan's Disagreements #1). */
    public boolean checkOverlap(String classroomId, String date, String startTime, String endTime) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)::int FROM pp.class_session
                WHERE classroom_id = :classroomId::integer
                  AND session_date = :date::date
                  AND (start_time, end_time) OVERLAPS (:startTime::time, :endTime::time)
                """)
                .param("classroomId", classroomId).param("date", date)
                .param("startTime", startTime).param("endTime", endTime)
                .query(Integer.class).single();
        return count != null && count > 0;
    }

    /** previewCSVAttendance's dbStudentsRes query (live, attendanceController.js:498-503). Scoped by
     *  batch_id (NOT classroom_id -- see plan Disagreements #2, the live handler queries by batch, matching
     *  ported here exactly). */
    public List<Map<String, Object>> attendanceStudentsByBatch(String batchId) {
        return jdbc.sql("""
                SELECT student_id, student_name, enr_id, active_yn
                FROM pp.student_master
                WHERE batch_id = :batchId::integer
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** attendanceController.fetchAttendance (live, attendanceController.js:338-366). sessionId may be null
     *  (Node: `session_id || null`) -- comparison against NULL is always unknown/false in SQL, matching
     *  Node's pg parameterization exactly; ACTIVE students always show, INACTIVE only if already marked. */
    public List<Map<String, Object>> fetchAttendance(String sessionId, String batchId) {
        return jdbc.sql("""
                SELECT sm.student_id, sm.student_name, sm.enr_id, sm.contact_no1, sm.student_email,
                       sm.active_yn, sa.status AS db_status
                FROM pp.student_master sm
                LEFT JOIN pp.student_attendance sa
                       ON sa.student_id = sm.student_id AND sa.session_id = :sessionId::integer
                WHERE sm.batch_id = :batchId::integer
                  AND (sm.active_yn = 'ACTIVE' OR sa.session_id IS NOT NULL)
                ORDER BY sm.student_name
                """)
                .param("sessionId", sessionId)
                .param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
