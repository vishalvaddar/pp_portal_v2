# Phase 5b — TAB-INVENTORY Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `tabInventoryRoutes.js` / `tabInventoryController.js` / `tabInventoryModel.js` trio (14 endpoints, zero Node auth) to a new Spring Boot module `com.rcf.imas.modules.tabinventory`, byte-compatible on the wire with the frozen React client, gated behind `hasRole('ADMIN')`.

**Architecture:** One `@RestController` (`TabInventoryController`, `@RequestMapping("/api/tabs")`, class-level `@PreAuthorize("hasRole('ADMIN')")`) backed by two `@Repository` classes using plain `JdbcClient` (`TabInventoryReadRepository` for the 10 read endpoints, `TabInventoryWriteRepository` for the 4 write endpoints — `createTab`, `createBrand`, `changeTabStatus`, `deleteTab`, `bulkCreateTabs`). Two small standalone types (`TabStatus` enum, `TabStatusNormalizer`) live in the module root package and are shared by both the controller (validation) and the write repository (typo-mapping). No JPA — every query is a literal, verbatim-ported SQL string bound with named `JdbcClient` params.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Maven, `JdbcClient` (no ORM), embedded-postgres for integration tests (no Docker), JUnit 5 + MockMvc.

---

## Firm Decisions (locked, do not re-litigate)

1. **Auth: class-level `@PreAuthorize("hasRole('ADMIN')")`** on `TabInventoryController`. Ground truth §0/§7-1 confirms Node applies *zero* auth middleware to any of the 14 `/api/tabs*` routes. Per this project's established rule for a zero-auth Node admin-management mount (same precedent as `modules/classroom.ClassroomController` and `modules/selectionreports.SelectionReportsController`, both of which carry the identical class-level annotation with an identical justifying comment), tab inventory is admin device-management → gated `ADMIN`. This is an intended hardening, not a byte-for-byte parity item.
2. **`TabStatus` enum whitelist (security hardening, SECURITY):** `IN_OFFICE, ASSIGNED, RETURNED, DAMAGED, LOST` — the exact 5 values from `pp.tab_inventory`'s `tab_inventory_status_check` CHECK constraint (`V1__baseline.sql:343`). Gates BOTH `changeTabStatus` (raw `req.body.status` in Node) and `bulkCreateTabs`' `normalizedStatus` (typo-mapped but otherwise unbounded in Node) with a clean 400 BEFORE any SQL runs. Node relies solely on the Postgres CHECK constraint and leaks raw Postgres error text on violation; the enum is defense-in-depth plus a clean 400, not a behavior change to the set of accepted values.
3. **Transactions:** `changeTabStatus`, `deleteTab`, `bulkCreateTabs` are `@Transactional` `@Repository` methods, one method = one connection/one transaction boundary, matching Node's `pool.connect()` → `BEGIN` → … → `COMMIT`/`ROLLBACK` → `client.release()` pattern exactly (ground truth §6). All three `ON CONFLICT` targets are ported VERBATIM: `tab_brand(brand_name, model_name)` (constraint `brand_model_unique`), `student_issue(tab_id, student_id)` (PK), `official_issue(tab_id, user_id)` (PK) — all three confirmed present in `V1__baseline.sql` (ground truth §3, "Cross-check verdict: No schema-vs-code mismatches found").
4. **`bulkCreateTabs` two-pass semantics reproduced exactly:** PASS 1 (read-only, validates every row, builds a simulated `tabHolderMap` tracking holders across rows within the pre-scan, collects ALL errors instead of failing fast) → if any errors, return early with `{success:false, errors:[...]}` (no writes to roll back) → PASS 2 (only runs if PASS 1 is clean) applies every row's writes on the SAME `@Transactional` connection, so a PASS-2 failure rolls back every row already written in PASS 2, even rows PASS 1 approved. `STATUS_TYPO_MAP` + normalization is declared ONCE in Java (`TabStatusNormalizer`), not duplicated like Node's two copies (ground truth §7 quirk 14 — a safe simplification, not a behavior change). The bulk row-error 400 response OMITS the `message` key entirely (ground truth §7 quirk 8) — the controller builds that body by hand via `ResponseEntity`, not `ApiException`, to guarantee the key is truly absent (not merely null).
5. **`getTabMovementReport` dynamic SQL** uses a `StringBuilder` + JdbcClient NAMED params (`:fromCohort` / `:toCohort`), appended conditionally exactly like Node's conditional `query +=` (ground truth §2.14, §7 quirk 13). Because named params are used (not positional `$1`/`$2`), there is no parameter-index-shift arithmetic to reproduce in Java — naming sidesteps the shift entirely; the thing that MUST be reproduced exactly is which clause(s) get appended and the `!= "ALL"`/non-blank sentinel check. The base CTE (`sequential_issues`, the `LEAD(...) OVER (PARTITION BY tab_id ORDER BY assignment_date)` window) is ported byte-for-byte, including its **INNER** `JOIN pp.batch`/`JOIN pp.cohort` (silently drops `student_issue` rows with a null `batch_id` or an unresolvable `cohort_number` — this differs from `getAllTabs`'s CTE, which uses LEFT JOIN for the identical relationship; both are preserved as-is, not harmonized).
6. **Quirks preserved verbatim (NOT fixed), each flagged below in Deferred / Flagged:** `getTabStats` swallows the real error → generic 500 "Internal Server Error"; bulk's row-error 400 omits `message`; `deleteTab` returns 200 even when the row doesn't exist; `getAllTabs` LEFT JOIN vs `getTabMovementReport` INNER JOIN for the same batch/cohort relationship; setting status to `IN_OFFICE` does NOT auto-close open `student_issue`/`official_issue` rows (only `RETURNED`/`DAMAGED`/`LOST` do); `changeTabStatus` can write `status='ASSIGNED'` with no assignment row actually inserted (missing/invalid `assignment_type` or missing id — no validation rejects this in Node); `getAllTabs`'s big `CASE WHEN status='IN_OFFICE' THEN NULL ELSE ...` projection is ported verbatim, column-for-column.
7. **genericRow + id serialization**, identical convention to `CoordinatorReadRepository.genericRow` / `ClassroomReadRepository`: `numeric(x,0)` → `String` (via `BigDecimal.toBigInteger().toString()`), Postgres `integer` → native `Integer` (passthrough `rs.getObject`), `DATE` → `"yyyy-MM-dd"`, `TIMESTAMP` → ISO-`Z` string, `bigint`/`COUNT(*)` → `String`. Verified per-table against `V1__baseline.sql`: `tab_id numeric(20,0)`, `student_id numeric(14,0)`, `user_id numeric(8,0)`, `applicant_id numeric(14,0)`, `enr_id numeric(11,0)` all serialize as `String`; `brand_id integer`, `cohort_number integer` serialize as `Integer`; every `COUNT(*)`/`COUNT(*) FILTER (...)` result in `getTabStats` is Postgres `bigint` → `String`.
8. **Error envelopes are per-endpoint EXACT, not uniform** (ground truth §5): every success body is `{success:true, ...}`; every error body is `{success:false, message:...}` EXCEPT `getTabStats`'s 500 (fixed generic text, not `e.getMessage()`) and `bulkCreateTabs`' row-error 400 (`errors` array, no `message` key at all). `createTab` and `createBrand` both special-case Postgres unique-violation (`DuplicateKeyException`) into a 409 before falling through to a generic 500.
9. **`createTab` does not supply `status`** — relies on the `pp.tab_inventory.status` column's `DEFAULT 'IN_OFFICE'` (`V1__baseline.sql:337`). `clean()` (`"" `/`null` → `null`) and `formatDate()` (blank/unparseable → `null`, otherwise pass through the incoming ISO-ish date string) are reproduced as small private static helpers in `TabInventoryWriteRepository`.

