package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.EvaluationReadRepository;
import com.rcf.imas.modules.evaluation.persistence.EvaluationWriteRepository;
import com.rcf.imas.modules.evaluation.service.CustomListPdfSupport;
import com.rcf.imas.modules.evaluation.service.CustomListXlsxSupport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual mount preserving Node's byte-identical evaluationRoutes.js / customListRoutes.js pair --
 * both /api/custom-list/* and /api/evaluation/* serve the exact same custom-list handlers.
 */
@RestController
@RequestMapping({"/api/custom-list", "/api/evaluation"})
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every custom-list/evaluation route open
class CustomListController {

    private final EvaluationReadRepository reads;
    private final EvaluationWriteRepository writes;
    private final CustomListXlsxSupport xlsx;
    private final CustomListPdfSupport pdf;

    CustomListController(EvaluationReadRepository reads, EvaluationWriteRepository writes,
                          CustomListXlsxSupport xlsx, CustomListPdfSupport pdf) {
        this.reads = reads;
        this.writes = writes;
        this.xlsx = xlsx;
        this.pdf = pdf;
    }

    @GetMapping("/lists")
    public List<Map<String, Object>> lists() { return reads.allLists(); }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(required = false) String cohortId) {
        return reads.allBatches(cohortId);
    }

    @GetMapping("/available-fields")
    public List<Map<String, Object>> availableFields() { return reads.availableFields(); }

    @GetMapping("/students-by-list/{listId}")
    public Map<String, Object> studentsByList(@PathVariable String listId) {
        return Map.of("students", reads.studentsByList(listId), "fields", reads.fieldsForList(listId));
    }

    @GetMapping("/students-by-cohort/{cohortId}")
    public List<Map<String, Object>> studentsByCohort(@PathVariable String cohortId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String stateId,
            @RequestParam(required = false) String divisionId,   // accepted, intentionally unused (Firm Decision 5e)
            @RequestParam(required = false) String districtId,
            @RequestParam(required = false) String blockId) {
        return reads.studentsByCohort(cohortId, batchId, stateId, districtId, blockId);
    }

    @PostMapping("/save-list-full")
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveListFull(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object listId = b.get("list_id");
        String listName = b.get("list_name") == null ? null : String.valueOf(b.get("list_name"));
        List<Object> studentIds = b.get("student_ids") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Map<String, Object>> selectedFields = b.get("selectedFields") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        var result = writes.saveListFull(listId == null ? null : String.valueOf(listId), listName, studentIds, selectedFields);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("list_id", result.listId());
        return out;
    }

    @DeleteMapping("/list/{id}")
    public Map<String, Object> deleteList(@PathVariable String id) {
        writes.deleteList(id);
        return Map.of("success", true);
    }

    @GetMapping("/download-xlsx/{listId}")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable String listId) {
        List<Map<String, Object>> students = reads.studentsByList(listId);
        List<Map<String, Object>> fields = reads.fieldsForList(listId);
        String listName = orDefault(reads.listName(listId), "Custom_List");
        byte[] bytes = xlsx.build(students, fields);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + listName + ".xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @GetMapping("/download-pdf/{listId}")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String listId) {
        List<Map<String, Object>> students = reads.studentsByList(listId);
        List<Map<String, Object>> fields = reads.fieldsForList(listId);
        String listName = orDefault(reads.listName(listId), "Custom_List");
        byte[] bytes = pdf.build(listName, students, fields);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + listName + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }
}
