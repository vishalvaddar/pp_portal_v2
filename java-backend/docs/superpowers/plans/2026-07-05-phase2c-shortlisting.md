# IMAS Spring Boot Migration — Plan 2c of 6: Admission — Shortlisting & Medium Filtering

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the two Node shortlisting routers (`/api/shortlist/generate` — 6 endpoints; `/api/shortlist-info` — 10 endpoints) to a new `com.rcf.imas.modules.shortlist` module, preserving exact SQL (the `PERCENT_RANK` block-ranking window query, criteria-prose→threshold parsing, medium/management-type rules), response shapes, and status codes, while adding the locked ADMIN authorization.

**Architecture:** Continues the Phase-1/2a/2b modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `shortlist` with `web/`, `service/`, `persistence/`. All multi-statement transactional writes (start-shortlist, freeze, bulk-update, delete) live in a dedicated `ShortlistWriteRepository` `@Repository` bean (Spring does NOT intercept self-invoked `@Transactional`). Reads live in `ShortlistReadRepository`. XLSX generation isolated in `XlsxSupport` (Apache POI, already a dependency from Plan 2a).

**Tech Stack (no additions over Plan 2a/2b):** Apache POI (`org.apache.poi:poi-ooxml`) for the XLSX download. No new runtime infra, no Docker.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Plans 1 + 2a + 2b are merged and green (115 tests): `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`), `SecurityConfig` (method security), `ApiException`, `GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1/2a/2b — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `WHERE nmms_year = :year::numeric`, `shortlist_batch_id = :id::numeric`, `applicant_id = :aid::numeric`. Jurisdiction-name joins use `LOWER(TRIM(juris_name)) = LOWER(TRIM(:name))` on **text** columns → bind `String`, NO cast. Where Node interpolates or casts inside SQL, port verbatim.
> 3. **Numeric + bigint ids serialize as Strings** via `rs.getBigDecimal(...).toBigInteger().toString()` (integer numerics) / `String.valueOf(rs.getLong(...))` (bigint counts). **Exceptions (Node coerces / computes):** `getShortlistInfo` `parseInt`s `totalStudents`/`shortlistedCount` → JSON **numbers**; `getCounts` returns `totalApplicants`/`totalShortlisted` as JSON **numbers** (parseInt); `start-shortlist` returns `shortlistedCountInBatch` as a **number** but `totalApplicantsCount`/`totalShortlistedInBlocks` as **Strings** (raw `COUNT()`); **`weighted_score` in show-data is a fractional numeric → emit the full decimal via `getBigDecimal(...).toPlainString()`, NOT `toBigInteger()`** (that would truncate `56.50`→`56`). Map keys are literal snake_case.
> 4. **snake_case JSON** global default. Request DTOs read as `Map<String,Object>`; nested `locations` is a `Map`.
> 5. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`. Match each endpoint's exact Node body key (this module mixes both — see the contract table). Do NOT remove the `GlobalExceptionHandler` AccessDenied re-throw.
> 6. **Controllers:** class package-private; every handler method **`public`**.
> 7. **Auth (NEW enforcement — audit CRITICAL):** every `/api/shortlist/**` and `/api/shortlist-info/**` endpoint is `@PreAuthorize("hasRole('ADMIN')")` (class-level on both controllers). Node left these fully open. Record in the fetch audit.
> 8. **Transactions:** multi-statement writes live in `ShortlistWriteRepository` (dedicated `@Repository`, `@Transactional`, not self-invoked).
> 9. **Test isolation:** all `*IT` extend `PgIntegrationTest` (one JVM-wide embedded Postgres). `@AfterEach`-delete exactly what you seed, **children before parents**: `applicant_shortlist_info` → `shortlist_batch_jurisdiction` → `shortlist_batch` → `applicant_primary_info` → `institute_medium` → `institute` → `jurisdiction` → `jurisdiction_type` → `shortlist_criteria` → `"user"`. Seed `jurisdiction_type` before `jurisdiction`. After explicit-PK seeds advance sequences (`setval`). NOTE `applicant_shortlist_info.shortlist_batch_id` FK is `ON DELETE CASCADE`, but delete children explicitly anyway to keep teardown obvious.
> 10. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.

---

## Ground truth used by this plan (verified against Node source + live pg_dump)

Node source read (all live CommonJS — no commented legacy in the live paths, but `shortlistInfoModel.js` DOES contain a large commented-out `bulkUpdateMediumsAndStatus` variant and a commented `getTotalShortlistedCount`; use the LIVE ones at lines 86, 172, 363):
- `server/routes/generateShortlistRoutes.js`, `controllers/generateShortlistController.js`, `models/generateShortlistModel.js`
- `server/routes/shortlistInfoRoutes.js`, `controllers/shortlistInfoController.js`, `models/shortlistInfoModel.js`
- Mounts (`index.js`): `app.use("/api/shortlist/generate", generateShortlistRoutes)`, `app.use("/api/shortlist-info", shortlistInfoRoutes)`.

### Table facts (from `live-schema.sql`)

- **`pp.shortlist_criteria`** — `criteria_id numeric(3,0)` PK (seq `criteria_id_seq`), `criteria varchar(500)` **UNIQUE**, timestamps, created_by/updated_by. Criteria is free text; threshold is parsed by lowercased substring: `top 4%`→0.04, `top 6%`→0.06, `top 8%`→0.08, else error.
- **`pp.shortlist_batch`** — `shortlist_batch_id numeric(6,0)` PK (seq `shortlist_batch_id_seq`), `shortlist_batch_name varchar(100) NOT NULL` **UNIQUE**, `description varchar(200)`, `created_on`, `criteria_id numeric(3,0)` (FK→criteria ON DELETE SET NULL), `frozen_yn char(1) DEFAULT 'N'` CHECK Y/N, `shortlisted_year numeric(4,0) NOT NULL`, `medium_filtered_yn char(1) DEFAULT 'N'` CHECK Y/N.
- **`pp.shortlist_batch_jurisdiction`** — PK `(shortlist_batch_id, juris_code)`; both FK ON DELETE CASCADE (→shortlist_batch, →jurisdiction).
- **`pp.applicant_shortlist_info`** — `shortlist_info_id numeric(14,0)` PK (seq `shortlist_info_seq`), `applicant_id numeric(14,0)` (FK→applicant_primary_info), `shortlisted_yn char(1)` CHECK Y/N, `shortlist_batch_id numeric(6,0)` (FK→shortlist_batch **ON DELETE CASCADE**), timestamps, `created_by/updated_by numeric(8,0)` (FK→"user"). **No unique on applicant_id** (a student can appear in multiple batches).
- **`pp.institute_medium`** — `dise_code varchar(15)`, `medium varchar(10)`. No constraints. A school (dise_code) may have multiple medium rows.
- **`pp.institute`** — has `management_type varchar(50)` (CHECK ∈ GOVERNMENT / PRIVATE AIDED / PRIVATE UNAIDED / OTHERS / NULL), `dise_code varchar(15)` UNIQUE, `institute_name varchar(200)`.
- **`pp.applicant_primary_info`** — `medium varchar(50)`, `current_institute_dise_code varchar(15)`, `gmat_score/sat_score numeric(2,0)`, `nmms_block/district/app_state numeric(12,0)`, `nmms_year numeric(4,0)`.

### The shortlisting algorithm (`createShortlistBatch`) — port SQL verbatim

Per block (loop over `blockNamesToSearch = blocks.map(lower.trim)`), threshold interpolated as a literal (whitelist 0.04/0.06/0.08 — safe):
```sql
WITH ApplicantRanked AS (
  SELECT applicant_id, app_state, district, nmms_block AS block,
         (gmat_score * 0.7 + sat_score * 0.3) AS weighted_score,
         PERCENT_RANK() OVER (
           PARTITION BY nmms_block
           ORDER BY (gmat_score * 0.7 + sat_score * 0.3) DESC, applicant_id ASC
         ) AS percentile_rank
  FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric)
SELECT ar.applicant_id FROM ApplicantRanked ar
JOIN pp.jurisdiction sj ON ar.app_state = sj.juris_code
JOIN pp.jurisdiction dj ON ar.district  = dj.juris_code
JOIN pp.jurisdiction bj ON ar.block     = bj.juris_code
WHERE LOWER(TRIM(sj.juris_name)) = LOWER(TRIM(:state))
  AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM(:district))
  AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM(:block))
  AND ar.percentile_rank <= <THRESHOLD_LITERAL>   -- interpolated 0.04|0.06|0.08, NOT a bind param
ORDER BY ar.weighted_score DESC;
```
`PERCENT_RANK()` = 0 for the top row, `(rank-1)/(N-1)` after; `<= threshold` keeps the top ~4/6/8% of each block's full-year population. Tie-break `weighted_score DESC, applicant_id ASC` is deterministic — preserve verbatim. Collect `applicant_id`s across all blocks (in block-loop order, each block's rows in `weighted_score DESC`), then bulk-insert.

Bulk insert (Node builds `($1,'Y',$2,$3,$4),($5,'Y',$6,$7,$8),…`): in Java, one `@Transactional` method issuing one parameterized INSERT per id (same atomicity; per-block volumes are small):
```sql
INSERT INTO pp.applicant_shortlist_info (applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by)
VALUES (:aid::numeric, 'Y', :batch::numeric, :uid::numeric, :uid::numeric)
```

### The two Node arg-count behaviors (preserve RUNTIME behavior; do NOT port dead code)

