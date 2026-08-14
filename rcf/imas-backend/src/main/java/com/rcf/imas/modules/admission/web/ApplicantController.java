package com.rcf.imas.modules.admission.web;

import com.rcf.imas.modules.admission.persistence.ApplicantRepository;
import com.rcf.imas.modules.admission.service.ApplicantFormatter;
import com.rcf.imas.modules.admission.service.ApplicantService;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applicants")
@PreAuthorize("hasRole('ADMIN')")   // class-level: every handler here handles student PII → ADMIN only
class ApplicantController {

    private final ApplicantRepository repo;
    private final ApplicantFormatter formatter;
    private final ApplicantService service;

    ApplicantController(ApplicantRepository repo, ApplicantFormatter formatter, ApplicantService service) {
        this.repo = repo;
        this.formatter = formatter;
        this.service = service;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    @GetMapping({"", "/"})
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = repo.listSummary();
        rows.forEach(formatter::formatResponse);
        return ok(rows);
    }

    @GetMapping("/reg/{nmmsRegNumber}")
    public Map<String, Object> getByReg(@PathVariable String nmmsRegNumber) {
        Map<String, Object> row = repo.findByRegNumber(nmmsRegNumber)
                .orElseThrow(() -> notFound());
        formatter.formatResponse(row);
        return ok(row);
    }

    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.countByYear(year));
        return m;
    }

    @GetMapping("/shortlisted/count")
    public Map<String, Object> shortlistedCount(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.shortlistedCount(year));
        return m;
    }

    @GetMapping("/selected/count")
    public Map<String, Object> selectedCount(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.selectedCount(year));
        return m;
    }

    @GetMapping("/cohortstudentcount")
    public Map<String, Object> cohortStudentCount(@RequestParam(required = false) String year) {
        int cur = Integer.parseInt(year);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentYear", cur);
        data.put("previousYear", cur - 1);
        data.put("counts", repo.cohortCounts(cur, cur - 1));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    @GetMapping("/today-classes-count")
    public Map<String, Object> todayClassesCount(@RequestParam(required = false) String year) {
        int cohortNumber = Integer.parseInt(year) - 2021;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.todayClasses(cohortNumber));  // ARRAY parity
        return m;
    }

    @GetMapping("/{applicantId}")
    public Map<String, Object> getById(@PathVariable String applicantId) {
        Map<String, Object> row = repo.findById(applicantId)
                .orElseThrow(() -> notFound());
        formatter.formatResponse(row);
        return ok(row);
    }

    @PostMapping("/create")
    public org.springframework.http.ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.rcf.imas.platform.security.JwtService.FinalToken principal) {

        String userId = principal == null ? null : principal.userId();

        @SuppressWarnings("unchecked")
        Map<String, Object> primaryData = body.get("primaryData") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : flatMinusSecondary(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> secondaryData = body.get("secondaryData") instanceof Map<?, ?> s
                ? (Map<String, Object>) s : Map.of();

        String applicantId = service.create(primaryData, secondaryData, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicant_id", applicantId);
        formatter.formatResponse(data);   // parity: response echoes formatResponse(model result)
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant created successfully");
        resp.put("data", data);
        return org.springframework.http.ResponseEntity.status(201).body(resp);
    }

    @PutMapping("/{applicantId}/update")
    public Map<String, Object> update(@PathVariable String applicantId,
                                      @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> primaryData = body.get("primaryData") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> secondaryData = body.get("secondaryData") instanceof Map<?, ?> s ? (Map<String, Object>) s : null;

        service.update(applicantId, primaryData, secondaryData);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("applicantId", applicantId);   // parity: model returns {success, applicantId}, echoed via data
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant updated successfully");
        resp.put("data", data);
        return resp;
    }

    @DeleteMapping("/{applicantId}")
    public Map<String, Object> delete(@PathVariable String applicantId) {
        repo.deleteById(applicantId).orElseThrow(ApplicantController::notFound);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant deleted successfully");
        return resp;
    }

    private static Map<String, Object> flatMinusSecondary(Map<String, Object> body) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove("secondaryData");
        return copy;
    }

    // Node 404 body: {success:false, message:"Applicant not found"}
    private static ApiException notFound() {
        return ApiException.message(404, "Applicant not found").with("success", false);
    }
}
