package com.rcf.imas.modules.events.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EventsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcClient jdbc;

    public EventsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same convention as CoordinatorReadRepository.genericRow (LOCKED CONVENTIONS #3, ground truth §8):
     *  numeric(x,0) -> String (event_district, event_block, created_by, updated_by, uploaded_by,
     *  generated_by, student_id); integer columns pass through natively (event_id, photo_id, report_id,
     *  event_type_id, cohort_number, boys/girls/parents_attended); date -> "yyyy-MM-dd"; timestamp ->
     *  ISO-Z. Package-private static so EventsWriteRepository reuses it for RETURNING * rows. */
    static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            int type = md.getColumnType(i);
            Object val;
            switch (type) {
                case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                    BigDecimal bd = rs.getBigDecimal(i);
                    val = bd == null ? null : bd.toBigInteger().toString();
                }
                case java.sql.Types.BIGINT -> {
                    long v = rs.getLong(i); val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.DATE -> {
                    java.sql.Date d = rs.getDate(i);
                    val = d == null ? null : DATE_FMT.format(d.toLocalDate());
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** getEventTypes (eventModel.js:28-36). */
    public List<Map<String, Object>> eventTypes() {
        return jdbc.sql("""
                SELECT event_type_id, event_type_name
                FROM pp.event_type
                ORDER BY event_type_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getStates (eventModel.js:232-236). */
    public List<Map<String, Object>> states() {
        return jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getDivisionsByState (eventModel.js:238-247). */
    public List<Map<String, Object>> divisionsByState(String stateName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction
                WHERE parent_juris IN (
                  SELECT juris_code FROM pp.jurisdiction
                  WHERE LOWER(TRIM(juris_name)) = LOWER(TRIM(:state)) AND LOWER(juris_type) = 'state'
                ) AND LOWER(juris_type) = 'division'
                """).param("state", stateName).query((rs, i) -> genericRow(rs)).list();
    }

    /** getDistrictsByDivisions (eventModel.js:249-265). Caller lower/trims divisionNames (JS-side in
     *  Node; Java does the same before binding, see EventsController). */
    public List<Map<String, Object>> districtsByDivisions(String[] lowerDivisionNames) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction
                WHERE parent_juris IN (
                  SELECT juris_code FROM pp.jurisdiction
                  WHERE LOWER(TRIM(juris_name)) = ANY(:divisions)
                  AND LOWER(juris_type) = 'division'
                ) AND LOWER(juris_type) = 'education district'
                """).param("divisions", lowerDivisionNames).query((rs, i) -> genericRow(rs)).list();
    }

    /** getBlocksByMultiDistricts (eventModel.js:267-314). $1=stateName RAW (SQL does LOWER(TRIM())),
     *  $2/$3=already-lowered/trimmed arrays (JS-side in Node, Java mirrors in EventsController). */
    public List<Map<String, Object>> blocksByMultiDistricts(String stateName, String[] lowerDivisionNames,
                                                              String[] lowerDistrictNames) {
        return jdbc.sql("""
                SELECT j.juris_code, j.juris_name,
                  CASE WHEN j.juris_code IN (
                    SELECT sbj.juris_code FROM pp.shortlist_batch_jurisdiction AS sbj
                    JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                    WHERE sb.frozen_yn = 'Y'
                  ) THEN TRUE ELSE FALSE END AS is_frozen_block
                FROM pp.jurisdiction AS j
                WHERE LOWER(j.juris_type) = 'block'
                  AND j.parent_juris IN (
                    SELECT d.juris_code FROM pp.jurisdiction d
                    WHERE LOWER(TRIM(d.juris_name)) = ANY(:districts)
                      AND LOWER(d.juris_type) = 'education district'
                      AND d.parent_juris IN (
                        SELECT div.juris_code FROM pp.jurisdiction div
                        WHERE LOWER(TRIM(div.juris_name)) = ANY(:divisions)
                          AND LOWER(div.juris_type) = 'division'
                          AND div.parent_juris IN (
                            SELECT s.juris_code FROM pp.jurisdiction s
                            WHERE LOWER(TRIM(s.juris_name)) = LOWER(TRIM(:state))
                              AND LOWER(s.juris_type) = 'state'
                          )
                      )
                  )
                """)
                .param("state", stateName).param("divisions", lowerDivisionNames)
                .param("districts", lowerDistrictNames)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllEvents (eventModel.js:177-203). cover_photo subquery has NO ORDER BY -- "first photo" is
     *  Postgres's undefined pick, not deterministic (ground truth §2 note) -- ported literally, do not add
     *  an ORDER BY. */
    public List<Map<String, Object>> allEvents() {
        return jdbc.sql("""
                SELECT
                  m.event_id, m.event_title, m.event_description,
                  m.event_start_date AS start_date, m.event_end_date AS end_date,
                  m.event_location, m.cohort_number,
                  m.boys_attended, m.girls_attended, m.parents_attended,
                  t.event_type_name AS event_type,
                  (
                    SELECT p.file_path FROM pp.event_photos p
                    WHERE p.event_id = m.event_id LIMIT 1
                  ) AS cover_photo
                FROM pp.event_master m
                JOIN pp.event_type t ON t.event_type_id = m.event_type_id
                ORDER BY m.event_start_date DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getEventById (eventModel.js:205-216). m.* returns every event_master column verbatim, including
     *  event_district/event_block as raw numeric jurisdiction codes (not names). */
    public Optional<Map<String, Object>> eventById(long eventId) {
        return jdbc.sql("""
                SELECT m.*, t.event_type_name
                FROM pp.event_master m
                JOIN pp.event_type t ON t.event_type_id = m.event_type_id
                WHERE m.event_id = :id
                """).param("id", eventId).query((rs, i) -> genericRow(rs)).optional();
    }

    /** getEventPhotos (eventModel.js:140-148). */
    public List<Map<String, Object>> eventPhotos(long eventId) {
        return jdbc.sql("SELECT photo_id, file_path, file_name FROM pp.event_photos WHERE event_id = :id")
                .param("id", eventId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getEventReports (eventModel.js:162-171). */
    public List<Map<String, Object>> eventReports(long eventId) {
        return jdbc.sql("SELECT * FROM pp.event_reports WHERE event_id = :id ORDER BY generated_at DESC")
                .param("id", eventId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getSammelanEvents (eventModel.js:221-230). Literal string match on 'Sammelan', case-sensitive, no
     *  ILIKE/trim (ground truth §2, §7.8) -- ported literally, do not relax. */
    public List<Map<String, Object>> sammelanEvents() {
        return jdbc.sql("""
                SELECT em.event_id, em.event_title
                FROM pp.event_master em
                JOIN pp.event_type et ON et.event_type_id = em.event_type_id
                WHERE et.event_type_name = 'Sammelan'
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** Event lookup by title, inline SQL in the controller in Node (eventController.js:226-229). event_title
     *  has a UNIQUE constraint (event_master_event_title_key) so this is safe as a single-row lookup. */
    public Optional<Map<String, Object>> eventByTitle(String eventTitle) {
        return jdbc.sql("SELECT event_id, cohort_number FROM pp.event_master WHERE event_title = :title")
                .param("title", eventTitle).query((rs, i) -> genericRow(rs)).optional();
    }

    /** getSammelanStudentList (eventModel.js:316-365). $6=searchName is always null in practice (the live
     *  controller never sets it, ground truth §2) -- still wired here for completeness/future use. */
    public List<Map<String, Object>> sammelanStudentList(long eventId, Integer cohortNumber, String stateName,
                                                            String[] districtNames, String[] blockNames,
                                                            String searchName, int limit, int offset) {
        return jdbc.sql("""
                SELECT DISTINCT
                    sm.student_id,
                    sm.student_name,
                    bl.juris_name AS block_name,
                    d.juris_name AS district_name,
                    (es.student_id IS NOT NULL) AS is_marked
                FROM pp.student_master sm
                JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
                LEFT JOIN pp.event_students es ON sm.student_id = es.student_id AND es.event_id = :eventId
                LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
                LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
                LEFT JOIN pp.jurisdiction s ON a.app_state = s.juris_code
                LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
                WHERE sm.active_yn = 'ACTIVE'
                  AND b.cohort_number = :cohortNumber
                  AND (:stateName::text IS NULL OR s.juris_name = :stateName)
                  AND (:districtNames::text[] IS NULL OR d.juris_name = ANY(:districtNames))
                  AND (:blockNames::text[] IS NULL OR bl.juris_name = ANY(:blockNames))
                  AND (:searchName::text IS NULL OR sm.student_name ILIKE '%' || :searchName || '%')
                ORDER BY sm.student_name
                LIMIT :limit OFFSET :offset
                """)
                .param("eventId", eventId).param("cohortNumber", cohortNumber).param("stateName", stateName)
                .param("districtNames", (districtNames == null || districtNames.length == 0) ? null : districtNames)
                .param("blockNames", (blockNames == null || blockNames.length == 0) ? null : blockNames)
                .param("searchName", searchName).param("limit", limit).param("offset", offset)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
