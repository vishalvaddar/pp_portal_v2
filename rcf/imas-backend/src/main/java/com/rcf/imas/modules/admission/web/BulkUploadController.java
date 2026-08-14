package com.rcf.imas.modules.admission.web;

import com.rcf.imas.modules.admission.service.BulkUploadService;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bulk-upload")
@PreAuthorize("hasRole('ADMIN')")   // bulk-mutates student PII → ADMIN only (Node had this route fully open)
class BulkUploadController {

    // deliberate hardening: content-type allowlist (Node had none)
    private static final Set<String> ALLOWED = Set.of(
            "text/csv", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream");  // some browsers send octet-stream for .csv

    private final BulkUploadService service;

    BulkUploadController(BulkUploadService service) { this.service = service; }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw ApiException.message(400,
                    "No file received. Ensure multipart/form-data and field name is \"file\".");
        }
        String ct = file.getContentType();
        if (ct != null && !ALLOWED.contains(ct)) {
            throw ApiException.message(400, "Invalid file type. Only CSV/XLS/XLSX are accepted.");
        }

        BulkUploadService.Result r = service.process(file);

        Map<String, Object> body = new LinkedHashMap<>();
        if (r.totalRecords() < 0) {
            // critical catch parity: {message, status, logFile}
            body.put("message", "Bulk upload failed");
            body.put("status", "failed");
            body.put("logFile", r.logFile());
        } else {
            body.put("totalRecords", r.totalRecords());
            body.put("insertedRecords", r.insertedRecords());
            body.put("validationErrors", r.validationErrors());
            body.put("dbErrors", r.dbErrors());
            body.put("status", r.status());
            body.put("logFile", r.logFile());
        }
        return ResponseEntity.status(r.httpStatus()).body(body);
    }
}
