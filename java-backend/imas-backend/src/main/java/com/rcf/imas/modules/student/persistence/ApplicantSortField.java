package com.rcf.imas.modules.student.persistence;

/**
 * Closed whitelist mirroring searchController.js:29-41's `sortableFields` array. Node validates `sort_by`
 * against this list BEFORE the string reaches `ORDER BY a.${sortBy}` in searchModel.js -- the model itself has
 * no guard, only the controller does. This enum enforces the same whitelist at the query-builder boundary so a
 * future caller can never invoke ApplicantSearchReadRepository with an unvalidated column name.
 *
 * BUG PRESERVED VERBATIM: SPL_HEALTH_COND / SPL_FAMILY_COND are in Node's whitelist but map to columns that
 * live on pp.applicant_secondary_info, NOT pp.applicant_primary_info (aliased `a` in the query, the only table
 * this endpoint orders by). Sorting by either one throws "column a.spl_health_cond does not exist" at the
 * database -- an uncaught PG error, mapped by the endpoint's catch block to the SAME 500 envelope Node produces
 * (`{error:"Internal Server Error", details:"..."}`). Do NOT fix -- see ApplicantSearchIT's pinning test.
 */
public enum ApplicantSortField {
    APPLICANT_ID("applicant_id"),
    STUDENT_NAME("student_name"),
    NMMS_YEAR("nmms_year"),
    NMMS_REG_NUMBER("nmms_reg_number"),
    MEDIUM("medium"),
    DISTRICT("district"),
    NMMS_BLOCK("nmms_block"),
    APP_STATE("app_state"),
    CURRENT_INSTITUTE_DISE_CODE("current_institute_dise_code"),
    SPL_HEALTH_COND("spl_health_cond"),
    SPL_FAMILY_COND("spl_family_cond");

    public final String column;

    ApplicantSortField(String column) { this.column = column; }

    public static ApplicantSortField fromRequestOrDefault(String requested) {
        for (ApplicantSortField f : values()) {
            if (f.column.equals(requested)) return f;
        }
        return APPLICANT_ID;
    }
}
