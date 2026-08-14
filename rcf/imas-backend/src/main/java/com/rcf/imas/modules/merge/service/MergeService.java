package com.rcf.imas.modules.merge.service;

import com.rcf.imas.modules.merge.persistence.MergeWriteRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MergeService {

    private final JdbcClient jdbc;
    private final MergeWriteRepository writes;
    private final MergeMatching match;

    public MergeService(JdbcClient jdbc, MergeWriteRepository writes, MergeMatching match) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.match = match;
    }

    public record UploadResult(boolean success, List<String> logs) {}

    private String jurisName(String jurisCode) {
        return jdbc.sql("SELECT juris_name FROM pp.jurisdiction WHERE juris_code = :c::numeric")
                .param("c", jurisCode).query(String.class).optional().orElse(null);
    }

    /** normalizeText(name) → juris_code, for blocks under a district. */
    private Map<String, String> loadBlocks(String districtId) {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE parent_juris = :d::numeric")
            .param("d", districtId)
            .query((rs, i) -> {
                m.put(match.normalizeText(rs.getString("juris_name")),
                      rs.getBigDecimal("juris_code").toBigInteger().toString());
                return null;
            }).list();
        return m;
    }

    // ---------- Phase 1 ----------
    public UploadResult uploadP1(List<Map<String, String>> records, String year, String stateId, String districtId) {
        List<String> logs = new ArrayList<>();

        if (writes.countStagedP1(districtId, String.valueOf(year)) > 0) {
            return new UploadResult(false, List.of("Upload Rejected: Data for Year " + year + " already uploaded for this district."));
        }

        String stateName = jurisName(stateId);
        String districtName = jurisName(districtId);
        Map<String, String> blockMap = loadBlocks(districtId);
        List<String> blockNames = new ArrayList<>(blockMap.keySet());

        Set<String> diseInFile = new LinkedHashSet<>();
        for (Map<String, String> r : records) {
            String c = digitsOnly(r.get("current_institute_dise_code"));
            if (!c.isEmpty()) diseInFile.add(c);
        }
        Set<String> validDise = new HashSet<>();
        if (!diseInFile.isEmpty()) {
            validDise.addAll(jdbc.sql("SELECT dise_code FROM pp.institute WHERE dise_code = ANY(:codes)")
                    .param("codes", diseInFile.toArray(new String[0]))
                    .query(String.class).list());
        }

        Set<String> reportedBlocks = new HashSet<>(), reportedDistricts = new HashSet<>(), reportedStates = new HashSet<>();
        List<Map<String, Object>> valid = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            Map<String, String> row = records.get(i);
            int rowNum = i + 1;
            boolean rowError = false;

            String cleanYear = trim(row.get("nmms_year"));
            if (!cleanYear.equals(String.valueOf(year))) {
                logs.add("Row " + rowNum + ": Year Mismatch (File has \"" + (cleanYear.isEmpty() ? "Empty" : cleanYear) + "\", expected \"" + year + "\")");
                rowError = true;
            }

            String inputState = trim(row.get("app_state"));
            if (!eq(match.normalizeText(inputState), match.normalizeText(stateName))) {
                if (reportedStates.add(inputState))
                    logs.add("Row " + rowNum + ": State Mismatch (File: \"" + inputState + "\", Expected: \"" + stateName + "\")");
                rowError = true;
            }

            String inputDist = trim(row.get("district")).replace(".", "");
            if (!eq(match.normalizeText(inputDist), match.normalizeText(districtName))) {
                if (reportedDistricts.add(inputDist))
                    logs.add("Row " + rowNum + ": District Mismatch (File: \"" + inputDist + "\", Expected: \"" + districtName + "\")");
                rowError = true;
            }

            String rawBlock = trim(row.get("nmms_block"));
            String blockKey = match.normalizeText(rawBlock);
            String blockId = blockMap.get(blockKey);
            if (blockId == null) {
                if (reportedBlocks.add(blockKey == null ? "" : blockKey)) {
                    String suggestion = match.suggestValue(rawBlock, blockNames);
                    logs.add("Row " + rowNum + ": Block \"" + rawBlock + "\" not found. "
                            + (suggestion != null ? "Did you mean \"" + suggestion + "\"?" : "Please check spelling."));
                }
                rowError = true;
            }

            String cleanDise = digitsOnly(row.get("current_institute_dise_code"));
            if (!validDise.contains(cleanDise)) {
                logs.add("Row " + rowNum + ": Invalid DISE Code \"" + cleanDise + "\"");
                rowError = true;
            }

            if (!rowError) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("nmms_year", year);
                v.put("exam", row.get("exam"));
                v.put("district", districtId);
                v.put("app_state", stateId);
                v.put("nmms_block", blockId);
                v.put("dise", cleanDise);
                v.put("sats", row.get("students_sats_id"));
                v.put("student_name", row.get("student_name"));
                v.put("father_name", row.get("father_name"));
                v.put("institute_name", row.get("institute_name"));
                v.put("contact_no1", row.get("contact_no1"));
                v.put("contact_no2", row.get("contact_no2"));
                v.put("name_key", match.generateStudentNameKey(row.get("student_name")));
                valid.add(v);
            }
        }

        if (!logs.isEmpty()) return new UploadResult(false, logs);

        writes.insertP1(valid);
        return new UploadResult(true, List.of("Successfully inserted " + valid.size() + " records."));
    }

    // ---------- Phase 2 ----------
    public UploadResult uploadP2(List<Map<String, String>> records, String year, String districtId) {
        List<String> logs = new ArrayList<>();

        if (writes.countStagedP2(districtId, String.valueOf(year)) > 0) {
            return new UploadResult(false, List.of("Upload Rejected: Results for Year " + year + " have already been uploaded for this district."));
        }

        String districtName = jurisName(districtId);
        Map<String, String> blockMap = loadBlocks(districtId);
        Set<String> reportedBlocks = new HashSet<>();
        List<Map<String, Object>> valid = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            Map<String, String> row = records.get(i);
            int rowNum = i + 1;
            boolean rowError = false;

            String blockKey = match.normalizeText(row.get("nmms_block"));
            String blockId = blockMap.get(blockKey);
            if (blockId == null) {
                if (reportedBlocks.add(blockKey == null ? "" : blockKey))   // Node computes suggestValue here but does NOT log it
                    logs.add("Row " + rowNum + ": Block \"" + row.get("nmms_block") + "\" invalid for " + districtName + ".");
                rowError = true;
            }

            String reg = row.get("nmms_reg_number");
            if (reg == null || !reg.matches("\\d{8,12}")) rowError = true;          // no log (Node quirk)
            String name = row.get("student_name");
            if (name == null || !name.matches("[A-Za-z\\s.]+")) rowError = true;    // no log

            if (!rowError) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("nmms_year", year);
                v.put("district", districtId);
                v.put("nmms_block", blockId);
                v.put("reg", reg);
                v.put("student_name", name);
                v.put("gmat", row.get("gmat_score"));
                v.put("sat", row.get("sat_score"));
                v.put("name_key", match.generateStudentNameKey(name));
                valid.add(v);
            }
        }

        if (!logs.isEmpty()) return new UploadResult(false, logs);

        writes.insertP2(valid);
        return new UploadResult(true, List.of("Successfully inserted " + valid.size() + " results."));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String digitsOnly(String s) { return s == null ? "" : s.replaceAll("[^0-9]", "").trim(); }
    private static boolean eq(String a, String b) { return Objects.equals(a, b); }

    // ---------- Preview merge ----------
    public Map<String, Object> previewMerge(String year, String district, com.rcf.imas.modules.merge.persistence.MergeReadRepository reads) {
        List<Map<String, Object>> rows = reads.previewRows(year, district);

        // group by phase1_id preserving first-seen order (rows already ORDER BY student_name)
        Map<Object, Map<String, Object>> studentMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object pid = row.get("phase1_id");
            Map<String, Object> app = studentMap.computeIfAbsent(pid, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("phase1_id", row.get("phase1_id"));
                a.put("student_name", row.get("student_name"));
                a.put("father_name", row.get("father_name"));
                a.put("students_sats_id", row.get("students_sats_id"));
                a.put("contact_no1", row.get("contact_no1"));
                a.put("institute_name", row.get("institute_name"));
                a.put("nmms_block", row.get("nmms_block"));
                a.put("block_name", row.get("block_name"));
                a.put("candidates", new ArrayList<Map<String, Object>>());
                return a;
            });
            if (row.get("result_stg_id") != null) {
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("result_stg_id", row.get("result_stg_id"));
                cand.put("nmms_reg_number", row.get("nmms_reg_number"));
                cand.put("student_name", row.get("result_student_name"));
                cand.put("gmat_score", row.get("gmat_score"));
                cand.put("sat_score", row.get("sat_score"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cands = (List<Map<String, Object>>) app.get("candidates");
                cands.add(cand);
            }
        }

        Map<String, List<Map<String, Object>>> blockWise = new LinkedHashMap<>();
        int total = 0, mapped = 0, conflicts = 0;
        for (Map<String, Object> app : studentMap.values()) {
            total++;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cands = (List<Map<String, Object>>) app.get("candidates");
            if (cands.size() == 1) mapped++;
            else if (cands.size() > 1) conflicts++;
            String blockName = String.valueOf(app.get("block_name"));
            blockWise.computeIfAbsent(blockName, k -> new ArrayList<>()).add(app);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_students", total);
        summary.put("mapped", mapped);
        summary.put("conflicts", conflicts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("blockWise", blockWise);
        return out;
    }
}