1. **`freeze`:** controller calls `autoUpdateSingleMediumStudents(shortlistBatchId, filterMediums)` but the live model fn is `autoUpdateSingleMediumStudents(batchId)` — **`filterMediums` is ignored** in the auto-update step; the reject rules are hard-coded (ENGLISH→GOVERNMENT only; KANNADA/MARATHI→GOVERNMENT or PRIVATE AIDED). `filterMediums` **is** used by `getInvalidMediumStudents(batchId, filterMediums)`. Port both exactly.
2. **`bulk-update-mediums`:** controller destructures **only `{updates, batchId}`** and calls `bulkUpdateMediumsAndStatus(updates, batchId)`; the model's 3rd param `allowedMediums` is `undefined`, so its `if (allowedMediums && allowedMediums.length>0)` Step-2 validation block **never runs at runtime**. Port ONLY Step 1 (per-student `medium` + `shortlisted_yn`) and Step 3 (set `frozen_yn='Y', medium_filtered_yn='Y'`). Do NOT port Step 2 (dead code). Note this in a comment.

### Endpoint contract (16 wired endpoints — `getTotalApplicantsByYear`/`getShortlistedStudentsByBatch` are NOT routed → skip)

**`/api/shortlist/generate/*` (GenerateShortlistController):**

| # | Method + Path | Success (200) | Errors |
|---|---|---|---|
| 1 | GET `/allstates` | `[{juris_code:"<str>", juris_name}]` (`LOWER(juris_type)='state'`) | 500 `{message:"Error fetching states", error:<msg>}` |
| 2 | GET `/divisions/{stateName}` | `[{juris_code, juris_name}]` (divisions under the named state) | 500 `{message:"Error fetching divisions", error}` |
| 3 | GET `/districts/{divisionName}` | `[{juris_code, juris_name}]` (`education district` under division) | 500 `{message:"Error fetching districts", error}` |
| 4 | GET `/blocks/{stateName}/{divisionName}/{districtName}/{year}` | `[{juris_code, juris_name, is_frozen_block:<bool>}]` | 500 `{message:"Error fetching blocks", error}` |
| 5 | GET `/criteria` | `[{criteria_id:"<str>", criteria}]` | 500 `{message:"Error fetching criteria", error}` |
| 6 | POST `/start-shortlist` | `{message, shortlistBatchId:"<str>", shortlistedCountInBatch:<int>, totalApplicantsCount:"<str>", totalShortlistedInBlocks:"<str>"}` | 400 `{error:"Required fields missing."}`; 409 `{error:"Shortlists already exist for these blocks in <year>. Please delete them first."}`; 500 `{error:<msg>}` (incl. unknown-criteria `Criteria "<c>" logic not implemented.`) |

**`/api/shortlist-info/*` (ShortlistInfoController):**

| # | Method + Path | Success (200) | Errors |
|---|---|---|---|
| 7 | GET `/names?year=` | bare array of strings `["batchName",...]` | 500 `{message:"Error fetching shortlist names", error}` |
| 8 | GET `/non-frozen-names?year=` | `[{name, id:"<str>"}]` (`frozen_yn='N'`) | 500 `{message:"Error fetching non-frozen shortlist names", error}` |
| 9 | GET `/counts?year=` | `{totalApplicants:<int>, totalShortlisted:<int>}` (totalShortlisted counts only FROZEN batches' `shortlisted_yn='Y'`) | 500 `{message:"Error fetching counts", error}` |
| 10 | POST `/freeze` | `{message:"Shortlist filtered and frozen successfully"}` | 400 `{message:"Batch ID required"}`; 400 `{message:"Select at least one medium"}`; 400 `{requiresCorrection:true, message:"<n> students require manual medium selection (Multi-medium schools detected).", students:[...]}`; 404 `{message:"Shortlist not found or already frozen"}`; 500 `{message:"Error during freeze process", error}` |
| 11 | DELETE `/delete?year=` | `{message:"Shortlist deleted successfully"}` | 404 `{message:"Shortlist not found"}`; 500 `{message:"Error deleting shortlist", error}` |
| 12 | GET `/show-data/{shortlistName}?year=` | `{name, data:[{applicant_id:"<str>", nmms_reg_number:"<str>", nmms_block:"<str>", student_name, gmat_score:"<str>", sat_score:"<str>", medium, weighted_score:"<decimal-str>"}]}` (ORDER BY student_name ASC) | 404 `{message:"Shortlist not found"}`; 500 `{message:"Error fetching display data", error}` |
| 13 | GET `/download-data/{shortlistName}?year=` | XLSX bytes (sheet "Applicants"; attachment `<name>_Applicants.xlsx`) OR `200 {status:"no_data", message:"No shortlisted students found."}` | 404 `{message:"Shortlist not found"}`; 500 `{message:"Error generating download", error}` |
| 14 | GET `/{shortlistName}?year=` | `{id:"<str>", name, description, criteria, blocks:[...], totalStudents:<int>, shortlistedCount:<int>, isFrozen:"Yes"\|"No"}` | 404 `{message:"Shortlist not found"}`; 500 `{message:"Error fetching shortlist info", error}` |
| 15 | POST `/bulk-update-mediums` | `{message:"Medium decisions updated successfully"}` | 400 `{message:"Missing data"}`; 500 `{message:"Failed to update student data", error}` |
| 16 | POST `/reset-mediums` | `{message:"Medium filtering reset successfully."}` | 400 `{message:"Reset failed. Batch may be frozen."}`; 500 `{message:"Error resetting filtering", error}` |

**Route ordering (`/{shortlistName}` catch-all):** declared LAST in Node, after `/names`, `/non-frozen-names`, `/counts`, `/show-data/*`, `/download-data/*` (and the POST/DELETE literals). Spring maps literal GET paths (`/names` etc.) and deeper paths (`/show-data/{x}`) ahead of GET `/{shortlistName}` by specificity — no ordering hack needed; the IT asserts `/names` is not swallowed.

### Medium/management-type rules (Task 4 — port verbatim)

`autoUpdateSingleMediumStudents(batchId)` = TWO statements:
1. Set `medium` from single-medium schools:
   ```sql
   UPDATE pp.applicant_primary_info api
   SET medium = im.single_med, updated_at = CURRENT_TIMESTAMP
   FROM (SELECT dise_code, MAX(medium) AS single_med FROM pp.institute_medium
         GROUP BY dise_code HAVING COUNT(DISTINCT medium) = 1) im
   WHERE api.current_institute_dise_code = im.dise_code
     AND api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :batch::numeric)
     AND (api.medium IS NULL OR api.medium = '');
   ```
2. Auto-reject (`shortlisted_yn='N'`) students violating management-type rules:
   ```sql
   UPDATE pp.applicant_shortlist_info asi SET shortlisted_yn = 'N'
   FROM pp.applicant_primary_info api
   JOIN pp.institute i ON TRIM(CAST(api.current_institute_dise_code AS TEXT)) = TRIM(CAST(i.dise_code AS TEXT))
   WHERE asi.applicant_id = api.applicant_id AND asi.shortlist_batch_id = :batch::numeric
     AND ( (TRIM(UPPER(api.medium)) = 'ENGLISH' AND TRIM(UPPER(i.management_type)) <> 'GOVERNMENT')
        OR (TRIM(UPPER(api.medium)) = 'KANNADA' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED'))
        OR (TRIM(UPPER(api.medium)) = 'MARATHI' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED')) );
   ```
`getInvalidMediumStudents(batchId, allowedMediums)` — returns conflict rows `{applicant_id, student_name, institute_name, dise_code, contact_no1, contact_no2, selected_medium, supported_mediums:[...]}` for students in multi-medium schools OR whose medium is null/empty/not-in-allowed, EXCLUDING single-medium-school students already set to an allowed medium. Full SQL in Task 4 code. `supported_mediums` is a Postgres `ARRAY_AGG(DISTINCT medium)` → maps to a JSON array of strings.
`freezeShortlist(batchId)` = `UPDATE pp.shortlist_batch SET frozen_yn='Y' WHERE shortlist_batch_id=:id::numeric` → rowCount>0.

### Download (Task 6)

`getShortlistedApplicantsForDownload` SQL selects aliased columns (ORDER BY `"Student Name" ASC`):
`nmms_reg_number AS "NMMS Registration No", student_name AS "Student Name", contact_no1 AS "Contact No 1", cur_inst.institute_name AS "Current School Name", medium AS Medium, d.juris_name AS District, b.juris_name AS Block, gmat_score AS "GMAT Score", sat_score AS "SAT Score"` from `applicant_primary_info` LEFT JOIN institute (dise) + jurisdiction ×2 (district, block), filtered to `applicant_id IN (SELECT applicant_id FROM applicant_shortlist_info WHERE shortlisted_yn='Y' AND shortlist_batch_id=:id)`. Controller prepends `"S. No."` (1-based). Build XLSX with POI: one sheet "Applicants", header row = the column order `["S. No.", "NMMS Registration No", "Student Name", "Contact No 1", "Current School Name", "Medium", "District", "Block", "GMAT Score", "SAT Score"]`, then data rows. Response: `Content-Disposition: attachment; filename="<name>_Applicants.xlsx"`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, body = workbook bytes. **Deliberately DROP the Node local-disk write** (`FILE_STORAGE_PATH/generated-shortlist-data/...`) — it is a server-side side effect the API contract/frontend does not consume; skipping it keeps the port simple and stateless (no-Docker plain-jar deploy). If empty → `200 {status:"no_data", message:"No shortlisted students found."}`.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/shortlist/
├── web/GenerateShortlistController.java     (/api/shortlist/generate — 6 handlers, @PreAuthorize ADMIN)
├── web/ShortlistInfoController.java         (/api/shortlist-info — 10 handlers, @PreAuthorize ADMIN)
├── service/ShortlistService.java            (threshold parse, start-shortlist orchestration + post-commit counts, freeze orchestration, download assembly)
├── service/XlsxSupport.java                 (POI: rows → xlsx bytes)
├── persistence/ShortlistReadRepository.java (all reads + generic + explicit row mappers)
└── persistence/ShortlistWriteRepository.java(@Repository; @Transactional createBatch, freeze steps, bulkUpdate, reset, delete; + DuplicateShortlistException)

imas-backend/src/test/java/com/rcf/imas/modules/shortlist/
├── ShortlistGenerateReadIT.java   (allstates, divisions, districts, blocks, criteria)
├── ShortlistGenerateIT.java       (start-shortlist: ranking golden data, tie-break, threshold boundary, dup 409, missing 400, unknown-criteria 500)
├── ShortlistInfoReadIT.java       (names, non-frozen-names, counts, show-data, detail + catch-all ordering + 404)
├── ShortlistFreezeIT.java         (freeze: single-medium auto-set+reject, multi-medium conflict 400, clean freeze, missing 400)
├── ShortlistMutateIT.java         (bulk-update-mediums, reset-mediums, delete)
└── ShortlistDownloadIT.java       (no_data 200, xlsx bytes, 404)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks.
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN"|"STUDENT")`. Write endpoints' `created_by` FK needs a real `pp."user"` row.
- Bare-array/`{data}`/scalar-map shapes: return `List<...>`/`Map<...>` directly; build `LinkedHashMap` for ordered envelopes.

---

## Task 1: module skeleton + generate-router reads (allstates, divisions, districts, blocks, criteria)

Port the 5 read GETs of `/api/shortlist/generate`. ADMIN-only. Simple queries + a generic row mapper.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/GenerateShortlistController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistGenerateReadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistGenerateReadIT.java`:
```java
package com.rcf.imas.modules.shortlist;

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
class ShortlistGenerateReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        // state 700001 → division 700002 → education district 700003 → block 700004
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700002,'BELGAUM DIV','DIVISION',700001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700003,'BELAGAVI','EDUCATION DISTRICT',700002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700004,'GOKAK','BLOCK',700003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria_id, criteria) VALUES (91,'Top 6% students per block') ON CONFLICT (criteria) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.criteria_id_seq', (SELECT MAX(criteria_id)::bigint FROM pp.shortlist_criteria))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria_id = 91").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (700001,700002,700003,700004)").update();
    }

    @Test
    void allStates() throws Exception {
        mvc.perform(get("/api/shortlist/generate/allstates").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.juris_name=='KARNATAKA')].juris_code").value(org.hamcrest.Matchers.hasItem("700001")));
    }

    @Test
    void divisionsByState() throws Exception {
        mvc.perform(get("/api/shortlist/generate/divisions/KARNATAKA").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("BELGAUM DIV"))
           .andExpect(jsonPath("$[0].juris_code").value("700002"));
    }

    @Test
    void districtsByDivision() throws Exception {
        mvc.perform(get("/api/shortlist/generate/districts/BELGAUM DIV").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("BELAGAVI"));
    }

    @Test
    void blocksByDistrictWithFrozenFlag() throws Exception {
        mvc.perform(get("/api/shortlist/generate/blocks/KARNATAKA/BELGAUM DIV/BELAGAVI/2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("GOKAK"))
           .andExpect(jsonPath("$[0].is_frozen_block").value(false));
    }

    @Test
    void criteriaList() throws Exception {
        mvc.perform(get("/api/shortlist/generate/criteria").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.criteria_id=='91')].criteria").value(org.hamcrest.Matchers.hasItem("Top 6% students per block")));
    }

    @Test
    void generateReadsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/shortlist/generate/allstates").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/shortlist/generate/criteria").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistGenerateReadIT` — Expected: FAIL (no controller).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java`:
