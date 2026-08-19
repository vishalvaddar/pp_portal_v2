package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.EvaluationReadRepository;
import com.rcf.imas.modules.evaluation.service.StudentExcelSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/evaluation")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left these routes open
class EvaluationController {

    private final EvaluationReadRepository reads;
    private final StudentExcelSupport excel;

    EvaluationController(EvaluationReadRepository reads, StudentExcelSupport excel) {
        this.reads = reads;
        this.excel = excel;
    }

    /** getExamNames parity: year.split("-")[0].trim() + "%" LIKE prefix, ORDER BY exam_id ASC.
     *  Response is the ApiResponse envelope built explicitly (statusCode/data/message/success, in that key order). */
    @GetMapping("/exam_names")
    public Map<String, Object> examNames(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) {
            throw ApiException.message(400, "Academic year is required");
        }
        String yearPrefix = year.split("-")[0].trim();
        List<Map<String, Object>> examNames = reads.examNames(yearPrefix + "%");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("statusCode", 200);
        envelope.put("data", examNames);
        envelope.put("message", "ok");
        envelope.put("success", true);
        return envelope;
    }

    /** downloadStudentExcel parity: fixed 34-column export, bug 5a preserved via reads.studentsForExam. Filename
     *  is NOT quoted (Firm Decision/quirk 13), unlike the custom-list exports. */
    @PostMapping("/download_excel")
    public ResponseEntity<byte[]> downloadExcel(@RequestBody(required = false) Map<String, Object> body) {
        String examName = body == null || body.get("exam_name") == null ? null : String.valueOf(body.get("exam_name"));
        List<Map<String, Object>> students = reads.studentsForExam(examName);
        byte[] bytes = excel.build(students);
        String filename = "students_" + (examName == null ? "" : examName.replaceAll("(?i)[^a-z0-9]", "_")) + ".xlsx";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=" + filename)
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
