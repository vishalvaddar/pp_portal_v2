package com.rcf.imas.modules.tracking.web;

import com.rcf.imas.modules.tracking.persistence.TrackingReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tracking")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node applies zero auth middleware to this mount (Firm Decision 1)
class TrackingController {

    private final TrackingReadRepository reads;
    private final String fileStoragePath;

    TrackingController(TrackingReadRepository reads, @Value("${imas.file-storage-path}") String fileStoragePath) {
        this.reads = reads;
        this.fileStoragePath = fileStoragePath;
    }

    @GetMapping("/interviewers")
    public List<Map<String, Object>> interviewers() {
        try {
            return reads.allInterviewers();
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch interviewers.");
        }
    }

    @GetMapping("/students/interviewer/{interviewerId}")
    public Map<String, Object> studentsByInterviewer(@PathVariable String interviewerId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        if (!interviewerId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Interviewer ID provided.");
        }
        try {
            return reads.studentsByInterviewer(interviewerId, page, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch students assigned to interviewer.");
        }
    }

    @GetMapping("/students/{applicantId}/details")
    public List<Map<String, Object>> studentDetails(@PathVariable String applicantId,
                                                      @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear,
                                                      @RequestParam(required = false) String filtered) {
        // filtered=true is INERT (quirk 4b) -- both branches call the identical repository method.
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            List<Map<String, Object>> rows = reads.studentDetailForFilter(applicantId, nmmsYear);
            if (rows.isEmpty()) {
                throw ApiException.error(404, "Student or interview data not found.");
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch student interview details.");
        }
    }

    @GetMapping("/students/{applicantId}/interviews/all")
    public List<Map<String, Object>> allInterviewRounds(@PathVariable String applicantId,
                                                          @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            return reads.allInterviewRounds(applicantId, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch all interview rounds.");
        }
    }

    @GetMapping("/students/{applicantId}/home/all")
    public List<Map<String, Object>> allHomeVerificationRounds(@PathVariable String applicantId) {
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            return reads.allHomeVerificationRounds(applicantId);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch home verification records.");
        }
    }

    @GetMapping("/students")
    public Map<String, Object> students(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(name = "statuses", required = false) List<String> statuses,
                                         @RequestParam(required = false) List<String> results,
                                         @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        // Firm Decision 7: the 2025 literal is kept (matches Node's req.query.nmms_year || 2025, ground
        // truth §7 quirk 17) rather than moved to config -- faithful-parity phase; flagged in Deferred.
        try {
            return reads.studentsWithLatestStatus(page, statuses, results, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch student tracking data.");
        }
    }

    /**
     * downloadDocument -- file-serving redirect (Firm Decision 6). This is the ONE endpoint in the whole
     * module with plain-text error bodies (matching Node's res.send(...) calls), not the module's usual
     * {error:...} JSON envelope -- handled directly here rather than via ApiException/GlobalExceptionHandler.
     */
    @GetMapping("/document/{applicantId}/{cohortId}")
    public ResponseEntity<?> downloadDocument(@PathVariable String applicantId, @PathVariable String cohortId,
                                               @RequestParam(required = false) String type) {
        if (!applicantId.matches("\\d+") || cohortId.isBlank()
                || !("interview".equals(type) || "home".equals(type))) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("Invalid parameters.");
        }
        try {
            Map<String, Object> meta = "interview".equals(type)
                    ? reads.interviewDocument(applicantId)
                    : reads.homeVerificationDocument(applicantId);
            if (meta == null || meta.get("doc_name") == null) {
                return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                        .body("Document metadata not found.");
            }
            String rawDocName = String.valueOf(meta.get("doc_name"));
            // basic traversal guard: strip to the last path segment on either separator (Firm Decision 6)
            String[] parts = rawDocName.split("[\\\\/]");
            String cleanDocName = parts.length == 0 ? rawDocName : parts[parts.length - 1];

            String folder = "interview".equals(type) ? "Interview-data" : "home-verification-data";
            Path onDisk = Path.of(fileStoragePath, folder, cohortId, cleanDocName);
            if (!Files.exists(onDisk)) {
                return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                        .body("File not found on storage.");
            }
            String location = "/Data/" + folder + "/" + cohortId + "/" + cleanDocName;
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN)
                    .body("Server Error.");
        }
    }
}
