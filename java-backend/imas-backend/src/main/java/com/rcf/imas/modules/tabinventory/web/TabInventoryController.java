package com.rcf.imas.modules.tabinventory.web;

import com.rcf.imas.modules.tabinventory.TabStatus;
import com.rcf.imas.modules.tabinventory.persistence.TabInventoryReadRepository;
import com.rcf.imas.modules.tabinventory.persistence.TabInventoryWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tabs")
@PreAuthorize("hasRole('ADMIN')")   // ground truth §0/§7-1: Node applies ZERO auth middleware to any of the
                                     // 14 /api/tabs* routes -- Firm Decision 1, same rule as modules/classroom
                                     // and modules/selectionreports (zero-auth Node admin-management mount -> ADMIN)
class TabInventoryController {

    final TabInventoryReadRepository reads;
    final TabInventoryWriteRepository writes;

    TabInventoryController(TabInventoryReadRepository reads, TabInventoryWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.tabStats());
            return out;
        } catch (Exception e) {
            // getTabStats is the ONE handler in this module that swallows the real error (quirk 6) --
            // every other read handler echoes e.getMessage(); this one always says "Internal Server Error".
            throw ApiException.message(500, "Internal Server Error").with("success", false);
        }
    }

    @GetMapping("/eligible-students")
    public Map<String, Object> eligibleStudents() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.eligibleStudents());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.usersWithoutTab());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.cohorts());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/brands")
    public Map<String, Object> brands() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.brands());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBrand(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object brandName = b.get("brand_name");
        Object modelName = b.get("model_name");
        Object createdBy = b.get("created_by");
        if (isFalsy(brandName) || isFalsy(modelName) || isFalsy(createdBy)) {
            throw ApiException.message(400, "brand_name, model_name, and created_by are required.").with("success", false);
        }
        try {
            Map<String, Object> data = writes.createBrand(String.valueOf(brandName), String.valueOf(modelName), String.valueOf(createdBy));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", data);
            return out;
        } catch (DuplicateKeyException e) {
            // controller.js:27-29: dead code under the current schema (ON CONFLICT DO UPDATE absorbs the
            // exact conflict this branch targets, quirk 7) -- kept for parity/future-proofing.
            throw ApiException.message(409, "This Brand and Model combination already exists.").with("success", false);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTab(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object serialNumber = b.get("serial_number");
        Object brandId = b.get("brand_id");
        Object createdBy = b.get("created_by");
        if (isFalsy(serialNumber) || isFalsy(brandId) || isFalsy(createdBy)) {
            throw ApiException.message(400, "Required fields missing.").with("success", false);
        }
        try {
            Map<String, Object> data = writes.createTab(
                    String.valueOf(serialNumber), strOrNull(b.get("imei")), strOrNull(b.get("inventory_id")),
                    String.valueOf(brandId), strOrNull(b.get("tab_purchase_date")), strOrNull(b.get("remarks")),
                    String.valueOf(createdBy));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Tablet created");
            out.put("data", data);
            return out;
        } catch (DuplicateKeyException e) {
            throw ApiException.message(409, "Serial number already exists in inventory.").with("success", false);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PutMapping("/{tabId}/status")
    public Map<String, Object> changeStatus(@PathVariable String tabId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String rawStatus = strOrNull(b.get("status"));
        TabStatus status = TabStatus.parse(rawStatus).orElse(null);
        if (status == null) {
            // Node lets an invalid status hit SQL and leaks the raw CHECK-violation text at 400
            // (ground truth §2.4); Java gates it here for a clean 400 instead (Firm Decision 2) -- same
            // status code, same {success:false, message:...} shape, friendlier message text.
            throw ApiException.message(400, "Invalid status: " + rawStatus).with("success", false);
        }
        try {
            writes.changeTabStatus(tabId, status, strOrNull(b.get("remarks")), strOrNull(b.get("assignment_type")),
                    strOrNull(b.get("student_id")), strOrNull(b.get("official_user_id")), strOrNull(b.get("user_id")),
                    strOrNull(b.get("transaction_date")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Status updated successfully");
            return out;
        } catch (Exception e) {
            throw ApiException.message(400, e.getMessage()).with("success", false);
        }
    }

    @DeleteMapping("/{tabId}")
    public Map<String, Object> deleteTab(@PathVariable String tabId) {
        try {
            String deletedTabId = writes.deleteTab(tabId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Deleted");
            // Node: `data: rows[0]` -- on a miss rows[0] is undefined and JSON.stringify DROPS the key, so
            // only include `data` when a row was actually deleted (omit it entirely on a miss).
            if (deletedTabId != null) out.put("data", Map.of("tab_id", deletedTabId));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulkCreateTabs(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object devicesObj = b.get("devices");
        if (!(devicesObj instanceof List<?> list) || list.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Excel is empty");
            return ResponseEntity.status(400).body(err);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) (List<?>) list;
        try {
            var result = writes.bulkCreateTabs(devices);
            if (!result.success()) {
                // Quirk 8: this specific 400 body has NO "message" key, only "errors" -- built by hand
                // (not via ApiException) to guarantee the key is truly absent, not merely null.
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("errors", result.errors());
                return ResponseEntity.status(400).body(err);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("count", result.count());
            return ResponseEntity.status(201).body(out);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage() == null ? "An error occurred during bulk upload" : e.getMessage());
            return ResponseEntity.status(400).body(err);
        }
    }

    @GetMapping("/movement-report")
    public Map<String, Object> movementReport(@RequestParam(required = false) String fromCohort,
                                               @RequestParam(required = false) String toCohort) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.movementReport(fromCohort, toCohort));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("")
    public Map<String, Object> allTabs() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.allTabs());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/{tabId}")
    public Map<String, Object> tabById(@PathVariable String tabId) {
        Map<String, Object> row;
        try {
            row = reads.tabById(tabId).orElse(null);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
        if (row == null) throw ApiException.message(404, "Not found").with("success", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", row);
        return out;
    }

    @GetMapping("/{tabId}/history")
    public Map<String, Object> tabHistory(@PathVariable String tabId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.tabHistory(tabId));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    static boolean isFalsy(Object o) {
        if (o == null) return true;
        if (o instanceof String s) return s.isEmpty();
        if (o instanceof Number n) return n.doubleValue() == 0;
        return false;
    }

    static String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
}
