package com.rcf.imas.modules.interview.web;

import com.rcf.imas.modules.interview.persistence.InterviewReadRepository;
import com.rcf.imas.modules.interview.persistence.InterviewWriteRepository;
import com.rcf.imas.modules.interview.service.InterviewReportPdfSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interview")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left EVERY route in this module open (no auth middleware)
class InterviewController {

    private final InterviewReadRepository reads;
    private final InterviewWriteRepository writes;
    private final InterviewReportPdfSupport reportPdf;

    InterviewController(InterviewReadRepository reads, InterviewWriteRepository writes, InterviewReportPdfSupport reportPdf) {
        this.reads = reads;
        this.writes = writes;
        this.reportPdf = reportPdf;
    }

    @GetMapping("/exam-centers")
    public List<Map<String, Object>> examCenters() {
        try {
            return reads.examCenters();
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching exam centers.");
        }
    }

    @GetMapping("/states")
    public List<Map<String, Object>> states() {
        try {
            return reads.states();
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching states.");
        }
    }

    @GetMapping("/divisions")
    public List<Map<String, Object>> divisions(@RequestParam(required = false) String stateName) {
        if (stateName == null || stateName.isEmpty()) {
            throw ApiException.message(400, "Missing stateName query parameter.");
        }
        try {
            return reads.divisionsByState(stateName);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching divisions.");
        }
    }

    @GetMapping("/districts")
    public List<Map<String, Object>> districts(@RequestParam(required = false) String divisionName) {
        if (divisionName == null || divisionName.isEmpty()) {
            throw ApiException.message(400, "Missing divisionName parameter.");
        }
        try {
            return reads.districtsByDivision(divisionName);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching districts.");
        }
    }

    @GetMapping("/blocks")
    public List<Map<String, Object>> blocks(@RequestParam(required = false) String stateName,
                                            @RequestParam(required = false) String divisionName,
                                            @RequestParam(required = false) String districtName) {
        if (isBlank(stateName) || isBlank(divisionName) || isBlank(districtName)) {
            throw ApiException.message(400, "Missing one or more required parameters: stateName, divisionName, or districtName.");
        }
        try {
            return reads.blocksByDistrict(stateName, divisionName, districtName);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching blocks.");
        }
    }

    @GetMapping("/interviewers")
    public List<Map<String, Object>> interviewers() {
        try {
            return reads.interviewers();
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching interviewers.");
        }
    }

    @GetMapping("/students-for-verification")
    public List<Map<String, Object>> studentsForVerification(@RequestParam(required = false) String nmmsYear) {
        if (nmmsYear == null || nmmsYear.isEmpty() || "undefined".equals(nmmsYear) || "null".equals(nmmsYear)) {
            throw ApiException.message(400, "Missing or invalid nmmsYear. Received: " + nmmsYear);
        }
        try {
            return reads.studentsForVerification(nmmsYear);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch students for verification.");
        }
    }

    @GetMapping("/students/{interviewerName}")
    public List<Map<String, Object>> studentsByInterviewer(@PathVariable String interviewerName,
                                                           @RequestParam(required = false) String nmmsYear) {
        if (isBlank(interviewerName) || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing interviewerName in parameters or nmmsYear in query.");
        }
        try {
            return reads.studentsByInterviewer(interviewerName, nmmsYear);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching students for interviewer.");
        }
    }

    @GetMapping("/unassigned-students")
    public List<Map<String, Object>> unassignedStudents(@RequestParam(required = false) String centerName,
                                                        @RequestParam(required = false) String nmmsYear) {
        if (isBlank(centerName) || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing centerName or nmmsYear query parameter.");
        }
        try {
            return reads.unassignedStudents(centerName, nmmsYear);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching unassigned students.");
        }
    }

