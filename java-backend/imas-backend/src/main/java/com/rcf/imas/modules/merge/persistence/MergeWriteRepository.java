package com.rcf.imas.modules.merge.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Dedicated bean for all multi-statement transactional merge writes.
 * (Convention #8: Spring does not intercept self-invoked @Transactional — these must live in their own bean.)
 */
@Repository
public class MergeWriteRepository {

    private final JdbcClient jdbc;

    public MergeWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long countStagedP1(String districtId, String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = :d::numeric AND nmms_year = :y")
                .param("d", districtId).param("y", year).query(Long.class).single();
    }

    public long countStagedP2(String districtId, String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = :d::numeric AND nmms_year = :y")
                .param("d", districtId).param("y", year).query(Long.class).single();
    }

    /** Batch-insert validated phase-1 rows in one transaction. Each map holds the 13 insert values keyed by column. */
    @Transactional
    public void insertP1(List<Map<String, Object>> rows) {
        String sql = """
            INSERT INTO pp.stg_nmms_phase1_applications
              (nmms_year, exam, district, app_state, nmms_block, current_institute_dise_code, students_sats_id,
               student_name, father_name, institute_name, contact_no1, contact_no2, student_name_key)
            VALUES (:nmms_year, :exam, :district::numeric, :app_state::numeric, :nmms_block::numeric, :dise, :sats,
                    :student_name, :father_name, :institute_name, :contact_no1, :contact_no2, :name_key)
            """;
        for (Map<String, Object> r : rows) {
            jdbc.sql(sql)
                .param("nmms_year", r.get("nmms_year"))
                .param("exam", r.get("exam"))
                .param("district", r.get("district"))
                .param("app_state", r.get("app_state"))
                .param("nmms_block", r.get("nmms_block"))
                .param("dise", r.get("dise"))
                .param("sats", r.get("sats"))
                .param("student_name", r.get("student_name"))
                .param("father_name", r.get("father_name"))
                .param("institute_name", r.get("institute_name"))
                .param("contact_no1", r.get("contact_no1"))
                .param("contact_no2", r.get("contact_no2"))
                .param("name_key", r.get("name_key"))
                .update();
        }
    }

    /** Batch-insert validated phase-2 rows in one transaction (match_status defaults to PENDING). */
    @Transactional
    public void insertP2(List<Map<String, Object>> rows) {
        String sql = """
            INSERT INTO pp.stg_nmms_phase2_results
              (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (:nmms_year, :district::numeric, :nmms_block::numeric, :reg, :student_name, :gmat, :sat, :name_key)
            """;
        for (Map<String, Object> r : rows) {
            jdbc.sql(sql)
                .param("nmms_year", r.get("nmms_year"))
                .param("district", r.get("district"))
                .param("nmms_block", r.get("nmms_block"))
                .param("reg", r.get("reg"))
                .param("student_name", r.get("student_name"))
                .param("gmat", r.get("gmat"))
                .param("sat", r.get("sat"))
                .param("name_key", r.get("name_key"))
                .update();
        }
    }

    /** bulk-auto-map: copy unique 1:1 name-key matches into draft, then mark those phase-2 rows MATCHED. */
    @Transactional
    public void moveMappedToStd(String districtId, String year, String userId) {
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info
              (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
               nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
            SELECT a.nmms_year::numeric, r.nmms_reg_number::numeric,
                   NULLIF(regexp_replace(a.students_sats_id, '\\D', '', 'g'), '')::numeric,
                   a.student_name, a.father_name, a.app_state::numeric, a.district::numeric, a.nmms_block::numeric,
                   (CASE WHEN r.gmat_score = 'AB' OR r.gmat_score IS NULL THEN '0' ELSE r.gmat_score END)::numeric,
                   (CASE WHEN r.sat_score  = 'AB' OR r.sat_score  IS NULL THEN '0' ELSE r.sat_score  END)::numeric,
                   a.contact_no1, a.contact_no2, a.current_institute_dise_code, :userId::numeric
            FROM pp.stg_nmms_phase1_applications a
            JOIN pp.stg_nmms_phase2_results r
              ON LOWER(REGEXP_REPLACE(a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(r.student_name, '[^a-zA-Z0-9]', '', 'g'))
             AND a.nmms_block = r.nmms_block
            WHERE a.district = :district::numeric AND a.nmms_year = :year AND r.match_status != 'MATCHED'
              AND a.id IN (
                SELECT sub_a.id FROM pp.stg_nmms_phase1_applications sub_a
                JOIN pp.stg_nmms_phase2_results sub_r
                  ON LOWER(REGEXP_REPLACE(sub_a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(sub_r.student_name, '[^a-zA-Z0-9]', '', 'g'))
                 AND sub_a.nmms_block = sub_r.nmms_block
                WHERE sub_a.district = :district::numeric GROUP BY sub_a.id HAVING COUNT(*) = 1)
            ON CONFLICT (nmms_reg_number) DO NOTHING
            """).param("district", districtId).param("year", year).param("userId", userId).update();

        jdbc.sql("""
            UPDATE pp.stg_nmms_phase2_results r SET match_status = 'MATCHED'
            FROM pp.std_applicant_primary_info s
            WHERE r.nmms_reg_number::numeric = s.nmms_reg_number AND r.district = :district::numeric
            """).param("district", districtId).update();
    }

    private static final String STD_INSERT = """
        INSERT INTO pp.std_applicant_primary_info
          (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
           nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
        VALUES (:nmms_year::numeric, :reg::numeric, NULLIF(regexp_replace(:sats, '\\D', '', 'g'), '')::numeric,
                :student_name, :father_name, :app_state::numeric, :district::numeric, :nmms_block::numeric,
                :gmat::numeric, :sat::numeric, :contact_no1, :contact_no2, :dise, :userId::numeric)
        """;

    /** resolve-lively: manual pair insert + domino auto-match of the remaining unique pair. */
    @Transactional
    public void resolveMatch(String appId, String resId, String userId) {
        Map<String, Object> app = jdbc.sql("SELECT * FROM pp.stg_nmms_phase1_applications WHERE id = :id::bigint")
                .param("id", appId).query((rs, i) -> MergeReadRepository.genericRow(rs)).optional().orElse(null);
        Map<String, Object> res = jdbc.sql("SELECT * FROM pp.stg_nmms_phase2_results WHERE result_stg_id = :id::bigint")
                .param("id", resId).query((rs, i) -> MergeReadRepository.genericRow(rs)).optional().orElse(null);
        if (app == null || res == null) throw new IllegalStateException("Records not found.");

        insertStd(app, res, userId);
        markMatched(resId);

        // domino: same normalized-name + block remaining rows
        String name = (String) app.get("student_name");
        Object block = app.get("nmms_block");
        List<Map<String, Object>> remApps = jdbc.sql("""
                SELECT * FROM pp.stg_nmms_phase1_applications
                WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(:name, '[^a-zA-Z0-9]', '', 'g'))
                  AND nmms_block = :block::numeric
                """).param("name", name).param("block", block).query((rs, i) -> MergeReadRepository.genericRow(rs)).list();
        List<Map<String, Object>> remRes = jdbc.sql("""
                SELECT * FROM pp.stg_nmms_phase2_results
                WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(:name, '[^a-zA-Z0-9]', '', 'g'))
                  AND nmms_block = :block::numeric AND match_status != 'MATCHED'
                """).param("name", name).param("block", block).query((rs, i) -> MergeReadRepository.genericRow(rs)).list();

        if (remApps.size() == 1 && remRes.size() == 1) {
            insertStd(remApps.get(0), remRes.get(0), userId);
            markMatched(String.valueOf(remRes.get(0).get("result_stg_id")));
        }
    }

    private void insertStd(Map<String, Object> app, Map<String, Object> res, String userId) {
        jdbc.sql(STD_INSERT)
            .param("nmms_year", app.get("nmms_year"))
            .param("reg", res.get("nmms_reg_number"))
            .param("sats", app.get("students_sats_id"))
            .param("student_name", app.get("student_name"))
            .param("father_name", app.get("father_name"))
            .param("app_state", app.get("app_state"))
            .param("district", app.get("district"))
            .param("nmms_block", app.get("nmms_block"))
            .param("gmat", "AB".equals(res.get("gmat_score")) ? "0" : res.get("gmat_score"))
            .param("sat", "AB".equals(res.get("sat_score")) ? "0" : res.get("sat_score"))
            .param("contact_no1", app.get("contact_no1"))
            .param("contact_no2", app.get("contact_no2"))
            .param("dise", app.get("current_institute_dise_code"))
            .param("userId", userId)
            .update();
    }

    private void markMatched(String resId) {
        jdbc.sql("UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = :id::bigint")
            .param("id", resId).update();
    }

    /** commit-to-primary: freeze draft rows into the primary table for a district/year. */
    @Transactional
    public void commitToPrimary(String districtId, String year) {
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info
              (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
               nmms_block, gmat_score, sat_score, created_by, current_institute_dise_code, contact_no1, contact_no2)
            SELECT nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
                   nmms_block, gmat_score, sat_score, created_by, current_institute_dise_code, contact_no1, contact_no2
            FROM pp.std_applicant_primary_info WHERE district = :district::numeric AND nmms_year = :year::numeric
            ON CONFLICT (nmms_reg_number) DO NOTHING
            """).param("district", districtId).param("year", year).update();
    }

    /** Whitelisted deletable targets. The request's phase/section maps to one of these three; nothing else is deletable. */
    public enum DeleteTarget {
        P1("pp.stg_nmms_phase1_applications"),
        P2("pp.stg_nmms_phase2_results"),
        MERGE("pp.std_applicant_primary_info");
        final String table;
        DeleteTarget(String table) { this.table = table; }
    }

    public long deleteDistrictData(DeleteTarget target, String districtId) {
        // target.table is a compile-time constant from the enum — never the request value.
        return jdbc.sql("DELETE FROM " + target.table + " WHERE district = :d::numeric")
                .param("d", districtId).update();
    }
}
