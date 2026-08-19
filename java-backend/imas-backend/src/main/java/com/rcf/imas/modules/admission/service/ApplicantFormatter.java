package com.rcf.imas.modules.admission.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Output-only parity with applicantController.js formatResponse + the two Node date sanitizers.
 * formatResponse mutates the row map in place (like the Node spread + reassignment).
 */
@Component
public class ApplicantFormatter {

    private static final Map<String, String> GENDER = Map.of("M", "Male", "F", "Female", "O", "Other");

    // Controller sanitizeDate: moment.utc(s, ["DD-MM-YYYY","YYYY-MM-DD", ISO_8601]) — lenient.
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-uuuu");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** In-place: gender code → word (only for M/F/O), dob → YYYY-MM-DD. */
    public void formatResponse(Map<String, Object> row) {
        if (row == null) return;

        Object g = row.get("gender");
        if (g instanceof String gs && GENDER.containsKey(gs)) {
            row.put("gender", GENDER.get(gs));
        }

        Object dob = row.get("dob");
        if (dob != null) {
            LocalDate d = toLocalDate(dob);
            if (d != null) row.put("dob", d.format(YMD));
        }
    }

    private static LocalDate toLocalDate(Object dob) {
        if (dob instanceof java.sql.Date sd) return sd.toLocalDate();
        if (dob instanceof LocalDate ld) return ld;
        if (dob instanceof java.util.Date ud) {
            return ud.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        if (dob instanceof String s && !s.isBlank()) {
            // already stored as ISO by our sanitizers; parse first 10 chars defensively
            try { return LocalDate.parse(s.substring(0, Math.min(10, s.length())), YMD); }
            catch (RuntimeException e) { return null; }
        }
        return null;
    }

    /** Lenient controller sanitizeDate: DD-MM-YYYY | YYYY-MM-DD | ISO_8601 → YYYY-MM-DD, else null. */
    public String sanitizeControllerDate(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        LocalDate d = tryParse(s, DMY);
        if (d == null) d = tryParse(s, YMD);
        if (d == null) {
            // ISO_8601 (with time/zone): take the date part
            try { d = java.time.OffsetDateTime.parse(s).toLocalDate(); }
            catch (RuntimeException e1) {
                try { d = java.time.LocalDateTime.parse(s).toLocalDate(); }
                catch (RuntimeException e2) { d = null; }
            }
        }
        return d == null ? null : d.format(YMD);
    }

    /** Strict bulk sanitizeDate: DD-MM-YYYY | YYYY-MM-DD only (no ISO fallback) → YYYY-MM-DD, else null. */
    public String sanitizeBulkDate(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        LocalDate d = tryParse(s, DMY);
        if (d == null) d = tryParse(s, YMD);
        return d == null ? null : d.format(YMD);
    }

    private static LocalDate tryParse(String s, DateTimeFormatter f) {
        try { return LocalDate.parse(s, f); }
        catch (RuntimeException e) { return null; }
    }
}