```java
package com.rcf.imas.modules.shortlist.persistence;

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

@Repository
public class ShortlistReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public ShortlistReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity for simple lists: integer numerics + bigint → String; timestamp → ISO-Z; boolean/text native.
     *  NOTE: not for fractional numerics (e.g. weighted_score) — those use an explicit toPlainString mapper. */
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
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    public List<Map<String, Object>> allStates() {
        return jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> divisionsByState(String stateName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction AS division
                WHERE division.parent_juris IN (
                    SELECT state.juris_code FROM pp.jurisdiction AS state
                    WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:name)))
                  AND LOWER(division.juris_type) = 'division'
                """).param("name", stateName).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> districtsByDivision(String divisionName) {
        return jdbc.sql("""
                SELECT juris_code, juris_name FROM pp.jurisdiction AS district
                WHERE district.parent_juris IN (
                    SELECT division.juris_code FROM pp.jurisdiction AS division
                    WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:name)))
                  AND LOWER(district.juris_type) = 'education district'
                """).param("name", divisionName).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String stateName, String divisionName, String districtName, String year) {
        return jdbc.sql("""
                SELECT j.juris_code, j.juris_name,
                    CASE WHEN j.juris_code IN (
                        SELECT sbj.juris_code FROM pp.shortlist_batch_jurisdiction AS sbj
                        JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                        WHERE sb.frozen_yn = 'Y' AND sb.shortlisted_year = :year::numeric)
                    THEN TRUE ELSE FALSE END AS is_frozen_block
                FROM pp.jurisdiction AS j
                WHERE LOWER(j.juris_type) = 'block' AND j.parent_juris IN (
                    SELECT district.juris_code FROM pp.jurisdiction AS district
                    WHERE LOWER(TRIM(district.juris_name)) = LOWER(TRIM(:district))
                      AND LOWER(district.juris_type) = 'education district'
                      AND district.parent_juris IN (
                        SELECT division.juris_code FROM pp.jurisdiction AS division
                        WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM(:division))
                          AND LOWER(division.juris_type) = 'division'
                          AND division.parent_juris IN (
                            SELECT state.juris_code FROM pp.jurisdiction AS state
                            WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM(:state))
                              AND LOWER(state.juris_type) = 'state')))
                """).param("state", stateName).param("division", divisionName)
                .param("district", districtName).param("year", year)
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> criteria() {
        return jdbc.sql("SELECT criteria_id, criteria FROM pp.shortlist_criteria")
                .query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/shortlist/web/GenerateShortlistController.java` (read handlers this task; `/start-shortlist` added in Task 2):
```java
package com.rcf.imas.modules.shortlist.web;

import com.rcf.imas.modules.shortlist.persistence.ShortlistReadRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shortlist/generate")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: shortlisting screens were open in Node
class GenerateShortlistController {

    private final ShortlistReadRepository reads;

    GenerateShortlistController(ShortlistReadRepository reads) { this.reads = reads; }

    @GetMapping("/allstates")
    public List<Map<String, Object>> allStates() { return reads.allStates(); }

    @GetMapping("/divisions/{stateName}")
    public List<Map<String, Object>> divisions(@PathVariable String stateName) { return reads.divisionsByState(stateName); }

    @GetMapping("/districts/{divisionName}")
    public List<Map<String, Object>> districts(@PathVariable String divisionName) { return reads.districtsByDivision(divisionName); }

    @GetMapping("/blocks/{stateName}/{divisionName}/{districtName}/{year}")
    public List<Map<String, Object>> blocks(@PathVariable String stateName, @PathVariable String divisionName,
                                            @PathVariable String districtName, @PathVariable String year) {
        return reads.blocksByDistrict(stateName, divisionName, districtName, year);
    }

    @GetMapping("/criteria")
    public List<Map<String, Object>> criteria() { return reads.criteria(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistGenerateReadIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist
git commit -m "feat(shortlist): generate-router reads (states/divisions/districts/blocks/criteria) ADMIN-only

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `/start-shortlist` — the ranking algorithm (transactional)

Port `createShortlistBatch` + `startShortlisting`: criteria prose→threshold, per-block `PERCENT_RANK` ranking (native SQL), dup-check→409, batch insert, results bulk insert; then the two post-commit counts. ADMIN-only.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/service/ShortlistService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java` (add the two counts + criteria lookup)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/GenerateShortlistController.java` (add `/start-shortlist`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistGenerateIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistGenerateIT.java`:
```java
package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistGenerateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;
    long criteriaId;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (710001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (710003,'BELAGAVI','EDUCATION DISTRICT',710001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (710004,'GOKAK','BLOCK',710003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('slseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='slseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "slseed", "ADMIN");
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 8% students per block') ON CONFLICT (criteria) DO NOTHING").update();
        criteriaId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 8% students per block'").query(Long.class).single();

        // 5 applicants in block 710004, year 2025 — weighted = 0.7*gmat + 0.3*sat.
        // ids/scores chosen so ranking is deterministic; top 8% keeps only the single top row (5 rows → only rank 0 <= 0.08).
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block, student_name, father_name, medium, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES
              (610001,2025,24010000001,710001,710003,710004,'A','f','Kannada','9000000001',90,90,:u,:u),
              (610002,2025,24010000002,710001,710003,710004,'B','f','Kannada','9000000002',80,80,:u,:u),
              (610003,2025,24010000003,710001,710003,710004,'C','f','Kannada','9000000003',70,70,:u,:u),
              (610004,2025,24010000004,710001,710003,710004,'D','f','Kannada','9000000004',60,60,:u,:u),
              (610005,2025,24010000005,710001,710003,710004,'E','f','Kannada','9000000005',50,50,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id BETWEEN 610001 AND 610005").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name LIKE 'GenIT%'").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name LIKE 'GenIT%'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id BETWEEN 610001 AND 610005").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 8% students per block'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (710001,710003,710004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='slseed'").update();
    }

    private String body(String name) {
        return """
            {"criteriaId":%d,"name":"%s","description":"d","year":2025,
             "userId":%d,"locations":{"state":"KARNATAKA","district":"BELAGAVI","blocks":["GOKAK"]}}
            """.formatted(criteriaId, name, uid);
    }

    @Test
    void startShortlistRanksTopSliceAndPersistsBatch() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-A")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.shortlistBatchId").isNotEmpty())
           .andExpect(jsonPath("$.shortlistedCountInBatch").value(1))   // 5 rows, top 8% → only PERCENT_RANK 0 (the single top applicant)
           .andExpect(jsonPath("$.totalApplicantsCount").value("5"))    // COUNT() → String
           .andExpect(jsonPath("$.totalShortlistedInBlocks").value("1"));

        Long shortlisted = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE applicant_id = 610001 AND shortlisted_yn='Y'").query(Long.class).single();
        assertThat(shortlisted).isEqualTo(1);   // the top applicant (weighted 90) was chosen
    }

    @Test
    void duplicateNonFrozenBatchForBlockIs409() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-B"))).andExpect(status().isOk());
        // second run over the same block/year while the first is non-frozen → 409
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body("GenIT-C")))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Shortlists already exist for these blocks in 2025. Please delete them first."));
    }

    @Test
    void missingFieldsIs400() throws Exception {
        mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"name\":\"x\",\"year\":2025,\"locations\":{\"state\":\"\",\"district\":\"\",\"blocks\":[]}}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Required fields missing."));
    }

    @Test
    void unknownCriteriaIs500WithMessage() throws Exception {
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 3% weird') ON CONFLICT (criteria) DO NOTHING").update();
        long badId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 3% weird'").query(Long.class).single();
        String b = """
            {"criteriaId":%d,"name":"GenIT-D","description":"d","year":2025,"userId":%d,
             "locations":{"state":"KARNATAKA","district":"BELAGAVI","blocks":["GOKAK"]}}
            """.formatted(badId, uid);
        try {
            mvc.perform(post("/api/shortlist/generate/start-shortlist").header("Authorization", "Bearer " + admin)
                    .contentType(APPLICATION_JSON).content(b))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.error").value("Criteria \"top 3% weird\" logic not implemented."));
        } finally {
            jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name='GenIT-D'").update();
            jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='GenIT-D'").update();
            jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 3% weird'").update();
        }
    }
}
```

