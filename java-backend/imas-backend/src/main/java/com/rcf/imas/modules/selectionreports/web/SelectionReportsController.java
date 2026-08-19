package com.rcf.imas.modules.selectionreports.web;

import com.rcf.imas.modules.selectionreports.persistence.SelectionReportsReadRepository;
import com.rcf.imas.modules.selectionreports.service.SammelanPdfSupport;
import com.rcf.imas.modules.selectionreports.service.SelectionReportPdfSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/selection-reports")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node applies zero auth middleware to this mount (Firm Decision 1)
class SelectionReportsController {

    private final SelectionReportsReadRepository reads;
    private final SelectionReportPdfSupport pdfSupport;
    private final SammelanPdfSupport sammelanPdfSupport;

    SelectionReportsController(SelectionReportsReadRepository reads, SelectionReportPdfSupport pdfSupport,
                                SammelanPdfSupport sammelanPdfSupport) {
        this.reads = reads;
        this.pdfSupport = pdfSupport;
        this.sammelanPdfSupport = sammelanPdfSupport;
    }

    /**
     * Year-format normalization (selectionReportsController.js: getNMMSData/getTurnOutData/getSelectionData/
     * getSelectsData, all identical, quirk 5). "2025-26" -> "2025"; "2025" unchanged; NOT applied to /init
     * or /sammelan-data.
     */
    private static String normalizeYear(String year) {
        if (year != null && year.contains("-")) {
            return year.split("-")[0];
        }
        return year;
    }

    @GetMapping("/init")
    public Map<String, Object> init() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("years", reads.academicYears());
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/nmms-data")
    public List<Map<String, Object>> nmmsData(@RequestParam(required = false) String year,
                                               @RequestParam(required = false) String type) {
        try {
            return reads.nmmsReport(normalizeYear(year), type);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/turnout-data")
    public List<Map<String, Object>> turnoutData(@RequestParam(required = false) String year,
                                                  @RequestParam(required = false) String type) {
        try {
            return reads.turnOutReport(normalizeYear(year), type);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/selection-data")
    public List<Map<String, Object>> selectionData(@RequestParam(required = false) String year,
                                                     @RequestParam(required = false) String type) {
        try {
            return reads.selectionReport(normalizeYear(year), type);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/selects-data")
    public List<Map<String, Object>> selectsData(@RequestParam(required = false) String year,
                                                   @RequestParam(required = false) String type) {
        try {
            return reads.selectsReport(normalizeYear(year), type);
        } catch (Exception e) {
            // getSelectsData's Node catch block skips console.error (quirk 10) -- cosmetic, not replicated.
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts() {
        try {
            return reads.cohorts();
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/sammelan-data")
    public List<Map<String, Object>> sammelanData(@RequestParam(required = false) String cohort,
                                                    @RequestParam(required = false) String fromDate,
                                                    @RequestParam(required = false) String toDate) {
        if (cohort == null || cohort.isBlank() || fromDate == null || fromDate.isBlank()
                || toDate == null || toDate.isBlank()) {
            throw ApiException.error(400, "Missing required parameters");
        }
        try {
            return reads.sammelanData(cohort, fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PostMapping("/download-pdf")
    public ResponseEntity<byte[]> downloadNmmsPdf(@RequestBody(required = false) Map<String, Object> body) {
        try {
            var pdf = pdfSupport.buildNmmsPdf(body == null ? Map.of() : body);
            return pdfResponse(pdf.bytes(), pdf.filename());
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Error generating PDF".getBytes());
        }
    }

    @PostMapping("/download-turnout-pdf")
    public ResponseEntity<byte[]> downloadTurnoutPdf(@RequestBody(required = false) Map<String, Object> body) {
        try {
            var pdf = pdfSupport.buildTurnoutPdf(body == null ? Map.of() : body);
            return pdfResponse(pdf.bytes(), pdf.filename());
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Error generating Turn-Out PDF".getBytes());
        }
    }

    @PostMapping("/download-selection-pdf")
    public ResponseEntity<byte[]> downloadSelectionPdf(@RequestBody(required = false) Map<String, Object> body) {
        try {
            var pdf = pdfSupport.buildSelectionPdf(body == null ? Map.of() : body);
            return pdfResponse(pdf.bytes(), pdf.filename());
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Error generating PDF".getBytes());
        }
    }

    @PostMapping("/download-selects-pdf")
    public ResponseEntity<byte[]> downloadSelectsPdf(@RequestBody(required = false) Map<String, Object> body) {
        try {
            var pdf = pdfSupport.buildSelectsPdf(body == null ? Map.of() : body);
            return pdfResponse(pdf.bytes(), pdf.filename());
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Error generating Selects PDF".getBytes());
        }
    }

    @PostMapping("/download-sammelan")
    public ResponseEntity<byte[]> downloadSammelan(@RequestBody(required = false) Map<String, Object> body) {
        try {
            var pdf = sammelanPdfSupport.build(body == null ? Map.of() : body);
            return pdfResponse(pdf.bytes(), pdf.filename());
        } catch (Exception e) {
            // Sammelan's Node handler sends the raw error message as PLAIN TEXT (res.send(e.message)),
            // not JSON and not a canned string -- the one download endpoint that differs (Firm Decision 10).
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body(String.valueOf(e.getMessage()).getBytes());
        }
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String filename) {
        ContentDisposition cd = ContentDisposition.attachment().filename(filename).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(bytes);
    }
}
