package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

/**
 * Backs timetable endpoints #30-34 (ground truth phase4e-coordinator-ground-truth.md §4.4/§4.5). start_time/
 * end_time are always cast to ::text in SQL (this module's established convention, see AttendanceReadRepository)
 * rather than adding a TIME case to genericRow, so they come back as "HH:mm:ss" strings matching node-pg's
 * default text format for a `time` column.
 */
@Repository
public class CoordinatorTimetableRepository {

    private final JdbcClient jdbc;

    public CoordinatorTimetableRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** timetableModel.getTimetableByBatch -- day-of-week CASE ordering, then start_time. */
    public List<Map<String, Object>> getTimetableByBatch(String batchId) {
        return jdbc.sql("""
                SELECT t.timetable_id, t.classroom_id, t.day_of_week,
                       t.start_time::text AS start_time, t.end_time::text AS end_time,
                       t.created_at, t.updated_at, t.created_by, t.updated_by,
                       c.classroom_name, c.class_link, s.subject_name, s.subject_code, te.teacher_name
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher te ON c.teacher_id = te.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE cb.batch_id = :batchId::integer
                ORDER BY
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7 END,
                    t.start_time
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** timetableModel.checkConflicts -- verbatim SQL (ground truth §4.4), named params, all six nullable. */
    public List<Map<String, Object>> checkConflicts(String day, String startTime, String endTime,
                                                       String classroomId, String teacherId, String excludeId) {
        return jdbc.sql("""
                SELECT t.timetable_id, t.start_time::text AS start_time, t.end_time::text AS end_time,
                       c.classroom_name, s.subject_name, te.teacher_name
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher te ON c.teacher_id = te.teacher_id
                WHERE
                    t.day_of_week = :day
                    AND (:startTime::time < t.end_time AND :endTime::time > t.start_time)
                    AND (
                          ( :classroomId::int IS NOT NULL AND t.classroom_id = :classroomId::int )
                       OR ( :teacherId::int IS NOT NULL AND c.teacher_id = :teacherId::int )
                       OR EXISTS (
                            SELECT 1 FROM pp.classroom_batch cb1
                            JOIN pp.classroom_batch cb2 ON cb1.batch_id = cb2.batch_id
                            WHERE cb1.classroom_id = t.classroom_id AND cb2.classroom_id = :classroomId::int
                          )
                    )
                    AND (:excludeId::int IS NULL OR t.timetable_id <> :excludeId::int)
                """)
                .param("day", day).param("startTime", startTime).param("endTime", endTime)
                .param("classroomId", classroomId).param("teacherId", teacherId).param("excludeId", excludeId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** timetableModel.createSlot -- @Transactional, explicit RETURNING column list (Firm Decision 6),
     *  created_by/updated_by hard-coded to the literal 1 (Firm Decision 3, Node bug, preserved verbatim). */
    @Transactional
    public Map<String, Object> createSlot(String classroomId, String day, String startTime, String endTime, String classLink) {
        Map<String, Object> created = jdbc.sql("""
                INSERT INTO pp.timetable (classroom_id, day_of_week, start_time, end_time, created_by, updated_by)
                VALUES (:classroomId::integer, :day, :startTime::time, :endTime::time, 1, 1)
                RETURNING timetable_id, classroom_id, day_of_week,
                          start_time::text AS start_time, end_time::text AS end_time,
                          created_at, updated_at, created_by, updated_by
                """)
                .param("classroomId", classroomId).param("day", day)
                .param("startTime", startTime).param("endTime", endTime)
                .query((rs, i) -> genericRow(rs)).single();

        jdbc.sql("UPDATE pp.classroom SET class_link = :classLink WHERE classroom_id = :classroomId::integer")
                .param("classLink", classLink).param("classroomId", classroomId).update();

        return created;
    }

    /** timetableModel.updateSlotAndLink -- @Transactional, does NOT set updated_by (ported as-is). */
    @Transactional
    public Map<String, Object> updateSlot(String id, String classroomId, String day, String startTime, String endTime, String classLink) {
        Map<String, Object> updated = jdbc.sql("""
                UPDATE pp.timetable
                SET classroom_id = :classroomId::integer, day_of_week = :day,
                    start_time = :startTime::time, end_time = :endTime::time, updated_at = NOW()
                WHERE timetable_id = :id::integer
                RETURNING timetable_id, classroom_id, day_of_week,
                          start_time::text AS start_time, end_time::text AS end_time,
                          created_at, updated_at, created_by, updated_by
                """)
                .param("id", id).param("classroomId", classroomId).param("day", day)
                .param("startTime", startTime).param("endTime", endTime)
                .query((rs, i) -> genericRow(rs)).single();

        jdbc.sql("UPDATE pp.classroom SET class_link = :classLink WHERE classroom_id = :classroomId::integer")
                .param("classLink", classLink).param("classroomId", classroomId).update();

        return updated;
    }

    /** timetableModel.deleteSlot -- single statement, no @Transactional needed. */
    public void deleteSlot(String id) {
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = :id::integer").param("id", id).update();
    }
}
