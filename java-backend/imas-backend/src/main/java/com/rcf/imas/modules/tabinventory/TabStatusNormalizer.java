package com.rcf.imas.modules.tabinventory;

import java.util.Map;

/**
 * bulkCreateTabsModel's STATUS_TYPO_MAP + normalization (model.js:271-276 and, identically, :403-408 --
 * Node declares this map TWICE, once per pass; ground truth §7 quirk 14). Java keeps ONE copy here,
 * called from both PASS 1 and PASS 2 of TabInventoryWriteRepository.bulkCreateTabs -- a safe
 * simplification, not a behavior change: the typo map and the
 * toUpperCase().trim().replace(whitespace,"_") normalization are byte-identical to Node's.
 */
public final class TabStatusNormalizer {

    private static final Map<String, String> TYPO_MAP = Map.of(
            "ASIGNED", "ASSIGNED", "ASSIGEND", "ASSIGNED", "ASSIGED", "ASSIGNED",
            "RETUREND", "RETURNED", "RETRUNED", "RETURNED",
            "DAMGED", "DAMAGED", "DAMMAGED", "DAMAGED",
            "IN_OFICE", "IN_OFFICE", "INOFFICE", "IN_OFFICE");

    private TabStatusNormalizer() {}

    /** (dev.status || "IN_OFFICE").toUpperCase().trim().replace(/\s+/g, "_"), then typo-map lookup. */
    public static String normalize(String rawStatus) {
        String base = (rawStatus == null || rawStatus.isBlank()) ? "IN_OFFICE" : rawStatus;
        String normalized = base.toUpperCase().trim().replaceAll("\\s+", "_");
        return TYPO_MAP.getOrDefault(normalized, normalized);
    }
}
