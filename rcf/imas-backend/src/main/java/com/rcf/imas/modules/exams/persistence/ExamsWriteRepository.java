package com.rcf.imas.modules.exams.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.exams.persistence.ExamsReadRepository.genericRow;

@Repository
public class ExamsWriteRepository {

    private final JdbcClient jdbc;

    public ExamsWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** addExamCentre() parity: created_at=now(), active_yn hardcoded 'Y', sitting_capacity/lat/long best-effort
     *  numeric parse-or-null (Node: parseInt/parseFloat(...) || null). Single autocommit statement, no @Transactional
     *  needed (matches Node). */
    public Map<String, Object> insertCentre(String code, String name, String address, String village, String pincode,
                                             String contactPerson, String contactPhone, String contactEmail,
                                             Integer sittingCapacity, java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                             String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.pp_exam_centre (
                  pp_exam_centre_code, pp_exam_centre_name, address, village, pincode,
                  contact_person, contact_phone, contact_email, sitting_capacity,
                  latitude, longitude, created_at, created_by, active_yn
                ) VALUES (:code, :name, :address, :village, :pincode, :contactPerson, :contactPhone, :contactEmail,
                          :capacity, :lat, :lng, :createdAt, :createdBy::numeric, 'Y')
                RETURNING *
                """)
                .param("code", code).param("name", name).param("address", address).param("village", village)
                .param("pincode", pincode).param("contactPerson", contactPerson).param("contactPhone", contactPhone)
                .param("contactEmail", contactEmail).param("capacity", sittingCapacity).param("lat", latitude)
                .param("lng", longitude).param("createdAt", java.sql.Timestamp.from(Instant.now()))
                .param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).single();
    }

    /** deleteExamCentre() parity: usage-guard SELECT (in ExamsReadRepository) then this DELETE. Not @Transactional
     *  (matches Node, which never wraps this pair either — the guard-then-delete race is accepted). */
    public void deleteCentre(String id) {
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = :id::numeric").param("id", id).update();
    }

    /** updateExamCentre() parity. activeYn is the ALREADY-DEFAULTED value (caller applies `active_yn || 'Y'`
     *  before calling this, per Firm Decision 11a). Returns null if 0 rows updated (id not found). */
    public Map<String, Object> updateCentre(String id, String name, String code, Integer sittingCapacity,
                                             java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                             String address, String village, String pincode, String contactPerson,
                                             String contactPhone, String contactEmail, String activeYn) {
        return jdbc.sql("""
                UPDATE pp.pp_exam_centre
                SET pp_exam_centre_name=:name, pp_exam_centre_code=:code, sitting_capacity=:capacity,
                    latitude=:lat, longitude=:lng, address=:address, village=:village, pincode=:pincode,
                    contact_person=:contactPerson, contact_phone=:contactPhone, contact_email=:contactEmail,
                    active_yn=:activeYn
                WHERE pp_exam_centre_id=:id::numeric
                RETURNING *
                """)
                .param("name", name).param("code", code).param("capacity", sittingCapacity).param("lat", latitude)
                .param("lng", longitude).param("address", address).param("village", village).param("pincode", pincode)
                .param("contactPerson", contactPerson).param("contactPhone", contactPhone).param("contactEmail", contactEmail)
                .param("activeYn", activeYn).param("id", id)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** deleteExamById(examId) parity, made GENUINELY atomic (Firm Decision 3) -- Node's version runs
     *  pool.query("BEGIN")/"COMMIT" on the shared pool, which is not a real transaction (each statement may hit
     *  a different pooled connection). No existence check (Firm Decision 11c) -- 0-row deletes are not an error. */
    @Transactional
    public void deleteExam(String examId) {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = :id::numeric").param("id", examId).update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
    }

    /** freezeExam parity: single autocommit UPDATE, NO existence check (Firm Decision 11b) -- 0 rows affected is
     *  not treated as an error, matching Node exactly. */
    public void freezeExam(String examId) {
        jdbc.sql("UPDATE pp.examination SET frozen_yn = 'Y' WHERE exam_id = :id::numeric").param("id", examId).update();
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record CreateExamResult(boolean conflict, String message, String examId) {}

    public static class ExamNotFoundException extends RuntimeException {
        public ExamNotFoundException() { super("Exam does not exist."); }
    }

    public static class NoShortlistedApplicantsException extends RuntimeException {
        public NoShortlistedApplicantsException() { super("No shortlisted applicants found for the selected region."); }
    }

    public record AssignedApplicant(String applicantId, String applicantName, String hallTicketNo) {}
    public record AssignResult(int totalAssigned, List<AssignedApplicant> applicants) {}

    /**
     * addcreateExamonly() parity. examYear is ALREADY the caller-computed academic_year.split("-")[0] value, or
     * null if academic_year was omitted (Firm Decision 11d orphan quirk -- allowed, not validated). startTime/
     * endTime are compared as zero-padded "HH:MM"/"HH:MM:SS" strings -- Java String.compareTo on such strings is
     * lexicographic and correct for this format, matching Node's JS string comparison operators exactly.
     */
    @Transactional
    public CreateExamResult createExamOnly(String centreId, String examName, String date, String startTime,
                                            String endTime, String examYear) {
        List<Map<String, Object>> existingExams = jdbc.sql("""
                SELECT exam_id, exam_start_time, exam_end_time
                FROM pp.examination
                WHERE pp_exam_centre_id = :centreId::numeric AND exam_date = :date::date AND exam_year = :year
                """).param("centreId", centreId).param("date", date).param("year", examYear)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("start", TIME_FMT.format(rs.getTime("exam_start_time").toLocalTime()));
                    m.put("end", TIME_FMT.format(rs.getTime("exam_end_time").toLocalTime()));
                    return m;
                }).list();

        for (Map<String, Object> existing : existingExams) {
            String existingStart = (String) existing.get("start");
            String existingEnd = (String) existing.get("end");
            boolean overlapping =
                    (startTime.compareTo(existingStart) >= 0 && startTime.compareTo(existingEnd) < 0) ||
                    (endTime.compareTo(existingStart) > 0 && endTime.compareTo(existingEnd) <= 0) ||
                    (startTime.compareTo(existingStart) <= 0 && endTime.compareTo(existingEnd) >= 0);
            if (overlapping) {
                return new CreateExamResult(true, "Exam exists from " + existingStart + " to " + existingEnd, null);
            }
        }

        String examId = jdbc.sql("""
                INSERT INTO pp.examination (exam_name, exam_date, pp_exam_centre_id, exam_start_time, exam_end_time, exam_year)
                VALUES (:name, :date::date, :centreId::numeric, :start::time, :end::time, :year)
                RETURNING exam_id
                """).param("name", examName).param("date", date).param("centreId", centreId)
                .param("start", startTime).param("end", endTime).param("year", examYear)
                .query((rs, i) -> rs.getBigDecimal("exam_id").toBigInteger().toString()).single();

        return new CreateExamResult(false, null, examId);
    }

    /**
     * assignApplicantsToExam() parity -- genuinely transactional in Node too (single client, BEGIN/COMMIT/ROLLBACK),
     * so this port just needs Spring's equivalent. Two distinct "year" values, deliberately never cross-validated
     * (Firm Decision 11f): `examYear` (fetched from the exam row, filters shortlist eligibility) vs. `academicYear`
     * (the raw request-body value, drives hall-ticket sequence numbering). An exam with a NULL exam_year (allowed
     * per createExamOnly's orphan quirk) makes `sb.shortlisted_year = NULL::numeric` always false -> 404.
     */
    @Transactional
    public AssignResult assignStudents(String examId, String division, String educationDistrict,
                                        List<String> blocks, String academicYear) {
        Map<String, Object> exam = jdbc.sql("SELECT exam_id, exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                .param("id", examId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (exam == null) throw new ExamNotFoundException();
        String examYear = (String) exam.get("exam_year");

        List<Map<String, Object>> shortlisted = jdbc.sql("""
                SELECT
                  api.applicant_id, api.student_name, api.nmms_year,
                  edu_district_juris.juris_code
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_shortlist_info asi ON api.applicant_id = asi.applicant_id
                INNER JOIN pp.shortlist_batch sb ON asi.shortlist_batch_id = sb.shortlist_batch_id
                INNER JOIN pp.jurisdiction block_juris
                  ON api.nmms_block = block_juris.juris_code AND block_juris.juris_type = 'BLOCK'
                INNER JOIN pp.jurisdiction edu_district_juris
                  ON block_juris.parent_juris = edu_district_juris.juris_code
                  AND edu_district_juris.juris_type = 'EDUCATION DISTRICT'
                INNER JOIN pp.jurisdiction division_juris
                  ON edu_district_juris.parent_juris = division_juris.juris_code
                  AND division_juris.juris_type = 'DIVISION'
                WHERE division_juris.juris_code = :division::numeric
                  AND edu_district_juris.juris_code = :eduDistrict::numeric
                  AND block_juris.juris_code = ANY(:blocks::numeric[])
                  AND asi.shortlisted_yn = 'Y'
                  AND sb.shortlisted_year = :examYear::numeric
                """)
                .param("division", division).param("eduDistrict", educationDistrict)
                .param("blocks", blocks.toArray(new String[0])).param("examYear", examYear)
                .query((rs, i) -> genericRow(rs)).list();

        if (shortlisted.isEmpty()) throw new NoShortlistedApplicantsException();

        List<AssignedApplicant> assigned = new ArrayList<>();
        for (Map<String, Object> applicant : shortlisted) {
            String applicantId = (String) applicant.get("applicant_id");
            String applicantName = (String) applicant.get("student_name");
            String jurisCode = (String) applicant.get("juris_code");

            // Firm Decision 11e: bumped even when the applicant_exam insert below is a DO NOTHING no-op --
            // a "gap not collision" quirk, preserved verbatim, do NOT peek-before-increment.
            long sequence = jdbc.sql("""
                    INSERT INTO pp.hall_ticket_sequence (academic_year, juris_code, last_sequence)
                    VALUES (:year, :juris, 1)
                    ON CONFLICT (academic_year, juris_code)
                    DO UPDATE SET last_sequence = pp.hall_ticket_sequence.last_sequence + 1
                    RETURNING last_sequence
                    """).param("year", academicYear).param("juris", jurisCode)
                    .query(Long.class).single();

            String hallTicketNo = generateHallTicket(sequence, jurisCode, academicYear);

            jdbc.sql("""
                    INSERT INTO pp.applicant_exam (applicant_id, exam_id, pp_hall_ticket_no)
                    VALUES (:applicantId::numeric, :examId::numeric, :ticket)
                    ON CONFLICT (applicant_id, exam_id) DO NOTHING
                    """).param("applicantId", applicantId).param("examId", examId).param("ticket", hallTicketNo).update();

            assigned.add(new AssignedApplicant(applicantId, applicantName, hallTicketNo));
        }

        return new AssignResult(assigned.size(), assigned);
    }

    /** generateHallTicket(sequenceNumber, juris_code, academicYear) parity (Firm Decision 12), verbatim:
     *  yearSuffix = academicYear[2:4]; jurisLast2 = last-2-chars-of-jurisCode padded to 2 with '0';
     *  sequence = sequenceNumber padded to 4 with '0'. */
    static String generateHallTicket(long sequenceNumber, String jurisCode, String academicYear) {
        if (jurisCode == null || academicYear == null) {
            throw new IllegalStateException("Missing required values for hall ticket generation");
        }
        String yearSuffix = academicYear.substring(2, 4);
        String tail = jurisCode.length() >= 2 ? jurisCode.substring(jurisCode.length() - 2) : jurisCode;
        String jurisLast2 = tail.length() < 2 ? "0".repeat(2 - tail.length()) + tail : tail;
        String sequence = String.format("%04d", sequenceNumber);
        return yearSuffix + jurisLast2 + sequence;
    }
}
