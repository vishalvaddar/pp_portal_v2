package com.rcf.imas.modules.exams.web;

import com.rcf.imas.modules.exams.persistence.ExamsReadRepository;
import com.rcf.imas.modules.exams.persistence.ExamsWriteRepository;
import com.rcf.imas.modules.exams.service.ExamCallingListXlsxSupport;
import com.rcf.imas.modules.exams.service.HallTicketPdfSupport;
import com.rcf.imas.modules.exams.service.HallTicketZipSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/exams")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every route in this module open, except #19 below
class ExamsController {

    private static final Pattern PINCODE = Pattern.compile("^\\d{5,12}$");
    private static final Pattern PHONE = Pattern.compile("^\\d{7,12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final ExamsReadRepository reads;
    private final ExamsWriteRepository writes;
    private final ExamCallingListXlsxSupport xlsx;
    private final HallTicketPdfSupport hallTicketPdf;
    private final HallTicketZipSupport hallTicketZip;

    ExamsController(ExamsReadRepository reads, ExamsWriteRepository writes, ExamCallingListXlsxSupport xlsx,
                     HallTicketPdfSupport hallTicketPdf, HallTicketZipSupport hallTicketZip) {
        this.reads = reads;
        this.writes = writes;
        this.xlsx = xlsx;
        this.hallTicketPdf = hallTicketPdf;
        this.hallTicketZip = hallTicketZip;
    }

    @GetMapping("/exam-centres")
    public List<Map<String, Object>> examCentres() {
        try {
            return reads.activeCentres();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch exam centres");
        }
    }

    @GetMapping("/viewcentres")
    public List<Map<String, Object>> viewCentres() {
        // Firm Decision 2: any DB failure here surfaces via GlobalExceptionHandler's generic
        // {error:"Internal Server Error"} fallback -- Node's equivalent (`console(...)` is a TypeError)
        // leaves the request hanging with no response at all. Deliberately no try/catch: let it propagate.
        return reads.allCentresAllColumns();
    }

    @PostMapping("/exam-centres")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCentre(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("pp_exam_centre_name"));
        String code = str(b.get("pp_exam_centre_code"));
        String phone = str(b.get("contact_phone"));
        String email = str(b.get("contact_email"));
        String pincode = str(b.get("pincode"));

        if (name == null || name.isBlank()) throw ApiException.message(400, "Centre name is required.");
        if (name.length() > 100) throw ApiException.message(400, "Centre name too long (max 100 characters).");
        if (code != null && code.length() > 20) throw ApiException.message(400, "Centre code too long (max 20 characters).");
        if (pincode != null && !PINCODE.matcher(pincode).matches()) throw ApiException.message(400, "Invalid pincode.");
        if (phone != null && !PHONE.matcher(phone).matches()) throw ApiException.message(400, "Invalid contact phone number.");
        if (email != null && !EMAIL.matcher(email).matches()) throw ApiException.message(400, "Invalid email address.");

        Map<String, Object> existing = reads.findExistingCentre(code, name, phone, email);
        if (existing != null) {
            String field, label;
            if (eq(existing.get("pp_exam_centre_code"), code)) { label = "Centre code"; field = "centre_code"; }
            else if (eq(existing.get("pp_exam_centre_name"), name)) { label = "Centre name"; field = "centre_name"; }
            else if (eq(existing.get("contact_phone"), phone)) { label = "Contact phone"; field = "contact_phone"; }
            else { label = "Contact email"; field = "contact_email"; }
            throw ApiException.message(409, label + " already exists. Please use a different value.").with("field", field);
        }

        try {
            Integer capacity = parseIntOrNull(b.get("sitting_capacity"));
            BigDecimal lat = parseDecimalOrNull(b.get("latitude"));
            BigDecimal lng = parseDecimalOrNull(b.get("longitude"));
            Map<String, Object> centre = writes.insertCentre(code, name, str(b.get("address")), str(b.get("village")),
                    pincode, str(b.get("contact_person")), phone, email, capacity, lat, lng, str(b.get("created_by")));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Exam centre created successfully");
            out.put("centre", centre);
            return out;
        } catch (Exception e) {
            // Firm Decision 7: the fictitious error.constraint name-matching branches are NOT ported (those
            // constraint names don't exist in the schema -- dead code in Node). A genuine TOCTOU race falls
            // through to this same generic message, matching Node's ultimate behavior for that path.
            throw ApiException.message(500, "Failed to create centre");
        }
    }