> **Ranking math note for the test.** 5 applicants, `PERCENT_RANK` over the block = {0, 0.25, 0.5, 0.75, 1.0}. `<= 0.08` keeps only the rank-0 row (weighted 90 = applicant 610001). So `shortlistedCountInBatch == 1`. This pins the boundary semantics without brittle tie cases. The dup test relies on the first batch being non-frozen (`frozen_yn` defaults 'N').

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistGenerateIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ShortlistReadRepository`:
```java
    public String criteriaText(String criteriaId) {
        return jdbc.sql("SELECT criteria FROM pp.shortlist_criteria WHERE criteria_id = :id::numeric")
                .param("id", criteriaId).query(String.class).optional().orElse(null);
    }

    /** Node totalPopRes: COUNT over the year's applicants in the named blocks (returns String, parity). */
    public String totalApplicantsInBlocks(List<String> blockNamesLower, String year) {
        return jdbc.sql("""
                SELECT COUNT(api.applicant_id) FROM pp.applicant_primary_info api
                WHERE api.nmms_year = :year::numeric AND api.nmms_block IN (
                    SELECT j.juris_code FROM pp.jurisdiction j
                    WHERE LOWER(TRIM(j.juris_name)) = ANY(:blocks) AND LOWER(j.juris_type) = 'block')
                """).param("year", year).param("blocks", blockNamesLower.toArray(new String[0]))
                .query(Long.class).single().toString();
    }

    /** Node getShortlistedCountForBlocksAndYear (returns String, parity). */
    public String shortlistedCountForBlocks(List<String> blockNamesLower, String year) {
        return jdbc.sql("""
                SELECT COUNT(asi.applicant_id) FROM pp.applicant_shortlist_info asi
                WHERE asi.shortlisted_yn = 'Y' AND asi.applicant_id IN (
                    SELECT api.applicant_id FROM pp.applicant_primary_info api
                    WHERE api.nmms_year = :year::numeric AND api.nmms_block IN (
                        SELECT j.juris_code FROM pp.jurisdiction j
                        WHERE LOWER(TRIM(j.juris_name)) = ANY(:blocks) AND LOWER(j.juris_type) = 'block'))
                """).param("year", year).param("blocks", blockNamesLower.toArray(new String[0]))
                .query(Long.class).single().toString();
    }
```
(Add `import java.util.List;` if not present.)

`src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistWriteRepository.java`:
```java
package com.rcf.imas.modules.shortlist.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class ShortlistWriteRepository {

    /** Thrown when a non-frozen batch already covers one of the requested blocks for the year → controller maps to 409. */
    public static class DuplicateShortlistException extends RuntimeException {
        public DuplicateShortlistException(String message) { super(message); }
    }

    private final JdbcClient jdbc;

    public ShortlistWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record BatchResult(String shortlistBatchId, int shortlistedCount) {}

    /**
     * createShortlistBatch parity. thresholdLiteral is one of "0.04"/"0.06"/"0.08" (validated whitelist) — safe to
     * interpolate. blockNamesLower are already lowercased/trimmed.
     */
    @Transactional
    public BatchResult createBatch(String name, String description, String criteriaId,
                                   List<String> blockNamesLower, String state, String district,
                                   String year, String userId, String thresholdLiteral) {
        // 1. duplicate check (non-frozen batch already covering any of these blocks this year)
        Integer dup = jdbc.sql("""
                SELECT 1 FROM pp.shortlist_batch_jurisdiction AS sbj
                JOIN pp.jurisdiction AS block ON sbj.juris_code = block.juris_code
                JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
                WHERE LOWER(TRIM(block.juris_name)) = ANY(:blocks) AND sb.shortlisted_year = :year::numeric AND sb.frozen_yn = 'N'
                LIMIT 1
                """).param("blocks", blockNamesLower.toArray(new String[0])).param("year", year)
                .query(Integer.class).optional().orElse(null);
        if (dup != null) {
            throw new DuplicateShortlistException("Shortlists already exist for these blocks in " + year + ". Please delete them first.");
        }

        // 2. insert batch
        String batchId = jdbc.sql("""
                INSERT INTO pp.shortlist_batch (shortlist_batch_name, description, criteria_id, shortlisted_year)
                VALUES (:name, :desc, :crit::numeric, :year::numeric) RETURNING shortlist_batch_id
                """).param("name", name).param("desc", description).param("crit", criteriaId).param("year", year)
                .query((rs, i) -> rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString()).single();

        // 3. link jurisdictions (blocks by name)
        jdbc.sql("""
                INSERT INTO pp.shortlist_batch_jurisdiction (shortlist_batch_id, juris_code)
                SELECT :batch::numeric, juris_code FROM pp.jurisdiction
                WHERE LOWER(TRIM(juris_name)) = ANY(:blocks) AND LOWER(juris_type) = 'block'
                """).param("batch", batchId).param("blocks", blockNamesLower.toArray(new String[0])).update();

        // 4. rank per block, collect applicant ids (block-loop order, weighted_score DESC within block)
        String rankSql = """
                WITH ApplicantRanked AS (
                    SELECT applicant_id, app_state, district, nmms_block AS block,
                           (gmat_score * 0.7 + sat_score * 0.3) AS weighted_score,
                           PERCENT_RANK() OVER (PARTITION BY nmms_block
                               ORDER BY (gmat_score * 0.7 + sat_score * 0.3) DESC, applicant_id ASC) AS percentile_rank
                    FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric)
                SELECT ar.applicant_id FROM ApplicantRanked ar
                JOIN pp.jurisdiction sj ON ar.app_state = sj.juris_code
                JOIN pp.jurisdiction dj ON ar.district = dj.juris_code
                JOIN pp.jurisdiction bj ON ar.block = bj.juris_code
                WHERE LOWER(TRIM(sj.juris_name)) = LOWER(TRIM(:state))
                  AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM(:district))
                  AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM(:block))
                  AND ar.percentile_rank <= """ + thresholdLiteral + """
                
                ORDER BY ar.weighted_score DESC
                """;
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String block : blockNamesLower) {
            ids.addAll(jdbc.sql(rankSql).param("year", year).param("state", state)
                    .param("district", district).param("block", block)
                    .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString()).list());
        }

        // 5. bulk insert results
        for (String id : ids) {
            jdbc.sql("""
                    INSERT INTO pp.applicant_shortlist_info (applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by)
                    VALUES (:aid::numeric, 'Y', :batch::numeric, :uid::numeric, :uid::numeric)
                    """).param("aid", id).param("batch", batchId).param("uid", userId).update();
        }
        return new BatchResult(batchId, ids.size());
    }
}
```

> **Note on `thresholdLiteral` interpolation.** The value is chosen by the service from the whitelist `{"0.04","0.06","0.08"}` only — never from user input — so string-concatenating it into the SQL is safe and keeps `PERCENT_RANK` semantics identical to Node (which also interpolates). The trailing blank line before `ORDER BY` in the text block is intentional (keeps a space after the literal).

`src/main/java/com/rcf/imas/modules/shortlist/service/ShortlistService.java` (start-shortlist orchestration this task; freeze/download added in Tasks 4/6):
```java
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
```

Add `/start-shortlist` to `GenerateShortlistController` (inject `ShortlistService service`):
```java
    // add: private final ShortlistService service; (thread through constructor)

    @PostMapping("/start-shortlist")
    public Map<String, Object> startShortlist(@RequestBody Map<String, Object> body) {
        return service.startShortlisting(body);
    }
