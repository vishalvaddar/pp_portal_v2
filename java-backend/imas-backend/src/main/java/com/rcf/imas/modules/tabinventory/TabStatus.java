package com.rcf.imas.modules.tabinventory;

import java.util.Optional;

/**
 * The 5 values allowed by pp.tab_inventory's tab_inventory_status_check CHECK constraint
 * (V1__baseline.sql:343): IN_OFFICE, ASSIGNED, RETURNED, DAMAGED, LOST. Node relies SOLELY on this DB
 * constraint for both changeTabStatus's raw req.body.status and bulkCreateTabs' typo-mapped
 * normalizedStatus (ground truth §2.4, §7 quirk 2) -- an invalid value bubbles up as a raw Postgres
 * error message. This enum is an explicit app-level whitelist gate, validated BEFORE any SQL runs, so
 * both write paths return a clean 400 instead (Firm Decision 2).
 */
public enum TabStatus {
    IN_OFFICE, ASSIGNED, RETURNED, DAMAGED, LOST;

    public static Optional<TabStatus> parse(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(TabStatus.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
