package com.rcf.imas.modules.shortlist.service;

import com.rcf.imas.modules.shortlist.persistence.ShortlistReadRepository;
import com.rcf.imas.modules.shortlist.persistence.ShortlistWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShortlistService {

    private final ShortlistReadRepository reads;
    private final ShortlistWriteRepository writes;

    public ShortlistService(ShortlistReadRepository reads, ShortlistWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /** Node prose→threshold: lowercased substring match; else the exact Node error. */
    static String thresholdLiteral(String procCriteriaLower) {
        if (procCriteriaLower.contains("top 4%")) return "0.04";
        if (procCriteriaLower.contains("top 6%")) return "0.06";
        if (procCriteriaLower.contains("top 8%")) return "0.08";
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> startShortlisting(Map<String, Object> body) {
        Map<String, Object> locations = body.get("locations") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String state = trimOrNull(locations.get("state"));
        String district = trimOrNull(locations.get("district"));
        List<Object> blocks = locations.get("blocks") instanceof List<?> l ? (List<Object>) l : List.of();
        String criteriaId = str(body.get("criteriaId"));
        String name = str(body.get("name"));
        String description = trimOrNull(body.get("description"));
        String year = str(body.get("year"));
        String userId = str(body.get("userId"));

        if (isBlank(state) || isBlank(district) || isBlank(criteriaId) || isBlank(name) || isBlank(year) || blocks.isEmpty()) {
            throw ApiException.error(400, "Required fields missing.");
        }

        // criteria prose → threshold (unknown → 500 with the exact Node message)
        String criteriaText = reads.criteriaText(criteriaId);
        String procLower = (criteriaText == null ? "" : criteriaText).toLowerCase();
        String threshold = thresholdLiteral(procLower);
        if (threshold == null) {
            throw ApiException.error(500, "Criteria \"" + procLower + "\" logic not implemented.");
        }

        List<String> blockNamesLower = blocks.stream().map(b -> String.valueOf(b).toLowerCase().trim()).toList();

        ShortlistWriteRepository.BatchResult result;
        try {
            result = writes.createBatch(name.trim(), description, criteriaId, blockNamesLower, state, district, year, userId, threshold);
        } catch (ShortlistWriteRepository.DuplicateShortlistException e) {
            throw ApiException.error(409, e.getMessage());
        }

        String totalApplicants = reads.totalApplicantsInBlocks(blockNamesLower, year);
        String totalShortlistedInBlocks = reads.shortlistedCountForBlocks(blockNamesLower, year);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Shortlist created successfully!\nShortlisted " + result.shortlistedCount()
                + " students for academic year starting " + year + ".");
        out.put("shortlistBatchId", result.shortlistBatchId());
        out.put("shortlistedCountInBatch", result.shortlistedCount());
        out.put("totalApplicantsCount", totalApplicants);
        out.put("totalShortlistedInBlocks", totalShortlistedInBlocks);
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String trimOrNull(Object o) { return o == null ? null : String.valueOf(o).trim(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
