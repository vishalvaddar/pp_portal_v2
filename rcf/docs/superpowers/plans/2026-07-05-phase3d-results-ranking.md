# IMAS Spring Boot Migration — Plan 3d: Results & Ranking

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `resultandrankinkRoutes.js` / `resultandrankingController.js` / `resultandrankingModel.js` trio (9 endpoints, all read-only) to a new `com.rcf.imas.modules.results` module mounted at `/api/results`, preserving exact SQL, response shapes, status codes, and — deliberately — two known quirks (the `search-by-exam` division/district join bug, and the fan-out from non-unique joins), while adding the locked ADMIN authorization Node never had.

**Architecture:** Continues the Phase 1/2a/2b/2c modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `results` with `web/`, `persistence/`, `service/`. All 9 endpoints are single-statement autocommit reads — **no `@Transactional` anywhere**, no dedicated write repository. XLSX generation isolated in a small `ResultsXlsxSupport` (Apache POI, already a dependency from Plan 2a — no pom change).

**Tech Stack (no additions):** Apache POI (`org.apache.poi:poi-ooxml`), already on the classpath.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Plans 1 + 2a + 2b + 2c are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`), `SecurityConfig` (method security), `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1/2a/2b/2c — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `WHERE parent_juris = :stateId::numeric`, `ae.exam_id = :examId::numeric`, `dist.parent_juris = :division::numeric`, `api.district = :educationDistrict::numeric`, and the blocks array is bound `numeric[]` (bind as `Long[]`/`BigDecimal[]` so `api.nmms_block = ANY(:blocks)` compares numeric-to-numeric without a cast on the column). Jurisdiction-name lookups elsewhere in the codebase use `LOWER(TRIM(...))` on text columns — not needed here since this module's cascades filter by numeric `juris_code`/`parent_juris`, not by name.
> 3. **Numeric + bigint ids serialize as Strings** via the shared `genericRow` mapper (`rs.getBigDecimal(...).toBigInteger().toString()` for integer numerics). `DATE` columns → `"yyyy-MM-dd"` string. `TIME` columns → `"HH:mm:ss"` string. `TIMESTAMP` → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`). Everything else passes through `rs.getObject(i)` natively. Map keys are literal snake_case.
> 4. **snake_case JSON** global default. Request DTOs read as `Map<String,Object>`.
> 5. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`. This module mixes both **per-endpoint, not module-wide** — GET cascades/`/all-exams`/`/filter-options` and both search 500s use `{error:"Internal Server Error"}`; `search-by-exam`'s 400 and **both download endpoints'** 400/404/500 use `{message:...}`. Match the contract table exactly; do not normalize.
> 6. **Controllers:** class package-private; every handler method **`public`**.
> 7. **Auth (NEW enforcement — audit CRITICAL):** every `/api/results/**` endpoint is `@PreAuthorize("hasRole('ADMIN')")` (class-level). Node left all 9 routes fully open. Record in the fetch audit.
> 8. **Transactions:** none needed — every endpoint in this module is a single-statement read (confirmed: zero `INSERT`/`UPDATE`/`DELETE`/`BEGIN` in the Node source). No write repository, no `@Transactional`.
> 9. **Test isolation:** all `*IT` extend `PgIntegrationTest` (one JVM-wide embedded Postgres). `@AfterEach`-clean children-before-parents: `applicant_shortlist_info`(if seeded)/`exam_results`/`student_interview`/`home_verification`/`applicant_exam` → `applicant_primary_info` → `jurisdiction` → `jurisdiction_type` → `institute` → `examination` → `rejection_reasons` → `"user"`. Seed `jurisdiction_type` before `jurisdiction`. Advance sequences (`setval`) after explicit-PK seeds.
> 10. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.

---

## Ground truth used by this plan (verified against Node source + live pg_dump)

Full detail: `docs/superpowers/plans/artifacts/phase3d-results-ranking-ground-truth.md`. Node source read (all 451+174+26 lines live, no dead code blocks to skip — but see the two *runtime-reachable-yet-unreached-by-frontend* routes below):
- `server/routes/resultandrankinkRoutes.js` (26 lines)
- `server/controllers/resultandrankingController.js` (451 lines)
- `server/models/resultandrankingModel.js` (174 lines)
- Mount: `app.use("/api/results", resultandrankinkRoutes)` (`server/index.js:316`).

### Table facts (from `live-schema.sql`, already summarized — see ground truth §3 for line numbers)

- **`pp.jurisdiction`** — `juris_code numeric(12,0)` PK, `juris_name varchar(100)`, `juris_type varchar(100)` (`'DIVISION'`, `'EDUCATION DISTRICT'`, `'BLOCK'` used here), `parent_juris numeric(12,0)` self-FK.
- **`pp.examination`** — `exam_id numeric(14,0)` PK, `exam_name varchar(100) NOT NULL`, `exam_date date NOT NULL`, `exam_start_time time NOT NULL`, `exam_end_time time NOT NULL`.
- **`pp.applicant_primary_info`** — `applicant_id numeric(14,0)` PK, `nmms_reg_number numeric(11,0) UNIQUE`, `app_state numeric(12,0)`, `district numeric(12,0)`, `nmms_block numeric(12,0)`, `student_name/father_name varchar(100)`, `gmat_score/sat_score numeric(2,0)`, `medium varchar(50)`, `contact_no1 varchar(12)`, `current_institute_dise_code varchar(15)`. No `division` column — division only reachable via `district → parent_juris`.
- **`pp.applicant_exam`** — composite PK `(applicant_id, exam_id)` — fan-out safe.
- **`pp.exam_results`** — no PK/unique on `applicant_id` — **fan-out risk**.
- **`pp.student_interview`** — PK `interview_id`, `applicant_id` FK **no unique** — **fan-out risk** (multi-round interviews are schema-legal, `'ANOTHER INTERVIEW REQUIRED'` is a valid `interview_result`).
- **`pp.home_verification`** — PK `verification_id`, `applicant_id` FK **no unique** — **fan-out risk**.
- **`pp.rejection_reasons`** — PK `rej_reason_id`, `rejection_reason varchar(200)`.
- **`pp.institute`** — `dise_code varchar(15) UNIQUE`, `institute_name varchar(200)`.

**Fan-out is deliberately preserved** (no `DISTINCT`/`DISTINCT ON`/window dedup) in both search queries — a student with 2 `student_interview` rows must yield 2 result rows, matching Node's plain `LEFT JOIN` exactly.

