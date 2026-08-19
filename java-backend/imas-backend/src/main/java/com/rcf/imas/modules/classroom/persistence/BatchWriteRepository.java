package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

/** Simple single-autocommit-statement batch/cohort writes -- NOT @Transactional, matching Node (none of
 *  these are wrapped in a transaction there either). Multi-step transactional batch writes (createBatch/
 *  updateBatch/deleteBatch) live in ClassroomWriteRepository instead (Task 4, Firm Decision 5). */
@Repository
public class BatchWriteRepository {

    private final JdbcClient jdbc;

    public BatchWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** insertBatchName() parity: ON CONFLICT (cohort_number,batch_name) DO NOTHING RETURNING * -- a zero-row
     *  result means "already exists" and is NOT an error (ground truth §7 quirk 8) -- returns null, the
     *  controller maps that to a 200 (not 4xx/5xx). */
    public Map<String, Object> insertBatchName(String batchName, String cohortNumber, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.batch (batch_name, cohort_number, created_by, updated_by)
                 VALUES (:batchName, :cohortNumber::integer, :createdBy::numeric, :createdBy::numeric)
                 ON CONFLICT (cohort_number, batch_name) DO NOTHING
                 RETURNING *
                """).param("batchName", batchName).param("cohortNumber", cohortNumber).param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    public boolean cohortNameExists(String cohortName) {
        return jdbc.sql("SELECT 1 FROM pp.cohort WHERE cohort_name = :name").param("name", cohortName)
                .query(Integer.class).optional().isPresent();
    }

    public boolean cohortYearExists(int cohortNumber) {
        return jdbc.sql("SELECT 1 FROM pp.cohort WHERE cohort_number = :n").param("n", cohortNumber)
                .query(Integer.class).optional().isPresent();
    }

    public Map<String, Object> insertCohort(int cohortNumber, String cohortName, String startDate, String description) {
        return jdbc.sql("""
                INSERT INTO pp.cohort (cohort_number, cohort_name, start_date, description)
                 VALUES (:cohortNumber, :cohortName, :startDate::date, :description)
                 RETURNING *
                """).param("cohortNumber", cohortNumber).param("cohortName", cohortName)
                .param("startDate", startDate).param("description", description)
                .query((rs, i) -> genericRow(rs)).single();
    }

    public int addStudentsToBatch(String batchId, java.util.List<String> studentIds) {
        return jdbc.sql("UPDATE pp.student_master SET batch_id = :batchId::int WHERE student_id = ANY(:studentIds::bigint[])")
                .param("batchId", batchId).param("studentIds", studentIds.toArray(new String[0])).update();
    }

    /** removeStudentBatchId() parity -- deliberately takes NO batch_id parameter at all (ground truth §7
     *  quirk 10): removal is scoped only by student_ids, never by whatever batch_id the caller sent. */
    public int removeStudentsFromBatch(java.util.List<String> studentIds) {
        return jdbc.sql("UPDATE pp.student_master SET batch_id = NULL WHERE student_id = ANY(:studentIds::bigint[])")
                .param("studentIds", studentIds.toArray(new String[0])).update();
    }

    public Map<String, Object> updateStudentStatus(String newStatus, Object studentId) {
        return jdbc.sql("UPDATE pp.student_master SET active_yn = :status WHERE student_id = :id::numeric RETURNING *")
                .param("status", newStatus).param("id", studentId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }
}
