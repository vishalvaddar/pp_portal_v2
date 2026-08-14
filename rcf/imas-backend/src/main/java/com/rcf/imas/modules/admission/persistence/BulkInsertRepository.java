package com.rcf.imas.modules.admission.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated bean holding the {@code @Transactional} batch insert so Spring's proxy actually applies
 * (self-invocation from {@code BulkUploadService} would bypass the transaction — see plan Transaction note).
 * Any row error throws out of this method → the whole batch rolls back (all-or-nothing at the DB level).
 */
@Repository
public class BulkInsertRepository {

    private final JdbcClient jdbc;
    private final JurisdictionLookupRepository jurisdiction;

    public BulkInsertRepository(JdbcClient jdbc, JurisdictionLookupRepository jurisdiction) {
        this.jdbc = jdbc;
        this.jurisdiction = jurisdiction;
    }

    /**
     * Insert every row in ONE transaction. On any row error, record it in {@code dbErrors} and rethrow
     * so the transaction rolls back — nothing persists. Node caught per-row, pushed a dbError, and
     * re-threw to roll back the batch; we replicate that exactly.
     *
     * @return number of rows inserted (only meaningful when the whole batch commits).
     */
    @Transactional
    public int insertBatch(List<Map<String, Object>> rows, List<String> dbErrors) {
        Map<String, String> cache = new HashMap<>();
        int inserted = 0;
        for (Map<String, Object> row : rows) {
            int rowNum = (int) row.get("originalRowIndex") + 1;
            Object reg = row.get("nmms_reg_number");
            if (reg == null || reg.toString().isBlank()) {
                dbErrors.add("Row " + rowNum + ": NMMS Registration Number is missing");
                throw new IllegalStateException("Missing NMMS Reg No");
            }
            try {
                String state = cachedLookup(cache, str(row.get("app_state")), "STATE", null);
                String district = cachedLookup(cache, str(row.get("district")), "EDUCATION DISTRICT", state);
                String block = cachedLookup(cache, str(row.get("nmms_block")), "BLOCK", district);

                // Convention 1: numeric columns bound from String params must cast the param (:param::numeric).
                jdbc.sql("""
                        INSERT INTO pp.applicant_primary_info (
                          nmms_year, nmms_reg_number, app_state, district, nmms_block,
                          student_name, father_name, mother_name, gender, dob, aadhaar,
                          gmat_score, sat_score, medium, home_address, family_income_total,
                          contact_no1, contact_no2, current_institute_dise_code,
                          previous_institute_dise_code, created_by, updated_by
                        ) VALUES (
                          :nmms_year::numeric, :nmms_reg_number::numeric, :app_state::numeric, :district::numeric, :nmms_block::numeric,
                          :student_name, :father_name, :mother_name, :gender, CAST(:dob AS DATE), :aadhaar,
                          :gmat_score::numeric, :sat_score::numeric, :medium, :home_address, :family_income_total::numeric,
                          :contact_no1, :contact_no2, :current_institute_dise_code,
                          :previous_institute_dise_code, 1, 1
                        )
                        """)
                        .param("nmms_year", str(row.get("nmms_year")))
                        .param("nmms_reg_number", str(row.get("nmms_reg_number")))
                        .param("app_state", state)
                        .param("district", district)
                        .param("nmms_block", block)
                        .param("student_name", row.get("student_name"))
                        .param("father_name", row.get("father_name"))
                        .param("mother_name", row.get("mother_name"))
                        .param("gender", row.get("gender"))
                        .param("dob", row.get("dob"))
                        .param("aadhaar", row.get("aadhaar"))
                        .param("gmat_score", str(row.get("gmat_score")))
                        .param("sat_score", str(row.get("sat_score")))
                        .param("medium", row.get("medium"))
                        .param("home_address", row.get("home_address"))
                        .param("family_income_total", str(row.get("family_income_total")))
                        .param("contact_no1", row.get("contact_no1"))
                        .param("contact_no2", row.get("contact_no2"))
                        .param("current_institute_dise_code", row.get("current_institute_dise_code"))
                        .param("previous_institute_dise_code", row.get("previous_institute_dise_code"))
                        .update();
                inserted++;
            } catch (RuntimeException rowErr) {
                dbErrors.add("Row " + rowNum + " (Reg No: " + reg + ") failed. " + rowErr.getMessage());
                throw rowErr;  // rollback whole batch
            }
        }
        return inserted;
    }

    private String cachedLookup(Map<String, String> cache, String name, String type, String parent) {
        String key = type + ":" + name + ":" + (parent == null ? "0" : parent);
        if (cache.containsKey(key)) return cache.get(key);
        String id = jurisdiction.findCodeByName(name, type, parent)
                .orElseThrow(() -> new IllegalStateException(
                        "Location not found: " + type + " " +
                        (name == null ? "" : name.trim().replaceAll("[.,]+$", "").toUpperCase())));
        cache.put(key, id);
        return id;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
