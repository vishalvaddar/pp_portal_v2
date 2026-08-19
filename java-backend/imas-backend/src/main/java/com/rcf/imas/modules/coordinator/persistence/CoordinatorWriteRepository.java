package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

@Repository
public class CoordinatorWriteRepository {

    private final JdbcClient jdbc;

    public CoordinatorWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** classroomModel.createClassroom parity. created_by/updated_by are trusted from the request body,
     *  NOT derived from the authenticated principal (Firm Decision 9 / LOCKED CONVENTIONS #9) -- a deliberate,
     *  documented, non-"fixed" quirk. Single statement, no @Transactional needed. */
    public Map<String, Object> createClassroom(String classroomName, String subjectId, String teacherId,
                                                 String platformId, String classLink, String activeYn,
                                                 String createdBy, String updatedBy) {
        return jdbc.sql("""
                INSERT INTO pp.classroom
                 (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
                VALUES (:name, :subjectId::integer, :teacherId::integer, :platformId::integer,
                        :classLink, :activeYn, :createdBy::numeric, :updatedBy::numeric)
                RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("createdBy", createdBy).param("updatedBy", updatedBy)
                .query((rs, i) -> genericRow(rs)).single();
    }

    /**
     * Hard, closed whitelist for updateStudentModel's dynamic SET clause (LOCKED CONVENTIONS #8, ground
     * truth §8.10). Any request-body key NOT listed here is silently ignored -- never interpolated into SQL.
     * batch_id is the only column needing an explicit ::integer cast (everything else is varchar/char/text,
     * for which Postgres accepts an implicit text bind). Final 21-column list. NOTE: student_email_password
     * is DELIBERATELY EXCLUDED though the client round-trips it -- the form renders it read-only, so the
     * value never changes, and excluding it (a) leaves the DB identical for the frozen client and (b) stops
     * any authenticated caller from overwriting a student's email-account password via this endpoint.
     */
    private enum StudentUpdatableColumn {
        STUDENT_NAME("student_name"), FATHER_NAME("father_name"), FATHER_OCCUPATION("father_occupation"),
        MOTHER_NAME("mother_name"), MOTHER_OCCUPATION("mother_occupation"), GENDER("gender"),
        STUDENT_EMAIL("student_email"),
        PARENT_EMAIL("parent_email"), CONTACT_NO1("contact_no1"), CONTACT_NO2("contact_no2"),
        HOME_ADDRESS("home_address"), CURRENT_INSTITUTE_DISE_CODE("current_institute_dise_code"),
        PREVIOUS_INSTITUTE_DISE_CODE("previous_institute_dise_code"), SIM_NAME("sim_name"),
        TEACHER_NAME("teacher_name"), TEACHER_MOBILE_NUMBER("teacher_mobile_number"),
        RECHARGE_STATUS("recharge_status"), SPONSOR("sponsor"), PHOTO_LINK("photo_link"),
        BATCH_ID("batch_id", "::integer"), ACTIVE_YN("active_yn");

        final String column;
        final String castSuffix;
        StudentUpdatableColumn(String column) { this(column, ""); }
        StudentUpdatableColumn(String column, String castSuffix) { this.column = column; this.castSuffix = castSuffix; }
    }

    /** updateStudentModel parity, whitelist-filtered. inactive_reason is never a real column (excluded by
     *  construction, not by a delete-from-payload step like Node's). active_yn is NOT uppercased here --
     *  the controller normalizes it before calling this method (mirrors Node's payload.active_yn =
     *  payload.active_yn.toUpperCase() happening in the model, but Java keeps that string-massaging in the
     *  controller alongside the inactive-branch decision, since both need the same uppercased value).
     *  No-op (does nothing, no exception) if the payload contains zero whitelisted keys -- Node parity. */
    public void updateStudent(String studentId, Map<String, Object> payload) {
        List<StudentUpdatableColumn> present = new ArrayList<>();
        for (StudentUpdatableColumn col : StudentUpdatableColumn.values()) {
            if (payload.containsKey(col.column)) present.add(col);
        }
        if (present.isEmpty()) return;

        List<String> setFragments = new ArrayList<>();
        for (StudentUpdatableColumn col : present) {
            setFragments.add(col.column + " = :" + col.column + col.castSuffix);
        }
        String sql = "UPDATE pp.student_master SET " + String.join(", ", setFragments)
                + ", updated_at = CURRENT_TIMESTAMP WHERE student_id = :studentId::numeric";

        StatementSpec spec = jdbc.sql(sql).param("studentId", studentId);
        for (StudentUpdatableColumn col : present) {
            Object v = payload.get(col.column);
            spec = spec.param(col.column, v == null ? null : String.valueOf(v));
        }
        spec.update();
    }

    /** markStudentInactiveModel parity, made genuinely atomic (LOCKED CONVENTIONS #7 / Firm Decision 4).
     *  Used by BOTH PUT /students/:id's inactive branch and the direct PUT /students/:id/inactive route. */
    @Transactional
    public void markStudentInactive(String studentId, String reason, String userId) {
        jdbc.sql("""
                INSERT INTO pp.inactive_students (student_id, inactive_reason, inactive_date, created_by, updated_by)
                VALUES (:studentId::numeric, :reason, CURRENT_DATE, :userId::numeric, :userId::numeric)
                """).param("studentId", studentId).param("reason", reason).param("userId", userId).update();

        jdbc.sql("""
                UPDATE pp.student_master SET active_yn = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE student_id = :studentId::numeric
                """).param("studentId", studentId).update();
    }
}
