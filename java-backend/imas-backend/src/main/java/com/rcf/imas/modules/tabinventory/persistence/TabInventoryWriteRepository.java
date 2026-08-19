package com.rcf.imas.modules.tabinventory.persistence;

import com.rcf.imas.modules.tabinventory.TabStatus;
import com.rcf.imas.modules.tabinventory.TabStatusNormalizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.rcf.imas.modules.tabinventory.persistence.TabInventoryReadRepository.genericRow;

@Repository
public class TabInventoryWriteRepository {

    final JdbcClient jdbc;

    public TabInventoryWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** createBrand (ground truth §2.2). ON CONFLICT (brand_name, model_name) DO UPDATE -- verbatim; the
     *  target matches pp.tab_brand's brand_model_unique constraint (V1__baseline.sql:1289-1290). Node's
     *  $3 is reused for both created_by (insert branch) and updated_by (both branches) -- ported by
     *  binding the SAME :createdBy param twice. clean() (model.js:3) maps ""/null -> null. */
    public Map<String, Object> createBrand(String brandName, String modelName, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.tab_brand (brand_name, model_name, created_by, updated_by)
                VALUES (:brandName, :modelName, :createdBy::numeric, :createdBy::numeric)
                ON CONFLICT (brand_name, model_name)
                DO UPDATE SET
                  updated_at = CURRENT_TIMESTAMP,
                  updated_by = :createdBy::numeric
                RETURNING *
                """)
                .param("brandName", clean(brandName))
                .param("modelName", clean(modelName))
                .param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).single();
    }

    /** createTab (ground truth §2.3). status is NOT supplied -- relies on the table DEFAULT 'IN_OFFICE'
     *  (V1__baseline.sql:337). clean() (model.js:3) maps ""/null -> null; formatDate() (model.js:5-12)
     *  maps blank/absent -> null and otherwise passes the incoming date-ish string straight through (the
     *  client already sends an ISO yyyy-MM-dd string; Node's own formatDate is a defensive no-op for that
     *  shape, only ever nulling out unparseable input). */
    public Map<String, Object> createTab(String serialNumber, String imei, String inventoryId, String brandId,
                                          String tabPurchaseDate, String remarks, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.tab_inventory (
                  serial_number, imei, inventory_id, brand_id, tab_purchase_date, remarks, created_by, updated_by
                )
                VALUES (:serialNumber, :imei, :inventoryId, :brandId::integer, :tabPurchaseDate::date, :remarks,
                        :createdBy::numeric, :createdBy::numeric)
                RETURNING tab_id
                """)
                .param("serialNumber", clean(serialNumber))
                .param("imei", clean(imei))
                .param("inventoryId", clean(inventoryId))
                .param("brandId", brandId)
                .param("tabPurchaseDate", formatDate(tabPurchaseDate))
                .param("remarks", clean(remarks))
                .param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).single();
    }

    static String formatDate(String val) {
        return (val == null || val.isBlank()) ? null : val;
    }

    /**
     * changeTabStatus (ground truth §2.4, §4, §6). All 4 statements share ONE @Transactional connection.
     * `status` has ALREADY been validated against TabStatus by the controller before this method is
     * called (Firm Decision 2) -- bound here as a plain param regardless, never interpolated. Preserves
     * quirk 3 (ASSIGNED written even with no assignment row inserted, when assignment_type/id is
     * missing/invalid) and quirk 4 (IN_OFFICE does NOT auto-close open issue rows) verbatim -- no extra
     * validation is added beyond the status whitelist itself.
     */
    @Transactional
    public void changeTabStatus(String tabId, TabStatus status, String remarks,
                                 String assignmentType, String studentId, String officialUserId, String userId,
                                 String transactionDate) {
        String activeTxDate = (transactionDate == null || transactionDate.isBlank())
                ? LocalDate.now().toString() : transactionDate;

        if (status == TabStatus.RETURNED || status == TabStatus.DAMAGED || status == TabStatus.LOST) {
            jdbc.sql("UPDATE pp.student_issue SET return_date = :d::date WHERE tab_id = :tabId::numeric AND return_date IS NULL")
                    .param("d", activeTxDate).param("tabId", tabId).update();
            jdbc.sql("UPDATE pp.official_issue SET return_date = :d::date WHERE tab_id = :tabId::numeric AND return_date IS NULL")
                    .param("d", activeTxDate).param("tabId", tabId).update();
        }

        if (status == TabStatus.ASSIGNED) {
            if ("STUDENT".equals(assignmentType) && studentId != null && !studentId.isBlank()) {
                jdbc.sql("""
                        INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
                        VALUES (:tabId::numeric, :studentId::numeric, :d::date, NULL, :userId::numeric)
                        ON CONFLICT (tab_id, student_id)
                        DO UPDATE SET return_date = NULL, assignment_date = :d::date
                        """)
                        .param("tabId", tabId).param("studentId", studentId).param("d", activeTxDate)
                        .param("userId", userId).update();
            } else if ("OFFICIAL".equals(assignmentType) && officialUserId != null && !officialUserId.isBlank()) {
                jdbc.sql("""
                        INSERT INTO pp.official_issue (tab_id, user_id, assignment_date, return_date, remark, created_by)
                        VALUES (:tabId::numeric, :officialUserId::numeric, :d::date, NULL, :remarks, :userId::numeric)
                        ON CONFLICT (tab_id, user_id)
                        DO UPDATE SET return_date = NULL, assignment_date = :d::date
                        """)
                        .param("tabId", tabId).param("officialUserId", officialUserId).param("d", activeTxDate)
                        .param("remarks", remarks).param("userId", userId).update();
            }
        }

        jdbc.sql("""
                UPDATE pp.tab_inventory SET status = :status, remarks = COALESCE(:remarks, remarks), updated_at = CURRENT_TIMESTAMP
                WHERE tab_id = :tabId::numeric
                """)
                .param("status", status.name()).param("remarks", remarks).param("tabId", tabId).update();
    }

    /** Node's clean() (model.js:3): "" or null/undefined -> null. */
    static String clean(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** deleteTab (ground truth §2.6, §6). 3 statements, ONE @Transactional connection. Returns the
     *  deleted tab_id as a String (numeric -> String convention), or null if the row never existed --
     *  quirk 5: the caller still returns 200, never 404, on a miss. */
    @Transactional
    public String deleteTab(String tabId) {
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id = :tabId::numeric").param("tabId", tabId).update();
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id = :tabId::numeric").param("tabId", tabId).update();
        Map<String, Object> row = jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id = :tabId::numeric RETURNING tab_id")
                .param("tabId", tabId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        return row == null ? null : (String) row.get("tab_id");
    }

    /** Result of bulkCreateTabs -- count is devices.size() (input row count, NOT rows-actually-changed,
     *  ground truth §7 quirk 9), errors is non-empty only when success=false. */
    public record BulkResult(boolean success, int count, List<String> errors) {}

    /**
     * bulkCreateTabs (ground truth §2.10, §4, §6, §7 quirks 8/9/14). Two passes on ONE @Transactional
     * connection. PASS 1 validates every row (collect-all-errors, not fail-fast) using a simulated
     * tabHolderMap that tracks holders across rows within the pre-scan; if PASS 1 finds ANY error, the
     * whole method returns early with success=false and the full error list (nothing was written in
     * PASS 1, so there is nothing to roll back). PASS 2 only runs if PASS 1 is clean; a PASS-2 failure
     * (e.g. a tab_brand varchar(15) overflow) throws, and @Transactional rolls back EVERYTHING already
     * written in PASS 2, including earlier successful rows in the same batch. Beyond Node parity, PASS 1
     * ALSO rejects any row whose (typo-mapped) status doesn't resolve to a real TabStatus -- Firm
     * Decision 2 requires this whitelist gate before ANY bulk-path SQL runs; it is folded into the same
     * collect-all-errors mechanism as every other PASS-1 check, so the response shape stays consistent.
     */
    @Transactional
    public BulkResult bulkCreateTabs(List<Map<String, Object>> devices) {
        List<String> allErrors = new ArrayList<>();

        Set<String> serialNumbers = new java.util.LinkedHashSet<>();
        for (Map<String, Object> dev : devices) {
            String sn = upperTrim(dev.get("serial_number"));
            if (sn != null) serialNumbers.add(sn);
        }

        // serial_number -> {enrId, studentId} if actively held right now, absent from the map otherwise.
        Map<String, String[]> tabHolderMap = new HashMap<>();
        if (!serialNumbers.isEmpty()) {
            List<Map<String, Object>> holderRows = jdbc.sql("""
                    SELECT ti.serial_number, sm.enr_id, sm.student_id
                    FROM pp.tab_inventory ti
                    LEFT JOIN pp.student_issue si ON si.tab_id = ti.tab_id AND si.return_date IS NULL
                    LEFT JOIN pp.student_master sm ON sm.student_id = si.student_id
                    WHERE ti.serial_number = ANY(:serials)
                    """).param("serials", serialNumbers.toArray(new String[0]))
                    .query((rs, i) -> genericRow(rs)).list();
            for (Map<String, Object> row : holderRows) {
                Object enrId = row.get("enr_id");
                if (enrId != null) {
                    tabHolderMap.put((String) row.get("serial_number"),
                            new String[]{String.valueOf(enrId), (String) row.get("student_id")});
                }
            }
        }

        // PASS 1: validate every row, collect ALL errors before deciding anything.
        for (Map<String, Object> dev : devices) {
            String serialNumber = upperTrim(dev.get("serial_number"));
            if (serialNumber == null) continue;
            Object rowNumber = dev.get("rowNumber");

            String normalizedStatus = TabStatusNormalizer.normalize(strOf(dev.get("status")));
            if (TabStatus.parse(normalizedStatus).isEmpty()) {
                allErrors.add("Row " + rowNumber + ": Invalid status \"" + dev.get("status") + "\".");
                continue;
            }

            Object inventoryId = dev.get("inventory_id");
            if (!isBlank(inventoryId)) {
                String existingSerial = jdbc.sql("SELECT serial_number FROM pp.tab_inventory WHERE inventory_id = :id")
                        .param("id", String.valueOf(inventoryId)).query(String.class).optional().orElse(null);
                if (existingSerial != null && !existingSerial.equals(serialNumber)) {
                    allErrors.add("Row " + rowNumber + ": Inventory ID \"" + inventoryId + "\" is already assigned to tablet " +
                            "\"" + existingSerial + "\" in the database, but your file assigns it to \"" + serialNumber + "\". " +
                            "Either the Inventory ID or the Serial Number is wrong — please verify physically and correct your Excel file.");
                    continue;
                }
            }

            Object imei = dev.get("imei");
            if (!isBlank(imei)) {
                String existingSerial = jdbc.sql("SELECT serial_number FROM pp.tab_inventory WHERE imei = :imei")
                        .param("imei", String.valueOf(imei)).query(String.class).optional().orElse(null);
                if (existingSerial != null && !existingSerial.equals(serialNumber)) {
                    allErrors.add("Row " + rowNumber + ": IMEI \"" + imei + "\" is already registered to tablet " +
                            "\"" + existingSerial + "\" in the database, but your file assigns it to \"" + serialNumber + "\". " +
                            "Please check your Excel file for this IMEI.");
                    continue;
                }
            }

            String enrId = trimOrNull(dev.get("enr_id"));
            if (enrId != null && !enrId.isEmpty()) {
                Long studentId = jdbc.sql("SELECT student_id FROM pp.student_master WHERE enr_id = :enrId::numeric")
                        .param("enrId", enrId).query(Long.class).optional().orElse(null);
                if (studentId == null) {
                    allErrors.add("Row " + rowNumber + ": Enrolment ID \"" + enrId + "\" not found in the database. " +
                            "Please check the Enrolment ID is correct (Serial: " + serialNumber + ").");
                    continue;
                }
                if ("ASSIGNED".equals(normalizedStatus)) {
                    String[] currentHolder = tabHolderMap.get(serialNumber);
                    if (currentHolder != null && !currentHolder[0].equals(enrId)) {
                        allErrors.add("Row " + rowNumber + ": Tab \"" + serialNumber + "\" is currently ASSIGNED to Student " +
                                currentHolder[0] + " and has not been returned, but this row assigns it to Student " + enrId + ". " +
                                "A tablet can only be held by one student at a time — add a RETURNED row for Student " +
                                currentHolder[0] + " (Tab: " + serialNumber + ") before assigning it to Student " + enrId + ".");
                        continue;
                    }
                    tabHolderMap.put(serialNumber, new String[]{enrId, String.valueOf(studentId)});
                }
            }

            if (Set.of("RETURNED", "DAMAGED", "LOST", "IN_OFFICE").contains(normalizedStatus)) {
                tabHolderMap.put(serialNumber, null);
            }
        }

        if (!allErrors.isEmpty()) {
            return new BulkResult(false, 0, allErrors);
        }

        // PASS 2: all clear -- apply every row.
        for (Map<String, Object> dev : devices) {
            String serialNumber = upperTrim(dev.get("serial_number"));
            if (serialNumber == null) continue;

            String normalizedStatus = TabStatusNormalizer.normalize(strOf(dev.get("status")));
            String createdBy = strOf(dev.get("created_by"));
            String remarks = strOf(dev.get("remarks"));

            String tabId = jdbc.sql("SELECT tab_id FROM pp.tab_inventory WHERE serial_number = :sn")
                    .param("sn", serialNumber).query((rs, i) -> genericRow(rs)).optional()
                    .map(r -> (String) r.get("tab_id")).orElse(null);

            if (tabId == null) {
                String brandName = strOf(dev.get("brand_name"));
                String modelName = strOf(dev.get("model_name"));
                Integer brandId = jdbc.sql("""
                        INSERT INTO pp.tab_brand (brand_name, model_name, created_by)
                        VALUES (:brandName, :modelName, :createdBy::numeric)
                        ON CONFLICT (brand_name, model_name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                        RETURNING brand_id
                        """)
                        .param("brandName", (brandName == null || brandName.isEmpty()) ? "Unknown" : brandName)
                        .param("modelName", (modelName == null || modelName.isEmpty()) ? "Unknown" : modelName)
                        .param("createdBy", createdBy)
                        .query(Integer.class).single();

                tabId = jdbc.sql("""
                        INSERT INTO pp.tab_inventory (serial_number, imei, inventory_id, brand_id, status, remarks, created_by)
                        VALUES (:sn, :imei, :invId, :brandId::integer, :status, :remarks, :createdBy::numeric)
                        RETURNING tab_id
                        """)
                        .param("sn", serialNumber).param("imei", strOf(dev.get("imei")))
                        .param("invId", strOf(dev.get("inventory_id"))).param("brandId", brandId)
                        .param("status", normalizedStatus).param("remarks", remarks).param("createdBy", createdBy)
                        .query((rs, i) -> genericRow(rs)).single().get("tab_id").toString();
            } else {
                jdbc.sql("UPDATE pp.tab_inventory SET status = :status, remarks = :remarks, updated_at = CURRENT_TIMESTAMP WHERE tab_id = :tabId::numeric")
                        .param("status", normalizedStatus).param("remarks", remarks).param("tabId", tabId).update();
            }

            String enrId = trimOrNull(dev.get("enr_id"));
            String assignedDate = strOf(dev.get("assigned_date"));
            if (assignedDate == null || assignedDate.isBlank()) assignedDate = LocalDate.now().toString();

            if (enrId != null && !enrId.isEmpty()) {
                Long studentId = jdbc.sql("SELECT student_id FROM pp.student_master WHERE enr_id = :enrId::numeric")
                        .param("enrId", enrId).query(Long.class).single();
                String studentIdStr = String.valueOf(studentId);

                if ("ASSIGNED".equals(normalizedStatus)) {
                    jdbc.sql("""
                            UPDATE pp.student_issue
                               SET return_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP
                             WHERE tab_id = :tabId::numeric AND return_date IS NULL AND student_id != :studentId::numeric
                            """).param("tabId", tabId).param("studentId", studentIdStr).update();

                    jdbc.sql("""
                            INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
                            VALUES (:tabId::numeric, :studentId::numeric, :assignedDate::date, NULL, :createdBy::numeric)
                            ON CONFLICT (tab_id, student_id)
                            DO UPDATE SET assignment_date = EXCLUDED.assignment_date, return_date = NULL, updated_at = CURRENT_TIMESTAMP
                            """).param("tabId", tabId).param("studentId", studentIdStr).param("assignedDate", assignedDate)
                            .param("createdBy", createdBy).update();
                } else if (Set.of("RETURNED", "DAMAGED", "LOST").contains(normalizedStatus)) {
                    String returnDate = strOf(dev.get("return_date"));
                    if (returnDate == null || returnDate.isBlank()) returnDate = LocalDate.now().toString();
                    jdbc.sql("""
                            INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
                            VALUES (:tabId::numeric, :studentId::numeric, :assignedDate::date, :returnDate::date, :createdBy::numeric)
                            ON CONFLICT (tab_id, student_id)
                            DO UPDATE SET
                              assignment_date = COALESCE(pp.student_issue.assignment_date, EXCLUDED.assignment_date),
                              return_date = EXCLUDED.return_date,
                              updated_at = CURRENT_TIMESTAMP
                            """).param("tabId", tabId).param("studentId", studentIdStr).param("assignedDate", assignedDate)
                            .param("returnDate", returnDate).param("createdBy", createdBy).update();
                }
            } else if (Set.of("RETURNED", "DAMAGED", "LOST").contains(normalizedStatus)) {
                String returnDate = strOf(dev.get("return_date"));
                if (returnDate == null || returnDate.isBlank()) returnDate = LocalDate.now().toString();
                jdbc.sql("UPDATE pp.student_issue SET return_date = :returnDate::date, updated_at = CURRENT_TIMESTAMP WHERE tab_id = :tabId::numeric AND return_date IS NULL")
                        .param("returnDate", returnDate).param("tabId", tabId).update();
            }
        }

        return new BulkResult(true, devices.size(), List.of());
    }

    private static String upperTrim(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toUpperCase();
        return s.isEmpty() ? null : s;
    }

    private static String trimOrNull(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String strOf(Object v) { return v == null ? null : String.valueOf(v); }

    private static boolean isBlank(Object v) { return v == null || String.valueOf(v).isEmpty(); }
}
