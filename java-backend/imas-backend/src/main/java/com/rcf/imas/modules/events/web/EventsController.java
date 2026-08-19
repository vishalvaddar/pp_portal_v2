package com.rcf.imas.modules.events.web;

import com.rcf.imas.modules.events.persistence.EventsReadRepository;
import com.rcf.imas.modules.events.persistence.EventsWriteRepository;
import com.rcf.imas.modules.events.service.EventFileStorageService;
import com.rcf.imas.modules.events.service.EventUploadValidation;
import com.rcf.imas.modules.events.service.EventValidation;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")   // Node: eventRoutes.js has ZERO auth middleware on all 12 routes (Locked Decision 1)
public class EventsController {

    private final EventsReadRepository reads;
    private final EventsWriteRepository writes;
    private final EventFileStorageService fileStorage;

    public EventsController(EventsReadRepository reads, EventsWriteRepository writes,
                             EventFileStorageService fileStorage) {
        this.reads = reads;
        this.writes = writes;
        this.fileStorage = fileStorage;
    }

    /* ===================== EVENT TYPE ===================== */

    /** createEventType (eventController.js:9-24). */
    @PostMapping("/event-types")
    public org.springframework.http.ResponseEntity<Map<String, Object>> createEventType(
            @RequestBody(required = false) Map<String, Object> body) {
        Object name = body == null ? null : body.get("event_type_name");
        // Node guards `if (!event_type_name)` -- only null/empty-string is falsy; a whitespace-only name
        // ("   ") is truthy in JS and gets inserted. Match with isEmpty (NOT isBlank).
        if (name == null || String.valueOf(name).isEmpty()) {
            throw ApiException.message(400, "Event type name is required");
        }
        try {
            Map<String, Object> row = writes.createEventType(String.valueOf(name));
            return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(row);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to create event type");
        }
    }

    /** updateEventType (eventController.js:26-35). */
    @PutMapping("/event-type/{id}")
    public Map<String, Object> updateEventType(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        long eventTypeId = EventValidation.validateEventId(id);
        Object name = body == null ? null : body.get("event_type_name");
        try {
            return writes.updateEventType(eventTypeId, name == null ? null : String.valueOf(name))
                    .map(row -> row)
                    .orElseGet(LinkedHashMap::new);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to update event type");
        }
    }

    /** getEventTypes (eventController.js:37-44). */
    @GetMapping("/event-types")
    public List<Map<String, Object>> getEventTypes() {
        try {
            return reads.eventTypes();
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch event types");
        }
    }

    /* ===================== JURISDICTIONS ===================== */

