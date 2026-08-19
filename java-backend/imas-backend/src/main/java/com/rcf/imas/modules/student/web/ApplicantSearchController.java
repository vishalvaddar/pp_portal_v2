package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.ApplicantSearchReadRepository;
import com.rcf.imas.modules.student.persistence.ApplicantSortField;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")   // searchRoutes.js: zero `authenticate` middleware in Node -- NEW hardening
class ApplicantSearchController {

    private final ApplicantSearchReadRepository reads;

    ApplicantSearchController(ApplicantSearchReadRepository reads) { this.reads = reads; }

    @GetMapping("/search")
    public Object search(
            @RequestParam(required = false) String nmms_year,
            @RequestParam(required = false) String nmms_reg_number,
            @RequestParam(required = false) String student_name,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String nmms_block,
            @RequestParam(required = false) String app_state,
            @RequestParam(required = false) String current_institute_dise_code,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_order) {

        // Node: `parseInt(limit,10) || 10` -- an explicit limit=0 ALSO falls back to 10 (JS falsy-zero).
        int pageLimit = (limit == null || limit == 0) ? 10 : limit;
        int pageOffset = (offset == null || offset == 0) ? 0 : offset;
        ApplicantSortField sortField = ApplicantSortField.fromRequestOrDefault(sort_by);
        String sortOrderSafe = (sort_order != null && sort_order.equalsIgnoreCase("DESC")) ? "DESC" : "ASC";

        String regTrim = nmms_reg_number == null ? null : nmms_reg_number.trim();
        String nameTrim = student_name == null ? null : student_name.trim();

        try {
            ApplicantSearchReadRepository.SearchResult result = (regTrim != null && !regTrim.isBlank())
                    ? reads.searchByRegNumber(regTrim, sortField, sortOrderSafe, pageLimit, pageOffset)
                    : reads.search(nameTrim, nmms_year, medium, app_state, district, nmms_block,
                            current_institute_dise_code, sortField, sortOrderSafe, pageLimit, pageOffset);

            if (result.rows().isEmpty() && pageOffset == 0) {
                throw ApiException.message(404, "No applications found matching the criteria.");
            }

            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("total", result.totalCount());
            pagination.put("limit", pageLimit);
            pagination.put("offset", pageOffset);
            pagination.put("totalPages", (long) Math.ceil((double) result.totalCount() / pageLimit));
            pagination.put("currentPage", pageOffset / pageLimit + 1);
            pagination.put("nextOffset", pageOffset + pageLimit < result.totalCount() ? pageOffset + pageLimit : null);
            pagination.put("prevOffset", pageOffset - pageLimit >= 0 ? pageOffset - pageLimit : null);

            Map<String, Object> sort = new LinkedHashMap<>();
            sort.put("sortBy", sortField.column);
            sort.put("sortOrder", sortOrderSafe);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", result.rows());
            body.put("pagination", pagination);
            body.put("sort", sort);
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        try {
            return Map.of("data", reads.allCohorts());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }

    @GetMapping("/batches/cohort/{cohortNumber}")
    public Map<String, Object> batchesByCohort(@PathVariable String cohortNumber) {
        try {
            return Map.of("data", reads.batchesByCohort(cohortNumber));
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }
}
