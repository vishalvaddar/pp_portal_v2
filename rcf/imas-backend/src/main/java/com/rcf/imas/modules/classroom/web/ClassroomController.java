package com.rcf.imas.modules.classroom.web;

import com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository;
import com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/classrooms")
@PreAuthorize("hasRole('ADMIN')")   // ground truth: zero Node `authenticate` middleware on this mount -- Firm Decision 1
class ClassroomController {

    private final ClassroomReadRepository reads;
    private final ClassroomWriteRepository writes;

    ClassroomController(ClassroomReadRepository reads, ClassroomWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        try { return reads.subjects(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); } // convention #7: raw err.message
    }

    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        try { return reads.teachingPlatforms(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping("/teachers/{subjectId}")
    public List<Map<String, Object>> teachersBySubject(@PathVariable String subjectId) {
        try { return reads.teachersBySubject(subjectId); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping("/batches/{cohortNumber}")
    public List<Map<String, Object>> batchesByCohort(@PathVariable String cohortNumber) {
        try { return reads.batchesByCohortClassroomSide(cohortNumber); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> classrooms() {
        try { return reads.classrooms(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @PostMapping({"", "/"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createClassroom(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            // Node destructures created_by AND updated_by separately from the body (classroomModel.js:26-27,41);
            // an absent updated_by binds NULL (matching Node's `undefined`).
            Map<String, Object> row = writes.createClassroom(str(b.get("classroom_name")), str(b.get("subject_id")),
                    str(b.get("teacher_id")), str(b.get("platform_id")), str(b.get("class_link")),
                    str(b.get("active_yn")), str(b.get("created_by")), str(b.get("updated_by")), asStringList(b.get("batch_ids")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Classroom created successfully");
            out.put("classroom_id", row.get("classroom_id"));
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateClassroom(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean batchIdsProvided = b.containsKey("batch_ids");
        try {
            Map<String, Object> row = writes.updateClassroom(id, str(b.get("classroom_name")), str(b.get("subject_id")),
                    str(b.get("teacher_id")), str(b.get("platform_id")), str(b.get("class_link")),
                    str(b.get("active_yn")), str(b.get("updated_by")), batchIdsProvided, asStringList(b.get("batch_ids")));
            if (row == null) throw ApiException.message(404, "Classroom not found");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("classroom_id", row.get("classroom_id"));
            out.put("message", "Classroom updated");
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteClassroom(@PathVariable String id) {
        try {
            boolean deleted = writes.deleteClassroom(id);
            if (!deleted) throw ApiException.message(404, "Classroom not found");
            return Map.of("message", "Classroom deleted successfully");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l)) return null;
        return l.stream().map(String::valueOf).toList();
    }
}
