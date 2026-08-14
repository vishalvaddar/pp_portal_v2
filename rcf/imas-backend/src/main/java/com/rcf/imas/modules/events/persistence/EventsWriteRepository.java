package com.rcf.imas.modules.events.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EventsWriteRepository {

    private final JdbcClient jdbc;

    public EventsWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** createEventType (eventModel.js:7-15). Autocommit, single statement (ground truth §6). */
    public Map<String, Object> createEventType(String name) {
        return jdbc.sql("INSERT INTO pp.event_type (event_type_name) VALUES (:name) RETURNING *")
                .param("name", name)
                .query((rs, i) -> EventsReadRepository.genericRow(rs)).single();
    }

    /** updateEventType (eventModel.js:17-26). Returns null (no row) if id doesn't match -- Node's
     *  `rows[0]` on an empty result is `undefined`; Java's controller maps this to an empty 200 body. */
    public Optional<Map<String, Object>> updateEventType(long id, String name) {
        return jdbc.sql("UPDATE pp.event_type SET event_type_name = :name WHERE event_type_id = :id RETURNING *")
                .param("name", name).param("id", id)
                .query((rs, i) -> EventsReadRepository.genericRow(rs)).optional();
    }

    /** createEvent (eventModel.js:52-76 + eventController.js:50-100), fused into one @Transactional method
     *  (Firm Decision 6). Only the POST-/events behavior: master INSERT + photos loop. Never touches reports
     *  (Firm Decision 3 -- the dead createEvent->updateEvent chain means Node's live POST /events never
     *  persists a report either). boys/girls/parents_attended are inserted exactly as sanitized (nullable,
     *  no `|| 0` coercion here -- see plan Disagreements #2, this differs from updateEvent). */
    @Transactional
    public int createEvent(Integer eventTypeId, String eventTitle, String eventDescription,
                            String eventStartDate, String eventEndDate,
                            String eventDistrict, String eventBlock, String eventLocation,
                            String pincode, String cohortNumber,
                            String boysAttended, String girlsAttended, String parentsAttended,
                            Long userId,
                            List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> photos) {
        int eventId = jdbc.sql("""
                INSERT INTO pp.event_master (
                  event_type_id, event_title, event_description, event_start_date, event_end_date,
                  event_district, event_block, event_location, pincode, cohort_number,
                  boys_attended, girls_attended, parents_attended, created_by, updated_by
                ) VALUES (
                  :eventTypeId::integer, :eventTitle, :eventDescription, :eventStartDate::date, :eventEndDate::date,
                  :eventDistrict::numeric, :eventBlock::numeric, :eventLocation, :pincode, :cohortNumber::integer,
                  :boysAttended::integer, :girlsAttended::integer, :parentsAttended::integer, :userId::numeric, :userId::numeric
                )
                RETURNING event_id
                """)
                .param("eventTypeId", eventTypeId).param("eventTitle", eventTitle)
                .param("eventDescription", eventDescription)
                .param("eventStartDate", eventStartDate).param("eventEndDate", eventEndDate)
                .param("eventDistrict", eventDistrict).param("eventBlock", eventBlock)
                .param("eventLocation", eventLocation).param("pincode", pincode)
                .param("cohortNumber", cohortNumber)
                .param("boysAttended", boysAttended).param("girlsAttended", girlsAttended)
                .param("parentsAttended", parentsAttended).param("userId", userId)
                .query(Integer.class).single();

        for (var p : photos) {
            jdbc.sql("""
                    INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                    VALUES (:eventId, :path, :name, :userId::numeric)
                    """)
                    .param("eventId", eventId).param("path", p.diskPath())
                    .param("name", p.originalFilename())   // createEvent stores the ORIGINAL filename (eventController.js:89)
                    .param("userId", userId)
                    .update();
        }
        return eventId;
    }

    /** updateEvent (eventModel.js:79-101 + eventController.js:102-173), fused into one @Transactional
     *  method. Order: (1) scoped photo-delete (Locked Decision 5 -- Node has NO event_id scoping here,
     *  ground truth §7.5, an IDOR; Java adds `AND event_id = :eventId`), (2) full master UPDATE (boys/girls/
     *  parents_attended DO get `|| 0` semantics here -- pass 0 when the sanitized value is null, matching
     *  JS `boys_attended || 0`, unlike createEvent -- see plan Disagreements #2), (3) conditional Sammelan
     *  count-resync via pp.event_students (only when eventTypeName.equals("Sammelan"), does NOT set
     *  updated_by/updated_at -- ported literally, an inconsistent audit trail vs. every other master UPDATE
     *  in this module), (4) new photo inserts (file_name = SERVER-GENERATED name, unlike createEvent),
     *  (5) report replace: delete old SAMMELAN_REPORT row (deleteOldReport, DB-only -- old file orphaned on
     *  disk, ported literally) then insert the new one. */
    @Transactional
    public void updateEvent(long eventId, java.util.List<Integer> photosToDelete,
                             Integer eventTypeId, String eventTitle, String eventDescription,
                             String eventStartDate, String eventEndDate,
                             String eventDistrict, String eventBlock, String eventLocation,
                             String pincode, String cohortNumber,
                             String boysAttendedRaw, String girlsAttendedRaw, String parentsAttendedRaw,
                             String eventTypeName, Long userId,
                             java.util.List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> newPhotos,
                             com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile newReport) {

        if (photosToDelete != null && !photosToDelete.isEmpty()) {
            jdbc.sql("DELETE FROM pp.event_photos WHERE photo_id = ANY(:ids::int[]) AND event_id = :eventId")
                    .param("ids", photosToDelete.toArray(new Integer[0])).param("eventId", eventId).update();
        }

        int boys = boysAttendedRaw == null || boysAttendedRaw.isEmpty() ? 0 : Integer.parseInt(boysAttendedRaw);
        int girls = girlsAttendedRaw == null || girlsAttendedRaw.isEmpty() ? 0 : Integer.parseInt(girlsAttendedRaw);
        int parents = parentsAttendedRaw == null || parentsAttendedRaw.isEmpty() ? 0 : Integer.parseInt(parentsAttendedRaw);

        jdbc.sql("""
                UPDATE pp.event_master
                SET event_type_id = :eventTypeId::integer, event_title = :eventTitle, event_description = :eventDescription,
                    event_start_date = :eventStartDate::date, event_end_date = :eventEndDate::date,
                    event_district = :eventDistrict::numeric, event_block = :eventBlock::numeric,
                    event_location = :eventLocation, pincode = :pincode, cohort_number = :cohortNumber::integer,
                    boys_attended = :boys, girls_attended = :girls, parents_attended = :parents,
                    updated_by = :userId::numeric, updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                """)
                .param("eventTypeId", eventTypeId).param("eventTitle", eventTitle).param("eventDescription", eventDescription)
                .param("eventStartDate", eventStartDate).param("eventEndDate", eventEndDate)
                .param("eventDistrict", eventDistrict).param("eventBlock", eventBlock)
                .param("eventLocation", eventLocation).param("pincode", pincode).param("cohortNumber", cohortNumber)
                .param("boys", boys).param("girls", girls).param("parents", parents)
                .param("userId", userId).param("eventId", eventId)
                .update();

        if ("Sammelan".equals(eventTypeName)) {
            Map<String, Object> counts = jdbc.sql("""
                    SELECT
                        COUNT(*) FILTER (WHERE UPPER(gender) IN ('M','MALE')) as boys,
                        COUNT(*) FILTER (WHERE UPPER(gender) IN ('F','FEMALE')) as girls
                    FROM pp.student_master sm
                    JOIN pp.event_students es ON sm.student_id = es.student_id
                    WHERE es.event_id = :eventId
                    """).param("eventId", eventId).query((rs, i) -> EventsReadRepository.genericRow(rs)).single();
            long syncBoys = Long.parseLong(String.valueOf(counts.getOrDefault("boys", 0)));
            long syncGirls = Long.parseLong(String.valueOf(counts.getOrDefault("girls", 0)));
            jdbc.sql("UPDATE pp.event_master SET boys_attended = :boys, girls_attended = :girls WHERE event_id = :eventId")
                    .param("boys", syncBoys).param("girls", syncGirls).param("eventId", eventId).update();
        }

        for (var p : newPhotos) {
            jdbc.sql("""
                    INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                    VALUES (:eventId, :path, :name, :userId::numeric)
                    """)
                    .param("eventId", eventId).param("path", p.diskPath())
                    .param("name", p.storedFilename())   // updateEvent stores the SERVER-GENERATED filename (eventController.js:158)
                    .param("userId", userId)
                    .update();
        }

        if (newReport != null) {
            jdbc.sql("DELETE FROM pp.event_reports WHERE event_id = :eventId AND report_type = 'SAMMELAN_REPORT'")
                    .param("eventId", eventId).update();
            jdbc.sql("""
                    INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
                    VALUES (:eventId, 'SAMMELAN_REPORT', :path, :name, :userId::numeric)
                    """)
                    .param("eventId", eventId).param("path", newReport.diskPath())
                    .param("name", newReport.storedFilename()).param("userId", userId)
                    .update();
        }
    }

    /** deleteEvent (eventModel.js:104-126). Node runs this as its OWN self-contained transaction inside the
     *  model function (unusual vs. every other multi-statement write, which BEGINs/COMMITs in the controller
     *  -- ground truth §6); Java just makes the whole method @Transactional, same net effect. Order: students,
     *  photos, reports, master (photos/reports ON DELETE CASCADE from event_master makes the explicit deletes
     *  technically redundant, ground truth §3, but ported literally/in-order regardless). */
    @Transactional
    public void deleteEvent(long eventId) {
        jdbc.sql("DELETE FROM pp.event_students WHERE event_id = :id").param("id", eventId).update();
        jdbc.sql("DELETE FROM pp.event_photos WHERE event_id = :id").param("id", eventId).update();
        jdbc.sql("DELETE FROM pp.event_reports WHERE event_id = :id").param("id", eventId).update();
        jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
    }

    /** submitAttendance (eventController.js:256-312). saveSammelanAttendance (eventModel.js:429-437) is a
     *  SINGLE `INSERT ... ON CONFLICT (event_id, student_id) DO NOTHING` -- NO preceding DELETE (see plan
     *  Disagreements #1: this endpoint can only ADD attendees, never remove them; verified against both the
     *  live model source and the ground-truth doc, which agree with each other and disagree with the task
     *  brief's "DELETE + INSERT" framing). student_id is numeric(14,0) but this query casts to ::int[]
     *  VERBATIM from Node (eventModel.js:432, eventController.js:273) -- a real overflow risk for any
     *  student_id > 2^31-1, tolerated in practice because ids come from a small sequential sequence; ported
     *  literally, not widened to bigint[]/numeric[]. Does NOT call deleteOldReport before inserting a new
     *  report (ground truth §4/§9 -- report rows accumulate across repeated calls, unlike updateEvent). */
    @Transactional
    public void submitAttendance(long eventId, java.util.List<Integer> studentIds, int parentsAttended,
                                  Long userId,
                                  java.util.List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> photos,
                                  com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile report) {

        Integer[] idsArray = studentIds.toArray(new Integer[0]);

        jdbc.sql("""
                INSERT INTO pp.event_students (event_id, student_id)
                SELECT :eventId::integer, unnest(:studentIds::int[])
                ON CONFLICT (event_id, student_id) DO NOTHING
                """).param("eventId", eventId).param("studentIds", idsArray).update();

        List<Map<String, Object>> genderRows = jdbc.sql("""
                SELECT gender, COUNT(*) as count FROM pp.student_master WHERE student_id = ANY(:ids::int[]) GROUP BY gender
                """).param("ids", idsArray).query((rs, i) -> EventsReadRepository.genericRow(rs)).list();

        int boys = 0, girls = 0;
        for (Map<String, Object> row : genderRows) {
            String g = row.get("gender") == null ? null : String.valueOf(row.get("gender")).toUpperCase();
            int count = Integer.parseInt(String.valueOf(row.get("count")));
            if ("MALE".equals(g) || "M".equals(g)) boys = count;
            if ("FEMALE".equals(g) || "F".equals(g)) girls = count;
        }

        jdbc.sql("""
                UPDATE pp.event_master
                SET boys_attended = :boys, girls_attended = :girls, parents_attended = :parents,
                    updated_by = :userId::numeric, updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId
                """).param("boys", boys).param("girls", girls).param("parents", parentsAttended)
                .param("userId", userId).param("eventId", eventId).update();

        for (var p : photos) {
            jdbc.sql("""
                    INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                    VALUES (:eventId, :path, :name, :userId::numeric)
                    """)
                    .param("eventId", eventId).param("path", p.diskPath())
                    .param("name", p.storedFilename())   // uses file.filename like updateEvent, not originalname (eventController.js:298)
                    .param("userId", userId)
                    .update();
        }
        if (report != null) {
            jdbc.sql("""
                    INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
                    VALUES (:eventId, 'SAMMELAN_REPORT', :path, :name, :userId::numeric)
                    """).param("eventId", eventId).param("path", report.diskPath())
                    .param("name", report.storedFilename()).param("userId", userId).update();
        }
    }
}