```

> **Parity note (error mapping).** Node's generic catch returns `500 {error:<message>}` for anything except the duplicate case (which it detects by `error.message.includes("already exist")` → 409). Here the service throws typed `ApiException` (400 for missing fields, 409 for `DuplicateShortlistException`, 500 for unknown criteria carrying the exact message). Any *unexpected* runtime error falls through to `GlobalExceptionHandler` → generic `500 {error:"Internal Server Error"}`; that differs from Node's echo of `error.message`, but only for truly unexpected failures (never on the tested paths). Acceptable and safer; note it.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistGenerateIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistGenerateIT.java
git commit -m "feat(shortlist): start-shortlist ranking algorithm (PERCENT_RANK per block, dup 409, counts)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: shortlist-info reads — names, non-frozen-names, counts, show-data, detail

Port the 5 read endpoints of `/api/shortlist-info` (`getShortlistInfo` powers both detail and show-data). New controller. ADMIN-only. Assert the `/{shortlistName}` catch-all doesn't shadow `/names`.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/ShortlistInfoController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistInfoReadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistInfoReadIT.java`:
```java
package com.rcf.imas.modules.shortlist;

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
class ShortlistInfoReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid, batchId, criteriaId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (720004,'GOKAK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('siseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='siseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% info') ON CONFLICT (criteria) DO NOTHING").update();
        criteriaId = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% info'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, description, criteria_id, shortlisted_year, frozen_yn) VALUES ('InfoIT-Batch','desc',:c,2025,'Y')").param("c", criteriaId).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='InfoIT-Batch'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch_jurisdiction(shortlist_batch_id, juris_code) VALUES (:b, 720004)").param("b", batchId).update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, medium, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (620001,2025,24010000021,720004,720004,'Asha',' f','Kannada','9000000001',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (620001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 620001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction sbj USING pp.shortlist_batch sb WHERE sbj.shortlist_batch_id=sb.shortlist_batch_id AND sb.shortlist_batch_name='InfoIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='InfoIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 620001").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% info'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 720004").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='siseed'").update();
    }

    @Test
    void namesForYear() throws Exception {
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasItem("InfoIT-Batch")));
    }

    @Test
    void nonFrozenNamesExcludesFrozen() throws Exception {
        // our only batch is frozen → not present
        mvc.perform(get("/api/shortlist-info/non-frozen-names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.name=='InfoIT-Batch')]").isEmpty());
    }

    @Test
    void countsForYear() throws Exception {
        mvc.perform(get("/api/shortlist-info/counts?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalApplicants").value(1))
           .andExpect(jsonPath("$.totalShortlisted").value(1));   // frozen batch, shortlisted_yn=Y
    }

    @Test
    void detailByName() throws Exception {
        mvc.perform(get("/api/shortlist-info/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("InfoIT-Batch"))
           .andExpect(jsonPath("$.criteria").value("Top 6% info"))
           .andExpect(jsonPath("$.blocks[0]").value("GOKAK"))
           .andExpect(jsonPath("$.totalStudents").value(1))
           .andExpect(jsonPath("$.shortlistedCount").value(1))
           .andExpect(jsonPath("$.isFrozen").value("Yes"));
    }

    @Test
    void detailMissingIs404() throws Exception {
        mvc.perform(get("/api/shortlist-info/NoSuchBatch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }

    @Test
    void showDataHasWeightedScoreDecimal() throws Exception {
        mvc.perform(get("/api/shortlist-info/show-data/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("InfoIT-Batch"))
           .andExpect(jsonPath("$.data[0].student_name").value("Asha"))
           .andExpect(jsonPath("$.data[0].nmms_reg_number").value("24010000021"))
           // weighted = 55*0.70 + 60*0.30 = 38.50 + 18.00 = 56.50 — must NOT truncate to "56"
           .andExpect(jsonPath("$.data[0].weighted_score").value("56.50"));
    }

    @Test
    void catchAllDoesNotSwallowNames() throws Exception {
        // GET /names must hit the names handler, not GET /{shortlistName} with name="names"
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$").isArray());
    }

    @Test
    void infoReadsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/shortlist-info/names?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/shortlist-info/InfoIT-Batch?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

> **Note on `weighted_score` scale.** PG computes `55*0.70 + 60*0.30` as `numeric` scale 2 → `56.50`. `getBigDecimal(...).toPlainString()` yields `"56.50"`, matching node-pg. If your DB returns a different scale, adjust the assertion to the actual PG output — but do NOT use `toBigInteger()` (that truncates the decimal).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistInfoReadIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ShortlistReadRepository`:
```java
    public List<String> shortlistNames(String year) {
        return jdbc.sql("SELECT shortlist_batch_name FROM pp.shortlist_batch WHERE shortlisted_year = :year::numeric")
                .param("year", year).query(String.class).list();
    }

    public List<Map<String, Object>> nonFrozenNames(String year) {
        return jdbc.sql("SELECT shortlist_batch_name, shortlist_batch_id FROM pp.shortlist_batch WHERE shortlisted_year = :year::numeric AND frozen_yn = 'N'")
                .param("year", year).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("shortlist_batch_name"));
                    m.put("id", rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString());
                    return m;
                }).list();
    }

    public int totalApplicantCount(String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric")
                .param("year", year).query(Integer.class).single();
    }

    public int totalShortlistedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info asi
                JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                JOIN pp.shortlist_batch sb ON asi.shortlist_batch_id = sb.shortlist_batch_id
                WHERE api.nmms_year = :year::numeric AND asi.shortlisted_yn = 'Y' AND sb.frozen_yn = 'Y'
                """).param("year", year).query(Integer.class).single();
    }

    /** getShortlistInfo parity. Returns null if the batch name+year doesn't exist. id kept as String (node-pg numeric). */
    public Map<String, Object> shortlistInfo(String name, String year) {
        Map<String, Object> head = jdbc.sql("""
                SELECT shortlist_batch_id, description, criteria_id, shortlist_batch_name, frozen_yn
                FROM pp.shortlist_batch WHERE shortlist_batch_name = :name AND shortlisted_year = :year::numeric
                """).param("name", name).param("year", year).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getBigDecimal("shortlist_batch_id").toBigInteger().toString());
                    m.put("description", rs.getString("description"));
                    m.put("criteria_id", rs.getBigDecimal("criteria_id") == null ? null : rs.getBigDecimal("criteria_id").toBigInteger().toString());
                    m.put("name", rs.getString("shortlist_batch_name"));
                    m.put("frozen_yn", rs.getString("frozen_yn"));
                    return m;
                }).optional().orElse(null);
        if (head == null) return null;

        String id = (String) head.get("id");
        String criteriaId = (String) head.get("criteria_id");
        String criteria = criteriaId == null ? "N/A" :
                jdbc.sql("SELECT criteria FROM pp.shortlist_criteria WHERE criteria_id = :id::numeric")
                        .param("id", criteriaId).query(String.class).optional().orElse("N/A");
        List<String> blocks = jdbc.sql("""
                SELECT j.juris_name FROM pp.jurisdiction j
                JOIN pp.shortlist_batch_jurisdiction sbj ON j.juris_code = sbj.juris_code
                WHERE sbj.shortlist_batch_id = :id::numeric
                """).param("id", id).query(String.class).list();
        int totalStudents = jdbc.sql("""
                SELECT COUNT(*) FROM pp.applicant_primary_info
                WHERE nmms_year = :year::numeric AND nmms_block IN (SELECT juris_code FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = :id::numeric)
                """).param("year", year).param("id", id).query(Integer.class).single();
        int shortlistedCount = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric")
                .param("id", id).query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", head.get("name"));
        out.put("description", head.get("description"));
        out.put("criteria", criteria);
        out.put("blocks", blocks);
        out.put("totalStudents", totalStudents);
        out.put("shortlistedCount", shortlistedCount);
        out.put("isFrozen", "Y".equals(head.get("frozen_yn")) ? "Yes" : "No");
        return out;
    }

    public List<Map<String, Object>> showData(String batchId) {
        return jdbc.sql("""
                SELECT api.applicant_id, api.nmms_reg_number, api.nmms_block, api.student_name,
                       api.gmat_score, api.sat_score, api.medium,
                       (api.gmat_score * 0.70 + api.sat_score * 0.30) AS weighted_score
                FROM pp.applicant_primary_info api
                WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric)
                ORDER BY api.student_name ASC
                """).param("id", batchId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("applicant_id", numStr(rs.getBigDecimal("applicant_id")));
                    m.put("nmms_reg_number", numStr(rs.getBigDecimal("nmms_reg_number")));
                    m.put("nmms_block", numStr(rs.getBigDecimal("nmms_block")));
                    m.put("student_name", rs.getString("student_name"));
                    m.put("gmat_score", numStr(rs.getBigDecimal("gmat_score")));
                    m.put("sat_score", numStr(rs.getBigDecimal("sat_score")));
                    m.put("medium", rs.getString("medium"));
                    java.math.BigDecimal ws = rs.getBigDecimal("weighted_score");
                    m.put("weighted_score", ws == null ? null : ws.toPlainString());   // decimal preserved
                    return m;
                }).list();
    }

    private static String numStr(java.math.BigDecimal bd) { return bd == null ? null : bd.toBigInteger().toString(); }
```