## Task decomposition

| Task | Scope | Endpoints |
|---|---|---|
| 1 | Module skeleton, `TabStatus` enum, `TabStatusNormalizer`, simple reads, brands | `GET /stats`, `GET /eligible-students`, `GET /users`, `GET /cohorts`, `GET /brands`, `POST /brands` |
| 2 | Tab reads | `GET /`, `GET /{tabId}`, `GET /{tabId}/history`, `GET /movement-report` |
| 3 | `createTab` + `changeTabStatus` (`@Transactional` + status whitelist) | `POST /`, `PUT /{tabId}/status` |
| 4 | `deleteTab` + `bulkCreateTabs` (two-pass `@Transactional`) | `DELETE /{tabId}`, `POST /bulk` |

All paths are relative to `/api/tabs` (class-level `@RequestMapping("/api/tabs")` — Node's Express router mounts everything at `/api` with routes defining their own full `/tabs...` path, ground truth §0).

---

### Task 1: Module skeleton, `TabStatus`, simple reads, brands

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/TabStatus.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/TabStatusNormalizer.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryStatsAndBrandsIT.java`

- [x] **Step 1: Write the failing test**

Create `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryStatsAndBrandsIT.java`:

```java
package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryStatsAndBrandsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        // Users: 951001 = admin/token issuer; 951002 = holds an open official_issue (excluded from /users);
        // 951003 = locked_yn left NULL (excluded from /users, quirk 11); 951004 = free staff (included).
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951001,'saAdmin951','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951002,'saStaffHeld951','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password) VALUES (951003,'saStaffNullLocked951','x')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (951004,'saStaffFree951','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (951001,'Cohort TI951')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (951001,'TI Batch',951001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (951001, 24951000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (951002, 24951000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (951001)").update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (951002)").update();

        // student 951001: active, no open student_issue -> eligible. student 951002: active, HAS an open
        // student_issue -> not eligible.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (951001, 951001, 95100001, 'Eligible Student 951', 'F', 951001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (951002, 951002, 95100002, 'Held Student 951', 'M', 951001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (951001,'BrandA951','ModelA951',951001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab_inventory: 951001 IN_OFFICE, 951002 DAMAGED (linked to student 951002's open issue),
        // 951003 ASSIGNED (linked to user 951002's open official_issue).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951001,'SN-TI-951001',951001,'IN_OFFICE',951001)").update();
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951002,'SN-TI-951002',951001,'DAMAGED',951001)").update();
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (951003,'SN-TI-951003',951001,'ASSIGNED',951001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (951002,951002,CURRENT_DATE,951001)").update();
        jdbc.sql("INSERT INTO pp.official_issue(tab_id, user_id, assignment_date, created_by) VALUES (951003,951002,CURRENT_DATE,951001)").update();

        adminToken = jwt.issueFinalToken("951001", "saAdmin951", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (951001,951002,951003)").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 951001 OR (brand_name = 'TestBrand951')").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (951001,951002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 951001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 951001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (951001,951002,951003,951004)").update();
    }

    @Test
    void statsReturnsFilterCountsAsStrings() throws Exception {
        mvc.perform(get("/api/tabs/stats").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.total").value("3"))
           .andExpect(jsonPath("$.data.in_office").value("1"))
           .andExpect(jsonPath("$.data.damaged").value("1"))
           .andExpect(jsonPath("$.data.lost").value("0"))
           .andExpect(jsonPath("$.data.returned_awaiting").value("0"))
           .andExpect(jsonPath("$.data.student_assigned").value("1"))
           .andExpect(jsonPath("$.data.official_assigned").value("1"));
    }

    @Test
    void eligibleStudentsExcludesHeldStudent() throws Exception {
        mvc.perform(get("/api/tabs/eligible-students").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[?(@.student_id=='951001')]").exists())
           .andExpect(jsonPath("$.data[?(@.student_id=='951002')]").doesNotExist());
    }

    @Test
    void usersExcludesHeldStaffAndNullLockedYn() throws Exception {
        mvc.perform(get("/api/tabs/users").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.user_id=='951004')]").exists())
           .andExpect(jsonPath("$.data[?(@.user_id=='951002')]").doesNotExist())
           .andExpect(jsonPath("$.data[?(@.user_id=='951003')]").doesNotExist());
    }

    @Test
    void cohortsListsSeededCohort() throws Exception {
        mvc.perform(get("/api/tabs/cohorts").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.cohort_number==951001)]").exists());
    }

    @Test
    void brandsListsSeededBrand() throws Exception {
        mvc.perform(get("/api/tabs/brands").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[?(@.brand_id==951001)]").exists());
    }

    @Test
    void createBrandUpsertsOnConflictAndReturnsSameBrandId() throws Exception {
        String body = """
            {"brand_name":"TestBrand951","model_name":"TestModel951","created_by":951001}
            """;
        String firstResponse = mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.brand_name").value("TestBrand951"))
           .andReturn().getResponse().getContentAsString();

        // Second identical create hits ON CONFLICT DO UPDATE -- same (brand_name, model_name), so it must
        // return the SAME brand_id (upsert, not a duplicate row), still 201.
        mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.brand_id").value(
                   com.jayway.jsonpath.JsonPath.read(firstResponse, "$.data.brand_id")));

        Long count = jdbc.sql("SELECT COUNT(*) FROM pp.tab_brand WHERE brand_name = 'TestBrand951'")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1L);
    }

    @Test
    void createBrandMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/tabs/brands").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("brand_name, model_name, and created_by are required."));
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryStatsAndBrandsIT`
Expected: FAIL (compilation error — `TabInventoryController`, `TabInventoryReadRepository`, `TabInventoryWriteRepository` do not exist yet).

- [x] **Step 3: Write `TabStatus`**

Create `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/TabStatus.java`:

```java
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
```

- [x] **Step 4: Write `TabStatusNormalizer`**

Create `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/TabStatusNormalizer.java`:

```java
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
```

- [x] **Step 5: Write `TabInventoryReadRepository`**

Create `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryReadRepository.java`:

```java
package com.rcf.imas.modules.tabinventory.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TabInventoryReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcClient jdbc;

    public TabInventoryReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow for this module (house convention, ground truth §5/§7-16, matching
     * CoordinatorReadRepository.genericRow): numeric(x,0) columns (tab_id, student_id, user_id,
     * applicant_id, enr_id) -> String via BigDecimal.toBigInteger().toString(); integer columns
     * (brand_id, cohort_number) pass through natively; bigint COUNT(*) results -> String; DATE ->
     * "yyyy-MM-dd"; TIMESTAMP -> ISO-Z. Package-private static so TabInventoryWriteRepository reuses it.
     */
    static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            int type = md.getColumnType(i);
            Object val;
            switch (type) {
                case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                    BigDecimal bd = rs.getBigDecimal(i);
                    val = bd == null ? null : bd.toBigInteger().toString();
                }
                case java.sql.Types.BIGINT -> {
                    long v = rs.getLong(i); val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.DATE -> {
                    java.sql.Date d = rs.getDate(i);
                    val = d == null ? null : DATE_FMT.format(d.toLocalDate());
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** getTabStats (ground truth §2.12). All 7 result columns are bigint COUNT(...)/COUNT(...) FILTER
     *  results -> String via genericRow's BIGINT case. */
    public Map<String, Object> tabStats() {
        return jdbc.sql("""
                SELECT
                  COUNT(*) as total,
                  COUNT(*) FILTER (WHERE status = 'IN_OFFICE') as in_office,
                  COUNT(*) FILTER (WHERE status = 'DAMAGED') as damaged,
                  COUNT(*) FILTER (WHERE status = 'LOST') as lost,
                  COUNT(*) FILTER (WHERE status = 'RETURNED') as returned_awaiting,
                  (SELECT COUNT(*) FROM pp.student_issue WHERE return_date IS NULL) as student_assigned,
                  (SELECT COUNT(*) FROM pp.official_issue WHERE return_date IS NULL) as official_assigned
                FROM pp.tab_inventory
                """).query((rs, i) -> genericRow(rs)).single();
    }

    /** getEligibleStudents (ground truth §2.11). */
    public List<Map<String, Object>> eligibleStudents() {
        return jdbc.sql("""
                SELECT s.student_id, s.applicant_id, s.student_name, s.enr_id
                FROM pp.student_master s
                WHERE s.active_yn = 'ACTIVE'
                AND NOT EXISTS (
                    SELECT 1 FROM pp.student_issue si
                    WHERE si.student_id = s.student_id AND si.return_date IS NULL
                )
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllUsers (ground truth §2.5). locked_yn is CHAR(1), nullable, no DEFAULT -- the exact `= 'N'`
     *  comparison is preserved verbatim (three-valued SQL logic silently excludes NULL rows, quirk 11). */
    public List<Map<String, Object>> usersWithoutTab() {
        return jdbc.sql("""
                SELECT user_id, user_name
                FROM pp."user" u
                WHERE locked_yn = 'N'
                AND NOT EXISTS (
                    SELECT 1 FROM pp.official_issue oi
                    WHERE oi.user_id = u.user_id AND oi.return_date IS NULL
                )
                ORDER BY user_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllCohorts (ground truth §2.13). */
    public List<Map<String, Object>> cohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name FROM pp.cohort ORDER BY cohort_name ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllBrands (ground truth §2.1). */
    public List<Map<String, Object>> brands() {
        return jdbc.sql("SELECT brand_id, brand_name, model_name FROM pp.tab_brand ORDER BY brand_name, model_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    private static final String ALL_TABS_SQL = """
            WITH latest_student_assignment AS (
              SELECT
                si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, sm.enr_id, b.batch_name, c.cohort_name,
                ROW_NUMBER() OVER(PARTITION BY si.tab_id ORDER BY si.assignment_date DESC, si.created_at DESC) as rn
              FROM pp.student_issue si
              JOIN pp.student_master sm ON si.student_id = sm.student_id
              LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
              LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            ),
            latest_official_assignment AS (
              SELECT
                oi.tab_id, oi.user_id, oi.assignment_date, oi.return_date, u.user_name as staff_name,
                ROW_NUMBER() OVER(PARTITION BY oi.tab_id ORDER BY oi.assignment_date DESC, oi.created_at DESC) as rn
              FROM pp.official_issue oi
              JOIN pp."user" u ON oi.user_id = u.user_id
            )
            SELECT
              t.tab_id, t.serial_number, t.imei, t.inventory_id, tb.brand_name, tb.model_name AS model,
              t.tab_purchase_date, t.status, t.remarks, t.updated_at,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE COALESCE(sa.student_name, oa.staff_name) END AS assigned_to,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.enr_id END AS enr_id,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.student_name END AS student_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE oa.staff_name END AS staff_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.cohort_name END as cohort_name,
              CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.batch_name END as batch_name,
              CASE
                WHEN t.status = 'IN_OFFICE' THEN NULL
                WHEN sa.student_id IS NOT NULL AND (sa.assignment_date >= COALESCE(oa.assignment_date, '1970-01-01')) THEN 'STUDENT'
                WHEN oa.user_id IS NOT NULL THEN 'OFFICIAL'
                ELSE NULL
              END AS assignment_category
            FROM pp.tab_inventory t
            LEFT JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
            LEFT JOIN latest_student_assignment sa ON t.tab_id = sa.tab_id AND sa.rn = 1
            LEFT JOIN latest_official_assignment oa ON t.tab_id = oa.tab_id AND oa.rn = 1
            ORDER BY t.created_at DESC
            """;

    /** getAllTabs (ground truth §2.8). NOTE: this CTE's batch/cohort joins are LEFT (unlike
     *  getTabMovementReport's INNER, ground truth §7 quirk 12) -- the two are preserved as-is, not
     *  harmonized. */
    public List<Map<String, Object>> allTabs() {
        return jdbc.sql(ALL_TABS_SQL).query((rs, i) -> genericRow(rs)).list();
    }

    /** getTabById (ground truth §2.7). */
    public Optional<Map<String, Object>> tabById(String tabId) {
        return jdbc.sql("SELECT * FROM pp.tab_inventory WHERE tab_id = :tabId::numeric")
                .param("tabId", tabId).query((rs, i) -> genericRow(rs)).optional();
    }

    /** getTabHistory (ground truth §2.9). student_issue has no remark column (unlike official_issue) --
     *  the literal NULL as staff_remark for the student branch is correct/required, not an oversight
     *  (ground truth §3 note). */
    public List<Map<String, Object>> tabHistory(String tabId) {
        return jdbc.sql("""
                SELECT
                  assignment_date, return_date, sm.student_name as name, sm.enr_id, 'Student' as category, NULL as staff_remark
                FROM pp.student_issue si
                JOIN pp.student_master sm ON si.student_id = sm.student_id
                WHERE si.tab_id = :tabId::numeric
                UNION ALL
                SELECT
                  assignment_date, return_date, u.user_name as name, NULL as enr_id, 'Staff' as category, remark as staff_remark
                FROM pp.official_issue oi
                JOIN pp."user" u ON oi.user_id = u.user_id
                WHERE oi.tab_id = :tabId::numeric
                ORDER BY assignment_date DESC
                """).param("tabId", tabId).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getTabMovementReport (ground truth §2.14, §7 quirk 13). Dynamic WHERE clauses via StringBuilder,
     * values always bound as NAMED params -- never interpolated. "ALL" (or absent/blank) is the
     * Node-equivalent sentinel for "no filter" (model.js:567 `fromCohort && fromCohort !== "ALL"`,
     * :572 same for toCohort). Because JdbcClient uses named params (not node-pg's positional $1/$2),
     * there is no index-shift arithmetic to replicate -- only the conditional clause presence matters.
     * sequential_issues uses INNER JOIN batch/cohort (unlike getAllTabs' LEFT, quirk 12) -- rows with an
     * unresolvable batch/cohort are silently dropped, preserved verbatim.
     */
    public List<Map<String, Object>> movementReport(String fromCohort, String toCohort) {
        StringBuilder sql = new StringBuilder("""
                WITH sequential_issues AS (
                  SELECT
                    si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, c.cohort_name,
                    LEAD(sm.student_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_holder,
                    LEAD(c.cohort_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_cohort,
                    LEAD(si.assignment_date) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as transfer_date
                  FROM pp.student_issue si
                  JOIN pp.student_master sm ON si.student_id = sm.student_id
                  JOIN pp.batch b ON sm.batch_id = b.batch_id
                  JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                )
                SELECT
                  t.serial_number, t.inventory_id, tb.brand_name, tb.model_name as model,
                  si.student_name AS previous_holder, si.cohort_name AS from_cohort,
                  si.next_holder AS new_holder, si.next_cohort AS to_cohort, si.transfer_date AS moved_at
                FROM sequential_issues si
                JOIN pp.tab_inventory t ON si.tab_id = t.tab_id
                JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
                WHERE si.next_cohort IS NOT NULL
                """);

        boolean hasFrom = fromCohort != null && !fromCohort.isEmpty() && !fromCohort.equals("ALL");
        boolean hasTo = toCohort != null && !toCohort.isEmpty() && !toCohort.equals("ALL");
        if (hasFrom) sql.append(" AND si.cohort_name = :fromCohort");
        if (hasTo) sql.append(" AND si.next_cohort = :toCohort");
        sql.append(" ORDER BY si.transfer_date DESC");

        var spec = jdbc.sql(sql.toString());
        if (hasFrom) spec = spec.param("fromCohort", fromCohort);
        if (hasTo) spec = spec.param("toCohort", toCohort);
        return spec.query((rs, i) -> genericRow(rs)).list();
    }
}
```

- [x] **Step 6: Write `TabInventoryWriteRepository`** (Task 1 scope: `createBrand` only)

Create `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java`:

```java
package com.rcf.imas.modules.tabinventory.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

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

    /** Node's clean() (model.js:3): "" or null/undefined -> null. */
    static String clean(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }
}
```

- [x] **Step 7: Write `TabInventoryController`** (Task 1 scope: 6 endpoints)

Create `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java`:

```java
package com.rcf.imas.modules.tabinventory.web;

import com.rcf.imas.modules.tabinventory.persistence.TabInventoryReadRepository;
import com.rcf.imas.modules.tabinventory.persistence.TabInventoryWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tabs")
@PreAuthorize("hasRole('ADMIN')")   // ground truth §0/§7-1: Node applies ZERO auth middleware to any of the
                                     // 14 /api/tabs* routes -- Firm Decision 1, same rule as modules/classroom
                                     // and modules/selectionreports (zero-auth Node admin-management mount -> ADMIN)
class TabInventoryController {

    final TabInventoryReadRepository reads;
    final TabInventoryWriteRepository writes;

    TabInventoryController(TabInventoryReadRepository reads, TabInventoryWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.tabStats());
            return out;
        } catch (Exception e) {
            // getTabStats is the ONE handler in this module that swallows the real error (quirk 6) --
            // every other read handler echoes e.getMessage(); this one always says "Internal Server Error".
            throw ApiException.message(500, "Internal Server Error").with("success", false);
        }
    }

    @GetMapping("/eligible-students")
    public Map<String, Object> eligibleStudents() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.eligibleStudents());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.usersWithoutTab());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.cohorts());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/brands")
    public Map<String, Object> brands() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.brands());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBrand(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object brandName = b.get("brand_name");
        Object modelName = b.get("model_name");
        Object createdBy = b.get("created_by");
        if (isFalsy(brandName) || isFalsy(modelName) || isFalsy(createdBy)) {
            throw ApiException.message(400, "brand_name, model_name, and created_by are required.").with("success", false);
        }
        try {
            Map<String, Object> data = writes.createBrand(String.valueOf(brandName), String.valueOf(modelName), String.valueOf(createdBy));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", data);
            return out;
        } catch (DuplicateKeyException e) {
            // controller.js:27-29: dead code under the current schema (ON CONFLICT DO UPDATE absorbs the
            // exact conflict this branch targets, quirk 7) -- kept for parity/future-proofing.
            throw ApiException.message(409, "This Brand and Model combination already exists.").with("success", false);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    static boolean isFalsy(Object o) {
        if (o == null) return true;
        if (o instanceof String s) return s.isEmpty();
        if (o instanceof Number n) return n.doubleValue() == 0;
        return false;
    }

    static String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
}
```

- [x] **Step 8: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryStatsAndBrandsIT`
Expected: PASS (7 tests).

- [x] **Step 9: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/tabinventory imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryStatsAndBrandsIT.java
git commit -m "$(cat <<'EOF'
feat(tabinventory): module skeleton + TabStatus whitelist + stats/eligible/users/cohorts/brands reads + createBrand

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Tab reads (`getAllTabs`, `getTabById`, `getTabHistory`, `getTabMovementReport`)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryTabReadsIT.java`

(`TabInventoryReadRepository` already has `allTabs()`, `tabById()`, `tabHistory()`, `movementReport()` from Task 1 Step 5 — this task only wires the controller mappings and tests them.)

- [x] **Step 1: Write the failing test**

Create `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryTabReadsIT.java`:

```java
package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryTabReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (952001,'saAdmin952','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Two cohorts + two batches -- one holder in each, so the tab hands over across cohorts.
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (952001,'CohortA952')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (952002,'CohortB952')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (952001,'Batch A 952',952001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (952002,'Batch B 952',952002)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (95200" + i + ", 2495200000" + i + ")").update();
            jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (95200" + i + ")").update();
        }
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        // student 952001: batch A (cohort A). student 952002: batch B (cohort B). student 952003: NO batch
        // (batch_id NULL) -- used to demonstrate getTabMovementReport's INNER JOIN drop vs getAllTabs' LEFT.
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (952001,952001,95200001,'Holder A 952','F',952001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (952002,952002,95200002,'Holder B 952','M',952002,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn) VALUES (952003,952003,95200003,'No Batch 952','F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (952001,'BrandA952','ModelA952',952001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 952001: hands over from student 952001 (cohort A) to student 952002 (cohort B). Tab is
        // currently ASSIGNED to student 952002 (return_date NULL on the second row).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952001,'SN-TR-952001',952001,'ASSIGNED',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, return_date, created_by) VALUES (952001,952001,'2026-01-01','2026-02-01',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (952001,952002,'2026-02-01',952001)").update();

        // tab 952002: currently ASSIGNED to student 952003 (no batch) -- appears in getAllTabs (LEFT JOIN)
        // but must be ABSENT from getTabMovementReport (INNER JOIN batch/cohort silently drops it).
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952002,'SN-TR-952002',952001,'ASSIGNED',952001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (952002,952003,'2026-02-01',952001)").update();

        // tab 952003: IN_OFFICE, no assignment history at all.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (952003,'SN-TR-952003',952001,'IN_OFFICE',952001)").update();

        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("952001", "saAdmin952", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 952001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (952001,952002,952003)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (952001,952002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (952001,952002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 952001").update();
    }

    @Test
    void allTabsProjectsCaseColumnsAndIncludesNoBatchHolder() throws Exception {
        mvc.perform(get("/api/tabs").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952001')].assigned_to").value("Holder B 952"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952001')].assignment_category").value("STUDENT"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952002')].assigned_to").value("No Batch 952"))
           .andExpect(jsonPath("$.data[?(@.serial_number=='SN-TR-952003')].assigned_to").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void tabByIdReturns200ForExistingAnd404ForMissing() throws Exception {
        mvc.perform(get("/api/tabs/952003").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.serial_number").value("SN-TR-952003"));

        mvc.perform(get("/api/tabs/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void tabHistoryUnionsStudentAndStaffRowsOrderedByAssignmentDateDesc() throws Exception {
        mvc.perform(get("/api/tabs/952001/history").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(2)))
           .andExpect(jsonPath("$.data[0].name").value("Holder B 952"))
           .andExpect(jsonPath("$.data[1].name").value("Holder A 952"));
    }

    @Test
    void movementReportShowsTransferAndDropsNoBatchRow() throws Exception {
        mvc.perform(get("/api/tabs/movement-report").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.data[0].previous_holder").value("Holder A 952"))
           .andExpect(jsonPath("$.data[0].from_cohort").value("CohortA952"))
           .andExpect(jsonPath("$.data[0].new_holder").value("Holder B 952"))
           .andExpect(jsonPath("$.data[0].to_cohort").value("CohortB952"));
    }

    @Test
    void movementReportFiltersByFromAndToCohort() throws Exception {
        mvc.perform(get("/api/tabs/movement-report?fromCohort=CohortA952&toCohort=CohortB952")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)));

        mvc.perform(get("/api/tabs/movement-report?fromCohort=NoSuchCohort&toCohort=ALL")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(0)));
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryTabReadsIT`
Expected: FAIL with 404s (`/api/tabs`, `/api/tabs/{tabId}`, `/api/tabs/{tabId}/history`, `/api/tabs/movement-report` are not yet mapped).

- [x] **Step 3: Add the 4 mappings to `TabInventoryController`**

Modify `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java` — add these methods (anywhere inside the class body, e.g. right after `brands()`):

```java
    @GetMapping("/movement-report")
    public Map<String, Object> movementReport(@RequestParam(required = false) String fromCohort,
                                               @RequestParam(required = false) String toCohort) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.movementReport(fromCohort, toCohort));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("")
    public Map<String, Object> allTabs() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.allTabs());
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @GetMapping("/{tabId}")
    public Map<String, Object> tabById(@PathVariable String tabId) {
        Map<String, Object> row;
        try {
            row = reads.tabById(tabId).orElse(null);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
        if (row == null) throw ApiException.message(404, "Not found").with("success", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", row);
        return out;
    }

    @GetMapping("/{tabId}/history")
    public Map<String, Object> tabHistory(@PathVariable String tabId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("data", reads.tabHistory(tabId));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }
```

Note: `@GetMapping("")` on the class mapped to `/api/tabs` matches the bare collection path; Spring MVC resolves the literal `/stats`, `/eligible-students`, `/users`, `/cohorts`, `/movement-report`, `/brands` paths ahead of the `/{tabId}` pattern automatically (ground truth §1 — no manual ordering needed, unlike Express).

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryTabReadsIT`
Expected: PASS (5 tests).

- [x] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryTabReadsIT.java
git commit -m "$(cat <<'EOF'
feat(tabinventory): getAllTabs, getTabById, getTabHistory, getTabMovementReport reads

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `createTab` + `changeTabStatus` (`@Transactional` + status whitelist)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryCreateAndStatusIT.java`

- [ ] **Step 1: Write the failing test**

Create `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryCreateAndStatusIT.java`:

```java
package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryCreateAndStatusIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (953001,'saAdmin953','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (953001,'Cohort TS953')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (953001,'TS Batch',953001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (953001, 24953000001)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (953001)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (953001,953001,95300001,'Status Student 953','F',953001,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (953001,'BrandA953','ModelA953',953001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 953001: IN_OFFICE, target of the changeTabStatus tests.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (953001,'SN-CS-953001',953001,'IN_OFFICE',953001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("953001", "saAdmin953", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (953001,953002)").update();
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (953001,953002)").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE tab_id IN (953001,953002) OR serial_number LIKE 'SN-CS-%'").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 953001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 953001").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id = 953001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 953001").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 953001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 953001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 953001").update();
    }

    @Test
    void createTabDefaultsStatusToInOffice() throws Exception {
        String body = """
            {"serial_number":"SN-CS-953002","brand_id":953001,"created_by":953001}
            """;
        mvc.perform(post("/api/tabs").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Tablet created"))
           .andExpect(jsonPath("$.data.tab_id").exists());

        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE serial_number = 'SN-CS-953002'")
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("IN_OFFICE");
    }

    @Test
    void createTabMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/tabs").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Required fields missing."));
    }

    @Test
    void changeStatusToAssignedWritesStudentIssueRow() throws Exception {
        String body = """
            {"status":"ASSIGNED","assignment_type":"STUDENT","student_id":953001,"user_id":953001}
            """;
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Status updated successfully"));

        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("ASSIGNED");
        Long openCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 953001 AND student_id = 953001 AND return_date IS NULL")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(openCount).isEqualTo(1L);
    }

    @Test
    void changeStatusInvalidValueIsCleanBadRequestBeforeSql() throws Exception {
        String body = """
            {"status":"NOT_A_REAL_STATUS"}
            """;
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Invalid status: NOT_A_REAL_STATUS"));

        // status column must be untouched -- the invalid value never reached SQL.
        String status = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status.trim()).isEqualTo("IN_OFFICE");
    }

    @Test
    void settingInOfficeDoesNotAutoCloseOpenStudentIssue() throws Exception {
        // First assign, leaving an open student_issue row.
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ASSIGNED\",\"assignment_type\":\"STUDENT\",\"student_id\":953001,\"user_id\":953001}"))
           .andExpect(status().isOk());

        // Then set IN_OFFICE directly (skipping RETURNED) -- ground truth §7 quirk 4: this must NOT close
        // the open student_issue row.
        mvc.perform(put("/api/tabs/953001/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_OFFICE\"}"))
           .andExpect(status().isOk());

        String tabStatus = jdbc.sql("SELECT status FROM pp.tab_inventory WHERE tab_id = 953001").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(tabStatus.trim()).isEqualTo("IN_OFFICE");
        Long stillOpen = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 953001 AND student_id = 953001 AND return_date IS NULL")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(stillOpen).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryCreateAndStatusIT`
Expected: FAIL with 404s (`POST /api/tabs`, `PUT /api/tabs/{tabId}/status` not yet mapped).

- [ ] **Step 3: Add `createTab` + `changeTabStatus` to `TabInventoryWriteRepository`**

Modify `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java` — add imports `com.rcf.imas.modules.tabinventory.TabStatus`, `org.springframework.transaction.annotation.Transactional`, `java.time.LocalDate`, and these methods inside the class (after `createBrand`):

```java
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
    public void changeTabStatus(String tabId, com.rcf.imas.modules.tabinventory.TabStatus status, String remarks,
                                 String assignmentType, String studentId, String officialUserId, String userId,
                                 String transactionDate) {
        String activeTxDate = (transactionDate == null || transactionDate.isBlank())
                ? java.time.LocalDate.now().toString() : transactionDate;

        if (status == com.rcf.imas.modules.tabinventory.TabStatus.RETURNED
                || status == com.rcf.imas.modules.tabinventory.TabStatus.DAMAGED
                || status == com.rcf.imas.modules.tabinventory.TabStatus.LOST) {
            jdbc.sql("UPDATE pp.student_issue SET return_date = :d::date WHERE tab_id = :tabId::numeric AND return_date IS NULL")
                    .param("d", activeTxDate).param("tabId", tabId).update();
            jdbc.sql("UPDATE pp.official_issue SET return_date = :d::date WHERE tab_id = :tabId::numeric AND return_date IS NULL")
                    .param("d", activeTxDate).param("tabId", tabId).update();
        }

        if (status == com.rcf.imas.modules.tabinventory.TabStatus.ASSIGNED) {
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
```

- [ ] **Step 4: Add `createTab` + `changeTabStatus` mappings to `TabInventoryController`**

Modify `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java` — add import `com.rcf.imas.modules.tabinventory.TabStatus`, `org.springframework.dao.DuplicateKeyException` (already imported from Task 1), and these methods (after `createBrand`):

```java
    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTab(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object serialNumber = b.get("serial_number");
        Object brandId = b.get("brand_id");
        Object createdBy = b.get("created_by");
        if (isFalsy(serialNumber) || isFalsy(brandId) || isFalsy(createdBy)) {
            throw ApiException.message(400, "Required fields missing.").with("success", false);
        }
        try {
            Map<String, Object> data = writes.createTab(
                    String.valueOf(serialNumber), strOrNull(b.get("imei")), strOrNull(b.get("inventory_id")),
                    String.valueOf(brandId), strOrNull(b.get("tab_purchase_date")), strOrNull(b.get("remarks")),
                    String.valueOf(createdBy));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Tablet created");
            out.put("data", data);
            return out;
        } catch (DuplicateKeyException e) {
            throw ApiException.message(409, "Serial number already exists in inventory.").with("success", false);
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PutMapping("/{tabId}/status")
    public Map<String, Object> changeStatus(@PathVariable String tabId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String rawStatus = strOrNull(b.get("status"));
        TabStatus status = TabStatus.parse(rawStatus).orElse(null);
        if (status == null) {
            // Node lets an invalid status hit SQL and leaks the raw CHECK-violation text at 400
            // (ground truth §2.4); Java gates it here for a clean 400 instead (Firm Decision 2) -- same
            // status code, same {success:false, message:...} shape, friendlier message text.
            throw ApiException.message(400, "Invalid status: " + rawStatus).with("success", false);
        }
        try {
            writes.changeTabStatus(tabId, status, strOrNull(b.get("remarks")), strOrNull(b.get("assignment_type")),
                    strOrNull(b.get("student_id")), strOrNull(b.get("official_user_id")), strOrNull(b.get("user_id")),
                    strOrNull(b.get("transaction_date")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Status updated successfully");
            return out;
        } catch (Exception e) {
            throw ApiException.message(400, e.getMessage()).with("success", false);
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryCreateAndStatusIT`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/tabinventory imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryCreateAndStatusIT.java
git commit -m "$(cat <<'EOF'
feat(tabinventory): createTab + changeTabStatus (transactional, status enum gate)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `deleteTab` + `bulkCreateTabs` (two-pass `@Transactional`)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryDeleteAndBulkIT.java`

- [ ] **Step 1: Write the failing test**

Create `imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryDeleteAndBulkIT.java`:

```java
package com.rcf.imas.modules.tabinventory;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TabInventoryDeleteAndBulkIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (954001,'saAdmin954','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (954001,'Cohort DB954')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (954001,'DB Batch',954001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (95400" + i + ", 2495400000" + i + ")").update();
            jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (95400" + i + ")").update();
        }
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954001,954001,95400001,'Bulk Student One 954','F',954001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954002,954002,95400002,'Bulk Student Two 954','M',954001,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn) VALUES (954003,954003,95400003,'Bulk Student Three 954','F',954001,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.tab_brand(brand_id, brand_name, model_name, created_by) VALUES (954001,'BrandA954','ModelA954',954001)").update();
        jdbc.sql("SELECT setval('pp.tab_brand_brand_id_seq', (SELECT MAX(brand_id)::bigint FROM pp.tab_brand))").query(Long.class).single();

        // tab 954001: for the deleteTab test, with an open student_issue that must cascade-delete.
        jdbc.sql("INSERT INTO pp.tab_inventory(tab_id, serial_number, brand_id, status, created_by) VALUES (954001,'SN-DB-954001',954001,'ASSIGNED',954001)").update();
        jdbc.sql("INSERT INTO pp.student_issue(tab_id, student_id, assignment_date, created_by) VALUES (954001,954001,CURRENT_DATE,954001)").update();
        jdbc.sql("SELECT setval('pp.tab_id_seq', (SELECT MAX(tab_id)::bigint FROM pp.tab_inventory))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("954001", "saAdmin954", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_issue WHERE tab_id IN (SELECT tab_id FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%')").update();
        jdbc.sql("DELETE FROM pp.official_issue WHERE tab_id IN (SELECT tab_id FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%')").update();
        jdbc.sql("DELETE FROM pp.tab_inventory WHERE serial_number LIKE 'SN-DB-%'").update();
        jdbc.sql("DELETE FROM pp.tab_brand WHERE brand_id = 954001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (954001,954002,954003)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 954001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 954001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 954001").update();
    }

    @Test
    void deleteTabRemovesRowAndCascadesIssueRows() throws Exception {
        mvc.perform(delete("/api/tabs/954001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Deleted"))
           .andExpect(jsonPath("$.data.tab_id").value("954001"));

        Long remaining = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE tab_id = 954001").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(0L);
        Long remainingIssues = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = 954001").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(remainingIssues).isEqualTo(0L);
    }

    @Test
    void deleteTabOnMissingRowStillReturns200WithNullData() throws Exception {
        mvc.perform(delete("/api/tabs/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Deleted"))
           .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void bulkCreateInsertsNewTabAndAssignsStudent() throws Exception {
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-1","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"ASSIGNED","enr_id":"95400002","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(1));

        String tabId = jdbc.sql("SELECT tab_id FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-1'").query(String.class).single();
        Long assignedCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_issue WHERE tab_id = :t::numeric AND student_id = 954002 AND return_date IS NULL")
                .param("t", tabId).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(assignedCount).isEqualTo(1L);
    }

    @Test
    void bulkCreateEmptyDevicesIs400() throws Exception {
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"devices\":[]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Excel is empty"));
    }

    @Test
    void bulkCreateUnknownEnrIdCollectsRowErrorWithNoMessageKeyAndRollsBackWholeBatch() throws Exception {
        // Row 1 is a perfectly valid NEW tab; row 2 references an enr_id that does not exist. PASS 1 must
        // collect the error for row 2 and reject the WHOLE batch (including row 1) -- nothing gets written.
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-2","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"IN_OFFICE","created_by":954001},
              {"rowNumber":3,"serial_number":"SN-DB-BULK-3","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"ASSIGNED","enr_id":"99999999","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").doesNotExist())
           .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.errors[0]", org.hamcrest.Matchers.containsString("99999999")));

        Long rowOneWritten = jdbc.sql("SELECT COUNT(*) FROM pp.tab_inventory WHERE serial_number = 'SN-DB-BULK-2'").query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(rowOneWritten).isEqualTo(0L);
    }

    @Test
    void bulkCreateInvalidStatusIsCollectedAsRowError() throws Exception {
        String body = """
            {"devices":[
              {"rowNumber":2,"serial_number":"SN-DB-BULK-4","brand_name":"BulkBrand954","model_name":"BulkModel954",
               "status":"NOT_A_STATUS","created_by":954001}
            ]}
            """;
        mvc.perform(post("/api/tabs/bulk").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.errors", org.hamcrest.Matchers.hasSize(1)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryDeleteAndBulkIT`
Expected: FAIL with 404s (`DELETE /api/tabs/{tabId}`, `POST /api/tabs/bulk` not yet mapped).

- [ ] **Step 3: Add `deleteTab` + `bulkCreateTabs` to `TabInventoryWriteRepository`**

Modify `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/persistence/TabInventoryWriteRepository.java` — add imports `com.rcf.imas.modules.tabinventory.TabStatus`, `com.rcf.imas.modules.tabinventory.TabStatusNormalizer`, `java.util.ArrayList`, `java.util.HashMap`, `java.util.HashSet`, `java.util.List`, `java.util.Set`, and these members inside the class:

```java
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
                            "Either the Inventory ID or the Serial Number is wrong \u2014 please verify physically and correct your Excel file.");
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
                                "A tablet can only be held by one student at a time \u2014 add a RETURNED row for Student " +
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
```

Also add `import java.time.LocalDate;` to the file's import block.

- [ ] **Step 4: Add `deleteTab` + `bulkCreateTabs` mappings to `TabInventoryController`**

Modify `imas-backend/src/main/java/com/rcf/imas/modules/tabinventory/web/TabInventoryController.java` — add imports `org.springframework.http.ResponseEntity`, `java.util.List`, and these methods (after `changeStatus`):

```java
    @DeleteMapping("/{tabId}")
    public Map<String, Object> deleteTab(@PathVariable String tabId) {
        try {
            String deletedTabId = writes.deleteTab(tabId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Deleted");
            out.put("data", deletedTabId == null ? null : Map.of("tab_id", deletedTabId));
            return out;
        } catch (Exception e) {
            throw ApiException.message(500, e.getMessage()).with("success", false);
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulkCreateTabs(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object devicesObj = b.get("devices");
        if (!(devicesObj instanceof List<?> list) || list.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "Excel is empty");
            return ResponseEntity.status(400).body(err);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) (List<?>) list;
        try {
            var result = writes.bulkCreateTabs(devices);
            if (!result.success()) {
                // Quirk 8: this specific 400 body has NO "message" key, only "errors" -- built by hand
                // (not via ApiException) to guarantee the key is truly absent, not merely null.
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("errors", result.errors());
                return ResponseEntity.status(400).body(err);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("count", result.count());
            return ResponseEntity.status(201).body(out);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage() == null ? "An error occurred during bulk upload" : e.getMessage());
            return ResponseEntity.status(400).body(err);
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryDeleteAndBulkIT`
Expected: PASS (7 tests).

- [ ] **Step 6: Run the full module test suite**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TabInventoryStatsAndBrandsIT,TabInventoryTabReadsIT,TabInventoryCreateAndStatusIT,TabInventoryDeleteAndBulkIT`
Expected: PASS (24 tests total across the 4 classes).

- [ ] **Step 7: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/tabinventory imas-backend/src/test/java/com/rcf/imas/modules/tabinventory/TabInventoryDeleteAndBulkIT.java
git commit -m "$(cat <<'EOF'
feat(tabinventory): deleteTab + bulkCreateTabs (two-pass transactional, status enum gate)

Completes the 14-endpoint tab-inventory module port from Node.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Deferred / Flagged (preserved quirks — do NOT silently fix)

| # | Quirk | Where it's preserved in this plan |
|---|---|---|
| 1 | No auth on any of the 14 Node routes | Task 1 Step 7 — deliberately hardened to `ADMIN`, a documented behavior change (Firm Decision 1), not a parity item |
| 2 | `status` unvalidated by Node app code, DB CHECK is the only gate | Task 1 Step 3 (`TabStatus`), Task 3 Step 4, Task 4 Step 3 — deliberately hardened with an explicit enum (Firm Decision 2), also a documented behavior change (clean 400 vs leaked Postgres text) |
| 3 | `changeTabStatus` can write `status='ASSIGNED'` with no assignment row inserted | Task 3 Step 3 — no extra validation added; the `if`/`else if` on `assignment_type` falls through silently exactly like Node |
| 4 | `status='IN_OFFICE'` does not auto-close open issue rows | Task 3 Step 3; asserted in Task 3's `settingInOfficeDoesNotAutoCloseOpenStudentIssue` test |
| 5 | `deleteTab` returns 200 even when the row doesn't exist | Task 4 Step 4; asserted in Task 4's `deleteTabOnMissingRowStillReturns200WithNullData` test |
| 6 | `getTabStats` swallows the real error, always "Internal Server Error" | Task 1 Step 7 (`stats()`) |
| 7 | `createBrand`'s 409 branch is dead code under the current schema (`ON CONFLICT DO UPDATE` absorbs the exact conflict it targets) | Task 1 Step 7 — kept for parity/future-proofing, never fires under current constraints |
| 8 | `bulkCreateTabs`' row-error 400 body omits the `message` key | Task 4 Step 4 (`bulkCreateTabs`, hand-built `ResponseEntity`) |
| 9 | `bulkCreateTabs`' `count` is the input row count, not rows actually changed | Task 4 Step 3 (`BulkResult(true, devices.size(), ...)`) |
| 10 | `tab_brand.brand_name`/`model_name` are `varchar(15)` — no pre-check, a >15-char value throws a raw Postgres error mid-transaction (can abort a bulk batch partway through pass 2) | Not pre-validated in this plan, matching Node exactly — flagged here for product/plan discussion, not required for parity |
| 11 | `locked_yn` is nullable, no DEFAULT — `getAllUsers`'s exact `= 'N'` comparison silently excludes `NULL` rows via three-valued SQL logic | Task 1 Step 5 (`usersWithoutTab`); asserted in Task 1's `usersExcludesHeldStaffAndNullLockedYn` test |
| 12 | `getAllTabs` uses LEFT JOIN for batch/cohort; `getTabMovementReport` uses INNER JOIN for the identical relationship | Task 1 Step 5 (`allTabs` vs `movementReport`); both asserted in Task 2's tests (`allTabsProjectsCaseColumnsAndIncludesNoBatchHolder` vs `movementReportShowsTransferAndDropsNoBatchRow`) |
| 13 | `getTabMovementReport` builds SQL text conditionally based on `fromCohort`/`toCohort` presence, `"ALL"` sentinel | Task 1 Step 5 (`movementReport`) — named params eliminate the positional-index-shift risk entirely |
| 14 | `STATUS_TYPO_MAP` declared twice in Node (once per pass) | Task 1 Step 4 (`TabStatusNormalizer`) — deduplicated to one shared Java method, a safe simplification |
| 15 | No dedicated tab-history/audit table — history is reconstructed from live `student_issue`/`official_issue` row state | Task 2 Step 3 (`tabHistory`) — same reconstruction, no new table introduced |
| 16 | Numeric id serialization must be field-exact (`numeric`→String, `integer`→Integer, `bigint`→String) | Task 1 Step 5 (`genericRow`), applied uniformly across all 4 tasks |
| 17 | Route ordering (static vs `/{tabId}`) | Task 2 Step 3 — Spring MVC resolves this automatically, noted inline |
