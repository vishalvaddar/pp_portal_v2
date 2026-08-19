package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorTimetableRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Timetable endpoints #30-34 (ground truth §1). Firm Decision 1: real @PreAuthorize("isAuthenticated()")
 * at class level -- matches Node's `authenticate` (JWT-verifying) middleware used for all timetable routes.
 */
@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")
public class CoordinatorTimetableController {

    private final CoordinatorTimetableRepository timetable;

    public CoordinatorTimetableController(CoordinatorTimetableRepository timetable) {
        this.timetable = timetable;
    }

    @GetMapping("/timetable")
    public List<Map<String, Object>> getTimetable(@RequestParam(required = false) String batchId) {
        if (isBlank(batchId)) throw ApiException.error(400, "batchId is required");
        try {
            return timetable.getTimetableByBatch(batchId);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch timetable");
        }
    }

    /** checkConflict -- accepts both camelCase and snake_case query params (timetableController.js:24-29). */
    @GetMapping("/timetable/check-conflict")
    public Map<String, Object> checkConflict(
            @RequestParam(required = false) String classroomId,
            @RequestParam(name = "classroom_id", required = false) String classroomIdSnake,
            @RequestParam(required = false) String teacherId,
            @RequestParam(name = "teacher_id", required = false) String teacherIdSnake,
            @RequestParam(required = false) String day,
            @RequestParam(required = false) String dayOfWeek,
            @RequestParam(required = false) String startTime,
            @RequestParam(name = "start_time", required = false) String startTimeSnake,
            @RequestParam(required = false) String endTime,
            @RequestParam(name = "end_time", required = false) String endTimeSnake,
            @RequestParam(required = false) String excludeId,
            @RequestParam(name = "exclude_id", required = false) String excludeIdSnake) {
        try {
            String cid = firstNonBlank(classroomId, classroomIdSnake);
            String tid = firstNonBlank(teacherId, teacherIdSnake);
            String d = firstNonBlank(day, dayOfWeek);
            String st = firstNonBlank(startTime, startTimeSnake);
            String et = firstNonBlank(endTime, endTimeSnake);
            String ex = firstNonBlank(excludeId, excludeIdSnake);

            List<Map<String, Object>> conflicts = timetable.checkConflicts(d, st, et, cid, tid, ex);
            Map<String, Object> body = new LinkedHashMap<>();
            if (!conflicts.isEmpty()) {
                body.put("overlap", true);
                body.put("conflicts", conflicts);
            } else {
                body.put("overlap", false);
            }
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to check conflicts");
        }
    }

    @DeleteMapping("/timetable/{id}")
    public Map<String, Object> deleteSlot(@PathVariable String id) {
        try {
            timetable.deleteSlot(id);
            return Map.of("success", true);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to delete timetable slot");
        }
    }

    @PostMapping("/timetable")
    public Map<String, Object> createSlot(@RequestBody Map<String, Object> body) {
        String batchId = str(body.get("batch_id"));
        String classroomId = str(body.get("classroom_id"));
        String day = str(body.get("day"));
        String startTime = str(body.get("start_time"));
        String endTime = str(body.get("end_time"));
        String classLink = str(body.get("class_link"));

        if (isBlank(batchId) || isBlank(classroomId) || isBlank(day) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            List<Map<String, Object>> conflicts = timetable.checkConflicts(day, startTime, endTime, classroomId, null, null);
            if (!conflicts.isEmpty()) {
                throw ApiException.message(400, "Conflict detected with existing schedule.")
                        .with("overlap", true).with("conflicts", conflicts);
            }
            Map<String, Object> created = timetable.createSlot(classroomId, day, startTime, endTime, classLink);
            return Map.of("success", true, "data", created);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to create timetable slot");
        }
    }

    @PutMapping("/timetable/{id}")
    public Map<String, Object> updateSlot(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String classroomId = str(body.get("classroom_id"));
        String day = str(body.get("day"));
        String startTime = str(body.get("start_time"));
        String endTime = str(body.get("end_time"));
        String classLink = str(body.get("class_link"));

        if (isBlank(classroomId) || isBlank(day) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            List<Map<String, Object>> conflicts = timetable.checkConflicts(day, startTime, endTime, classroomId, null, id);
            if (!conflicts.isEmpty()) {
                throw ApiException.message(400, "Conflict detected with existing schedule.")
                        .with("overlap", true).with("conflicts", conflicts);
            }
            Map<String, Object> updated = timetable.updateSlot(id, classroomId, day, startTime, endTime, classLink);
            return Map.of("success", true, "data", updated);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to update timetable slot");
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    static boolean isBlank(String s) { return s == null || s.isBlank(); }

    static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
    }
}