    @GetMapping("/unassigned-students-by-block")
    public List<Map<String, Object>> unassignedStudentsByBlock(@RequestParam(required = false) String stateName,
                                                              @RequestParam(required = false) String districtName,
                                                              @RequestParam(required = false) String blockName,
                                                              @RequestParam(required = false) String nmmsYear) {
        if (isBlank(stateName) || isBlank(districtName) || isBlank(blockName) || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing required query parameters.");
        }
        try {
            return reads.unassignedStudentsByBlock(stateName, districtName, blockName, nmmsYear);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching unassigned students by block.");
        }
    }

    @GetMapping("/reassignable-students")
    public List<Map<String, Object>> reassignableStudents(@RequestParam(required = false) String centerName,
                                                          @RequestParam(required = false) String nmmsYear) {
        if (isBlank(centerName) || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing centerName or nmmsYear query parameter.");
        }
        try {
            return reads.reassignableStudents(centerName, nmmsYear);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while fetching reassignable students.");
        }
    }

    // Firm Decision 8: NO 400 validation here (parity with Node's getReassignableStudentsByBlock, which omits it).
    @GetMapping("/reassignable-students-by-block")
    public List<Map<String, Object>> reassignableStudentsByBlock(@RequestParam(required = false) String stateName,
                                                                @RequestParam(required = false) String districtName,
                                                                @RequestParam(required = false) String blockName,
                                                                @RequestParam(required = false) String nmmsYear) {
        try {
            return reads.reassignableStudentsByBlock(stateName, districtName, blockName, nmmsYear);
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error.");
        }
    }

