package com.rcf.imas.modules.masterdata.web;

import com.rcf.imas.modules.masterdata.persistence.SystemConfigRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system-config")
// Node's system-config mount had NO auth. Mutations are ADMIN (below). `/active` stays role-agnostic
// (isAuthenticated) because the app-wide SystemConfigContext reads it for EVERY authenticated role on load
// -- gating it ADMIN would 403 the whole non-admin app. `/all` (full config list) is ADMIN (admin-only screen).
@PreAuthorize("isAuthenticated()")
class SystemConfigController {

    record ConfigRequest(String academicYear, String phase, Boolean isActive) {}
    // NOTE: global snake_case Jackson maps academic_year -> academicYear, is_active -> isActive
    // automatically — the frontend sends snake_case keys, so NO @JsonProperty pins here.

    private final SystemConfigRepository repo;

    SystemConfigController(SystemConfigRepository repo) { this.repo = repo; }

    @PreAuthorize("hasRole('ADMIN')")   // full config list is admin-only (Admin/SystemConfig.js)
    @GetMapping("/all")
    public List<Map<String, Object>> all() { return repo.findAll(); }

    @GetMapping("/active")
    public List<Map<String, Object>> active() { return repo.findActive(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Map<String, Object> create(@RequestBody ConfigRequest req) {
        try {
            return repo.insert(req.academicYear(), req.phase(), req.isActive());
        } catch (DuplicateKeyException e) {
            throw ApiException.error(400, "Academic year already exists.");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ConfigRequest req) {
        long configId = parseId(id);
        try {
            return repo.update(configId, req.academicYear(), req.phase(), req.isActive())
                    .orElseThrow(() -> ApiException.error(404, "Configuration not found"));
        } catch (DuplicateKeyException e) {
            throw ApiException.error(400, "Academic year already exists.");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        repo.delete(parseId(id)).orElseThrow(() -> ApiException.error(404, "Configuration not found"));
        return Map.of("message", "Configuration deleted successfully");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable String id) {
        return repo.activate(parseId(id))
                .orElseThrow(() -> ApiException.error(404, "Configuration not found"));
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.error(400, "Invalid or missing config ID");
        }
    }
}