    /** getJurisdictionData (eventController.js:209-219). No `else` branch for an unrecognized `type` --
     *  `data` stays unset and the response omits the "data" key entirely (200, not 400) -- ported literally. */
    @GetMapping("/attendance/jurisdictions")
    public Map<String, Object> jurisdictionData(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String stateName,
                                                  @RequestParam(required = false) List<String> divisionNames,
                                                  @RequestParam(required = false) List<String> districtNames) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            List<Map<String, Object>> data = switch (type == null ? "" : type) {
                case "state" -> reads.states();
                case "division" -> reads.divisionsByState(stateName);
                case "district" -> reads.districtsByDivisions(lowerTrim(divisionNames));
                case "block" -> reads.blocksByMultiDistricts(stateName, lowerTrim(divisionNames), lowerTrim(districtNames));
                default -> null;
            };
            if (data != null) out.put("data", data);
            return out;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("msg", e.getMessage());
        }
    }

    /** Node lower/trims divisionNames/districtNames arrays in JS before binding (eventModel.js:251-253,
     *  273-279) -- a single non-array query value is treated as a 1-element array (Spring's List<String>
     *  binding already normalizes single-vs-multi query params to a List, so no extra branch is needed here). */
    /** fetchStudentAttendanceList's `(page||1)` (eventController.js:244) -- a client sending "page" as a
     *  string (e.g. "2") must not 500 via a hard (Number) cast; parse robustly and default to 1 on
     *  null/blank/non-numeric/0, matching JS's falsy-OR semantics. */
    private static int parsePageOrDefault(Object pageObj) {
        if (pageObj == null) return 1;
        String s = String.valueOf(pageObj).trim();
        if (s.isEmpty()) return 1;
        try {
            int p = Integer.parseInt(s);
            return p == 0 ? 1 : p;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String[] lowerTrim(List<String> values) {
        if (values == null) return new String[0];
        return values.stream().map(v -> v == null ? "" : v.trim().toLowerCase()).toArray(String[]::new);
    }

    /* ===================== EVENTS ===================== */

    /** getAllEvents (eventController.js:183-188). */
    @GetMapping("/events")
    public List<Map<String, Object>> getAllEvents() {
        try {
            return reads.allEvents();
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("message", "Fetch failed");
        }
    }

    /** getEventById (eventController.js:190-199). */
    @GetMapping("/events/{id}")
    public Map<String, Object> getEventById(@PathVariable String id) {
        long eventId = EventValidation.validateEventId(id);
        try {
            Map<String, Object> event = reads.eventById(eventId)
                    .orElseThrow(() -> ApiException.message(404, "Not found"));
            Map<String, Object> out = new LinkedHashMap<>(event);
            out.put("photos", reads.eventPhotos(eventId));
            out.put("reports", reads.eventReports(eventId));
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("message", "Fetch failed");
        }
    }

    /* ===================== EVENTS: CREATE / DELETE ===================== */

    /** createEvent-only behavior for POST /events (Firm Decision 3 -- updateEvent never runs on this route
     *  in live Node, so Java doesn't implement it here at all). */
    @PostMapping(value = "/events", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public org.springframework.http.ResponseEntity<Map<String, Object>> createEvent(
            @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
            @RequestParam(value = "event_type_id", required = false) String eventTypeId,
            @RequestParam(value = "event_title", required = false) String eventTitle,
            @RequestParam(value = "event_description", required = false) String eventDescription,
            @RequestParam(value = "event_start_date", required = false) String eventStartDate,
            @RequestParam(value = "event_end_date", required = false) String eventEndDate,
            @RequestParam(value = "event_district", required = false) String eventDistrictRaw,
            @RequestParam(value = "event_block", required = false) String eventBlockRaw,
            @RequestParam(value = "event_location", required = false) String eventLocation,
            @RequestParam(value = "pincode", required = false) String pincodeRaw,
            @RequestParam(value = "cohort_number", required = false) String cohortNumberRaw,
            @RequestParam(value = "boys_attended", required = false) String boysAttendedRaw,
            @RequestParam(value = "girls_attended", required = false) String girlsAttendedRaw,
            @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
            @RequestParam(value = "user_id", required = false) String userIdBody,
            @RequestParam(value = "photos", required = false) MultipartFile[] photos,
            @RequestParam(value = "reports", required = false) MultipartFile[] reports,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.rcf.imas.platform.security.JwtService.FinalToken principal) {

        EventUploadValidation.validate(photos, reports);

        String eventDistrict = EventValidation.sanitizeNumeric(eventDistrictRaw);
        String eventBlock = EventValidation.sanitizeNumeric(eventBlockRaw);
        String pincode = EventValidation.sanitizeNumeric(pincodeRaw);
        String cohortNumber = EventValidation.sanitizeNumeric(cohortNumberRaw);
        String boysAttended = EventValidation.sanitizeNumeric(boysAttendedRaw);
        String girlsAttended = EventValidation.sanitizeNumeric(girlsAttendedRaw);
        String parentsAttended = EventValidation.sanitizeNumeric(parentsAttendedRaw);

        EventValidation.validateEventBody(eventTypeId, eventTitle, eventStartDate, eventEndDate);

        // req.user?.user_id || user_id || null (eventController.js:63) -- principal is real now that this
        // controller enforces ADMIN auth (Locked Decision 1), so it's the operative path in practice.
        Long userId = principal != null ? Long.valueOf(principal.userId())
                : (userIdBody != null && !userIdBody.isBlank() ? Long.valueOf(userIdBody) : null);

        try {
            List<EventFileStorageService.StoredFile> stored = new ArrayList<>();
            if (photos != null) {
                int idx = 0;
                for (MultipartFile f : photos) {
                    if (f == null || f.isEmpty()) continue;
                    idx++;
                    stored.add(fileStorage.storePhoto(f, eventTitleFilenameField, idx));
                }
            }

            int eventId = writes.createEvent(Integer.valueOf(eventTypeId), eventTitle, eventDescription,
                    eventStartDate, eventEndDate, eventDistrict, eventBlock, eventLocation, pincode, cohortNumber,
                    boysAttended, girlsAttended, parentsAttended, userId, stored);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Event created");
            body.put("event_id", eventId);
            return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("message", "Failed to create event");
        }
    }

    /** updateEvent (eventController.js:102-173), the PUT /events/:id handler. */
    @PutMapping(value = "/events/{id}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateEvent(
            @PathVariable String id,
            @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
            // required=false so validateEventBody's 400 fires (Node runs its middleware on a plain body where
            // these are `undefined`); a required @RequestParam would 500 via MissingServletRequestParameterException.
            @RequestParam(value = "event_type_id", required = false) String eventTypeId,
            @RequestParam(value = "event_title", required = false) String eventTitle,
            @RequestParam(value = "event_description", required = false) String eventDescription,
            @RequestParam(value = "event_start_date", required = false) String eventStartDate,
            @RequestParam(value = "event_end_date", required = false) String eventEndDate,
            @RequestParam(value = "event_district", required = false) String eventDistrictRaw,
            @RequestParam(value = "event_block", required = false) String eventBlockRaw,
            @RequestParam(value = "event_location", required = false) String eventLocation,
            @RequestParam(value = "pincode", required = false) String pincodeRaw,
            @RequestParam(value = "cohort_number", required = false) String cohortNumberRaw,
            @RequestParam(value = "boys_attended", required = false) String boysAttendedRaw,
            @RequestParam(value = "girls_attended", required = false) String girlsAttendedRaw,
            @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
            @RequestParam(value = "event_type_name", required = false) String eventTypeName,
            @RequestParam(value = "photos_to_delete", required = false) String photosToDeleteJson,
            @RequestParam(value = "photos", required = false) MultipartFile[] photos,
            @RequestParam(value = "reports", required = false) MultipartFile[] reports,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.rcf.imas.platform.security.JwtService.FinalToken principal) {

        long eventId = EventValidation.validateEventId(id);
        EventUploadValidation.validate(photos, reports);

        String eventDistrict = EventValidation.sanitizeNumeric(eventDistrictRaw);
        String eventBlock = EventValidation.sanitizeNumeric(eventBlockRaw);
        String pincode = EventValidation.sanitizeNumeric(pincodeRaw);
        String cohortNumber = EventValidation.sanitizeNumeric(cohortNumberRaw);
        String boysAttended = EventValidation.sanitizeNumeric(boysAttendedRaw);
        String girlsAttended = EventValidation.sanitizeNumeric(girlsAttendedRaw);
        String parentsAttended = EventValidation.sanitizeNumeric(parentsAttendedRaw);

        EventValidation.validateEventBody(eventTypeId, eventTitle, eventStartDate, eventEndDate);

        Long userId = principal != null ? Long.valueOf(principal.userId()) : null;

        try {
            java.util.List<Integer> photosToDelete = new ArrayList<>();
            if (photosToDeleteJson != null && !photosToDeleteJson.isBlank()) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                int[] ids = om.readValue(photosToDeleteJson, int[].class);
                for (int pid : ids) photosToDelete.add(pid);
            }

            List<EventFileStorageService.StoredFile> storedPhotos = new ArrayList<>();
            if (photos != null) {
                for (MultipartFile f : photos) {
                    if (f == null || f.isEmpty()) continue;
                    storedPhotos.add(fileStorage.storePhoto(f, eventTitleFilenameField, storedPhotos.size() + 1));
                }
            }
            EventFileStorageService.StoredFile storedReport = null;
            if (reports != null) {
                for (MultipartFile f : reports) {
                    if (f == null || f.isEmpty()) continue;
                    storedReport = fileStorage.storeReport(f, eventTitleFilenameField);
                    break; // only reports[0] is ever used (eventController.js:163)
                }
            }

            writes.updateEvent(eventId, photosToDelete, Integer.valueOf(eventTypeId), eventTitle, eventDescription,
                    eventStartDate, eventEndDate, eventDistrict, eventBlock, eventLocation, pincode, cohortNumber,
                    boysAttended, girlsAttended, parentsAttended, eventTypeName, userId, storedPhotos, storedReport);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Updated successfully");
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // updateEvent's Node catch block leaks the raw exception message to the client (eventController.js:171,
            // ground truth §5 row 7) -- ported literally, not sanitized.
            throw ApiException.of(500).with("success", false).with("message", e.getMessage());
        }
    }

    /** deleteEvent (eventController.js:175-181). */
    @DeleteMapping("/events/{id}")
    public Map<String, Object> deleteEvent(@PathVariable String id) {
        long eventId = EventValidation.validateEventId(id);
        try {
            writes.deleteEvent(eventId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", "Deleted successfully");
            return body;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("message", "Delete failed");
        }
    }

    /* ===================== ATTENDANCE ===================== */

    /** getSammelanEvents (eventController.js:202-207). */
    @GetMapping("/attendance/sammelan-list")
    public Map<String, Object> sammelanList() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.sammelanEvents());
            return out;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("msg", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    /** fetchStudentAttendanceList (eventController.js:221-254). */
    @PostMapping("/attendance/students-list")
    public Map<String, Object> studentsList(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String eventTitle = (String) b.get("eventTitle");
        Map<String, Object> event = reads.eventByTitle(eventTitle).orElse(null);
        if (event == null) {
            throw ApiException.of(404).with("success", false).with("msg", "Event not found");
        }
        try {
            long eventId = Long.parseLong(String.valueOf(event.get("event_id")));
            Integer cohortNumber = (Integer) event.get("cohort_number");
            String stateName = (String) b.get("stateName");
            List<String> districtNames = (List<String>) b.get("districtNames");
            List<String> blockNames = (List<String>) b.get("blockNames");
            Object pageObj = b.get("page");
            int page = parsePageOrDefault(pageObj);
            int limit = 15;
            int offset = (page - 1) * limit;

            List<Map<String, Object>> students = reads.sammelanStudentList(eventId, cohortNumber, stateName,
                    (districtNames == null || districtNames.isEmpty()) ? null : districtNames.toArray(new String[0]),
                    (blockNames == null || blockNames.isEmpty()) ? null : blockNames.toArray(new String[0]),
                    null, limit, offset);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", students);
            return out;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("msg", "Internal Server Error");
        }
    }

    /** submitAttendance (eventController.js:256-312). */
    @PostMapping(value = "/attendance/save", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> submitAttendance(
            @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
            // Node has NO validation middleware here. required=false reproduces its behavior on missing fields:
            // absent eventId -> Long.parseLong(null) throws -> 500 {success:false,msg} (Node's SQL fails the same);
            // absent studentIds -> Node binds null -> unnest(null) -> 0 rows -> 200 success (handled below).
            @RequestParam(value = "eventId", required = false) String eventIdRaw,
            @RequestParam(value = "studentIds", required = false) String studentIdsRaw,
            @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
            @RequestParam(value = "user_id", required = false) String userIdBody,
            @RequestParam(value = "photos", required = false) MultipartFile[] photos,
            @RequestParam(value = "reports", required = false) MultipartFile[] reports,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.rcf.imas.platform.security.JwtService.FinalToken principal) {

        EventUploadValidation.validate(photos, reports);
        try {
            long eventId = Long.parseLong(eventIdRaw);
            // Node binds a missing studentIds as null -> unnest(null) -> 0 rows -> 200. Treat absent/blank as
            // an empty list (no INSERT rows) rather than throwing, so the handler still returns success.
            List<Integer> studentIds = new ArrayList<>();
            if (studentIdsRaw != null && !studentIdsRaw.isBlank()) {
                int[] parsedIds = new ObjectMapper().readValue(studentIdsRaw, int[].class);
                for (int sid : parsedIds) studentIds.add(sid);
            }

            int parentsAttended = (parentsAttendedRaw == null || parentsAttendedRaw.isBlank())
                    ? 0 : Integer.parseInt(parentsAttendedRaw);

            Long userId = principal != null ? Long.valueOf(principal.userId())
                    : (userIdBody != null && !userIdBody.isBlank() ? Long.valueOf(userIdBody) : null);

            List<EventFileStorageService.StoredFile> storedPhotos = new ArrayList<>();
            if (photos != null) {
                for (MultipartFile f : photos) {
                    if (f == null || f.isEmpty()) continue;
                    storedPhotos.add(fileStorage.storePhoto(f, eventTitleFilenameField, storedPhotos.size() + 1));
                }
            }
            EventFileStorageService.StoredFile storedReport = null;
            if (reports != null) {
                for (MultipartFile f : reports) {
                    if (f == null || f.isEmpty()) continue;
                    storedReport = fileStorage.storeReport(f, eventTitleFilenameField);
                    break;
                }
            }

            writes.submitAttendance(eventId, studentIds, parentsAttended, userId, storedPhotos, storedReport);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("msg", "Attendance updated successfully!");
            return body;
        } catch (Exception e) {
            throw ApiException.of(500).with("success", false).with("msg", "Server Error: " + e.getMessage());
        }
    }
}