### Endpoint contract (9 routes, all `@PreAuthorize("hasRole('ADMIN')")`)

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 1 | GET `/divisions-by-state/{stateId}` | `200 [{id,name}]` | `500 {error:"Internal Server Error"}` |
| 2 | GET `/education-districts-by-division/{divisionId}` | `200 [{id,name}]` | `500 {error:"Internal Server Error"}` |
| 3 | GET `/blocks-by-district/{districtId}` | `200 [{id,name}]` | `500 {error:"Internal Server Error"}` |
| 4 | GET `/all-exams` | `200 [{exam_id,exam_name,exam_date,exam_start_time,exam_end_time}]` (ORDER BY exam_date DESC) | `500 {error:"Internal Server Error"}` |
| 5 | GET `/filter-options/{field}` | `200 [<value>,...]` for the 4 known fields; unknown → `200 []` | `500 {error:"Internal Server Error"}` |
| 6 | POST `/search-by-blocks` | `200 [{...21 fields...}]` bare array, dynamic WHERE | `500 {error:"Internal Server Error"}` |
| 7 | POST `/search-by-exam` | `200 [{...23 fields...}]` bare array, static SQL, **division_name==district_name bug preserved** | `400 {message:"Exam ID is required"}`; `500 {error:"Internal Server Error"}` |
| 8 | POST `/download-by-blocks` | `200` XLSX bytes (sheet "Results", 21 cols, fill `FFE6E6FA`) | `404 {message:"No data found"}`; `500 {message:"Failed to generate excel file"}` |
| 9 | POST `/download-by-exam` | `200` XLSX bytes (sheet "Exam Results", 23 cols, fill `FFE6F5E6`) | `400 {message:"Exam ID is required"}`; `404 {message:"No data found for this exam"}`; `500 {message:"Failed to generate excel file"}` |

**Note the error-key split:** endpoints 1–7's 500s use `{error:...}`; endpoint 7's 400 and endpoints 8–9's 400/404/500 all use `{message:...}`. This is an existing Node inconsistency — preserve it per-endpoint, do not normalize module-wide.

**Field list for search rows** (`search-by-blocks`, 21 fields): `applicant_id, nmms_reg_number, student_name, father_name, gmat_score, sat_score, contact_no1, current_institute_dise_code, medium, school_name, pp_exam_score, pp_exam_cleared, interview_status, interview_result, interview_remarks, verification_status, verification_remarks, rejection_reasons, division_name, district_name, block_name`. `search-by-exam` adds `exam_name, exam_date` (23 total).

## Exact SQL (verbatim, from `resultandrankingModel.js`)

### `getDivisionsByState(stateId)`
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'DIVISION' AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```
(Postgres folds unquoted identifiers to lower-case; behaves identically to `pp.jurisdiction`.)

### `getEducationDistrictsByDivision(divisionId)`
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'EDUCATION DISTRICT' AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```

### `getBlocksByDistrict(districtId)`
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'BLOCK' AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```

### `getAllExams()`
```sql
SELECT exam_id, exam_name, exam_date, exam_start_time, exam_end_time
FROM pp.examination
ORDER BY exam_date DESC
```
No params, no pagination — returns all rows.

### `searchStudentsByBlocks(division, education_district, blocks, app_state)` — dynamic WHERE

Base (always present):
```sql
SELECT
  api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
  api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
  si.institute_name as school_name,
  er.pp_exam_score, er.pp_exam_cleared,
  si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
  hv.status as verification_status, hv.remarks as verification_remarks,
  rr.rejection_reason as rejection_reasons,
  div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name
FROM pp.applicant_primary_info api
LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
LEFT JOIN pp.jurisdiction div ON div.juris_code = dist.parent_juris
WHERE api.app_state = :appState::numeric
```
Then, **conditionally appended in this exact order** (only if present), then always:
```sql
 [AND dist.parent_juris = :division::numeric]
 [AND api.district = :educationDistrict::numeric]
 [AND api.nmms_block = ANY(:blocks)]
ORDER BY COALESCE(blk.juris_name, 'Unknown'), api.student_name
```
No `LIMIT`/`OFFSET`. `app_state` defaults to `1` at the controller, always bound, never optional. **Division derivation quirk:** `division_name` is `div.juris_name` where `div.juris_code = dist.parent_juris` — division is the parent of the applicant's district, **not** looked up directly (there is no `api.division` column).

### `searchStudentsByExam(exam_id)` — static SQL

```sql
SELECT
  api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
  api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
  si.institute_name as school_name,
  er.pp_exam_score, er.pp_exam_cleared,
  si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
  hv.status as verification_status, hv.remarks as verification_remarks,
  rr.rejection_reason as rejection_reasons,
  div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name,
  e.exam_name, e.exam_date
FROM pp.applicant_primary_info api
INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
LEFT JOIN pp.jurisdiction div ON div.juris_code = api.district
LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
LEFT JOIN pp.examination e ON ae.exam_id = e.exam_id
WHERE ae.exam_id = :examId::numeric
ORDER BY api.student_name
```
**Bug to reproduce verbatim (do NOT fix):** both `div` and `dist` join `ON juris_code = api.district` — they resolve to the same row, so `division_name` in this endpoint is actually the **district's own name**, duplicated. This is a live discrepancy vs. `searchStudentsByBlocks` (which correctly walks `dist.parent_juris`). Port both behaviors exactly; flag with a code comment and a pinning test.

### `getFilterOptions(field)` — closed dispatch (`resultandrankingController.js:89-118`)

```java
switch (field) {
  case "interview_status"    -> "SELECT DISTINCT status as value FROM pp.student_interview WHERE status IS NOT NULL";
  case "interview_result"    -> "SELECT DISTINCT interview_result as value FROM pp.student_interview WHERE interview_result IS NOT NULL";
  case "verification_status" -> "SELECT DISTINCT status as value FROM pp.home_verification WHERE status IS NOT NULL";
  case "pp_exam_cleared"     -> "SELECT DISTINCT pp_exam_cleared as value FROM pp.exam_results WHERE pp_exam_cleared IS NOT NULL";
  default -> return empty list, 200;  // no query executed
}
```
No parameters in any branch. Model as a **closed Java enum/switch** mapping to 4 fixed, parameterless SQL strings — never string-concatenate `field` into SQL. Unknown value → `200 []`, never 400/throw.

## File-generating endpoints (Apache POI, exact reproduction of ExcelJS output)

### `downloadByBlocks` (`resultandrankingController.js:146-296`)

Body: `{division, district, blocks, app_state=1}` — **body key is `district`, not `education_district`** (frontend renames it: `ResultandrankHooks.js:214`). Reuses `searchStudentsByBlocks(division, district, blocks, app_state)` — `district` fills the model's `education_district` positional parameter. `404 {message:"No data found"}` if empty.

Sheet `"Results"`. Header row bold, fill `FFE6E6FA` solid pattern. **Exact 21 headers, in order:**
```
Applicant ID, NMMS Number, Student Name, Father Name, GMAT Score, SAT Score, PP Exam Score, PP Exam Cleared,
Interview Status, Interview Result, Interview Remarks, Verification Status, Verification Remarks, Rejection Reasons,
Contact Number, School DISE Code, Medium, School Name, Division, District, Block
```
Data row mapping (from `resultandrankingController.js:194-223`):
- `gmat_score`, `sat_score`, `pp_exam_score` → `Number(x || 0)` (never null, 0 default).
- `pp_exam_cleared, interview_status, interview_result, interview_remarks, verification_status, verification_remarks, rejection_reasons, division_name, district_name, block_name` → `x || 'N/A'`.
- `applicant_id, nmms_reg_number, student_name, father_name, contact_no1, current_institute_dise_code, medium, school_name` → passed through as-is, no fallback (can be blank/null cell).

Column width: `min(max(maxCellTextLength+2, 15), 50)` (longest stringified cell in the column incl. header; **empty cell counts as length 10** per ExcelJS's `cell.value ? cell.value.toString().length : 10` — reproduce that exact default, not 0).

**Filename generation** (verbatim from `resultandrankingController.js:238-278`):
```js
let fileName = "results";
const uniqueDivisions = [...new Set(results.map(r => r.division_name).filter(Boolean))];
const uniqueDistricts = [...new Set(results.map(r => r.district_name).filter(Boolean))];
const uniqueBlocks    = [...new Set(results.map(r => r.block_name).filter(Boolean))];

