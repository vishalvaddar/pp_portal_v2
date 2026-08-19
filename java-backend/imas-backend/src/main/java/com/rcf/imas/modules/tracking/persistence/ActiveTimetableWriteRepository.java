package com.rcf.imas.modules.tracking.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

import static com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository.genericRow;

@Repository
public class ActiveTimetableWriteRepository {

    private final JdbcClient jdbc;

    public ActiveTimetableWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** addSubject($1=subject_code,$2=subject_name,$3=created_by used for BOTH created_by and updated_by). */
    public Map<String, Object> addSubject(String subjectCode, String subjectName, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.subject (subject_code, subject_name, created_by, updated_by)
                VALUES (:subjectCode, :subjectName, :createdBy::numeric, :createdBy::numeric)
                RETURNING *
                """).param("subjectCode", subjectCode).param("subjectName", subjectName)
                .param("createdBy", createdBy).query((rs, i) -> genericRow(rs)).single();
    }

    /** addTeacherSkill($1=teacherId,$2=subjectId,$3=medium.toUpperCase()) -- medium IS uppercased on add. */
    public void addTeacherSkill(String teacherId, String subjectId, String medium) {
        jdbc.sql("INSERT INTO pp.teacher_subject (teacher_id, subject_id, medium) VALUES (:teacherId::integer, :subjectId::integer, :medium)")
                .param("teacherId", teacherId).param("subjectId", subjectId)
                .param("medium", medium.toUpperCase()).update();
        // NOTE: no 23505 special-case here (quirk 4b/ground truth §7 quirk 7) -- a duplicate (teacher_id,
        // subject_id, medium) throws a raw DataIntegrityViolationException that the controller's generic
        // catch turns into 500 {error:"Database error: "+message}, matching Node exactly.
    }

    /**
     * deleteTeacherSkill($1=teacherId,$2=subjectId,$3=medium) -- medium is NOT uppercased here (quirk 4d,
     * unlike addTeacherSkill). A case-mismatched call silently deletes 0 rows -- no rowcount check, no error.
     */
    public void deleteTeacherSkill(String teacherId, String subjectId, String medium) {
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = :teacherId::integer AND subject_id = :subjectId::integer AND medium = :medium")
                .param("teacherId", teacherId).param("subjectId", subjectId).param("medium", medium).update();
    }
}
