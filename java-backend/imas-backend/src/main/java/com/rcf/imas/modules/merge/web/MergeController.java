package com.rcf.imas.modules.merge.web;

import com.rcf.imas.modules.merge.persistence.MergeReadRepository;
import com.rcf.imas.modules.merge.persistence.MergeWriteRepository;
import com.rcf.imas.modules.merge.service.CsvSupport;
import com.rcf.imas.modules.merge.service.MergeService;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/merge")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: NMMS merge mutates student PII → ADMIN only (Node left it open)
class MergeController {

    private final MergeReadRepository reads;
    private final MergeService service;
    private final CsvSupport csv;
    private final MergeWriteRepository writes;

    MergeController(MergeReadRepository reads, MergeService service, CsvSupport csv, MergeWriteRepository writes) {
        this.reads = reads;
        this.service = service;
        this.csv = csv;
        this.writes = writes;
    }

    @GetMapping("/jurisdiction")
    public List<Map<String, Object>> jurisdiction(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String parent) {
        return reads.jurisdictions(type, parent);
    }

    @GetMapping("/applications")
    public Map<String, Object> applications(@RequestParam(required = false) String year,
                                            @RequestParam(required = false) String district,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false, defaultValue = "1") int page) {
        return reads.stagedPage("stg_nmms_phase1_applications", "a", year, district, search, page);
    }

    @GetMapping("/results")
    public Map<String, Object> results(@RequestParam(required = false) String year,
                                       @RequestParam(required = false) String district,
                                       @RequestParam(required = false) String search,
                                       @RequestParam(required = false, defaultValue = "1") int page) {
        return reads.stagedPage("stg_nmms_phase2_results", "r", year, district, search, page);
    }

    @GetMapping("/draft-districts")
    public List<Map<String, Object>> draftDistricts() {
        return reads.draftDistricts();
    }

    @GetMapping("/draft-district-students")
    public List<Map<String, Object>> draftDistrictStudents(@RequestParam(required = false) String district,
                                                           @RequestParam(required = false) String year) {
        return reads.draftDistrictStudents(district, year);
    }

    @GetMapping("/merged-status")
    public List<Map<String, Object>> mergedStatus() {
        return reads.mergedStatus();
    }

    @GetMapping("/commit-status")
    public Map<String, Object> commitStatus(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", reads.commitStatus(year));
        return m;
    }

    @GetMapping("/merge-status")
    public Map<String, Object> mergeStatus(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", reads.mergeStatus(year));
        return m;
    }

    @PostMapping("/upload-p1")
    public org.springframework.http.ResponseEntity<Map<String, Object>> uploadP1(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String state_id,
            @RequestParam(required = false) String district_id) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw ApiException.error(400, "No CSV file provided");
        var records = csv.parse(file.getBytes(), true);   // p1: strip BOM + trim headers
        var result = service.uploadP1(records, year, state_id, district_id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("logs", result.logs());
        return org.springframework.http.ResponseEntity.status(result.success() ? 200 : 400).body(body);
    }

    @PostMapping("/upload-p2")
    public org.springframework.http.ResponseEntity<Map<String, Object>> uploadP2(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String district_id) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw ApiException.error(400, "No CSV file provided");
        var records = csv.parse(file.getBytes(), false);  // p2: headers verbatim (no BOM strip)
        var result = service.uploadP2(records, year, district_id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("logs", result.logs());
        return org.springframework.http.ResponseEntity.ok(body);
    }

    @PostMapping("/preview-merge")
    public Map<String, Object> previewMerge(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String year = str(b.get("year"));
        String district = str(b.get("district"));
        return service.previewMerge(year, district, reads);
    }

    @PostMapping("/bulk-auto-map")
    public Map<String, Object> bulkAutoMap(@RequestBody(required = false) Map<String, Object> body,
                                           @org.springframework.security.core.annotation.AuthenticationPrincipal
                                           com.rcf.imas.platform.security.JwtService.FinalToken principal) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String userId = principal == null || principal.userId() == null ? "1" : principal.userId();
        try {
            writes.moveMappedToStd(str(b.get("district")), str(b.get("year")), userId);
        } catch (RuntimeException e) {
            throw ApiException.error(500, "Failed to process bulk mapping");
        }
        return Map.of("message", "Bulk mapping successful. Records copied to draft.");
    }

    @PostMapping("/resolve-lively")
    public Map<String, Object> resolveLively(@RequestBody(required = false) Map<String, Object> body,
                                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.rcf.imas.platform.security.JwtService.FinalToken principal) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String userId = principal == null || principal.userId() == null ? "1" : principal.userId();
        try {
            writes.resolveMatch(str(b.get("app_id")), str(b.get("res_id")), userId);
        } catch (RuntimeException e) {
            throw ApiException.error(500, "Mapping failed");
        }
        return Map.of("message", "Mapped successfully");
    }

    @PostMapping("/commit-to-primary")
    public Map<String, Object> commitToPrimary(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            writes.commitToPrimary(str(b.get("district")), str(b.get("year")));
        } catch (RuntimeException e) {
            throw ApiException.error(500, "Failed to finalize merge.");
        }
        return Map.of("message", "Successfully committed to Primary Table.");
    }

    private static final List<String> P1_TEMPLATE = List.of(
        "nmms_year","Exam","app_state","district","nmms_block","current_institute_dise_code","students_sats_id",
        "student_name","father_name","institute_name","institute_type","category_name","disability_status",
        "contact_no1","contact_no2","date_of_application");
    private static final List<String> P2_TEMPLATE = List.of(
        "nmms_year","nmms_block","nmms_reg_number","student_name","gmat_score","sat_score","total");

    @GetMapping("/download-template")
    public org.springframework.http.ResponseEntity<String> downloadTemplate(@RequestParam(required = false) String phase) {
        List<String> fields;
        if ("p1".equals(phase)) fields = P1_TEMPLATE;
        else if ("p2".equals(phase)) fields = P2_TEMPLATE;
        else throw ApiException.error(400, "Invalid phase");
        String body = csv.write(fields, List.of(new LinkedHashMap<>()));   // header-only (single empty row → header line)
        return csvResponse(body, "NMMS_" + phase + "_Template.csv");
    }

    @GetMapping("/district/{districtId}/download-csv")
    public org.springframework.http.ResponseEntity<String> districtCsv(@PathVariable String districtId) {
        List<Map<String, Object>> data = reads.districtMergedData(districtId);
        if (data.isEmpty()) throw ApiException.message(404, "No data found for this district.");
        List<String> fields = new java.util.ArrayList<>(data.get(0).keySet());
        String body = csv.write(fields, data);
        return csvResponse(body, "district_" + districtId + "_merged.csv");
    }

    @DeleteMapping("/delete-district-data")
    public Map<String, Object> deleteDistrictData(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String district = str(b.get("district"));
        String year = str(b.get("year"));
        String phase = str(b.get("phase"));
        String section = str(b.get("section"));
        if (district == null || district.isBlank()) throw ApiException.error(400, "District is required");
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");

        if ("merge".equals(section)) {
            if (reads.applicantPrimaryExists(district, year))
                throw ApiException.error(400, "Deletion not allowed!! The currrent district merge process is already completed");
        } else {
            if (reads.stdPrimaryExists(district, year))
                throw ApiException.error(400, "Deletion not allowed!! Data already merged. To continue with the deletion you need to delete the merged data");
        }

        long n;
        if ("merge".equals(section)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.MERGE, district);
            return Map.of("message", n + " records deleted from Primary Table for district " + district);
        }
        if ("p1".equals(phase)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.P1, district);
            return Map.of("message", n + " Phase 1 application records deleted for district " + district);
        }
        if ("p2".equals(phase)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.P2, district);
            return Map.of("message", n + " Phase 2 result records deleted for district " + district);
        }
        throw ApiException.error(400, "Invalid phase or section");
    }

    private static org.springframework.http.ResponseEntity<String> csvResponse(String body, String filename) {
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Type", "text/csv")
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(body);
    }

    // helper for request-body scalar → String (numbers or strings both accepted)
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
