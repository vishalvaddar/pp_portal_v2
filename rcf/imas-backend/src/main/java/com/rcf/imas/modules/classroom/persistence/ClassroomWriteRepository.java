package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

@Repository
public class ClassroomWriteRepository {

    private final JdbcClient jdbc;

    public ClassroomWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** createClassroom() parity, made genuinely atomic (Firm Decision 5) -- Node's version runs manual
     *  pool BEGIN/COMMIT which is not a real cross-statement transaction on a pooled client. N+1 loop-insert
     *  for batch links preserved verbatim (ground truth §7 quirk 14 -- fine for the expected small lists). */
    @Transactional
    public Map<String, Object> createClassroom(String classroomName, String subjectId, String teacherId,
                                                 String platformId, String classLink, String activeYn,
                                                 String createdBy, String updatedBy, List<String> batchIds) {
        Map<String, Object> row = jdbc.sql("""
                INSERT INTO pp.classroom
                 (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
                 VALUES (:name, :subjectId::integer, :teacherId::integer, :platformId::integer, :classLink, :activeYn, :createdBy::numeric, :updatedBy::numeric)
                 RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("createdBy", createdBy).param("updatedBy", updatedBy)
                .query((rs, i) -> genericRow(rs)).single();

        Object classroomId = row.get("classroom_id");
        if (batchIds != null) {
            for (String batchId : batchIds) {
                jdbc.sql("INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES (:classroomId::integer, :batchId::integer)")
                        .param("classroomId", classroomId).param("batchId", batchId).update();
            }
        }
        return row;
    }

    /** updateClassroom() parity. batchIdsProvided distinguishes "key absent from the request body" (skip
     *  the resync entirely -- existing links untouched) from "key present, even as []" (full delete+reinsert
     *  resync) -- ground truth §6 point 2, a meaningful behavioral branch, NOT the same as batchIds==null. */
    @Transactional
    public Map<String, Object> updateClassroom(String classroomId, String classroomName, String subjectId,
                                                 String teacherId, String platformId, String classLink,
                                                 String activeYn, String updatedBy,
                                                 boolean batchIdsProvided, List<String> batchIds) {
        Map<String, Object> row = jdbc.sql("""
                UPDATE pp.classroom
                    SET classroom_name = :name, subject_id = :subjectId::integer, teacher_id = :teacherId::integer, platform_id = :platformId::integer,
                        class_link = :classLink, active_yn = :activeYn, updated_by = :updatedBy::numeric, updated_at = NOW()
                    WHERE classroom_id = :id::integer
                    RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("updatedBy", updatedBy).param("id", classroomId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return null;

        if (batchIdsProvided) {
            jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = :id::integer").param("id", classroomId).update();
            if (batchIds != null) {
                for (String batchId : batchIds) {
                    jdbc.sql("INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES (:classroomId::integer, :batchId::integer)")
                            .param("classroomId", classroomId).param("batchId", batchId).update();
                }
            }
        }
        return row;
    }

    /** deleteClassroom() parity. The manual classroom_batch DELETE is redundant given both junction FKs
     *  CASCADE (ground truth §3 note) but kept explicit/visible inside the transaction, matching Node. */
    @Transactional
    public boolean deleteClassroom(String classroomId) {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = :id::integer").param("id", classroomId).update();
        Map<String, Object> row = jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = :id::integer RETURNING classroom_id")
                .param("id", classroomId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        return row != null;
    }

    public record BatchWriteResult(Status status, Map<String, Object> row) {
        public enum Status { OK, CONFLICT, NOT_FOUND }
        static BatchWriteResult ok(Map<String, Object> row) { return new BatchWriteResult(Status.OK, row); }
        static BatchWriteResult conflict() { return new BatchWriteResult(Status.CONFLICT, null); }
        static BatchWriteResult notFound() { return new BatchWriteResult(Status.NOT_FOUND, null); }
    }

    /** createBatch() parity, made genuinely atomic (Firm Decision 5) -- Node ran the insert then the optional
     *  coordinator-assignment as two loose sequential autocommit queries with no transaction at all. */
    @Transactional
    public BatchWriteResult createBatch(String batchName, String cohortNumber, String coordinatorId) {
        boolean exists = jdbc.sql("SELECT 1 FROM pp.batch WHERE batch_name = :name AND cohort_number = :cohort::integer")
                .param("name", batchName).param("cohort", cohortNumber).query(Integer.class).optional().isPresent();
        if (exists) return BatchWriteResult.conflict();

        Map<String, Object> row = jdbc.sql("INSERT INTO pp.batch (batch_name, cohort_number) VALUES (:name, :cohort::integer) RETURNING *")
                .param("name", batchName).param("cohort", cohortNumber).query((rs, i) -> genericRow(rs)).single();

        if (coordinatorId != null && !coordinatorId.isBlank()) {
            Object batchId = row.get("batch_id");
            jdbc.sql("INSERT INTO pp.batch_coordinator_batches (user_id, batch_id) VALUES (:coordinatorId::numeric, :batchId::integer) ON CONFLICT DO NOTHING")
                    .param("coordinatorId", coordinatorId).param("batchId", batchId).update();
        }
        return BatchWriteResult.ok(row);
    }

    /** updateBatch() parity. batch_status is deliberately never read from the caller (Firm Decision 3) --
     *  there is no parameter for it here at all, matching updateBatchDetails' exact 2-column SET list. */
    @Transactional
    public BatchWriteResult updateBatch(String batchId, String batchName, String cohortNumber, String coordinatorId) {
        boolean dup = jdbc.sql("SELECT 1 FROM pp.batch WHERE batch_name = :name AND cohort_number = :cohort::integer AND batch_id != :id::integer")
                .param("name", batchName).param("cohort", cohortNumber).param("id", batchId).query(Integer.class).optional().isPresent();
        if (dup) return BatchWriteResult.conflict();

        Map<String, Object> row = jdbc.sql("UPDATE pp.batch SET batch_name = :name, cohort_number = :cohort::integer WHERE batch_id = :id::integer RETURNING *")
                .param("name", batchName).param("cohort", cohortNumber).param("id", batchId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return BatchWriteResult.notFound();

        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = :id::integer").param("id", batchId).update();
        if (coordinatorId != null && !coordinatorId.isBlank()) {
            jdbc.sql("INSERT INTO pp.batch_coordinator_batches (user_id, batch_id) VALUES (:coordinatorId::numeric, :id::integer) ON CONFLICT DO NOTHING")
                    .param("coordinatorId", coordinatorId).param("id", batchId).update();
        }
        return BatchWriteResult.ok(row);
    }

    /** deleteBatch() parity. NO pre-check against pp.student_master (Firm Decision 6) -- if any student row
     *  still points at this batch, the DELETE FROM pp.batch statement below throws a raw FK-violation that
     *  propagates OUT of this @Transactional method uncaught, rolling back the coordinator-delete too (an
     *  improvement over Node's non-atomic two-query sequence -- Firm Decision 5), and is caught only by
     *  GlobalExceptionHandler's generic Exception handler -> 500 {error:"Internal Server Error"}. Do NOT add
     *  a try/catch here or in the controller beyond the not-found check -- that would silently change this
     *  preserved-500 behavior into something Node never did. */
    @Transactional
    public BatchWriteResult deleteBatch(String batchId) {
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = :id::integer").param("id", batchId).update();
        Map<String, Object> row = jdbc.sql("DELETE FROM pp.batch WHERE batch_id = :id::integer RETURNING *")
                .param("id", batchId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return BatchWriteResult.notFound();
        return BatchWriteResult.ok(row);
    }
}