if (division) {
  fileName += `_${uniqueDivisions.length > 0
      ? uniqueDivisions[0].replace(/[^\w\s]/gi, '').replace(/\s+/g, '_')
      : 'Division_' + division}`;
} else { fileName += '_All_Divisions'; }

if (district) {
  fileName += `_${uniqueDistricts.length > 0
      ? uniqueDistricts[0].replace(/[^\w\s]/gi, '').replace(/\s+/g, '_')
      : 'District_' + district}`;
} else { fileName += '_All_Districts'; }

if (blocks && blocks.length > 0) {
  if (uniqueBlocks.length === 1) fileName += `_${uniqueBlocks[0].replace(/[^\w\s]/gi, '').replace(/\s+/g, '_')}`;
  else if (uniqueBlocks.length > 1) fileName += `_${uniqueBlocks.length}_Blocks`;
  else fileName += '_Selected_Blocks';
} else { fileName += '_All_Blocks'; }

fileName += '.xlsx';
fileName = fileName.replace(/_+/g, '_');
```
Java note: `/[^\w\s]/gi` in JS = "strip anything that's not a word char or whitespace" → Java regex `[^\\w\\s]` (case-insensitive irrelevant since `\w`/`\s` aren't letter classes); `\s+` → `_` collapse; then a final global `_+` → `_` cleanup (this is what neutralizes empty name-segments from stripped punctuation).

Headers: `Content-Disposition: attachment; filename="<fileName>"`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. `500 {message:"Failed to generate excel file"}` on any exception.

### `downloadByExam` (`resultandrankingController.js:299-439`)

Body: `{exam_id}` — `400 {message:"Exam ID is required"}` if missing. Reuses `searchStudentsByExam(exam_id)`; `404 {message:"No data found for this exam"}` if empty.

Sheet `"Exam Results"`. Header fill `FFE6F5E6`. **Exact 23 headers** = the 21 shared + `Exam Name, Exam Date`. Same 21-column fallback rules, plus:
- `exam_name || 'N/A'`.
- `exam_date` cell value: `result.exam_date ? new Date(result.exam_date).toLocaleDateString() : 'N/A'` — en-US locale (Node default) → `M/d/yyyy`, **no leading zeros** (e.g. `6/15/2025`, not `06/15/2025`). Pin in Java as `DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)`.

Same column-width logic (15–50, empty-cell length 10).

**Filename generation** (verbatim from `resultandrankingController.js:398-421`):
```js
let fileName = "results";
if (results.length > 0 && results[0].exam_name) {
  fileName += `_${results[0].exam_name.replace(/[^\w\s]/gi, '').replace(/\s+/g, '_').slice(0, 50)}`;
} else { fileName += '_Exam'; }

if (results.length > 0 && results[0].exam_date) {
  const dateStr = new Date(results[0].exam_date).toISOString().split('T')[0].replace(/-/g, '_');
  fileName += `_${dateStr}`;
}
fileName += '.xlsx';
fileName = fileName.replace(/_+/g, '_');
```
**Two distinct date formats in the same handler:** the sheet cell uses locale `M/d/yyyy`; the filename uses ISO/UTC `yyyy_MM_dd` (from `toISOString().split('T')[0]`, `-`→`_`). Do not derive one from the other — implement both independently, from `results.get(0).get("exam_date")` (a `LocalDate`).

Same headers/content-type; same `500 {message:"Failed to generate excel file"}`.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/results/
├── web/ResultsController.java              (all 9 handlers, @PreAuthorize ADMIN, class package-private)
├── persistence/ResultsReadRepository.java  (genericRow mapper + all 9 queries incl. dynamic WHERE builder)
└── service/ResultsXlsxSupport.java         (POI: rows → xlsx bytes for both download endpoints, incl. filename builders)

imas-backend/src/test/java/com/rcf/imas/modules/results/
├── ResultsCascadeAndFilterOptionsIT.java   (Task 1: 3 cascades, all-exams, filter-options ×5 incl. unknown, admin-only)
├── ResultsSearchIT.java                    (Task 2: search-by-blocks dynamic filters + fan-out, search-by-exam bug pin + 400)
└── ResultsDownloadIT.java                  (Task 3: download-by-blocks incl. filename cases + 404, download-by-exam incl. two date formats + 400/404)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks.
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN"|"STUDENT")`.
- This module never writes — no `created_by`/`updated_by` FK burden on seeded rows beyond what other tables already require (e.g. `applicant_primary_info.created_by/updated_by` FK to `pp."user"`).

---

## Task 1: module skeleton + `ResultsReadRepository` + 5 simple reads

