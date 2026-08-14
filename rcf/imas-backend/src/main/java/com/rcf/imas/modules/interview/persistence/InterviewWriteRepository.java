package com.rcf.imas.modules.interview.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class InterviewWriteRepository {

    /** The single sentinel — Node defines NO_INTERVIEWER_ID twice (controller + model), byte-identical;
     *  consolidated to ONE constant here (Firm Decision 3). Used by reassignStudents (Task 4). */
    static final String NO_INTERVIEWER_ID = "NO_ONE";

    private final JdbcClient jdbc;

    public InterviewWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * assignStudents(applicantIds, interviewerId, nmmsYear) — interviewModel.js:430-564 (the live copy; a
     * byte-identical dead copy at :294-428 is NOT ported). Whole-batch @Transactional: any thrown error rolls
     * back every applicant's writes. Per-applicant, up to 4 sequential statements. Ported verbatim, all 4 branches.
     * applicantIds echoed back in the result maps as their original JSON value (input-order preserved).
     */
    @Transactional
    public List<Map<String, Object>> assignStudents(List<Object> applicantIds, String interviewerId, String nmmsYear) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Object applicantId : applicantIds) {
            String aid = String.valueOf(applicantId);

            // 1. last interview (by round, not date)
            Map<String, Object> last = jdbc.sql("""
                    SELECT interview_id, interview_round, status, interview_result
                    FROM pp.student_interview
                    WHERE applicant_id = :aid::numeric
                    ORDER BY interview_round DESC
                    LIMIT 1
                    """).param("aid", aid).query((rs, i) -> InterviewReadRepository.genericRow(rs)).optional().orElse(null);

            int nextRound = 1;

            if (last != null) {
                String status = upper(last.get("status"));
                String result = upper(last.get("interview_result"));
                int round = ((Number) last.get("interview_round")).intValue();

                // A. max rounds
                if (round >= 3) {
                    results.add(skipped(applicantId, "Max rounds reached (3 rounds completed)."));
                    continue;
                }

                if ("RESCHEDULED".equals(status) && "ANOTHER INTERVIEW REQUIRED".equals(result)) {
                    // B. eligible for next round
                    nextRound = round + 1;
                } else if ("CANCELLED".equals(status)) {
                    // C. fix a CANCELLED record in place (UPDATE, reuse same row + pre-existing round)
                    int rc = jdbc.sql("""
                            UPDATE pp.student_interview
                            SET interviewer_id = :iid::numeric,
                                status = 'SCHEDULED'
                            WHERE interview_id = :interviewId::numeric
                              AND applicant_id = :aid::numeric
                            """).param("iid", interviewerId).param("interviewId", last.get("interview_id")).param("aid", aid).update();
                    if (rc > 0) {
                        results.add(assigned(applicantId, round)); // pre-existing round, not nextRound
                        continue;
                    }
                    // rc == 0: Node does NOT continue here (actionTaken stays false) -> fall through to dup-check + insert
                } else {
                    // D. ineligible
                    results.add(skipped(applicantId,
                            "Current status (" + status + ") or result (" + (result != null ? result : "NONE") + ") does not allow reassignment."));
                    continue;
                }
            }

            // 2. cross-round duplicate-interviewer guard (ANY round)
            boolean alreadyAssigned = !jdbc.sql("""
                    SELECT 1 FROM pp.student_interview
                    WHERE applicant_id = :aid::numeric AND interviewer_id = :iid::numeric
                    """).param("aid", aid).param("iid", interviewerId).query(Integer.class).list().isEmpty();
            if (alreadyAssigned) {
                results.add(skipped(applicantId, "Already assigned to this interviewer in a previous round."));
                continue;
            }

            // 3. insert new round (guarded against applicant/year mismatch by the INSERT...SELECT)
            Integer insertedRound = jdbc.sql("""
                    INSERT INTO pp.student_interview (interviewer_id, applicant_id, interview_round, status)
                    SELECT :iid::numeric, :aid::numeric, :round, 'SCHEDULED'
                    FROM pp.applicant_primary_info api
                    WHERE api.applicant_id = :aid::numeric AND api.nmms_year = :year::numeric
                    RETURNING interview_round
                    """).param("iid", interviewerId).param("aid", aid).param("round", nextRound).param("year", nmmsYear)
                    .query(Integer.class).optional().orElse(null);

            if (insertedRound != null) {
                results.add(assigned(applicantId, insertedRound));
            } else {
                results.add(skipped(applicantId, "Student data not found for the specified year."));
            }
        }
        return results;
    }

    /**
     * reassignStudents(applicantIds, newInterviewerId, nmmsYear) — interviewModel.js:666-743. Whole-batch
     * @Transactional. isCancellation when newInterviewerId == "NO_ONE" (the single NO_INTERVIEWER_ID constant).
     * Each branch is ONE UPDATE ... RETURNING interview_round, status. The cancellation UPDATE has no LIMIT
     * (Node quirk 7): it cancels ALL matching SCHEDULED/RESCHEDULED rows but only rows[0] is reported — preserved
     * here by reading .list() and taking element 0. Reported status is the literal DB value.
     */
    @Transactional
    public List<Map<String, Object>> reassignStudents(List<Object> applicantIds, String newInterviewerId, String nmmsYear) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean isCancellation = NO_INTERVIEWER_ID.equals(String.valueOf(newInterviewerId));

        for (Object applicantId : applicantIds) {
            String aid = String.valueOf(applicantId);
            List<Map<String, Object>> rows;

            if (isCancellation) {
                rows = jdbc.sql("""
                        UPDATE pp.student_interview si
                        SET interviewer_id = NULL,
                            status = 'CANCELLED'
                        FROM pp.applicant_primary_info api
                        WHERE si.applicant_id = api.applicant_id
                          AND si.applicant_id = :aid::numeric
                          AND api.nmms_year = :year::numeric
                          AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
                        RETURNING si.interview_round, si.status
                        """).param("aid", aid).param("year", nmmsYear)
                        .query((rs, i) -> InterviewReadRepository.genericRow(rs)).list();
            } else {
                rows = jdbc.sql("""
                        UPDATE pp.student_interview si
                        SET interviewer_id = :iid::numeric,
                            status = 'RESCHEDULED'
                        FROM pp.applicant_primary_info api
                        WHERE si.applicant_id = :aid::numeric
                          AND api.applicant_id = si.applicant_id
                          AND api.nmms_year = :year::numeric
                          AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
                          AND si.interview_result IS NULL
                          AND si.interviewer_id IS DISTINCT FROM :iid::numeric
                        RETURNING si.interview_round, si.status
                        """).param("iid", newInterviewerId).param("aid", aid).param("year", nmmsYear)
                        .query((rs, i) -> InterviewReadRepository.genericRow(rs)).list();
            }

            if (!rows.isEmpty()) {
                Map<String, Object> r0 = rows.get(0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("applicantId", applicantId);
                m.put("status", r0.get("status"));               // literal DB status: 'CANCELLED' or 'RESCHEDULED'
                m.put("interviewRound", r0.get("interview_round"));
                results.add(m);
            } else {
                results.add(skipped(applicantId, isCancellation
                        ? "Already unassigned or not in a cancellable state"
                        : "Student is already assigned to this interviewer or has a finalized result"));
            }
        }
        return results;
    }

    static Map<String, Object> assigned(Object applicantId, int round) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicantId", applicantId);
        m.put("status", "Assigned");
        m.put("interviewRound", round);
        return m;
    }

    static Map<String, Object> skipped(Object applicantId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicantId", applicantId);
        m.put("status", "Skipped");
        m.put("reason", reason);
        return m;
    }

    static String upper(Object o) { return o == null ? null : String.valueOf(o).toUpperCase().trim(); }

    /**
     * generateEnrollmentId(nmmsYear) — the racy MAX+1-per-year scheme duplicated in Node's submitInterviewDetails
     * (interviewModel.js:851-860) and submitHomeVerification (:961-972), consolidated to ONE helper (Firm Decision 4).
     * MAX(CAST(SUBSTRING(enr_id::TEXT,5) AS INTEGER)) over existing student_master rows for the year, +1, zero-pad to 4.
     * NO row lock / advisory lock / sequence — the race is accepted exactly as Node does it (documented in risk section).
     * Returns the enr_id as a STRING "<year><padded4>" (e.g. "20270001"); callers bind it with an explicit ::numeric cast.
     */
    private String generateEnrollmentId(String applicantId, String nmmsYear) {
        // reuse an existing enr_id for this applicant if present (prevents duplicates on re-submit)
        String existing = jdbc.sql("SELECT enr_id FROM pp.student_master WHERE applicant_id = :aid::numeric")
                .param("aid", applicantId)
                .query((rs, i) -> {
                    java.math.BigDecimal v = rs.getBigDecimal("enr_id");
                    return v == null ? null : v.toBigInteger().toString();
                }).optional().orElse(null);
        if (existing != null) return existing;

        Integer lastSeq = jdbc.sql("""
                SELECT MAX(CAST(SUBSTRING(enr_id::TEXT, 5) AS INTEGER)) AS last_seq
                FROM pp.student_master
                WHERE enr_id::TEXT LIKE :year || '%'
                """).param("year", nmmsYear).query(Integer.class).optional().orElse(null);
        int nextSeq = (lastSeq == null ? 0 : lastSeq) + 1;
        String padded = String.format("%04d", nextSeq);
        return nmmsYear + padded;
    }

    /** The identical ON CONFLICT upsert used by BOTH submit endpoints (interviewModel.js:889-906 / :1014-1033). */
    private void upsertStudentMaster(String applicantId, String enrollmentId) {
        jdbc.sql("""
                INSERT INTO pp.student_master (
                  applicant_id, enr_id, student_name, father_name, mother_name,
                  father_occupation, mother_occupation, gender,
                  contact_no1, contact_no2, current_institute_dise_code,
                  previous_institute_dise_code, home_address
                )
                SELECT
                  p.applicant_id, :enr::numeric, p.student_name, p.father_name, p.mother_name,
                  s.father_occupation, s.mother_occupation, p.gender,
                  p.contact_no1, p.contact_no2, p.current_institute_dise_code,
                  p.previous_institute_dise_code, p.home_address
                FROM pp.applicant_primary_info p
                LEFT JOIN pp.applicant_secondary_info s ON p.applicant_id = s.applicant_id
                WHERE p.applicant_id = :aid::numeric
                ON CONFLICT (applicant_id) DO UPDATE SET enr_id = EXCLUDED.enr_id
                """).param("enr", enrollmentId).param("aid", applicantId).update();
    }

    /**
     * submitInterviewDetails — interviewModel.js:774-922. docName/docType are derived from the uploaded file's
     * name by the controller (Firm Decision 7 — bytes are NOT stored). Throws on validation / no-matching-row so
     * the @Transactional rolls back (controller maps to 500 {error:true,...}).
     */
    @Transactional
    public Map<String, Object> submitInterviewDetails(Map<String, String> form, String docName, String docType) {
        String applicantId = form.get("applicantId");
        String nmmsYear = form.get("nmmsYear");
        String remarks = form.get("remarks");
        if (remarks == null || remarks.trim().isEmpty()) throw new IllegalStateException("Remarks field is mandatory.");
        if (nmmsYear == null || nmmsYear.isEmpty()) throw new IllegalStateException("Academic Year (nmmsYear) is missing.");

        // LOGIC PROCESSING — ACCEPTED / HOME VERIFICATION REQUIRED -> SELECTED (Firm Decision 10)
        String dbInterviewResult = form.getOrDefault("interviewResult", "").toUpperCase().trim();
        if ("ACCEPTED".equals(dbInterviewResult)) dbInterviewResult = "SELECTED";
        boolean homeVerificationRequired = "Required".equals(form.get("homeVerificationRequired"))
                || "HOME VERIFICATION REQUIRED".equals(dbInterviewResult);
        String homeVerificationYN = homeVerificationRequired ? "Y" : "N";
        if ("HOME VERIFICATION REQUIRED".equals(dbInterviewResult)) dbInterviewResult = "SELECTED";

        String dbStatus = form.getOrDefault("interviewStatus", "").toUpperCase().trim();
        String dbMode = form.getOrDefault("interviewMode", "").toUpperCase().trim();

        String enrollmentId = null;
        if ("SELECTED".equals(dbInterviewResult)) {
            enrollmentId = generateEnrollmentId(applicantId, nmmsYear);
        }

        Map<String, Object> updated = jdbc.sql("""
                UPDATE pp.student_interview
                SET
                  interview_date = :interviewDate::date, interview_time = :interviewTime::time, interview_mode = :mode,
                  status = :status, life_goals_and_zeal = :lgz::numeric, commitment_to_learning = :ctl::numeric,
                  integrity = :integrity::numeric, communication_skills = :cs::numeric, home_verification_req_yn = :hv,
                  interview_result = :result, doc_name = :docName, doc_type = :docType, remarks = :remarks
                WHERE applicant_id = :aid::numeric
                  AND UPPER(TRIM(status)) = 'SCHEDULED'
                  AND interview_result IS NULL
                RETURNING *
                """)
                .param("interviewDate", emptyToNull(form.get("interviewDate")))
                .param("interviewTime", emptyToNull(form.get("interviewTime")))
                .param("mode", dbMode).param("status", dbStatus)
                .param("lgz", emptyToNull(form.get("lifeGoalsAndZeal")))
                .param("ctl", emptyToNull(form.get("commitmentToLearning")))
                .param("integrity", emptyToNull(form.get("integrity")))
                .param("cs", emptyToNull(form.get("communicationSkills")))
                .param("hv", homeVerificationYN).param("result", dbInterviewResult)
                .param("docName", docName).param("docType", docType).param("remarks", remarks)
                .param("aid", applicantId)
                .query((rs, i) -> InterviewReadRepository.genericRow(rs)).optional().orElse(null);

        if (updated == null) throw new IllegalStateException("Update failed. No matching scheduled interview found.");

        if ("SELECTED".equals(dbInterviewResult)) {
            upsertStudentMaster(applicantId, enrollmentId);
        }

        Map<String, Object> out = new LinkedHashMap<>(updated);
        out.put("enr_id", enrollmentId);
        return out;
    }

    /**
     * submitHomeVerification — interviewModel.js:924-1047. status vocabulary is PENDING/SCHEDULED/REJECTED/ACCEPTED
     * (NOT remapped). rejection_reason_id always inserted NULL. enr_id generated only when status == ACCEPTED.
     */
    @Transactional
    public Map<String, Object> submitHomeVerification(Map<String, String> form, String docName, String docType) {
        String applicantId = form.get("applicantId");
        String nmmsYear = form.get("nmmsYear");
        if (nmmsYear == null || nmmsYear.isEmpty()) throw new IllegalStateException("Academic Year (nmmsYear) is missing.");

        String dbStatus = form.getOrDefault("status", "").toUpperCase().trim();
        String enrollmentId = null;
        if ("ACCEPTED".equals(dbStatus)) {
            enrollmentId = generateEnrollmentId(applicantId, nmmsYear);
        }
        String dbVerificationType = form.getOrDefault("verificationType", "").toUpperCase().trim();

        Map<String, Object> row = jdbc.sql("""
                INSERT INTO pp.home_verification (
                    applicant_id, date_of_verification, remarks, status,
                    verified_by, rejection_reason_id, verification_type,
                    doc_name, doc_type
                ) VALUES (:aid::numeric, :dov::date, :remarks, :status, :verifiedBy, NULL, :vtype, :docName, :docType)
                RETURNING *
                """)
                .param("aid", applicantId).param("dov", emptyToNull(form.get("dateOfVerification")))
                .param("remarks", form.get("remarks")).param("status", dbStatus)
                .param("verifiedBy", form.get("verifiedBy")).param("vtype", dbVerificationType)
                .param("docName", docName).param("docType", docType)
                .query((rs, i) -> InterviewReadRepository.genericRow(rs)).single();

        if ("ACCEPTED".equals(dbStatus)) {
            upsertStudentMaster(applicantId, enrollmentId);
        }

        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("enr_id", enrollmentId);
        return out;
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