    @DeleteMapping("/exam-centres/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCentre(@PathVariable String id) {
        try {
            String usedBy = reads.examNameUsingCentre(id);
            if (usedBy != null) {
                throw ApiException.message(400, "Centre already used in exam: " + usedBy);
            }
            writes.deleteCentre(id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to delete centre");
        }
    }

    @PutMapping("/exam-centres/{id}")
    public Map<String, Object> updateCentre(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            String activeYnRaw = str(b.get("active_yn"));
            String activeYn = (activeYnRaw == null || activeYnRaw.isBlank()) ? "Y" : activeYnRaw; // Firm Decision 11a
            Map<String, Object> centre = writes.updateCentre(id, str(b.get("pp_exam_centre_name")), str(b.get("pp_exam_centre_code")),
                    parseIntOrNull(b.get("sitting_capacity")), parseDecimalOrNull(b.get("latitude")), parseDecimalOrNull(b.get("longitude")),
                    str(b.get("address")), str(b.get("village")), str(b.get("pincode")), str(b.get("contact_person")),
                    str(b.get("contact_phone")), str(b.get("contact_email")), activeYn);
            if (centre == null) throw ApiException.message(404, "Centre not found");
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Updated successfully");
            out.put("centre", centre);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Update failed").with("error", e.getMessage());
        }
    }

    @GetMapping("/divisions-by-state/{stateId}")
    public List<Map<String, Object>> divisionsByState(@PathVariable String stateId) {
        return reads.divisionsByState(stateId);
    }

    @GetMapping("/education-districts-by-division/{divisionId}")
    public List<Map<String, Object>> educationDistrictsByDivision(@PathVariable String divisionId) {
        return reads.educationDistrictsByDivision(divisionId);
    }

    @GetMapping("/blocks-by-district/{districtId}")
    public List<Map<String, Object>> blocksByDistrict(@PathVariable String districtId) {
        return reads.blocksByDistrict(districtId);
    }

    @GetMapping("/clusters-by-block/{blockId}")
    public List<Map<String, Object>> clustersByBlock(@PathVariable String blockId) {
        return reads.clustersByBlock(blockId);
    }

