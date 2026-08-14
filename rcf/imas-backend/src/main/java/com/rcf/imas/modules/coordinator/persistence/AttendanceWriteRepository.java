package com.rcf.imas.modules.coordinator.persistence;

import com.rcf.imas.modules.coordinator.service.AttendanceSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class AttendanceWriteRepository {

    private final JdbcClient jdbc;

    public AttendanceWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** attendanceController.undoLastAttendanceCommit (live, attendanceController.js:689-699). Node runs
     *  these as two sequential autocommit statements (not wrapped in BEGIN/COMMIT); ported the same way --
     *  student_attendance also cascades on class_session delete (ON DELETE CASCADE, V1__baseline.sql:1868)
     *  so the first DELETE is technically redundant once the second runs, but kept for exact parity and to
     *  tolerate a session_id with no matching class_session row. */
    public void undoAttendance(String sessionId) {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = :sessionId::integer")
                .param("sessionId", sessionId).update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = :sessionId::integer")
                .param("sessionId", sessionId).update();
    }

    /**
     * commitCSVAttendance (live, attendanceController.js:617-685) + getOrCreateSession (live,
     * attendanceModel.js:50-80), fused into ONE @Transactional method on ONE connection (Firm Decision 2 /
     * ground truth §7.2, §8.2: Node's getOrCreateSession uses the module-level `pool`, not the caller's
     * `client`, so the session INSERT commits independently of the surrounding BEGIN/COMMIT/ROLLBACK -- a
     * real atomicity hole this Java port deliberately closes). Both ON CONFLICT clauses are ported VERBATIM
     * (Firm Decision 1) -- class_session_classroom_id_session_date_start_time_end_time_key and
     * student_attendance_session_id_student_id_key both exist in V1__baseline.sql (lines 1293, 1440).
     */
    @Transactional
    public Integer commitCsvAttendance(String classroomId, String sessionDate,
                                         String normalizedStartTime, String normalizedEndTime,
                                         List<Map<String, Object>> previewData) {
        Long teacherId = jdbc.sql("SELECT teacher_id FROM pp.classroom WHERE classroom_id = :classroomId::integer")
                .param("classroomId", classroomId).query(Long.class).optional().orElse(null);

        Integer sessionId = jdbc.sql("""
                INSERT INTO pp.class_session (classroom_id, session_date, start_time, end_time, teacher_id)
                VALUES (:classroomId::integer, :sessionDate::date, :startTime::time, :endTime::time, :teacherId::integer)
                ON CONFLICT (classroom_id, session_date, start_time, end_time)
                DO UPDATE SET teacher_id = EXCLUDED.teacher_id, updated_at = CURRENT_TIMESTAMP
                RETURNING session_id
                """)
                .param("classroomId", classroomId).param("sessionDate", sessionDate)
                .param("startTime", normalizedStartTime).param("endTime", normalizedEndTime)
                .param("teacherId", teacherId)
                .query(Integer.class).single();

        int startMins = AttendanceSupport.timeToMinutes(normalizedStartTime);
        int endMins = AttendanceSupport.timeToMinutes(normalizedEndTime);
        int totalSessionMins = endMins > startMins ? (endMins - startMins) : 0;

        for (Map<String, Object> r : previewData) {
            Object studentId = r.get("student_id");
            if (studentId == null) continue;

            Number durationObj = (Number) r.getOrDefault("duration_minutes", 0);
            int durationMinutes = durationObj == null ? 0 : durationObj.intValue();
            double attPct = totalSessionMins > 0 ? (durationMinutes / (double) totalSessionMins) * 100 : 0;
            double cappedPct = Math.min(100, Math.round(attPct * 100.0) / 100.0);

            jdbc.sql("""
                    INSERT INTO pp.student_attendance
                        (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent)
                    VALUES (:sessionId, :studentId::numeric, :status, :timeJoined::time, :timeExited::time, :duration, :pct)
                    ON CONFLICT (session_id, student_id)
                    DO UPDATE SET
                        status = EXCLUDED.status,
                        duration_minutes = EXCLUDED.duration_minutes,
                        time_joined = EXCLUDED.time_joined,
                        time_exited = EXCLUDED.time_exited,
                        attendance_percent = EXCLUDED.attendance_percent,
                        updated_at = NOW()
                    """)
                    .param("sessionId", sessionId)
                    .param("studentId", String.valueOf(studentId))
                    .param("status", r.get("status"))
                    .param("timeJoined", AttendanceSupport.normalizeTimeToDB((String) r.get("time_joined")))
                    .param("timeExited", AttendanceSupport.normalizeTimeToDB((String) r.get("time_exited")))
                    .param("duration", durationMinutes)
                    .param("pct", cappedPct)
                    .update();
        }

        return sessionId;
    }
}