Port the 3 jurisdiction cascades, `/all-exams`, and `/filter-options/{field}`. ADMIN-only. Establishes the shared `genericRow` mapper (DATE/TIME/TIMESTAMP/numeric handling) reused by every later task.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/results/persistence/ResultsReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/results/web/ResultsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/results/ResultsCascadeAndFilterOptionsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/results/ResultsCascadeAndFilterOptionsIT.java`:
```java
package com.rcf.imas.modules.results;

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
class ResultsCascadeAndFilterOptionsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800002,'BELGAUM DIV','DIVISION',800001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800003,'BELAGAVI','EDUCATION DISTRICT',800002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800004,'GOKAK','BLOCK',800003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (890001, 'NMMS Aptitude 2025', '2025-06-15', '09:00:00', '11:00:00')
            ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        // exam_results/student_interview/home_verification all FK applicant_id -> applicant_primary_info,
        // so the referenced applicant MUST exist first (999999). nmms_reg_number is NOT NULL UNIQUE; other cols nullable.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (999999, 24099999999) ON CONFLICT (applicant_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (890101, 999999, 'COMPLETED', 'SELECTED') ON CONFLICT (interview_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.home_verification(verification_id, applicant_id, status) VALUES (890201, 999999, 'ACCEPTED') ON CONFLICT (verification_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_cleared) VALUES (999999, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id = 999999").update();
        jdbc.sql("DELETE FROM pp.home_verification WHERE verification_id = 890201").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE interview_id = 890101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 999999").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 890001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (800001,800002,800003,800004)").update();
    }

    @Test
    void divisionsByState() throws Exception {
        mvc.perform(get("/api/results/divisions-by-state/800001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800002"))
           .andExpect(jsonPath("$[0].name").value("BELGAUM DIV"));
    }

    @Test
    void educationDistrictsByDivision() throws Exception {
        mvc.perform(get("/api/results/education-districts-by-division/800002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800003"))
           .andExpect(jsonPath("$[0].name").value("BELAGAVI"));
    }

    @Test
    void blocksByDistrict() throws Exception {
        mvc.perform(get("/api/results/blocks-by-district/800003").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("800004"))
           .andExpect(jsonPath("$[0].name").value("GOKAK"));
    }

    @Test
    void allExamsReturnsDateAndTimeAsStrings() throws Exception {
        mvc.perform(get("/api/results/all-exams").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_name").value(org.hamcrest.Matchers.hasItem("NMMS Aptitude 2025")))
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_date").value(org.hamcrest.Matchers.hasItem("2025-06-15")))
           .andExpect(jsonPath("$[?(@.exam_id=='890001')].exam_start_time").value(org.hamcrest.Matchers.hasItem("09:00:00")));
    }

    @Test
    void filterOptionsKnownFields() throws Exception {
        mvc.perform(get("/api/results/filter-options/interview_status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("COMPLETED")));
        mvc.perform(get("/api/results/filter-options/interview_result").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("SELECTED")));
        mvc.perform(get("/api/results/filter-options/verification_status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("ACCEPTED")));
        mvc.perform(get("/api/results/filter-options/pp_exam_cleared").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("Y")));
    }

    @Test
    void filterOptionsUnknownFieldIsEmpty200NotError() throws Exception {
        mvc.perform(get("/api/results/filter-options/not_a_real_field").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    @Test
    void allEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/results/divisions-by-state/800001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/results/all-exams").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/results/filter-options/interview_status").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsCascadeAndFilterOptionsIT` — Expected: FAIL (no controller/repository yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/results/persistence/ResultsReadRepository.java`:
```java
package com.rcf.imas.modules.results.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ResultsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The 4 closed filter-options branches. Values are hardcoded, parameterless SQL — never derived from `field`. */
    private static final Map<String, String> FILTER_OPTION_SQL = Map.of(
        "interview_status",    "SELECT DISTINCT status as value FROM pp.student_interview WHERE status IS NOT NULL",
        "interview_result",    "SELECT DISTINCT interview_result as value FROM pp.student_interview WHERE interview_result IS NOT NULL",
        "verification_status", "SELECT DISTINCT status as value FROM pp.home_verification WHERE status IS NOT NULL",
        "pp_exam_cleared",     "SELECT DISTINCT pp_exam_cleared as value FROM pp.exam_results WHERE pp_exam_cleared IS NOT NULL"
    );

    private final JdbcClient jdbc;

    public ResultsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * node-pg parity for this module's generic lists: integer numerics + bigint -> String; DATE -> "yyyy-MM-dd";
     * TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z; else native passthrough. Map keys are the column label as-is.
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
                case java.sql.Types.TIME -> {
                    java.sql.Time t = rs.getTime(i);
                    val = t == null ? null : TIME_FMT.format(t.toLocalTime());
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

    public List<Map<String, Object>> divisionsByState(String stateId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'DIVISION' AND parent_juris = :stateId::numeric
                ORDER BY juris_name
                """).param("stateId", stateId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> educationDistrictsByDivision(String divisionId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' AND parent_juris = :divisionId::numeric
                ORDER BY juris_name
                """).param("divisionId", divisionId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String districtId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'BLOCK' AND parent_juris = :districtId::numeric
                ORDER BY juris_name
                """).param("districtId", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> allExams() {
        return jdbc.sql("""
                SELECT exam_id, exam_name, exam_date, exam_start_time, exam_end_time
                FROM pp.examination ORDER BY exam_date DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** Closed dispatch: unknown field -> empty list, no query executed, 200 (matches Node's default: branch). */
    public List<String> filterOptions(String field) {
        String sql = FILTER_OPTION_SQL.get(field);
        if (sql == null) return List.of();
        return jdbc.sql(sql).query(String.class).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/results/web/ResultsController.java` (5 read handlers this task; search/download added in Tasks 2/3):
```java
package com.rcf.imas.modules.results.web;

import com.rcf.imas.modules.results.persistence.ResultsReadRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/results")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every /api/results/** route open
class ResultsController {

    private final ResultsReadRepository reads;

    ResultsController(ResultsReadRepository reads) { this.reads = reads; }

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

    @GetMapping("/all-exams")
    public List<Map<String, Object>> allExams() { return reads.allExams(); }

    @GetMapping("/filter-options/{field}")
    public List<String> filterOptions(@PathVariable String field) { return reads.filterOptions(field); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsCascadeAndFilterOptionsIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/results imas-backend/src/test/java/com/rcf/imas/modules/results
git commit -m "feat(results): module skeleton + cascades/all-exams/filter-options (closed enum) ADMIN-only

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `/search-by-blocks` (dynamic WHERE) + `/search-by-exam` (bug-preserving static SQL)

Port the two search reads. `search-by-blocks` builds its WHERE clause with a `StringBuilder` + named params (never positional `?`, to sidestep the 8 filter-presence combinations). `search-by-exam` reproduces the `div`/`dist` both-join-`api.district` bug verbatim, with a pinning test and a code comment. Also seeds and asserts the interview/verification fan-out is NOT deduped.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/results/persistence/ResultsReadRepository.java` (add `searchByBlocks`, `searchByExam`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/results/web/ResultsController.java` (add the 2 POST handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/results/ResultsSearchIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/results/ResultsSearchIT.java`:
```java
package com.rcf.imas.modules.results;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ResultsSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (810001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810003,'BELAGAVI','EDUCATION DISTRICT',810001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810004,'GOKAK','BLOCK',810003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('SR100000000001','SearchSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('rsseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='rsseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "rsseed", "ADMIN");

        // applicant 1: fans out to 2 interview rows (2 rounds) -> assert search-by-blocks returns 2 rows for it.
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES (820001,2025,24020000001,810001,810003,810004,'Fanout','f','KANNADA','9000000001','SR100000000001',70,80,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (820101,820001,'COMPLETED','ANOTHER INTERVIEW REQUIRED')").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status, interview_result) VALUES (820102,820001,'SCHEDULED','SELECTED')").update();

        jdbc.sql("INSERT INTO pp.shortlist_criteria(criteria) VALUES ('Top 6% search-exam') ON CONFLICT (criteria) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (820201, 'SearchExam', '2025-05-01', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (820001, 820201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 820201").update();
        jdbc.sql("DELETE FROM pp.shortlist_criteria WHERE criteria = 'Top 6% search-exam'").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 820001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'SR100000000001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (810001,810003,810004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'rsseed'").update();
    }

    @Test
    void searchByBlocksFiltersByDivisionDistrictAndBlocksAndDoesNotDedupFanOut() throws Exception {
        String body = """
            {"division":810001,"education_district":810003,"blocks":[810004],"app_state":810001}
            """;
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))   // 2 student_interview rows -> 2 result rows, no DISTINCT
           .andExpect(jsonPath("$[0].applicant_id").value("820001"))
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"));
    }

    @Test
    void searchByBlocksWithNoFiltersReturnsAllForAppState() throws Exception {
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_state\":810001}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchByExamReproducesDivisionNameEqualsDistrictNameBug() throws Exception {
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":820201}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].division_name").value("BELAGAVI"))   // BUG: should be parent division, is district
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$[0].exam_name").value("SearchExam"));
    }

    @Test
    void searchByExamMissingIdIs400() throws Exception {
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Exam ID is required"));
    }

    @Test
    void searchEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/results/search-by-blocks").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/search-by-exam").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":1}")).andExpect(status().isForbidden());
    }
}
```

> **Fixture note.** `district`'s parent is `state` (810003's parent is 810001), and the test binds `division=810001` — matching `dist.parent_juris = :division` (BELAGAVI's own parent, 810001) is intentional: it exercises the real join shape (`dist.parent_juris`) without needing a 4th jurisdiction level. If a true division tier is desired, add a `'DIVISION'` row between state and district; not required to pin the behavior under test here.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsSearchIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ResultsReadRepository` (add `import java.util.ArrayList;` and `import java.math.BigDecimal;` if not already present):
```java
    private static final String SEARCH_BASE_SELECT = """
            SELECT
              api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
              api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
              si.institute_name as school_name,
              er.pp_exam_score, er.pp_exam_cleared,
              si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
              hv.status as verification_status, hv.remarks as verification_remarks,
              rr.rejection_reason as rejection_reasons,
              div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name
            FROM pp.applicant_primary_info api
            LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
            LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
            LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
            LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
            LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
            LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
            LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
            LEFT JOIN pp.jurisdiction div ON div.juris_code = dist.parent_juris
            WHERE api.app_state = :appState::numeric
            """;

    /**
     * searchStudentsByBlocks parity: base WHERE always binds app_state (defaults to 1 at the controller);
     * the three optional predicates are appended in this exact order, only when present — built with a
     * StringBuilder + named params (never positional ?) to avoid off-by-one errors across the 8 filter
     * combinations. No DISTINCT/dedup: exam_results/student_interview/home_verification can fan out per applicant.
     */
    public List<Map<String, Object>> searchByBlocks(String division, String educationDistrict,
                                                     List<Object> blocks, String appState) {
        boolean hasDivision = division != null && !division.isBlank();
        boolean hasEducationDistrict = educationDistrict != null && !educationDistrict.isBlank();
        boolean hasBlocks = blocks != null && !blocks.isEmpty();

        StringBuilder sql = new StringBuilder(SEARCH_BASE_SELECT);
        if (hasDivision) sql.append(" AND dist.parent_juris = :division::numeric");
        if (hasEducationDistrict) sql.append(" AND api.district = :educationDistrict::numeric");
        if (hasBlocks) sql.append(" AND api.nmms_block = ANY(:blocks)");
        sql.append(" ORDER BY COALESCE(blk.juris_name, 'Unknown'), api.student_name");

        var query = jdbc.sql(sql.toString()).param("appState", appState);
        if (hasDivision) query = query.param("division", division);
        if (hasEducationDistrict) query = query.param("educationDistrict", educationDistrict);
        if (hasBlocks) {
            BigDecimal[] arr = blocks.stream().map(b -> new BigDecimal(String.valueOf(b))).toArray(BigDecimal[]::new);
            query = query.param("blocks", arr);
        }
        return query.query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * searchStudentsByExam parity. BUG PRESERVED VERBATIM (Node resultandrankingModel.js:152-153): both `div` and
     * `dist` join ON juris_code = api.district, so `division_name` here is actually the district's own name, not
     * the true parent division (unlike searchByBlocks, which correctly walks dist.parent_juris). Do NOT fix.
     */
    public List<Map<String, Object>> searchByExam(String examId) {
        return jdbc.sql("""
                SELECT
                  api.applicant_id, api.nmms_reg_number, api.student_name, api.father_name,
                  api.gmat_score, api.sat_score, api.contact_no1, api.current_institute_dise_code, api.medium,
                  si.institute_name as school_name,
                  er.pp_exam_score, er.pp_exam_cleared,
                  si_interview.status as interview_status, si_interview.interview_result, si_interview.remarks as interview_remarks,
                  hv.status as verification_status, hv.remarks as verification_remarks,
                  rr.rejection_reason as rejection_reasons,
                  div.juris_name as division_name, dist.juris_name as district_name, blk.juris_name as block_name,
                  e.exam_name, e.exam_date
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
                LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
                LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
                LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
                LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
                LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
                LEFT JOIN pp.jurisdiction div ON div.juris_code = api.district
                LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
                LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
                LEFT JOIN pp.examination e ON ae.exam_id = e.exam_id
                WHERE ae.exam_id = :examId::numeric
                ORDER BY api.student_name
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }
```

Add handlers to `ResultsController` (add `import com.rcf.imas.platform.error.ApiException;` and `import java.util.List;` if missing):
```java
    @PostMapping("/search-by-blocks")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchByBlocks(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object division = b.get("division");
        Object educationDistrict = b.get("education_district");
        List<Object> blocks = b.get("blocks") instanceof List<?> l ? (List<Object>) l : List.of();
        Object appState = b.getOrDefault("app_state", 1);
        return reads.searchByBlocks(str(division), str(educationDistrict), blocks, str(appState));
    }

    @PostMapping("/search-by-exam")
    public List<Map<String, Object>> searchByExam(@RequestBody(required = false) Map<String, Object> body) {
        Object examId = body == null ? null : body.get("exam_id");
        if (examId == null || String.valueOf(examId).isBlank()) {
            throw ApiException.message(400, "Exam ID is required");
        }
        return reads.searchByExam(String.valueOf(examId));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
```

> **Note on the shared 500 `{error:"Internal Server Error"}`.** Both search handlers rely on the module-wide `GlobalExceptionHandler`'s generic-exception fallback (already established in Plans 1/2a/2b/2c) to emit `{error:"Internal Server Error"}` on unexpected failures — no per-endpoint try/catch needed, matching Node's `catch(error){ res.status(500).json({error:"Internal Server Error"}) }` for these two routes.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsSearchIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/results imas-backend/src/test/java/com/rcf/imas/modules/results/ResultsSearchIT.java
git commit -m "feat(results): search-by-blocks (dynamic WHERE) + search-by-exam (division/district bug preserved)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: `/download-by-blocks` + `/download-by-exam` — XLSX exports (Apache POI)

Port both ExcelJS-based downloads to POI. Exact headers, fill colors, N/A fallbacks, `Number(x||0)` coercions, auto column width, and the two independent filename-generation algorithms (including the two distinct `exam_date` formats). Then run the full suite.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/results/persistence/ResultsReadRepository.java` (no new queries — reuses `searchByBlocks`/`searchByExam`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/results/service/ResultsXlsxSupport.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/results/web/ResultsController.java` (`/download-by-blocks`, `/download-by-exam`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/results/ResultsDownloadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/results/ResultsDownloadIT.java`:
```java
package com.rcf.imas.modules.results;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.*;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ResultsDownloadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (830001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830003,'BELAGAVI','EDUCATION DISTRICT',830001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830004,'GOKAK BLOCK!','BLOCK',830003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name, management_type) VALUES ('DL200000000001','DlSchool','GOVERNMENT') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dxseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dxseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "dxseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, contact_no1, current_institute_dise_code, gmat_score, sat_score, created_by, updated_by)
            VALUES (840001,2025,24030000001,830001,830003,830004,'Downloadee','f','KANNADA','9000000001','DL200000000001',88,77,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time)
            VALUES (840101, 'Download Exam!!', '2025-06-15', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (840001, 840101)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 840001").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 840101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 840001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DL200000000001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (830001,830003,830004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'dxseed'").update();
    }

    @Test
    void downloadByBlocksNoDataIs404() throws Exception {
        mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_state\":999999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found"));
    }

    @Test
    void downloadByBlocksReturnsXlsxWithHeadersFillAndFilename() throws Exception {
        String body = """
            {"division":830001,"district":830003,"blocks":[830004],"app_state":830001}
            """;
        byte[] bytes = mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
           .andReturn().getResponse().getContentAsByteArray();

        String disposition = null;
        var result = mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andReturn();
        disposition = result.getResponse().getHeader("Content-Disposition");
        // block name "GOKAK BLOCK!" -> strip punctuation -> "GOKAK BLOCK" -> spaces to underscore -> "GOKAK_BLOCK"
        assertThat(disposition).isEqualTo("attachment; filename=\"results_KARNATAKA_BELAGAVI_GOKAK_BLOCK.xlsx\"");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Results");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Applicant ID");
            assertThat(header.getCell(20).getStringCellValue()).isEqualTo("Block");
            assertThat(header.getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex()).isEqualToIgnoringCase("FFE6E6FA");
            Row data = sheet.getRow(1);
            assertThat(data.getCell(2).getStringCellValue()).isEqualTo("Downloadee");   // student_name
            assertThat(data.getCell(7).getStringCellValue()).isEqualTo("N/A");          // pp_exam_cleared fallback
        }
    }

    @Test
    void downloadByExamMissingIdIs400() throws Exception {
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Exam ID is required"));
    }

    @Test
    void downloadByExamNoDataIs404() throws Exception {
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":999999999}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found for this exam"));
    }

    @Test
    void downloadByExamHasCellDateAndFilenameDateInDifferentFormats() throws Exception {
        var result = mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":840101}"))
           .andExpect(status().isOk())
           .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String disposition = result.getResponse().getHeader("Content-Disposition");
        // exam_name "Download Exam!!" -> "Download_Exam"; date 2025-06-15 -> filename uses ISO/UTC yyyy_MM_dd
        assertThat(disposition).isEqualTo("attachment; filename=\"results_Download_Exam_2025_06_15.xlsx\"");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Exam Results");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(21).getStringCellValue()).isEqualTo("Exam Name");
            assertThat(header.getCell(22).getStringCellValue()).isEqualTo("Exam Date");
            assertThat(header.getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex()).isEqualToIgnoringCase("FFE6F5E6");
            Row data = sheet.getRow(1);
            // cell value uses en-US locale M/d/yyyy (no leading zeros) -- distinct from the ISO filename format above.
            assertThat(data.getCell(22).getStringCellValue()).isEqualTo("6/15/2025");
        }
    }

    @Test
    void downloadEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/results/download-by-blocks").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/download-by-exam").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{\"exam_id\":1}")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsDownloadIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/results/service/ResultsXlsxSupport.java`:
```java
package com.rcf.imas.modules.results.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the two Results-module XLSX exports (POI equivalent of the Node ExcelJS workbooks). */
@Component
public class ResultsXlsxSupport {

