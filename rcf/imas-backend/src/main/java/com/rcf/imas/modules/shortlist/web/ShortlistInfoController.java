package com.rcf.imas.modules.shortlist.web;

import com.rcf.imas.modules.shortlist.persistence.ShortlistReadRepository;
import com.rcf.imas.modules.shortlist.persistence.ShortlistWriteRepository;
import com.rcf.imas.modules.shortlist.service.XlsxSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shortlist-info")
@PreAuthorize("hasRole('ADMIN')")
class ShortlistInfoController {

    private static final List<String> DOWNLOAD_HEADERS = List.of(
        "S. No.", "NMMS Registration No", "Student Name", "Contact No 1", "Current School Name",
        "Medium", "District", "Block", "GMAT Score", "SAT Score");

    private final ShortlistReadRepository reads;
    private final ShortlistWriteRepository writes;
    private final XlsxSupport xlsx;

    ShortlistInfoController(ShortlistReadRepository reads, ShortlistWriteRepository writes, XlsxSupport xlsx) {
        this.reads = reads;
        this.writes = writes;
        this.xlsx = xlsx;
    }

    @GetMapping("/names")
    public List<String> names(@RequestParam(required = false) String year) { return reads.shortlistNames(year); }

    @GetMapping("/non-frozen-names")
    public List<Map<String, Object>> nonFrozenNames(@RequestParam(required = false) String year) { return reads.nonFrozenNames(year); }

    @GetMapping("/counts")
    public Map<String, Object> counts(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalApplicants", reads.totalApplicantCount(year));
        m.put("totalShortlisted", reads.totalShortlistedCount(year));
        return m;
    }

    @GetMapping("/show-data/{shortlistName}")
    public Map<String, Object> showData(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", info.get("name"));
        out.put("data", reads.showData((String) info.get("id")));
        return out;
    }

    @GetMapping("/download-data/{shortlistName}")
    public Object downloadData(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        String id = (String) info.get("id");
        if (reads.shortlistedCountInBatch(id) == 0) {
            return Map.of("status", "no_data", "message", "No shortlisted students found.");
        }
        List<Map<String, Object>> rows = reads.downloadRows(id);
        List<Map<String, Object>> withSno = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("S. No.", i + 1);
            r.putAll(rows.get(i));
            withSno.add(r);
        }
        byte[] bytes = xlsx.build("Applicants", DOWNLOAD_HEADERS, withSno);
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + shortlistName + "_Applicants.xlsx\"")
            .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(bytes);
    }

    @GetMapping("/{shortlistName}")
    public Map<String, Object> detail(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        return info;
    }

    @PostMapping("/freeze")
    public Object freeze(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object batchIdRaw = b.get("shortlistBatchId");
        if (batchIdRaw == null || String.valueOf(batchIdRaw).isBlank())
            throw ApiException.message(400, "Batch ID required");
        @SuppressWarnings("unchecked")
        List<Object> filterMediums = b.get("filterMediums") instanceof List<?> l ? (List<Object>) l : List.of();
        if (filterMediums.isEmpty()) throw ApiException.message(400, "Select at least one medium");

        String batchId = String.valueOf(batchIdRaw);
        List<String> allowed = filterMediums.stream().map(String::valueOf).toList();

        writes.autoUpdateSingleMediumStudents(batchId);
        List<Map<String, Object>> invalid = reads.invalidMediumStudents(batchId, allowed);
        if (!invalid.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requiresCorrection", true);
            out.put("message", invalid.size() + " students require manual medium selection (Multi-medium schools detected).");
            out.put("students", invalid);
            // 400 body carries no error/message-key envelope — return a ResponseEntity directly
            return org.springframework.http.ResponseEntity.status(400).body(out);
        }
        if (writes.freezeShortlist(batchId)) {
            return Map.of("message", "Shortlist filtered and frozen successfully");
        }
        return org.springframework.http.ResponseEntity.status(404).body(Map.of("message", "Shortlist not found or already frozen"));
    }

    @PostMapping("/bulk-update-mediums")
    @SuppressWarnings("unchecked")
    public Map<String, Object> bulkUpdateMediums(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object updatesRaw = b.get("updates");
        Object batchIdRaw = b.get("batchId");
        if (!(updatesRaw instanceof List<?>) || batchIdRaw == null || String.valueOf(batchIdRaw).isBlank())
            throw ApiException.message(400, "Missing data");
        List<Map<String, Object>> updates = (List<Map<String, Object>>) updatesRaw;
        writes.bulkUpdateMediumsAndStatus(updates, String.valueOf(batchIdRaw));
        return Map.of("message", "Medium decisions updated successfully");
    }

    @PostMapping("/reset-mediums")
    public Object resetMediums(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String batchId = b.get("shortlistBatchId") == null ? null : String.valueOf(b.get("shortlistBatchId"));
        if (batchId != null && writes.resetMediumFiltering(batchId)) {
            return Map.of("message", "Medium filtering reset successfully.");
        }
        return org.springframework.http.ResponseEntity.status(400).body(Map.of("message", "Reset failed. Batch may be frozen."));
    }

    @DeleteMapping("/delete")
    public Object deleteShortlist(@RequestBody(required = false) Map<String, Object> body,
                                  @RequestParam(required = false) String year) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String batchId = b.get("shortlistBatchId") == null ? null : String.valueOf(b.get("shortlistBatchId"));
        if (batchId != null && writes.deleteShortlist(batchId)) {
            return Map.of("message", "Shortlist deleted successfully");
        }
        return org.springframework.http.ResponseEntity.status(404).body(Map.of("message", "Shortlist not found"));
    }
}
