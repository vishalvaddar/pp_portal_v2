package com.rcf.imas.modules.results.web;

import com.rcf.imas.modules.results.persistence.ResultsReadRepository;
import com.rcf.imas.modules.results.service.ResultsXlsxSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/results")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every /api/results/** route open
class ResultsController {

    private final ResultsReadRepository reads;
    private final ResultsXlsxSupport xlsx;

    ResultsController(ResultsReadRepository reads, ResultsXlsxSupport xlsx) {
        this.reads = reads;
        this.xlsx = xlsx;
    }

    @GetMapping("/divisions-by-state/{stateId}")
    public List<Map<String, Object>> divisionsByState(@PathVariable String stateId) {
        return reads.divisionsByState(stateId);
    }

    @GetMapping("/education-districts-by-division/{divisionId}")
    public List<Map<String, Object>> educationDistrictsByDivision(@PathVariable String divisionId) {
        return reads.educationDistrictsByDivision(divisionId);
    }

    @GetMapping("/blocks-by-district/{districtId}")
    public List<Map<String, Object>> blocksByDistrict(@PathVariable String districtId) {
        return reads.blocksByDistrict(districtId);
    }

    @GetMapping("/all-exams")
    public List<Map<String, Object>> allExams() { return reads.allExams(); }

    @GetMapping("/filter-options/{field}")
    public List<String> filterOptions(@PathVariable String field) { return reads.filterOptions(field); }

    @PostMapping("/search-by-blocks")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchByBlocks(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object division = b.get("division");
        Object educationDistrict = b.get("education_district");
        List<Object> blocks = b.get("blocks") instanceof List<?> l ? (List<Object>) l : List.of();
        Object appState = b.getOrDefault("app_state", 1);
        return reads.searchByBlocks(str(division), str(educationDistrict), blocks, str(appState));
    }

    @PostMapping("/search-by-exam")
    public List<Map<String, Object>> searchByExam(@RequestBody(required = false) Map<String, Object> body) {
        Object examId = body == null ? null : body.get("exam_id");
        if (examId == null || String.valueOf(examId).isBlank()) {
            throw ApiException.message(400, "Exam ID is required");
        }
        return reads.searchByExam(String.valueOf(examId));
    }

    @PostMapping("/download-by-blocks")
    @SuppressWarnings("unchecked")
    public Object downloadByBlocks(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object division = b.get("division");
        Object district = b.get("district");   // NOTE: body key is "district", not "education_district" (frontend renames it)
        List<Object> blocks = b.get("blocks") instanceof List<?> l ? (List<Object>) l : List.of();
        Object appState = b.getOrDefault("app_state", 1);

        List<Map<String, Object>> results = reads.searchByBlocks(str(division), str(district), blocks, str(appState));
        if (results.isEmpty()) {
            throw ApiException.message(404, "No data found");
        }
        byte[] bytes = xlsx.buildResultsSheet(results);
        String filename = xlsx.blocksFilename(division, district, blocks, results);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @PostMapping("/download-by-exam")
    public Object downloadByExam(@RequestBody(required = false) Map<String, Object> body) {
        Object examId = body == null ? null : body.get("exam_id");
        if (examId == null || String.valueOf(examId).isBlank()) {
            throw ApiException.message(400, "Exam ID is required");
        }
        List<Map<String, Object>> results = reads.searchByExam(String.valueOf(examId));
        if (results.isEmpty()) {
            throw ApiException.message(404, "No data found for this exam");
        }
        byte[] bytes = xlsx.buildExamResultsSheet(results);
        String filename = xlsx.examFilename(results);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