    private static final List<String> SHARED_21_HEADERS = List.of(
        "Applicant ID", "NMMS Number", "Student Name", "Father Name", "GMAT Score", "SAT Score",
        "PP Exam Score", "PP Exam Cleared", "Interview Status", "Interview Result", "Interview Remarks",
        "Verification Status", "Verification Remarks", "Rejection Reasons", "Contact Number",
        "School DISE Code", "Medium", "School Name", "Division", "District", "Block");

    private static final DateTimeFormatter EXAM_CELL_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);
    private static final DateTimeFormatter EXAM_FILENAME_DATE = DateTimeFormatter.ofPattern("yyyy_MM_dd");

    /** downloadByBlocks: sheet "Results", fill FFE6E6FA, 21 columns. */
    public byte[] buildResultsSheet(List<Map<String, Object>> rows) {
        return build("Results", SHARED_21_HEADERS, "FFE6E6FA", rows, this::sharedRowValues);
    }

    /** downloadByExam: sheet "Exam Results", fill FFE6F5E6, 23 columns (shared 21 + Exam Name/Exam Date). */
    public byte[] buildExamResultsSheet(List<Map<String, Object>> rows) {
        List<String> headers = new java.util.ArrayList<>(SHARED_21_HEADERS);
        headers.add("Exam Name");
        headers.add("Exam Date");
        return build("Exam Results", headers, "FFE6F5E6", rows, r -> {
            List<Object> vals = new java.util.ArrayList<>(sharedRowValues(r));
            vals.add(orNA(r.get("exam_name")));
            Object examDate = r.get("exam_date");
            LocalDate d = asLocalDate(examDate);
            vals.add(d == null ? "N/A" : EXAM_CELL_DATE.format(d));
            return vals;
        });
    }

    private List<Object> sharedRowValues(Map<String, Object> r) {
        return List.of(
            emptyIfNull(r.get("applicant_id")),
            emptyIfNull(r.get("nmms_reg_number")),
            emptyIfNull(r.get("student_name")),
            emptyIfNull(r.get("father_name")),
            numberOrZero(r.get("gmat_score")),
            numberOrZero(r.get("sat_score")),
            numberOrZero(r.get("pp_exam_score")),
            orNA(r.get("pp_exam_cleared")),
            orNA(r.get("interview_status")),
            orNA(r.get("interview_result")),
            orNA(r.get("interview_remarks")),
            orNA(r.get("verification_status")),
            orNA(r.get("verification_remarks")),
            orNA(r.get("rejection_reasons")),
            emptyIfNull(r.get("contact_no1")),
            emptyIfNull(r.get("current_institute_dise_code")),
            emptyIfNull(r.get("medium")),
            emptyIfNull(r.get("school_name")),
            orNA(r.get("division_name")),
            orNA(r.get("district_name")),
            orNA(r.get("block_name"))
        );
    }

    /** Node `x || 'N/A'`. */
    private static Object orNA(Object v) { return (v == null || "".equals(v)) ? "N/A" : v; }

    /** Node `x` passthrough (no fallback) — blank cell if null. */
    private static Object emptyIfNull(Object v) { return v == null ? "" : v; }

    /** Node `Number(x || 0)`. */
    private static double numberOrZero(Object v) {
        if (v == null) return 0d;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return 0d; }
    }

    private static LocalDate asLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        return LocalDate.parse(String.valueOf(v)); // genericRow emits DATE as "yyyy-MM-dd"
    }

    /** Filename builder for downloadByBlocks — matches resultandrankingController.js:238-278 verbatim. */
    public String blocksFilename(Object division, Object district, List<Object> blocks, List<Map<String, Object>> results) {
        Set<String> uniqueDivisions = uniqueNonBlank(results, "division_name");
        Set<String> uniqueDistricts = uniqueNonBlank(results, "district_name");
        Set<String> uniqueBlocks = uniqueNonBlank(results, "block_name");

        StringBuilder fn = new StringBuilder("results");
        if (present(division)) {
            fn.append('_').append(!uniqueDivisions.isEmpty()
                ? sanitize(uniqueDivisions.iterator().next())
                : "Division_" + division);
        } else {
            fn.append("_All_Divisions");
        }
        if (present(district)) {
            fn.append('_').append(!uniqueDistricts.isEmpty()
                ? sanitize(uniqueDistricts.iterator().next())
                : "District_" + district);
        } else {
            fn.append("_All_Districts");
        }
        if (blocks != null && !blocks.isEmpty()) {
            if (uniqueBlocks.size() == 1) fn.append('_').append(sanitize(uniqueBlocks.iterator().next()));
            else if (uniqueBlocks.size() > 1) fn.append('_').append(uniqueBlocks.size()).append("_Blocks");
            else fn.append("_Selected_Blocks");
        } else {
            fn.append("_All_Blocks");
        }
        fn.append(".xlsx");
        return fn.toString().replaceAll("_+", "_");
    }

    /** Filename builder for downloadByExam — matches resultandrankingController.js:398-421 verbatim. */
    public String examFilename(List<Map<String, Object>> results) {
        StringBuilder fn = new StringBuilder("results");
        Object examName = results.isEmpty() ? null : results.get(0).get("exam_name");
        if (examName != null && !String.valueOf(examName).isBlank()) {
            String sanitized = sanitize(String.valueOf(examName));
            fn.append('_').append(sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized);
        } else {
            fn.append("_Exam");
        }
        Object examDate = results.isEmpty() ? null : results.get(0).get("exam_date");
        LocalDate d = asLocalDate(examDate);
        if (d != null) {
            fn.append('_').append(EXAM_FILENAME_DATE.format(d));
        }
        fn.append(".xlsx");
        return fn.toString().replaceAll("_+", "_");
    }

    private static boolean present(Object v) { return v != null && !String.valueOf(v).isBlank(); }

    private static Set<String> uniqueNonBlank(List<Map<String, Object>> rows, String key) {
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(key);
            if (v != null && !String.valueOf(v).isBlank()) set.add(String.valueOf(v));
        }
        return set;
    }

    /** JS `/[^\w\s]/gi` strip then `/\s+/g` -> '_'. \w = [A-Za-z0-9_], \s = whitespace -- same classes in Java regex. */
    private static String sanitize(String s) {
        return s.replaceAll("[^\\w\\s]", "").replaceAll("\\s+", "_");
    }

    private byte[] build(String sheetName, List<String> headers, String fillArgb,
                         List<Map<String, Object>> rows, java.util.function.Function<Map<String, Object>, List<Object>> rowFn) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(
                    javaAwtColorFromArgb(fillArgb), null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(headerStyle);
            }

            int[] maxLen = new int[headers.size()];
            for (int c = 0; c < headers.size(); c++) maxLen[c] = headers.get(c).length();

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rowFn.apply(rows.get(r));
                for (int c = 0; c < headers.size(); c++) {
                    Object v = c < values.size() ? values.get(c) : null;
                    Cell cell = row.createCell(c);
                    int len;
                    if (v instanceof Double dv) { cell.setCellValue(dv); len = String.valueOf(dv).length(); }
                    else if (v == null || "".equals(v)) { cell.setCellValue(""); len = 10; } // ExcelJS empty-cell default length
                    else { String s = String.valueOf(v); cell.setCellValue(s); len = s.length(); }
                    if (len > maxLen[c]) maxLen[c] = len;
                }
            }
            for (int c = 0; c < headers.size(); c++) {
                int width = Math.min(Math.max(maxLen[c] + 2, 15), 50);
                sheet.setColumnWidth(c, width * 256); // POI width units = 1/256th of a character
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static java.awt.Color javaAwtColorFromArgb(String argb) {
        // argb e.g. "FFE6E6FA" -> skip alpha (FF), take RGB
        int r = Integer.parseInt(argb.substring(2, 4), 16);
        int g = Integer.parseInt(argb.substring(4, 6), 16);
        int b = Integer.parseInt(argb.substring(6, 8), 16);
        return new java.awt.Color(r, g, b);
    }
}
```

> **POI fill-color note.** `XSSFColor(java.awt.Color, IndexedColorMap)` sets the ARGB with alpha forced to `FF` — matches the `FFxxxxxx` values used here (`FFE6E6FA`, `FFE6F5E6`) exactly, so `getFillForegroundColorColor().getARGBHex()` round-trips to the same 8-hex-digit string.

Add to `ResultsController` (constructor now takes `ResultsXlsxSupport xlsx`; add imports `com.rcf.imas.modules.results.service.ResultsXlsxSupport`, `org.springframework.http.ResponseEntity`, `org.springframework.http.MediaType`):
```java
    private final ResultsXlsxSupport xlsx;

    ResultsController(ResultsReadRepository reads, ResultsXlsxSupport xlsx) {
        this.reads = reads;
        this.xlsx = xlsx;
    }

    @PostMapping("/download-by-blocks")
    @SuppressWarnings("unchecked")
    public Object downloadByBlocks(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object division = b.get("division");
        Object district = b.get("district");   // NOTE: body key is "district", not "education_district" (frontend renames it)
        List<Object> blocks = b.get("blocks") instanceof List<?> l ? (List<Object>) l : List.of();
        Object appState = b.getOrDefault("app_state", 1);

        List<Map<String, Object>> results = reads.searchByBlocks(str(division), str(district), blocks, str(appState));
        if (results.isEmpty()) {
            throw ApiException.message(404, "No data found");
        }
        byte[] bytes = xlsx.buildResultsSheet(results);
        String filename = xlsx.blocksFilename(division, district, blocks, results);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @PostMapping("/download-by-exam")
    public Object downloadByExam(@RequestBody(required = false) Map<String, Object> body) {
        Object examId = body == null ? null : body.get("exam_id");
        if (examId == null || String.valueOf(examId).isBlank()) {
            throw ApiException.message(400, "Exam ID is required");
        }
        List<Map<String, Object>> results = reads.searchByExam(String.valueOf(examId));
        if (results.isEmpty()) {
            throw ApiException.message(404, "No data found for this exam");
        }
        byte[] bytes = xlsx.buildExamResultsSheet(results);
        String filename = xlsx.examFilename(results);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
```

> **500 mapping for the two download endpoints.** Node wraps the *entire* handler (query + workbook build + stream write) in one `try/catch` → `{message:"Failed to generate excel file"}`. In Java, any `RuntimeException` thrown from `ResultsXlsxSupport` (e.g. a malformed ARGB, an IO failure converted via `UncheckedIOException`) should be caught by an endpoint-scoped handler that emits the same `{message:...}` body+500 rather than falling through to the module's generic `{error:"Internal Server Error"}` — add a small `@ExceptionHandler(UncheckedIOException.class)` (or catch/rethrow as `ApiException.message(500, "Failed to generate excel file")`) local to `ResultsController` if the generic handler would otherwise emit the wrong body key. This case is not covered by an IT here (POI failures are not realistically triggerable from valid seeded data) — flag for the code reviewer as an untested edge.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ResultsDownloadIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + the new results tests green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/results imas-backend/src/test/java/com/rcf/imas/modules/results/ResultsDownloadIT.java
git commit -m "feat(results): download-by-blocks + download-by-exam XLSX exports (POI), filenames + dual date formats

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 3 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/results` package against this plan + the spec, checking:
- **Dynamic WHERE parity:** `search-by-blocks`'s conditional predicate order (`dist.parent_juris` → `api.district` → `api.nmms_block = ANY`), always-bound `app_state`, and that all 8 filter-presence combinations bind params correctly (no off-by-one, no leaked `spec` scratch variable from drafting).
- **The preserved bug:** `search-by-exam`'s `div`/`dist` both joining `api.district` — confirm the code comment and the pinning test both exist and neither was "fixed."
- **Fan-out parity:** no `DISTINCT`/`DISTINCT ON` added anywhere; a multi-interview-round applicant yields multiple result rows in both search endpoints.
- **Error-key split:** `{error:...}` on cascades/all-exams/filter-options/both-search-500s; `{message:...}` on search-by-exam's 400 and both download endpoints' 400/404/500 — confirm no cross-contamination.
- **XLSX exactness:** 21 vs 23 headers, fill colors `FFE6E6FA`/`FFE6F5E6`, `Number(x||0)` vs `x||'N/A'` vs raw-passthrough field lists, column width `min(max(len+2,15),50)` with empty-cell length 10, and — critically — the two independent `exam_date` formats (`M/d/yyyy` cell vs `yyyy_MM_dd` filename) are not accidentally derived from one shared formatter.
- **Filename builders:** both `sanitize()`+`_+`→`_` collapse behaviors match the JS regex semantics (`[^\w\s]` strip, `\s+`→`_`), including the exam-name 50-char truncation.
- **Auth:** class-level `@PreAuthorize("hasRole('ADMIN')")`; all handlers `public`; class package-private.
- **No transactions/writes:** confirm zero `@Transactional`, zero write repository — this module is 100% reads.
- **Closed enum for `filter-options`:** confirm `field` is never string-concatenated into SQL, and unknown values return `200 []`.

Update `imas-migration-status` memory: Phase 3d complete, new test count, ready for the next Phase-3 sub-module.

## Deferred / parity decisions carried into this plan

- **`calcPercentRank` omitted.** A full `PercentRank.INC`-style function exists in the Node controller (`resultandrankingController.js:121-143`) but is never invoked anywhere in the live request path — despite the module name, there is no ranking/percentile computation in this module at all (that logic lives in the separate Phase 2c shortlisting module, a different codebase area). Not ported; noted for the record only.
- **Three jurisdiction-cascade routes kept for strict parity** (`/divisions-by-state`, `/education-districts-by-division`, `/blocks-by-district`) even though the results-search frontend actually calls the equivalent `/api/exams/...` routes for its own cascades. A client could still call `/api/results/...` directly, so they're implemented identically rather than omitted/redirected.
- **`/filter-options/{field}` implemented despite being unused by the current UI** (`ResultandrankHooks.js` computes filter dropdown values client-side over already-fetched search results instead). Kept live and closed-enum-guarded for API completeness and to avoid a silent contract break for any other consumer.
- **`search-by-exam`'s `division_name == district_name` bug preserved deliberately**, not fixed — flagged with an explicit code comment and a pinning test (`searchByExamReproducesDivisionNameEqualsDistrictNameBug`). Distinct from `search-by-blocks`, which correctly resolves division via `dist.parent_juris`.
- **Fan-out via non-unique joins preserved** (`pp.exam_results`, `pp.student_interview`, `pp.home_verification` all lack a uniqueness constraint on `applicant_id`) — no `DISTINCT`/window dedup added in either search query, matching Node's plain `LEFT JOIN` exactly, even though this means a student with multiple interview rounds appears multiple times in results/exports.
- **Two independent `exam_date` formats in `download-by-exam`**, both implemented explicitly rather than one derived from the other: the sheet cell uses en-US locale `M/d/yyyy` (no leading zeros); the filename uses ISO/UTC `yyyy_MM_dd`.
- **Generic `500 {error:"Internal Server Error"}`** relies on the existing module-wide `GlobalExceptionHandler` fallback for unexpected failures on the 7 non-download endpoints, matching Node's per-route `catch(error){res.status(500).json({error:"Internal Server Error"})}` blocks; the two download endpoints instead need their failure path mapped explicitly to `{message:"Failed to generate excel file"}` (see the Task 3 implementation note) since that differs from the module's generic error-key default.
- **ADMIN enforcement is NEW** vs Node's fully-open `/api/results/**` routes (audit CRITICAL, all 9 endpoints). Add to the fetch audit.