    @GetMapping("/used-blocks")
    public List<Long> usedBlocks(@RequestParam(required = false) String year) {
        try {
            return reads.usedBlocks(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch used blocks");
        }
    }

    @GetMapping("/assigned")
    public List<Map<String, Object>> assigned(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.message(400, "Year is required");
        try {
            return reads.assignedExams(year.split("-")[0]);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch exams");
        }
    }

    @GetMapping("/notassigned")
    public List<Map<String, Object>> notAssigned(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.message(400, "Year is required");
        try {
            return reads.notAssignedExams(year.split("-")[0]);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch exams");
        }
    }

    @PutMapping("/{examId}/freeze")
    public Map<String, Object> freeze(@PathVariable String examId) {
        try {
            writes.freezeExam(examId);
            return Map.of("message", "✅ Exam frozen successfully");
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to freeze exam");
        }
    }

    @DeleteMapping("/{examId}")
    public Map<String, Object> deleteExam(@PathVariable String examId) {
        try {
            writes.deleteExam(examId);
            return Map.of("message", "Exam and related data deleted successfully");
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to delete exam");
        }
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createExamOnly(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String centreId = str(b.get("centreId"));
        String examName = str(b.get("examName"));
        String date = str(b.get("date"));
        String startTime = str(b.get("startTime"));
        String endTime = str(b.get("endTime"));
        String academicYear = str(b.get("academic_year"));

        if (isBlank(centreId) || isBlank(examName) || isBlank(date) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields.");
        }
        String examYear = isBlank(academicYear) ? null : academicYear.split("-")[0];

        try {
            var result = writes.createExamOnly(centreId, examName, date, startTime, endTime, examYear);
            if (result.conflict()) {
                throw ApiException.error(409, "Time conflict").with("message", result.message());
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Exam created successfully");
            out.put("examId", result.examId());
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Server error").with("error", e.getMessage());
        }
    }

    @PostMapping("/{examId}/assign-students")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignStudents(@PathVariable String examId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String division = str(b.get("division"));
        String educationDistrict = str(b.get("educationDistrict"));
        List<String> blocks = b.get("blocks") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        String academicYear = str(b.get("academicYear"));

        if (isBlank(division) || isBlank(educationDistrict) || blocks.isEmpty()) {
            throw ApiException.error(400, "Missing required fields: examId, division, educationDistrict, blocks[]");
        }

        try {
            var result = writes.assignStudents(examId, division, educationDistrict, blocks, academicYear);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Applicants assigned to exam successfully ✅");
            out.put("examId", examId);
            out.put("totalAssigned", result.totalAssigned());
            out.put("applicants", result.applicants().stream().map(a -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("applicant_id", a.applicantId());
                m.put("applicant_name", a.applicantName());
                m.put("hall_ticket_no", a.hallTicketNo());
                return m;
            }).toList());
            return out;
        } catch (ExamsWriteRepository.ExamNotFoundException e) {
            throw ApiException.error(404, "Exam does not exist.");
        } catch (ExamsWriteRepository.NoShortlistedApplicantsException e) {
            throw ApiException.message(404, "No shortlisted applicants found for the selected region.");
        } catch (Exception e) {
            throw ApiException.message(500, "Server error").with("error", e.getMessage());
        }
    }

    @GetMapping("/{examId}/student-list")
    public ResponseEntity<byte[]> studentList(@PathVariable String examId) {
        List<Map<String, Object>> rows;
        try {
            rows = reads.studentListRows(examId);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to generate Excel file").with("error", e.getMessage());
        }
        if (rows.isEmpty()) throw ApiException.message(404, "No students found for this exam.");

        byte[] bytes;
        try {
            bytes = xlsx.build(rows);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to generate Excel file").with("error", e.getMessage());
        }
        String examName = String.valueOf(rows.get(0).get("exam_name")).replaceAll("\\s+", "_");
        String filename = examName + "_Calling_List.xlsx";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    /** PUBLIC (Firm Decision 8) -- overrides the class-level @PreAuthorize("hasRole('ADMIN')"); Spring Method
     *  Security evaluates the METHOD annotation instead of the class one when both are present (they do not
     *  combine/AND). SecurityConfig's filter-chain permit matcher for GET /api/exams/hallticket/** already exists
     *  (added in Plan 1, forward-declared) so the request never even reaches JwtAuthFilter's auth requirement. */
    @GetMapping("/hallticket/{hallTicketNo}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> hallTicket(@PathVariable String hallTicketNo) {
        Map<String, Object> student;
        try {
            student = reads.hallTicketByNumber(hallTicketNo);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall ticket").with("error", e.getMessage());
        }
        if (student == null) throw ApiException.message(404, "Hall ticket not found");

        byte[] pdf;
        try {
            pdf = hallTicketPdf.build(student);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall ticket").with("error", e.getMessage());
        }
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + hallTicketNo + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @GetMapping("/{examId}/{examName}/download-all-hall-tickets")
    public ResponseEntity<byte[]> downloadAllHallTickets(@PathVariable String examId, @PathVariable String examName) {
        List<Map<String, Object>> students;
        try {
            students = reads.hallTicketsForExam(examId);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall tickets").with("error", e.getMessage());
        }
        if (students.isEmpty()) throw ApiException.message(404, "No hall tickets found");

        byte[] zip;
        try {
            zip = hallTicketZip.build(students);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall tickets").with("error", e.getMessage());
        }
        String filename = "All_Hall_Tickets_" + examId + "_" + com.rcf.imas.modules.exams.service.HallTicketZipSupport.sanitize(examName) + ".zip";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=" + filename)
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean eq(Object a, String b) { return a != null && b != null && String.valueOf(a).equals(b); }
    private static Integer parseIntOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
    private static BigDecimal parseDecimalOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
