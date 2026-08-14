package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

@Repository
public class BatchReadRepository {

    /** Duplicated in BatchController too (convention #13) -- mirrors Node's own duplication of this literal
     *  across batchController.js:3 and the fetchAllBatches query string. */
    static final int COHORT_START_YEAR = 2021;

    private final JdbcClient jdbc;

    public BatchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public static class CoordinatorRoleNotFoundException extends RuntimeException {}

    public List<Map<String, Object>> coordinators() {
        Long roleId = jdbc.sql("SELECT role_id FROM pp.role WHERE role_name = 'BATCH COORDINATOR'")
                .query(Long.class).optional().orElseThrow(CoordinatorRoleNotFoundException::new);
        return jdbc.sql("""
                SELECT u.user_id AS id, u.user_name AS name
                 FROM pp.user u
                 JOIN pp.user_role ur ON u.user_id = ur.user_id
                 WHERE ur.role_id = :roleId
                """).param("roleId", roleId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<String> batchNames() {
        return jdbc.sql("SELECT batch_name FROM pp.batch ORDER BY batch_name ASC").query(String.class).list();
    }

    public List<Map<String, Object>> allCohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name, start_date, description FROM pp.cohort ORDER BY cohort_number ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> activeCohorts() {
        return jdbc.sql("SELECT * FROM pp.cohort WHERE end_date IS NULL").query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> studentsNotInAnyBatch() {
        return jdbc.sql("""
                SELECT sm.student_id, sm.enr_id, sm.student_name, sm.student_email, sm.contact_no1
                 FROM pp.student_master sm
                 WHERE sm.batch_id IS NULL AND sm.active_yn = 'ACTIVE'
                 ORDER BY sm.student_name
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** fetchStudentInfoByEnrId -- reused verbatim by both endpoint #18 (display) and, in Task 5, by
     *  updateStudentStatusInBatch's enr_id->student_id resolution (ground truth §7 quirk 9). */
    public Optional<Map<String, Object>> studentInfoByEnrId(String enrId) {
        return jdbc.sql("""
                SELECT
                   sm.student_id, sm.enr_id,
                   api.nmms_reg_number, api.nmms_year, api.student_name, api.father_name, api.mother_name,
                   api.gender, api.aadhaar, api.dob, api.medium, api.home_address, api.family_income_total,
                   api.contact_no1, api.contact_no2, api.current_institute_dise_code, api.previous_institute_dise_code,
                   asi.village, asi.father_occupation, asi.mother_occupation, asi.father_education, asi.mother_education,
                   asi.household_size, asi.own_house, asi.smart_phone_home, asi.internet_facility_home,
                   asi.career_goals, asi.subjects_of_interest, asi.transportation_mode, asi.distance_to_school,
                   asi.num_two_wheelers, asi.num_four_wheelers, asi.irrigation_land, asi.neighbor_name,
                   asi.neighbor_phone, asi.favorite_teacher_name, asi.favorite_teacher_phone
                 FROM pp.student_master sm
                 JOIN pp.applicant_primary_info api USING (applicant_id)
                 JOIN pp.applicant_secondary_info asi USING (applicant_id)
                 WHERE sm.enr_id = :enrId::numeric
                """).param("enrId", enrId).query((rs, i) -> genericRow(rs)).optional();
    }

    /** batchModel.fetchBatchesByCohortNumber -- SELECT *, batch-module version. Distinct from
     *  ClassroomReadRepository.batchesByCohortClassroomSide (ground truth §7 quirk 5) -- no ORDER BY in Node,
     *  so none here either (verbatim). */
    public List<Map<String, Object>> batchesByCohortBatchSide(String cohortNumber) {
        return jdbc.sql("SELECT * FROM pp.batch WHERE cohort_number = :cohortNumber::integer")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }

    /** fetchAllBatches -- active-academic-year-cohort-scoped (Firm Decision 4). COHORT_START_YEAR is
     *  string-interpolated into the query text exactly like Node's own hard-coded literal (not a bind param --
     *  it is a server constant, never user input, matching the ground truth's own injection-risk note). */
    public List<Map<String, Object>> allBatches() {
        String sql = """
                SELECT
                  b.batch_id AS id, b.batch_name, b.cohort_number, c.cohort_name,
                  u.user_name AS coordinator_name, u.user_id AS coordinator_id
                FROM pp.batch b
                LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                LEFT JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                LEFT JOIN pp.user u ON bcb.user_id = u.user_id
                WHERE EXISTS (
                  SELECT 1 FROM pp.system_config sc
                  WHERE sc.is_active = 'true'
                  AND c.cohort_number = (CAST(SUBSTRING(sc.academic_year FROM 1 FOR 4) AS INTEGER) - %d)
                )
                ORDER BY b.batch_id DESC
                """.formatted(COHORT_START_YEAR);
        return jdbc.sql(sql).query((rs, i) -> genericRow(rs)).list();
    }

    public Optional<Map<String, Object>> batchById(String batchId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, c.cohort_name
                 FROM pp.batch b
                 LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                 WHERE b.batch_id = :batchId::integer
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).optional();
    }

    public List<Map<String, Object>> studentsInBatch(String batchId) {
        return jdbc.sql("""
                SELECT
                   sm.student_id, sm.enr_id, sm.student_name, sm.student_email,
                   sm.contact_no1, sm.active_yn, api.nmms_reg_number
                 FROM pp.student_master sm
                 JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                 WHERE sm.batch_id = :batchId::integer
                 ORDER BY sm.student_name
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }
}