    @PostMapping("/assign-students")
    public Map<String, Object> assignStudents(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object applicantIds = b.get("applicantIds");
        Object interviewerId = b.get("interviewerId");
        Object nmmsYear = b.get("nmmsYear");
        // Node: !applicantIds || !interviewerId || !nmmsYear  (an EMPTY array is truthy in JS, so it passes).
        if (!(applicantIds instanceof List) || isFalsy(interviewerId) || isFalsy(nmmsYear)) {
            throw ApiException.message(400, "Missing applicantIds, interviewerId, or nmmsYear in request body.");
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) applicantIds;
            List<Map<String, Object>> results = writes.assignStudents(ids, String.valueOf(interviewerId), String.valueOf(nmmsYear));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Assignment process completed.");
            out.put("results", results);
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while assigning students.");
        }
    }

    @PostMapping("/reassign-students")
    public Map<String, Object> reassignStudents(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object applicantIds = b.get("applicantIds");
        Object newInterviewerId = b.get("newInterviewerId");
        Object nmmsYear = b.get("nmmsYear");
        if (!(applicantIds instanceof List) || isFalsy(newInterviewerId) || isFalsy(nmmsYear)) {
            throw ApiException.message(400, "Missing applicantIds, newInterviewerId, or nmmsYear in request body.");
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) applicantIds;
            List<Map<String, Object>> results = writes.reassignStudents(ids, String.valueOf(newInterviewerId), String.valueOf(nmmsYear));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Reassignment process completed.");
            out.put("results", results);
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal server error while reassigning students.");
        }
    }

    @PostMapping("/submit-interview")
    public Map<String, Object> submitInterview(@RequestParam Map<String, String> form,
                                               @RequestParam(value = "file", required = false) MultipartFile file) {
        String applicantId = form.get("applicantId");
        String remarks = form.get("remarks");
        String nmmsYear = form.get("nmmsYear");
        if (isBlank(applicantId) || isBlank(remarks) || file == null || file.isEmpty() || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing applicantId, remarks, interview file, or nmmsYear.");
        }
        try {
            String ext = extensionOf(file.getOriginalFilename());          // ".pdf"
            String docType = ext.isEmpty() ? "" : ext.substring(1).toUpperCase();
            String docName = "INTERVIEW-" + applicantId + "-" + nmmsYear + ext;
            Map<String, Object> data = writes.submitInterviewDetails(form, docName, docType);
            String msg = "Interview details submitted successfully.";
            if (data.get("enr_id") != null) msg += " Enrollment ID: " + data.get("enr_id");
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", msg);
            out.put("data", data);
            return out;
        } catch (Exception e) {
            // UNIQUE to this endpoint: the 500 body carries error:true (interviewController.js:592)
            throw ApiException.message(500, e.getMessage() == null ? "Internal server error." : e.getMessage()).with("error", true);
        }
    }

    @PostMapping("/submit-home-verification")
    public Map<String, Object> submitHomeVerification(@RequestParam Map<String, String> form,
                                                      @RequestParam(value = "verificationDocument", required = false) MultipartFile file) {
        String applicantId = form.get("applicantId");
        String status = form.get("status");
        String verifiedBy = form.get("verifiedBy");
        String verificationType = form.get("verificationType");
        String dateOfVerification = form.get("dateOfVerification");
        String nmmsYear = form.get("nmmsYear");
        if (isBlank(applicantId) || isBlank(status) || isBlank(verifiedBy) || isBlank(verificationType)
                || isBlank(dateOfVerification) || isBlank(nmmsYear)) {
            throw ApiException.message(400, "Missing required fields including nmmsYear.");
        }
        try {
            String docName = null, docType = null;
            if (file != null && !file.isEmpty()) {
                String ext = extensionOf(file.getOriginalFilename());
                docType = ext.isEmpty() ? "" : ext.substring(1).toUpperCase();
                docName = "HOME-VERI-" + applicantId + "-" + nmmsYear + ext;
            }
            Map<String, Object> data = writes.submitHomeVerification(form, docName, docType);
            String msg = "Home verification submitted successfully.";
            if (data.get("enr_id") != null) msg += " Student Enrolled as: " + data.get("enr_id");
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", msg);
            out.put("data", data);
            return out;
        } catch (Exception e) {
            // Node parity: interviewModel.js:1043 wraps the cause as "Home verification failed: <cause>".
            throw ApiException.message(500, "Home verification failed: " + (e.getMessage() == null ? "Internal server error." : e.getMessage()));
        }
    }

    @PostMapping("/download-assignment-report")
    public ResponseEntity<byte[]> downloadAssignmentReport(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object interviewerId = b.get("interviewerId");
        Object nmmsYear = b.get("nmmsYear");
        Object applicantIdsRaw = b.get("applicantIds");
        @SuppressWarnings("unchecked")
        List<Object> applicantIds = applicantIdsRaw instanceof List ? (List<Object>) applicantIdsRaw : List.of();
        if (isFalsy(interviewerId) || isFalsy(nmmsYear) || applicantIds.isEmpty()) {
            throw ApiException.error(400, "Missing required parameters: interviewerId, nmmsYear, or applicantIds list is empty/invalid.");
        }

        List<Map<String, Object>> students;
        try {
            students = reads.assignmentReportData(String.valueOf(nmmsYear), applicantIds);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to generate PDF report.");
        }
        if (students.isEmpty()) {
            throw ApiException.error(404, "No student data found for the selected criteria.");
        }

        byte[] pdf;
        try {
            pdf = reportPdf.build(String.valueOf(nmmsYear), students);
        } catch (Exception e) {
            // Node returns the fixed string (interviewController.js:340); don't leak the exception into the body.
            throw ApiException.error(500, "Failed to generate PDF report.");
        }
        String cleanId = String.valueOf(interviewerId).replaceAll("[^a-zA-Z0-9-]", "");
        String filename = "Interview-Assignment" + cleanId + "_" + System.currentTimeMillis() + ".pdf";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** path.extname parity: the last "." onward (incl. the dot), or "" if none. */
    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }

    /** JS `!value` parity for JSON body values: null, "", 0, false are falsy. An empty List is NOT falsy (handled
     *  by the `instanceof List` check at the call site, mirroring JS `![]===false`). */
    private static boolean isFalsy(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof Boolean bo) return !bo;
        if (v instanceof Number n) return n.doubleValue() == 0.0;
        return false;
    }
}