`src/main/java/com/rcf/imas/modules/shortlist/web/ShortlistInfoController.java` (read handlers this task; write/download added Tasks 4–6):
```java
package com.rcf.imas.modules.shortlist.web;

import com.rcf.imas.modules.shortlist.persistence.ShortlistReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shortlist-info")
@PreAuthorize("hasRole('ADMIN')")
class ShortlistInfoController {

    private final ShortlistReadRepository reads;

    ShortlistInfoController(ShortlistReadRepository reads) { this.reads = reads; }

    @GetMapping("/names")
    public List<String> names(@RequestParam(required = false) String year) { return reads.shortlistNames(year); }

    @GetMapping("/non-frozen-names")
    public List<Map<String, Object>> nonFrozenNames(@RequestParam(required = false) String year) { return reads.nonFrozenNames(year); }

    @GetMapping("/counts")
    public Map<String, Object> counts(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalApplicants", reads.totalApplicantCount(year));
        m.put("totalShortlisted", reads.totalShortlistedCount(year));
        return m;
    }

    @GetMapping("/show-data/{shortlistName}")
    public Map<String, Object> showData(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", info.get("name"));
        out.put("data", reads.showData((String) info.get("id")));
        return out;
    }

    @GetMapping("/{shortlistName}")
    public Map<String, Object> detail(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        return info;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistInfoReadIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistInfoReadIT.java
git commit -m "feat(shortlist): shortlist-info reads (names, counts, show-data, detail) + catch-all ordering

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: `/freeze` — medium auto-update + conflict detection + freeze (transactional)

Port `freezeShortlist`: STEP 1 auto-set single-medium + auto-reject by management-type; STEP 2 detect multi-medium conflicts; STEP 3 freeze or return `requiresCorrection`. ADMIN-only.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistWriteRepository.java` (freeze steps)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java` (`getInvalidMediumStudents`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/ShortlistInfoController.java` (`/freeze`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistFreezeIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistFreezeIT.java`:
```java
package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistFreezeIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('fzseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='fzseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% freeze') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% freeze'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn) VALUES ('FreezeIT-Batch',:c,2025,'N')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='FreezeIT-Batch'").query(Long.class).single();

        // single-medium GOVERNMENT school 'ss1' (Kannada) → auto-set + kept; multi-medium school 'ms1' → conflict
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('SS100000000001','SingleGov','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('MS100000000001','MultiMed','PRIVATE UNAIDED') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute_medium(dise_code, medium) VALUES ('SS100000000001','KANNADA')").update();
        jdbc.sql("INSERT INTO pp.institute_medium(dise_code, medium) VALUES ('MS100000000001','ENGLISH'),('MS100000000001','KANNADA')").update();

        // applicant 630001 at single-medium school (medium NULL → will be auto-set to KANNADA, GOVERNMENT → kept)
        // applicant 630002 at multi-medium school (medium NULL) → conflict
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES
              (630001,2025,24010000031,'SingleStu','f','9000000001','SS100000000001',55,60,:u,:u),
              (630002,2025,24010000032,'MultiStu','f','9000000002','MS100000000001',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (630001,'Y',:b,:u,:u),(630002,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id IN (630001,630002)").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='FreezeIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (630001,630002)").update();
        jdbc.sql("DELETE FROM pp.institute_medium WHERE dise_code IN ('SS100000000001','MS100000000001')").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code IN ('SS100000000001','MS100000000001')").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% freeze'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='fzseed'").update();
    }

    @Test
    void freezeWithMultiMediumConflictReturns400RequiresCorrection() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.requiresCorrection").value(true))
           .andExpect(jsonPath("$.students[?(@.applicant_id=='630002')]").exists())
           .andExpect(jsonPath("$.students[?(@.applicant_id=='630002')].supported_mediums").exists());

        // batch stays non-frozen; single-medium student got auto-set to KANNADA
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(frozen).isEqualTo("N");
        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=630001").query(String.class).single();
        assertThat(med).isEqualTo("KANNADA");
    }

    @Test
    void freezeSucceedsWhenNoConflicts() throws Exception {
        // remove the multi-medium student so no conflicts remain
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 630002").update();
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Shortlist filtered and frozen successfully"));
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(frozen).isEqualTo("Y");
    }

    @Test
    void freezeMissingBatchIdIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"filterMediums\":[\"KANNADA\"]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Batch ID required"));
    }

    @Test
    void freezeMissingMediumsIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/freeze").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + ",\"filterMediums\":[]}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Select at least one medium"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistFreezeIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ShortlistReadRepository` (`getInvalidMediumStudents`):
```java
    public List<Map<String, Object>> invalidMediumStudents(String batchId, List<String> allowedMediums) {
        return jdbc.sql("""
                SELECT api.applicant_id, api.student_name, inst.institute_name, inst.dise_code,
                       api.contact_no1, api.contact_no2, api.medium AS selected_medium,
                       (SELECT ARRAY_AGG(DISTINCT m.medium) FROM pp.institute_medium m WHERE m.dise_code = inst.dise_code) AS supported_mediums
                FROM pp.applicant_primary_info api
                JOIN pp.applicant_shortlist_info asi ON api.applicant_id = asi.applicant_id
                JOIN pp.institute inst ON api.current_institute_dise_code = inst.dise_code
                WHERE asi.shortlist_batch_id = :batch::numeric
                  AND ( (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) > 1
                        OR (api.medium IS NULL OR api.medium = '' OR api.medium != ANY(:allowed)) )
                  AND NOT ( (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) = 1
                            AND api.medium = ANY(:allowed) )
                GROUP BY api.applicant_id, api.student_name, inst.institute_name, inst.dise_code, api.contact_no1, api.contact_no2, api.medium
                ORDER BY inst.institute_name
                """).param("batch", batchId).param("allowed", allowedMediums.toArray(new String[0]))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("applicant_id", rs.getBigDecimal("applicant_id").toBigInteger().toString());
                    m.put("student_name", rs.getString("student_name"));
                    m.put("institute_name", rs.getString("institute_name"));
                    m.put("dise_code", rs.getString("dise_code"));
                    m.put("contact_no1", rs.getString("contact_no1"));
                    m.put("contact_no2", rs.getString("contact_no2"));
                    m.put("selected_medium", rs.getString("selected_medium"));
                    java.sql.Array arr = rs.getArray("supported_mediums");
                    m.put("supported_mediums", arr == null ? null : java.util.Arrays.asList((Object[]) arr.getArray()));
                    return m;
                }).list();
    }
```

Add to `ShortlistWriteRepository` (freeze steps):
```java
    /** autoUpdateSingleMediumStudents(batchId) — NOTE the Node controller passes filterMediums but the model ignores it. */
    @Transactional
    public void autoUpdateSingleMediumStudents(String batchId) {
        // 1. set medium from schools that have exactly one distinct medium, where the student's medium is null/empty
        jdbc.sql("""
                UPDATE pp.applicant_primary_info api
                SET medium = im.single_med, updated_at = CURRENT_TIMESTAMP
                FROM (SELECT dise_code, MAX(medium) AS single_med FROM pp.institute_medium
                      GROUP BY dise_code HAVING COUNT(DISTINCT medium) = 1) im
                WHERE api.current_institute_dise_code = im.dise_code
                  AND api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :batch::numeric)
                  AND (api.medium IS NULL OR api.medium = '')
                """).param("batch", batchId).update();

        // 2. auto-reject by management-type rules (hard-coded, NOT driven by filterMediums)
        jdbc.sql("""
                UPDATE pp.applicant_shortlist_info asi SET shortlisted_yn = 'N'
                FROM pp.applicant_primary_info api
                JOIN pp.institute i ON TRIM(CAST(api.current_institute_dise_code AS TEXT)) = TRIM(CAST(i.dise_code AS TEXT))
                WHERE asi.applicant_id = api.applicant_id AND asi.shortlist_batch_id = :batch::numeric
                  AND ( (TRIM(UPPER(api.medium)) = 'ENGLISH' AND TRIM(UPPER(i.management_type)) <> 'GOVERNMENT')
                     OR (TRIM(UPPER(api.medium)) = 'KANNADA' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED'))
                     OR (TRIM(UPPER(api.medium)) = 'MARATHI' AND TRIM(UPPER(i.management_type)) NOT IN ('GOVERNMENT','PRIVATE AIDED')) )
                """).param("batch", batchId).update();
    }

    /** freezeShortlist(batchId) → true if a row was updated. */
    public boolean freezeShortlist(String batchId) {
        return jdbc.sql("UPDATE pp.shortlist_batch SET frozen_yn = 'Y' WHERE shortlist_batch_id = :id::numeric")
                .param("id", batchId).update() > 0;
    }
