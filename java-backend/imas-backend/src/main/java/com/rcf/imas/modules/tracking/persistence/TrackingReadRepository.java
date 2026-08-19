package com.rcf.imas.modules.tracking.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository.genericRow;

@Repository
public class TrackingReadRepository {

    private static final int PAGE_SIZE = 10; // hard-coded in Node for both /students and /students/interviewer/:id

    private final JdbcClient jdbc;

    public TrackingReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** getAllInterviewers -- NO active_status filter (quirk 14): inactive interviewers still returned. */
    public List<Map<String, Object>> allInterviewers() {
        return jdbc.sql("SELECT interviewer_id, interviewer_name FROM pp.interviewer ORDER BY interviewer_name ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getStudentsByInterviewer($1=interviewerId,$2=limit,$3=offset,$4=nmmsYear). Does NOT dedupe to latest
     * round (quirk 11, ground truth §7): one row per (applicant_id, interview_round). totalCount below
     * counts ROWS, not distinct applicants -- pagination semantics intentionally differ from
     * getStudentsWithLatestStatus (Task 5). Do not add DISTINCT/ROW_NUMBER dedup here.
     */
    public Map<String, Object> studentsByInterviewer(String interviewerId, int page, String nmmsYear) {
        int offset = (page - 1) * PAGE_SIZE;
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT a.applicant_id, a.student_name, s.interview_round, s.status, s.interview_result AS interview_result
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                WHERE s.interviewer_id = :interviewerId::numeric AND a.nmms_year = :nmmsYear::numeric
                ORDER BY a.student_name ASC, s.interview_round DESC
                LIMIT :limit OFFSET :offset
                """).param("interviewerId", interviewerId).param("nmmsYear", nmmsYear)
                .param("limit", PAGE_SIZE).param("offset", offset)
                .query((rs, i) -> genericRow(rs)).list();

        Integer totalRows = jdbc.sql("""
                SELECT COUNT(s.applicant_id)
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                WHERE s.interviewer_id = :interviewerId::numeric AND a.nmms_year = :nmmsYear::numeric
                """).param("interviewerId", interviewerId).param("nmmsYear", nmmsYear)
                .query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", rows);
        out.put("currentPage", page);
        out.put("totalPages", (int) Math.ceil(totalRows / (double) PAGE_SIZE));
        out.put("totalStudents", totalRows);
        return out;
    }

    /**
     * getStudentdetailforFilter($1=applicantId,$2=nmmsYear) -- used by BOTH branches of getStudentDetails
     * (the ?filtered=true query flag is inert, quirk 4b). The MAX(interview_round) sub-select does NOT
     * filter by nmms_year (quirk 4f) -- reproduce exactly, do not add a year filter to the subquery.
     */
    public List<Map<String, Object>> studentDetailForFilter(String applicantId, String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    a.student_name, s.interview_round,
                    TO_CHAR(s.interview_date, 'YYYY-MM-DD') AS interview_date,
                    s.interview_time, s.interview_mode, s.status, s.life_goals_and_zeal,
                    s.commitment_to_learning, s.integrity, s.communication_skills,
                    s.interview_result AS interview_result, s.home_verification_req_yn,
                    i.interviewer_name AS interviewer
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                LEFT JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
                WHERE a.applicant_id = :applicantId::numeric AND a.nmms_year = :nmmsYear::numeric
                AND s.interview_round = (
                    SELECT MAX(interview_round)
                    FROM pp.student_interview
                    WHERE applicant_id = a.applicant_id
                )
                ORDER BY s.interview_round DESC
                """).param("applicantId", applicantId).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllInterviewRounds($1=applicantId,$2=nmmsYear). */
    public List<Map<String, Object>> allInterviewRounds(String applicantId, String nmmsYear) {
        return jdbc.sql("""
                SELECT
                    a.student_name, s.applicant_id, s.interview_round,
                    TO_CHAR(s.interview_date, 'YYYY-MM-DD') AS interview_date,
                    s.interview_time, s.interview_mode, s.status, s.life_goals_and_zeal,
                    s.commitment_to_learning, s.integrity, s.communication_skills,
                    s.interview_result AS interview_result, s.home_verification_req_yn,
                    s.doc_name, s.doc_type, i.interviewer_name AS interviewer
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                LEFT JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
                WHERE s.applicant_id = :applicantId::numeric AND a.nmms_year = :nmmsYear::numeric
                ORDER BY s.interview_round ASC
                """).param("applicantId", applicantId).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllHomeVerificationRounds($1=applicantId). */
    public List<Map<String, Object>> allHomeVerificationRounds(String applicantId) {
        return jdbc.sql("""
                SELECT
                    h.verification_id,
                    TO_CHAR(h.date_of_verification, 'YYYY-MM-DD') AS date_of_verification,
                    h.status AS home_verification_status, h.verified_by,
                    h.verification_type AS home_verification_type,
                    h.doc_name AS home_verification_doc_name, h.doc_type AS home_verification_doc_type,
                    h.remarks
                FROM pp.home_verification h
                WHERE h.applicant_id = :applicantId::numeric
                ORDER BY h.date_of_verification ASC
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getStudentsWithLatestStatus parity (Firm Decision 5) -- dynamic WHERE built with a StringBuilder +
     * named params shared in lockstep between the data query (adds ORDER BY/LIMIT/OFFSET) and the count
     * query (same WHERE, no LIMIT/OFFSET). 'HOME VERIFICATION REQUIRED' is peeled out of `results` before
     * building the IN-list and instead ORs in persistent_verification_req='Y' (Firm Decision 4g).
     */
    public Map<String, Object> studentsWithLatestStatus(int page, List<String> statuses, List<String> results,
                                                          String nmmsYear) {
        boolean homeVerificationSelected = results != null && results.contains("HOME VERIFICATION REQUIRED");
        List<String> realResults = results == null ? List.of()
                : results.stream().filter(r -> !"HOME VERIFICATION REQUIRED".equals(r)).toList();
        boolean hasStatuses = statuses != null && !statuses.isEmpty();
        boolean hasResults = !realResults.isEmpty();

        String cte = """
                WITH RankedInterviews AS (
                    SELECT
                        a.applicant_id, a.student_name,
                        s.interview_round, s.status, s.interview_result,
                        MAX(s.home_verification_req_yn) OVER (PARTITION BY a.applicant_id) as persistent_verification_req,
                        ROW_NUMBER() OVER (PARTITION BY a.applicant_id ORDER BY s.interview_round DESC) as rn
                    FROM pp.student_interview s
                    JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                    WHERE a.nmms_year = :nmmsYear::numeric
                ),
                LatestInterviews AS (
                    SELECT * FROM RankedInterviews WHERE rn = 1
                )
                """;

        StringBuilder where = new StringBuilder();
        if (hasStatuses) {
            where.append(" AND UPPER(TRIM(status)) IN (:statuses)");
        }
        if (hasResults || homeVerificationSelected) {
            StringBuilder orClause = new StringBuilder();
            if (hasResults) orClause.append("UPPER(TRIM(interview_result)) IN (:results)");
            if (homeVerificationSelected) {
                if (!orClause.isEmpty()) orClause.append(" OR ");
                orClause.append("UPPER(TRIM(persistent_verification_req)) = 'Y'");
            }
            where.append(" AND (").append(orClause).append(")");
        }

        // WHERE 1=1 anchor: the dynamic `where` StringBuilder only ever emits " AND ..." fragments
        // (never a leading "WHERE"), so both queries need a syntactically-valid WHERE clause to attach to.
        String selectCore = """
                SELECT applicant_id, student_name, interview_round, status,
                       interview_result AS result, persistent_verification_req as home_verification_req_yn
                FROM LatestInterviews WHERE 1=1
                """;
        String countCore = "SELECT COUNT(*) FROM LatestInterviews WHERE 1=1";

        String dataSql = cte + selectCore + where + " ORDER BY student_name ASC LIMIT :limit OFFSET :offset";
        String countSql = cte + countCore + where;

        var dataQuery = jdbc.sql(dataSql).param("nmmsYear", nmmsYear)
                .param("limit", PAGE_SIZE).param("offset", (page - 1) * PAGE_SIZE);
        var countQuery = jdbc.sql(countSql).param("nmmsYear", nmmsYear);
        if (hasStatuses) {
            List<String> upperStatuses = statuses.stream().map(s -> s.toUpperCase().trim()).toList();
            dataQuery = dataQuery.param("statuses", upperStatuses);
            countQuery = countQuery.param("statuses", upperStatuses);
        }
        if (hasResults) {
            List<String> upperResults = realResults.stream().map(r -> r.toUpperCase().trim()).toList();
            dataQuery = dataQuery.param("results", upperResults);
            countQuery = countQuery.param("results", upperResults);
        }

        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        Integer totalRows = countQuery.query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", rows);
        out.put("currentPage", page);
        out.put("totalPages", (int) Math.ceil(totalRows / (double) PAGE_SIZE));
        out.put("totalStudents", totalRows);
        return out;
    }

    /** getInterviewDocument($1=applicantId) -- most recent round with a non-null doc_name. */
    public Map<String, Object> interviewDocument(String applicantId) {
        return jdbc.sql("""
                SELECT doc_name, doc_type, interview_round
                FROM pp.student_interview
                WHERE applicant_id = :applicantId::numeric AND doc_name IS NOT NULL
                ORDER BY interview_round DESC LIMIT 1
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** getHomeVerificationDocument($1=applicantId). */
    public Map<String, Object> homeVerificationDocument(String applicantId) {
        return jdbc.sql("""
                SELECT doc_name, doc_type
                FROM pp.home_verification
                WHERE applicant_id = :applicantId::numeric AND doc_name IS NOT NULL
                ORDER BY date_of_verification DESC, verification_id DESC LIMIT 1
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }
}
