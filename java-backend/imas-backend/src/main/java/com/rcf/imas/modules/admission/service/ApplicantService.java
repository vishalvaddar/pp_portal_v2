package com.rcf.imas.modules.admission.service;

import com.rcf.imas.modules.admission.persistence.ApplicantRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApplicantService {

    private static final List<String> REQUIRED = List.of(
            "nmms_year", "nmms_reg_number", "student_name", "father_name",
            "medium", "contact_no1", "district", "nmms_block");

    // exact copy of the Node buildPrimaryData key set (sanitizeValue trims strings; empty→null)
    private static final List<String> PRIMARY_KEYS = List.of(
            "nmms_year", "nmms_reg_number", "app_state", "district", "nmms_block",
            "student_name", "father_name", "mother_name", "gmat_score", "sat_score",
            "gender", "medium", "aadhaar", "home_address", "family_income_total",
            "contact_no1", "contact_no2", "current_institute_dise_code", "previous_institute_dise_code");

    private final ApplicantRepository repo;
    private final ApplicantFormatter formatter;

    public ApplicantService(ApplicantRepository repo, ApplicantFormatter formatter) {
        this.repo = repo;
        this.formatter = formatter;
    }

    /** Returns applicant_id (String). Throws ApiException for validation / 23505. */
    public String create(Map<String, Object> primaryData, Map<String, Object> secondaryData, String userId) {
        // Node "missing" = JS falsy: null, "", 0, undefined. Match with a falsy check.
        List<String> missing = REQUIRED.stream().filter(f -> isFalsy(primaryData.get(f))).toList();
        if (!missing.isEmpty()) {
            throw ApiException.message(400, "Missing fields: " + String.join(", ", missing)).with("success", false);
        }
        Object contact = primaryData.get("contact_no1");
        if (contact == null || !contact.toString().matches("\\d{10}")) {
            throw ApiException.message(400, "Invalid contact_no1").with("success", false);
        }

        Map<String, Object> primary = new LinkedHashMap<>();
        for (String k : PRIMARY_KEYS) primary.put(k, sanitize(primaryData.get(k)));
        primary.put("dob", formatter.sanitizeControllerDate(asString(primaryData.get("dob"))));

        Map<String, Object> secondary = secondaryData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(secondaryData);

        try {
            return repo.insertApplicant(primary, secondary, userId);
        } catch (DuplicateKeyException e) {
            throw ApiException.message(400, "Registration Number already exists").with("success", false);
        }
    }

    /** Update primary + upsert secondary. No 404 (silent 0-row). Node's update writes only updated_at (no updated_by), so userId is not needed. */
    public void update(String applicantId, Map<String, Object> primaryData, Map<String, Object> secondaryData) {
        Map<String, Object> primary = null;
        if (primaryData != null) {
            primary = new LinkedHashMap<>();
            for (String k : PRIMARY_KEYS) primary.put(k, sanitize(primaryData.get(k)));
            primary.remove("nmms_reg_number");  // never updated
            primary.put("dob", formatter.sanitizeControllerDate(asString(primaryData.get("dob"))));
        }
        // secondaryData passed through as-is (Node only sets updated_by = userId on it); default wheel counts handled in repo
        repo.updateApplicant(applicantId, primary, secondaryData);
    }

    private static boolean isFalsy(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() == 0d;
        if (v instanceof Boolean b) return !b;
        return false;
    }

    // sanitizeValue: undefined/null/"" → null; strings trimmed; else as-is
    private static Object sanitize(Object v) {
        if (v == null) return null;
        if (v instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
        return v;
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