```

Add `/freeze` to `ShortlistInfoController` (inject `ShortlistWriteRepository writes`; keep `reads`):
```java
    // add: private final ShortlistWriteRepository writes; (thread through constructor)

    @PostMapping("/freeze")
    public Object freeze(@RequestBody Map<String, Object> body) {
        Object batchIdRaw = body.get("shortlistBatchId");
        if (batchIdRaw == null || String.valueOf(batchIdRaw).isBlank())
            throw ApiException.message(400, "Batch ID required");
        @SuppressWarnings("unchecked")
        List<Object> filterMediums = body.get("filterMediums") instanceof List<?> l ? (List<Object>) l : List.of();
        if (filterMediums.isEmpty()) throw ApiException.message(400, "Select at least one medium");

        String batchId = String.valueOf(batchIdRaw);
        List<String> allowed = filterMediums.stream().map(String::valueOf).toList();

        writes.autoUpdateSingleMediumStudents(batchId);
        List<Map<String, Object>> invalid = reads.invalidMediumStudents(batchId, allowed);
        if (!invalid.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requiresCorrection", true);
            out.put("message", invalid.size() + " students require manual medium selection (Multi-medium schools detected).");
            out.put("students", invalid);
            // 400 body carries no error/message-key envelope — return a ResponseEntity directly
            return org.springframework.http.ResponseEntity.status(400).body(out);
        }
        if (writes.freezeShortlist(batchId)) {
            return Map.of("message", "Shortlist filtered and frozen successfully");
        }
        return org.springframework.http.ResponseEntity.status(404).body(Map.of("message", "Shortlist not found or already frozen"));
    }
```

> **Parity note (freeze bodies).** The `requiresCorrection` 400 and the `Shortlist not found or already frozen` 404 are plain `{...}` bodies (the first has NO `error`/`message`-only envelope — it carries `requiresCorrection`+`message`+`students`). Returning `ResponseEntity` directly (mixed return type `Object`) is the simplest faithful path; the two 400 validation errors keep the `{message:...}` shape via `ApiException.message`. `filterMediums` is passed only to `invalidMediumStudents` (conflict detection) — NOT to `autoUpdateSingleMediumStudents`, matching the Node arg-count behavior exactly.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistFreezeIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistFreezeIT.java
git commit -m "feat(shortlist): freeze flow (single-medium auto-set + reject rules, conflict detection)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: `/bulk-update-mediums`, `/reset-mediums`, `/delete`

Port the three remaining writes. `bulk-update-mediums` reproduces RUNTIME behavior (Step 1 + Step 3 only; `allowedMediums` dropped by the Node controller — Step 2 dead code, not ported). ADMIN-only.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/ShortlistInfoController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistMutateIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistMutateIT.java`:
```java
package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistMutateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mtseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mtseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% mutate') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% mutate'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn, medium_filtered_yn) VALUES ('MutateIT-Batch',:c,2025,'N','N')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='MutateIT-Batch'").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, contact_no1, medium, gmat_score, sat_score, created_by, updated_by)
            VALUES (640001,2025,24010000041,'Stu','f','9000000001',NULL,55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (640001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 640001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='MutateIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 640001").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% mutate'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='mtseed'").update();
    }

    @Test
    void bulkUpdateAppliesMediumStatusAndFreezes() throws Exception {
        String body = """
            {"batchId":%d,"updates":[{"applicant_id":640001,"selected_medium":"KANNADA","status":"Y"}]}
            """.formatted(batchId);
        mvc.perform(post("/api/shortlist-info/bulk-update-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Medium decisions updated successfully"));

        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=640001").query(String.class).single();
        assertThat(med).isEqualTo("KANNADA");
        String flags = jdbc.sql("SELECT frozen_yn || medium_filtered_yn FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(String.class).single();
        assertThat(flags).isEqualTo("YY");   // Step 3 sets both flags
    }

    @Test
    void bulkUpdateMissingDataIs400() throws Exception {
        mvc.perform(post("/api/shortlist-info/bulk-update-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"batchId\":" + batchId + "}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Missing data"));
    }

    @Test
    void resetMediumsNullsMediumWhenNotMediumFiltered() throws Exception {
        jdbc.sql("UPDATE pp.applicant_primary_info SET medium='KANNADA' WHERE applicant_id=640001").update();
        mvc.perform(post("/api/shortlist-info/reset-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Medium filtering reset successfully."));
        // null-safe read: query(String.class).optional() collapses a SQL NULL to Optional.empty(),
        // so use a row mapper + list().get(0) to distinguish "row exists, value NULL" from "no row".
        String med = jdbc.sql("SELECT medium FROM pp.applicant_primary_info WHERE applicant_id=640001").query((rs, i) -> rs.getString(1)).list().get(0);
        assertThat(med).isNull();
    }

    @Test
    void resetMediumsFailsWhenMediumFiltered() throws Exception {
        jdbc.sql("UPDATE pp.shortlist_batch SET medium_filtered_yn='Y' WHERE shortlist_batch_id=:b").param("b", batchId).update();
        mvc.perform(post("/api/shortlist-info/reset-mediums").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Reset failed. Batch may be frozen."));
    }

    @Test
    void deleteBatchCascadesAndReturnsMessage() throws Exception {
        mvc.perform(delete("/api/shortlist-info/delete?year=2025").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":" + batchId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Shortlist deleted successfully"));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.shortlist_batch WHERE shortlist_batch_id=:b").param("b", batchId).query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void deleteMissingBatchIs404() throws Exception {
        mvc.perform(delete("/api/shortlist-info/delete?year=2025").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"shortlistBatchId\":99999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistMutateIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ShortlistWriteRepository`:
```java
    /**
     * bulkUpdateMediumsAndStatus RUNTIME parity: the Node controller drops `allowedMediums`, so the model's Step-2
     * validation never runs. Port only Step 1 (per-student medium + status) and Step 3 (set flags).
     */
    @Transactional
    public void bulkUpdateMediumsAndStatus(List<Map<String, Object>> updates, String batchId) {
        for (Map<String, Object> s : updates) {
            String applicantId = String.valueOf(s.get("applicant_id"));
            jdbc.sql("UPDATE pp.applicant_primary_info SET medium = :med WHERE applicant_id = :aid::numeric")
                .param("med", s.get("selected_medium")).param("aid", applicantId).update();
            jdbc.sql("UPDATE pp.applicant_shortlist_info SET shortlisted_yn = :st WHERE applicant_id = :aid::numeric AND shortlist_batch_id = :batch::numeric")
                .param("st", s.get("status")).param("aid", applicantId).param("batch", batchId).update();
        }
        jdbc.sql("UPDATE pp.shortlist_batch SET frozen_yn = 'Y', medium_filtered_yn = 'Y' WHERE shortlist_batch_id = :batch::numeric")
            .param("batch", batchId).update();
    }

    /** resetMediumFiltering: null medium for the batch's applicants only when the batch is NOT medium_filtered. */
    public boolean resetMediumFiltering(String batchId) {
        return jdbc.sql("""
                UPDATE pp.applicant_primary_info SET medium = NULL
                WHERE applicant_id IN (
                    SELECT asi.applicant_id FROM pp.applicant_shortlist_info asi, pp.shortlist_batch sb
                    WHERE asi.shortlist_batch_id = :id::numeric AND asi.shortlist_batch_id = sb.shortlist_batch_id
                      AND sb.medium_filtered_yn = 'N')
                """).param("id", batchId).update() > 0;
    }

    /** deleteShortlist: three sequential deletes (info → jurisdiction → batch) wrapped transactionally. batch rowCount>0 → true. */
    @Transactional
    public boolean deleteShortlist(String batchId) {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update();
        jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update();
        return jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = :id::numeric").param("id", batchId).update() > 0;
    }
```
(`ShortlistWriteRepository` needs `import java.util.Map;`.)

Add handlers to `ShortlistInfoController`:
```java
    @PostMapping("/bulk-update-mediums")
    @SuppressWarnings("unchecked")
    public Map<String, Object> bulkUpdateMediums(@RequestBody Map<String, Object> body) {
        Object updatesRaw = body.get("updates");
        Object batchIdRaw = body.get("batchId");
        if (!(updatesRaw instanceof List<?>) || batchIdRaw == null || String.valueOf(batchIdRaw).isBlank())
            throw ApiException.message(400, "Missing data");
        List<Map<String, Object>> updates = (List<Map<String, Object>>) updatesRaw;
        writes.bulkUpdateMediumsAndStatus(updates, String.valueOf(batchIdRaw));
        return Map.of("message", "Medium decisions updated successfully");
    }

    @PostMapping("/reset-mediums")
    public Object resetMediums(@RequestBody Map<String, Object> body) {
        String batchId = body.get("shortlistBatchId") == null ? null : String.valueOf(body.get("shortlistBatchId"));
        if (batchId != null && writes.resetMediumFiltering(batchId)) {
            return Map.of("message", "Medium filtering reset successfully.");
        }
        return org.springframework.http.ResponseEntity.status(400).body(Map.of("message", "Reset failed. Batch may be frozen."));
    }

    @DeleteMapping("/delete")
    public Object deleteShortlist(@RequestBody Map<String, Object> body,
                                  @RequestParam(required = false) String year) {
        String batchId = body.get("shortlistBatchId") == null ? null : String.valueOf(body.get("shortlistBatchId"));
        if (batchId != null && writes.deleteShortlist(batchId)) {
            return Map.of("message", "Shortlist deleted successfully");
        }
        return org.springframework.http.ResponseEntity.status(404).body(Map.of("message", "Shortlist not found"));
    }
```

> **Parity notes.** `bulk-update-mediums`: `updates[]` elements are `{applicant_id, selected_medium, status}`; only Step 1 + Step 3 run (Node dropped `allowedMediums` → its Step 2 is dead — intentionally not ported). `reset-mediums`/`delete` return `{message:...}` bodies with success/failure status codes; returning `ResponseEntity` keeps the exact body (no `error`/`message`-envelope wrapper). `delete` reads `year` from the query but Node never uses it in the model — accept and ignore it.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistMutateIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistMutateIT.java
git commit -m "feat(shortlist): bulk-update-mediums (Step1+3 runtime parity), reset-mediums, delete

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: `/download-data` — XLSX export (Apache POI)

Port `getShortlistedApplicantsForDownload`: no_data 200 JSON, else an XLSX workbook (sheet "Applicants", S.No. + aliased columns). Skip the Node local-disk write (server-side side effect not in the API contract). ADMIN-only. Then run the full suite.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/persistence/ShortlistReadRepository.java` (download query + count)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/service/XlsxSupport.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/shortlist/web/ShortlistInfoController.java` (`/download-data`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistDownloadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/shortlist/ShortlistDownloadIT.java`:
```java
package com.rcf.imas.modules.shortlist;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ShortlistDownloadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid, batchId;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (730003,'BELAGAVI','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (730004,'GOKAK','BLOCK',730003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('DL100000000001','DownloadSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dlseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dlseed'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% dl') ON CONFLICT (criteria) DO NOTHING").update();
        long cid = jdbc.sql("SELECT criteria_id FROM pp.shortlist_criteria WHERE criteria='Top 6% dl'").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_name, criteria_id, shortlisted_year, frozen_yn) VALUES ('DownloadIT-Batch',:c,2025,'Y')").param("c", cid).update();
        batchId = jdbc.sql("SELECT shortlist_batch_id FROM pp.shortlist_batch WHERE shortlist_batch_name='DownloadIT-Batch'").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 650001").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_name='DownloadIT-Batch'").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 650001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DL100000000001'").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria='Top 6% dl'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (730003,730004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name='dlseed'").update();
    }

    private void addShortlistedApplicant() {
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, contact_no1, current_institute_dise_code, medium, gmat_score, sat_score, created_by, updated_by)
            VALUES (650001,2025,24010000051,730003,730004,'Asha','f','9000000001','DL100000000001','KANNADA',55,60,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES (650001,'Y',:b,:u,:u)").param("b", batchId).param("u", uid).update();
    }

    @Test
    void downloadNoDataReturns200Json() throws Exception {
        mvc.perform(get("/api/shortlist-info/download-data/DownloadIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("no_data"))
           .andExpect(jsonPath("$.message").value("No shortlisted students found."));
    }

    @Test
    void downloadReturnsXlsxWithHeadersAndData() throws Exception {
        addShortlistedApplicant();
        byte[] bytes = mvc.perform(get("/api/shortlist-info/download-data/DownloadIT-Batch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"DownloadIT-Batch_Applicants.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Applicants");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("S. No.");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("NMMS Registration No");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Student Name");
            Row data = sheet.getRow(1);
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Asha");
        }
    }

    @Test
    void downloadMissingBatchIs404() throws Exception {
        mvc.perform(get("/api/shortlist-info/download-data/NoSuchBatch?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Shortlist not found"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistDownloadIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ShortlistReadRepository`:
```java
    public int shortlistedCountInBatch(String batchId) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = :id::numeric AND shortlisted_yn = 'Y'")
                .param("id", batchId).query(Integer.class).single();
    }

    /** Download rows in the exact column order (values only; the controller/XlsxSupport supplies headers incl. "S. No."). */
    public List<Map<String, Object>> downloadRows(String batchId) {
        return jdbc.sql("""
                SELECT api.nmms_reg_number AS "NMMS Registration No", api.student_name AS "Student Name",
                       api.contact_no1 AS "Contact No 1", cur_inst.institute_name AS "Current School Name",
                       api.medium AS "Medium", d.juris_name AS "District", b.juris_name AS "Block",
                       api.gmat_score AS "GMAT Score", api.sat_score AS "SAT Score"
                FROM pp.applicant_primary_info api
                LEFT JOIN pp.institute cur_inst ON api.current_institute_dise_code = cur_inst.dise_code
                LEFT JOIN pp.jurisdiction d ON api.district = d.juris_code
                LEFT JOIN pp.jurisdiction b ON api.nmms_block = b.juris_code
                WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = :id::numeric)
                ORDER BY api.student_name ASC
                """).param("id", batchId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("NMMS Registration No", numStr(rs.getBigDecimal("NMMS Registration No")));
                    m.put("Student Name", rs.getString("Student Name"));
                    m.put("Contact No 1", rs.getString("Contact No 1"));
                    m.put("Current School Name", rs.getString("Current School Name"));
                    m.put("Medium", rs.getString("Medium"));
                    m.put("District", rs.getString("District"));
                    m.put("Block", rs.getString("Block"));
                    m.put("GMAT Score", numStr(rs.getBigDecimal("GMAT Score")));
                    m.put("SAT Score", numStr(rs.getBigDecimal("SAT Score")));
                    return m;
                }).list();
    }
```

`src/main/java/com/rcf/imas/modules/shortlist/service/XlsxSupport.java`:
```java
package com.rcf.imas.modules.shortlist.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Builds a single-sheet XLSX (header row + data rows) as a byte[]. */
@Component
public class XlsxSupport {

    public byte[] build(String sheetName, List<String> headers, List<Map<String, Object>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) headerRow.createCell(c).setCellValue(headers.get(c));
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < headers.size(); c++) {
                    Object v = data.get(headers.get(c));
                    row.createCell(c).setCellValue(v == null ? "" : String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

Add `/download-data` to `ShortlistInfoController` (inject `XlsxSupport xlsx`):
```java
    private static final List<String> DOWNLOAD_HEADERS = List.of(
        "S. No.", "NMMS Registration No", "Student Name", "Contact No 1", "Current School Name",
        "Medium", "District", "Block", "GMAT Score", "SAT Score");

    @GetMapping("/download-data/{shortlistName}")
    public Object downloadData(@PathVariable String shortlistName, @RequestParam(required = false) String year) {
        Map<String, Object> info = reads.shortlistInfo(shortlistName, year);
        if (info == null) throw ApiException.message(404, "Shortlist not found");
        String id = (String) info.get("id");
        if (reads.shortlistedCountInBatch(id) == 0) {
            return Map.of("status", "no_data", "message", "No shortlisted students found.");
        }
        List<Map<String, Object>> rows = reads.downloadRows(id);
        List<Map<String, Object>> withSno = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("S. No.", i + 1);
            r.putAll(rows.get(i));
            withSno.add(r);
        }
        byte[] bytes = xlsx.build("Applicants", DOWNLOAD_HEADERS, withSno);
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + shortlistName + "_Applicants.xlsx\"")
            .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(bytes);
    }
```

> **Deliberate drop.** Node also writes the workbook to `${FILE_STORAGE_PATH}/generated-shortlist-data/<name>_Applicants.xlsx` on disk. That local artifact is not part of the HTTP contract (the frontend consumes the response body) — we skip it for a stateless plain-jar deploy. Recorded in the parity notes.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ShortlistDownloadIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + the new shortlist tests green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/shortlist imas-backend/src/test/java/com/rcf/imas/modules/shortlist/ShortlistDownloadIT.java
git commit -m "feat(shortlist): download-data XLSX export (POI); disk-write dropped as stateless

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 6 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/shortlist` package against this plan + the spec, checking:
- **Ranking parity:** the `PERCENT_RANK` window (`PARTITION BY nmms_block ORDER BY weighted_score DESC, applicant_id ASC`), `<= threshold` boundary, threshold-from-whitelist interpolation — identical membership to Node. Tie-break preserved.
- **Response shapes:** the mixed String/number typing (weighted_score decimal via `toPlainString`; counts as parseInt numbers vs raw-COUNT Strings; ids as Strings); bare-array vs `{data}` vs scalar-map envelopes; the exact `{error:...}` vs `{message:...}` body key per endpoint.
- **The two arg-count behaviors:** freeze ignores `filterMediums` in auto-update (uses it only for conflict detection); bulk-update runs Step 1 + Step 3 only (Step 2 not ported). Both preserved deliberately.
- **Auth:** class-level `@PreAuthorize("hasRole('ADMIN')")` on both controllers; all handlers `public`.
- **Transactions:** start-shortlist, freeze steps, bulk-update, delete all in `ShortlistWriteRepository` (@Transactional); the 409 via typed `DuplicateShortlistException`.
- **Isolation:** every IT `@AfterEach`-cleans children-before-parents; full suite green.
- **Route ordering:** `/{shortlistName}` catch-all doesn't shadow `/names` etc.

Update `imas-migration-status` memory: Phase 2c complete, new test count. This completes the ADMISSION domain (2a+2b+2c). Next: Phase 3.

## Deferred / parity decisions carried into this plan

- **`bulk-update-mediums` Step-2 not ported** — dead at runtime (Node controller drops `allowedMediums`). Only Step 1 + Step 3 execute. Simplification matching actual behavior.
- **`freeze` `filterMediums`** used only for conflict detection, ignored in the auto-update step (Node arg-count) — preserved.
- **XLSX disk-write dropped** — the Node local-file save (`FILE_STORAGE_PATH`) is a server side effect outside the API contract; skipped for a stateless deploy.
- **Threshold interpolated as a SQL literal** — from the fixed whitelist {0.04, 0.06, 0.08}, never user input; safe and keeps `PERCENT_RANK` planning identical.
- **Generic `500 {error:"Internal Server Error"}`** for truly unexpected failures instead of Node's `{error: error.message}` echo — never on tested paths; safer.
- **ADMIN enforcement is NEW** vs Node's fully-open shortlist routes (audit CRITICAL). Add to the fetch audit.
