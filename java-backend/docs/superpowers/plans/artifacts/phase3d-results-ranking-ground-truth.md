# RESULTS & RANKING Module — Ground Truth (for Plan 3d)

Captured from a full read of the Node source. Base mount: `app.use("/api/results", resultandrankinkRoutes)` (`server/index.js:316`). Files: `server/routes/resultandrankinkRoutes.js` (26 lines, all live — no commented blocks), `server/controllers/resultandrankingController.js` (451 lines, all live), `server/models/resultandrankingModel.js` (174 lines, all live). No dead code blocks to ignore in this module, but see §7 for **dead/unreachable routes** (frontend never calls them) and **dead code inside the controller** (`calcPercentRank` is defined but never invoked — despite the file being named "resultandranking", there is **no actual ranking/percentile computation** anywhere in the live request path).

## 1. Endpoint Inventory (9 routes)

| # | Method | Path | Controller fn | Model fn | Notes |
|---|--------|------|----------------|----------|-------|
| 1 | GET | `/divisions-by-state/:stateId` | `fetchDivisionsByState` | `getDivisionsByState` | **Dead from this frontend** — `client/src/hooks/ResultandrankHooks.js` calls `/api/exams/divisions-by-state/...` instead (identical route also lives in `examRoutes.js` and `jurisdictionRoutes.js`). Keep for API parity; not exercised by the results UI. |
| 2 | GET | `/education-districts-by-division/:divisionId` | `fetchEducationDistrictsByDivision` | `getEducationDistrictsByDivision` | Same — frontend uses `/api/exams/education-districts-by-division/...`. |
| 3 | GET | `/blocks-by-district/:districtId` | `fetchBlocksByDistrict` | `getBlocksByDistrict` | Same — frontend uses `/api/exams/blocks-by-district/...`. |
| 4 | GET | `/all-exams` | `fetchAllExams` | `getAllExams` | Used by frontend (`useEffect` on mount). |
| 5 | POST | `/search-by-blocks` | `searchByBlocks` | `searchStudentsByBlocks` | Used. Body `{division, education_district, blocks, app_state=1}`. |
| 6 | POST | `/search-by-exam` | `searchByExam` | `searchStudentsByExam` | Used. Body `{exam_id}`; 400 if missing. |
| 7 | POST | `/download-by-blocks` | `downloadByBlocks` | `searchStudentsByBlocks` (reused) | Used. XLSX stream. |
| 8 | POST | `/download-by-exam` | `downloadByExam` | `searchStudentsByExam` (reused) | Used. XLSX stream. |
| 9 | GET | `/filter-options/:field` | `getFilterOptions` | inline `pool.query` (no model fn) | **Dead from this frontend** — the hook computes filter dropdown values client-side via `getUniqueValues(field)` over already-fetched `searchResults` (`ResultandrankHooks.js:299-304`), never calling this endpoint. Still live/reachable — carries the `:field` whitelist risk (see §2, §7). |

**Route-ordering hazard:** `router.get("/filter-options/:field", ...)` is registered **last** (line 24) in `resultandrankinkRoutes.js`, *after* the more specific GETs (`/all-exams` etc.), so there is no shadowing today. But `:field` is a single-segment wildcard GET — if a route like `GET /:something` were ever added before it, or if `/all-exams` were removed and `field` happened to equal `all-exams`, Express would still match `/filter-options/:field` correctly only because the literal prefix `/filter-options/` disambiguates it from `/all-exams`. No live collision, but flag for Java: map `/filter-options/{field}` as its own `@GetMapping` — do not let it become a catch-all under `/api/results/**`.

**`:field` whitelist risk:** `getFilterOptions` (`resultandrankingController.js:89-118`) switches on `field` and only allows exactly 4 literal values (see §2). Any other value returns `200 []` (not 404/400) via the `default:` branch — **no error, no SQL executed**. So while the column name is never actually interpolated from user input (it's a hardcoded `switch`), the *risk pattern* is a request-controlled dispatch key selecting one of several fixed queries — for Java this maps to a **closed enum** (`InterviewStatus`, `InterviewResult`, `VerificationStatus`, `PpExamCleared` — or one enum `FilterField` with 4 values), each bound to its own fixed, parameterless SQL string. Unknown enum value ⇒ return `200 []` to match Node exactly (do not throw 400 — that would be a behavior change).

## 2. Exact SQL (verbatim)

### 2.1 `getDivisionsByState(stateId)` — `resultandrankingModel.js:4-13`
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'DIVISION'
  AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```
(`$1` = stateId; column/table names are upper-case in source but Postgres folds unquoted identifiers to lower-case — behaves identically to `pp.jurisdiction`.)

### 2.2 `getEducationDistrictsByDivision(divisionId)` — lines 15-24
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'EDUCATION DISTRICT'
  AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```

### 2.3 `getBlocksByDistrict(districtId)` — lines 26-35
```sql
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'BLOCK'
  AND PARENT_JURIS = $1
ORDER BY JURIS_NAME
```

### 2.4 `getAllExams()` — lines 38-50
```sql
SELECT
  exam_id,
  exam_name,
  exam_date,
  exam_start_time,
  exam_end_time
FROM pp.examination
ORDER BY exam_date DESC
```
No params. No pagination — returns **all** rows in `pp.examination`.

### 2.5 `searchStudentsByBlocks(division, education_district, blocks, app_state)` — lines 54-119 (dynamic SQL, string-concatenated)

Base query (always present):
```sql
SELECT
  api.applicant_id,
  api.nmms_reg_number,
  api.student_name,
  api.father_name,
  api.gmat_score,
  api.sat_score,
  api.contact_no1,
  api.current_institute_dise_code,
  api.medium,
  si.institute_name as school_name,
  er.pp_exam_score,
  er.pp_exam_cleared,
  si_interview.status as interview_status,
  si_interview.interview_result,
  si_interview.remarks as interview_remarks,
  hv.status as verification_status,
  hv.remarks as verification_remarks,
  rr.rejection_reason as rejection_reasons,
  div.juris_name as division_name,
  dist.juris_name as district_name,
  blk.juris_name as block_name
FROM pp.applicant_primary_info api
LEFT JOIN pp.institute si ON api.current_institute_dise_code = si.dise_code
LEFT JOIN pp.exam_results er ON api.applicant_id = er.applicant_id
LEFT JOIN pp.student_interview si_interview ON api.applicant_id = si_interview.applicant_id
LEFT JOIN pp.home_verification hv ON api.applicant_id = hv.applicant_id
LEFT JOIN pp.rejection_reasons rr ON hv.rejection_reason_id = rr.rej_reason_id
LEFT JOIN pp.jurisdiction blk ON blk.juris_code = api.nmms_block
LEFT JOIN pp.jurisdiction dist ON dist.juris_code = api.district
LEFT JOIN pp.jurisdiction div ON div.juris_code = dist.parent_juris
WHERE api.app_state = $1
```
Then, **conditionally appended in this exact order** (each only if truthy / non-empty), with params pushed in append order (so `$2`, `$3`, `$4` positions depend on which filters are present):
```sql
 AND dist.parent_juris = $N        -- if division && division !== ''
 AND api.district = $N             -- if education_district && education_district !== ''
 AND api.nmms_block = ANY($N)      -- if blocks && blocks.length > 0  (array param)
```
Finally, always appended:
```sql
ORDER BY COALESCE(blk.juris_name, 'Unknown'), api.student_name
```
No `LIMIT`/`OFFSET` — returns **all** matching rows. No window functions, no RANK/ROW_NUMBER, **no ranking or percentile computation at all** despite the module name — this is a plain filtered projection.

`app_state` defaults to `1` at the controller (`req.body.app_state ?? 1` via destructuring default), always bound as `$1` and always present in the WHERE clause (not optional).

**Division derivation quirk:** `division_name` here is `div.juris_name` where `div.juris_code = dist.parent_juris` — i.e. division is the **parent of the applicant's district**, not looked up directly from `api`. There is no `api.division` column; division is inferred transitively through `district → parent_juris`.

### 2.6 `searchStudentsByExam(exam_id)` — lines 122-165 (static SQL, single param)
```sql
SELECT
  api.applicant_id,
  api.nmms_reg_number,
  api.student_name,
  api.father_name,
  api.gmat_score,
  api.sat_score,
  api.contact_no1,
  api.current_institute_dise_code,
  api.medium,
  si.institute_name as school_name,
  er.pp_exam_score,
  er.pp_exam_cleared,
  si_interview.status as interview_status,
  si_interview.interview_result,
  si_interview.remarks as interview_remarks,
  hv.status as verification_status,
  hv.remarks as verification_remarks,
  rr.rejection_reason as rejection_reasons,
  div.juris_name as division_name,
  dist.juris_name as district_name,
  blk.juris_name as block_name,
  e.exam_name,
  e.exam_date
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
WHERE ae.exam_id = $1
ORDER BY api.student_name
```
**Bug to reproduce verbatim (not fix silently):** both `div` and `dist` are joined `ON juris_code = api.district` — they resolve to **the same row**. So in this endpoint `division_name` is actually the **district's own name**, NOT the true parent division (unlike `searchStudentsByBlocks`, which correctly walks `dist.parent_juris`). This is a live discrepancy between the two search paths — port both behaviors exactly as-is (Java should reproduce the same "wrong" division_name for search-by-exam) unless the user explicitly asks to fix it; flag prominently in code comments/tests.

`INNER JOIN pp.applicant_exam ae` restricts to applicants who registered for that `exam_id`; PK on `applicant_exam` is `(applicant_id, exam_id)` composite, so this join cannot fan out rows by itself.

### 2.7 `getFilterOptions(field)` — `resultandrankingController.js:89-118` (dynamic dispatch, NOT string-interpolated SQL)
```js
switch(field) {
  case 'interview_status':
    query = `SELECT DISTINCT status as value FROM pp.student_interview WHERE status IS NOT NULL`;
    break;
  case 'interview_result':
    query = `SELECT DISTINCT interview_result as value FROM pp.student_interview WHERE interview_result IS NOT NULL`;
    break;
  case 'verification_status':
    query = `SELECT DISTINCT status as value FROM pp.home_verification WHERE status IS NOT NULL`;
    break;
  case 'pp_exam_cleared':
    query = `SELECT DISTINCT pp_exam_cleared as value FROM pp.exam_results WHERE pp_exam_cleared IS NOT NULL`;
    break;
  default:
    return res.json([]);   // 200, empty array, no query run
}
const result = await pool.query(query);
const values = result.rows.map(row => row.value);
res.json(values);
```
No parameters bound in any branch (`pool.query(query)` — no args array). This is **not** a raw column-name interpolation vulnerability (all 4 SQL strings are hardcoded literals in the switch), but it **is** a request-controlled routing key selecting among fixed SQL statements — for Java, model as a closed `enum`/`Map<String,String>` lookup, default → empty list, HTTP 200 (never throw for unknown field).

## 3. Table DDL Facts (from `docs/superpowers/plans/artifacts/live-schema.sql`)

- **`pp.jurisdiction`** (line 1177): `juris_code numeric(12,0) NOT NULL` (PK, line 2689), `juris_name varchar(100)`, `juris_type varchar(100)` (FK → `pp.jurisdiction_type`, line 3840), `parent_juris numeric(12,0)` (self-FK → `juris_code`, line 3849), audit cols. Values used: `'DIVISION'`, `'EDUCATION DISTRICT'`, `'BLOCK'` for `juris_type`.
- **`pp.examination`** (line 907): `exam_id numeric(14,0) DEFAULT nextval('pp.examination_seq') NOT NULL` (PK, line 2625), `exam_name varchar(100) NOT NULL`, `exam_date date NOT NULL`, `exam_start_time time NOT NULL`, `exam_end_time time NOT NULL`, `pp_exam_centre_id numeric(10,0)` (FK), `frozen_yn char(1) DEFAULT 'N'` (CHECK Y/N), `exam_year varchar(10)`, audit cols (`created_by`/`updated_by` FK → `pp."user"`).
- **`pp.applicant_primary_info`** (line 166): `applicant_id numeric(14,0) DEFAULT nextval('pp.applicant_id_seq') NOT NULL` (PK, line 2401), `nmms_reg_number numeric(11,0) NOT NULL` (UNIQUE, line 2393), `app_state numeric(12,0)` (FK → jurisdiction), `district numeric(12,0)` (FK → jurisdiction), `nmms_block numeric(12,0)` (FK → jurisdiction), `student_name/father_name/mother_name varchar(100)`, `gmat_score/sat_score numeric(2,0)`, `gender char(1)` (CHECK M/F/O), `medium varchar(50)`, `contact_no1/contact_no2 varchar(12)`, `current_institute_dise_code/previous_institute_dise_code varchar(15)` (FK → institute, ON DELETE SET NULL), `students_sats_id numeric(11,0)`, audit cols. No `division` column — division is only reachable via `district → parent_juris`.
- **`pp.applicant_exam`** (line 84): composite PK `(applicant_id, exam_id)` (line 2722), plus `UNIQUE(hall_ticket...)` constraint `unique_hall_ticket` (line 3049 — on `pp_hall_ticket_no` presumably), `pp_hall_ticket_no varchar(20)`. FKs to `applicant_primary_info` and `examination`. **No unique constraint problem** for the search-by-exam join (composite PK guarantees ≤1 row per applicant per exam).
- **`pp.exam_results`** (line 877): **no declared PK, no unique constraint** on `applicant_id` — only an FK (line 3689). Columns: `applicant_id numeric(14,0)`, `pp_exam_score numeric(3,0)`, `pp_exam_cleared char(1)` (CHECK Y/N), `interview_required_yn char(1)` (CHECK Y/N). **Multiple rows per applicant_id are schema-legal** → the LEFT JOIN in both search queries can fan out result rows if duplicate `exam_results` rows exist for an applicant (data-integrity assumption, not enforced by DB).
- **`pp.student_interview`** (line 1638): PK `interview_id numeric(12,0)` (own sequence `pp.interview_id_seq`), `applicant_id numeric(14,0)` (FK, no unique) — **schema allows multiple interviews per applicant** (e.g. re-interview rounds via `interview_round`). The LEFT JOIN on `api.applicant_id = si_interview.applicant_id` with no `interview_round`/latest-only filter means **a student with 2+ interview rows produces 2+ result rows** in both search endpoints — this is a real fan-out risk, not just theoretical (the CHECK constraints include `'ANOTHER INTERVIEW REQUIRED'` as a valid `interview_result`, implying multi-round interviews are an expected real scenario). `status` CHECK ∈ {SCHEDULED, COMPLETED, CANCELLED, RESCHEDULED}; `interview_result` CHECK ∈ {SELECTED, REJECTED, ANOTHER INTERVIEW REQUIRED}.
- **`pp.home_verification`** (line 1009): PK `verification_id numeric(12,0)`, `applicant_id numeric(14,0)` (FK, no unique) — same fan-out risk as student_interview if multiple verification attempts exist per applicant. `status` CHECK ∈ {PENDING, SCHEDULED, REJECTED, ACCEPTED}; `verification_type` CHECK ∈ {PHYSICAL, VIRTUAL}; `rejection_reason_id` FK → `pp.rejection_reasons`.
- **`pp.rejection_reasons`** (line 1329): PK `rej_reason_id numeric(4,0)`, `rejection_reason varchar(200) NOT NULL`. Simple lookup table.
- **`pp.institute`** (line 1064): PK `institute_id`, **`dise_code varchar(15)` UNIQUE** (line 2665 — this is what `applicant_primary_info.current_institute_dise_code` FKs to), `institute_name varchar(200)`, plus many CHECK-constrained categorical columns (board, management type, etc. — not used by this module).

**Fan-out summary:** the only guaranteed-safe (≤1-row) join in this module is `applicant_exam` (composite PK). `exam_results`, `student_interview`, `home_verification` all lack a uniqueness guarantee on `applicant_id`, so both `searchStudentsByBlocks` and `searchStudentsByExam` can silently return duplicate/multiplied rows per applicant if any of those tables ever has >1 row for the same applicant. Reproduce the plain `LEFT JOIN` (no dedup/first-row logic) faithfully — do not "fix" this by adding `DISTINCT ON` unless asked, since Node doesn't do it either.

## 4. Response Shapes & Status Codes

- **`GET /divisions-by-state/:stateId`** → `200 [{id, name}, ...]` (bare array, from `res.json(divisions)`); `500 {error:"Internal Server Error"}`.
- **`GET /education-districts-by-division/:divisionId`** → `200 [{id, name}, ...]`; `500 {error:"Internal Server Error"}`.
- **`GET /blocks-by-district/:districtId`** → `200 [{id, name}, ...]`; `500 {error:"Internal Server Error"}`.
- **`GET /all-exams`** → `200 [{exam_id, exam_name, exam_date, exam_start_time, exam_end_time}, ...]` (raw pg row objects — numeric `exam_id` returned as pg's default numeric mapping, i.e. **string** for `numeric` type via node-pg unless a type parser override is configured elsewhere — check global `pg` config; not overridden in this file). `500 {error:"Internal Server Error"}`.
- **`POST /search-by-blocks`** → `200 [{...21 fields...}, ...]` bare array (same shape as XLSX row source, §5); no special envelope, no pagination metadata, no count. `500 {error:"Internal Server Error"}`. Body validation: none — `division`/`education_district`/`blocks` are all optional; `app_state` defaults to `1` if omitted.
- **`POST /search-by-exam`** → `200 [{...23 fields (adds exam_name, exam_date)...}, ...]`; `400 {message:"Exam ID is required"}` if `exam_id` falsy; `500 {error:"Internal Server Error"}`. **Note the differing error-body key convention**: this endpoint uses `message` for its 400, while search-by-blocks/divisions/etc. use `error` for their 500s — preserve both keys exactly, do not normalize.
- **`POST /download-by-blocks`** → `200` binary XLSX stream (see §5); `404 {message:"No data found"}` if zero rows; `500 {message:"Failed to generate excel file"}` (note: `message` not `error` here too).
- **`POST /download-by-exam`** → `200` binary XLSX stream; `400 {message:"Exam ID is required"}`; `404 {message:"No data found for this exam"}`; `500 {message:"Failed to generate excel file"}`.
- **`GET /filter-options/:field`** → `200 [<value>, ...]` bare array of scalar strings (`row.value` extracted); unknown `field` → `200 []`; `500 {error:"Internal Server Error"}`.

Field list returned per search row (`searchStudentsByBlocks`/`searchStudentsByExam`, raw pg row — no camelCase transform, no explicit type coercion at the model layer):
`applicant_id, nmms_reg_number, student_name, father_name, gmat_score, sat_score, contact_no1, current_institute_dise_code, medium, school_name, pp_exam_score, pp_exam_cleared, interview_status, interview_result, interview_remarks, verification_status, verification_remarks, rejection_reasons, division_name, district_name, block_name` (+ `exam_name, exam_date` for search-by-exam only).

`numeric(...)` columns (`applicant_id`, `nmms_reg_number`, `gmat_score`, `sat_score`, `pp_exam_score`) come back through node-pg's default numeric handling for this project (verify against other modules' established convention — per `RESUME-migration.md` LOCKED convention #3, Java should serialize numeric ids as Strings to match observed Node/pg behavior, except where a module already established Number passthrough; apply the same rule here for `applicant_id`/`nmms_reg_number`; `gmat_score`/`sat_score`/`pp_exam_score` are small numerics likely returned as JS strings too by pg — confirm against a live sample before committing to type in Java DTOs).

## 5. File-Generating Endpoints

Both use **`exceljs`** (`const ExcelJS = require("exceljs")`, controller line 9) — **not** the same library as other modules that may use `xlsx`/POI on the Node side; note Java side already standardized on Apache POI per Phase 2a, so this is a library-equivalence port (ExcelJS → POI), not a new dependency.

### `downloadByBlocks` (`resultandrankingController.js:146-296`)
- Body: `{division, district, blocks, app_state=1}` — **note the body key is `district`, not `education_district`** as used by `/search-by-blocks` (frontend maps `formData.education_district` → `district` for this call, see `ResultandrankHooks.js:214`). Reuses `searchStudentsByBlocks(division, district, blocks, app_state)` (positional — `district` param fills the `education_district` model parameter).
- `404 {message:"No data found"}` if `results.length === 0`.
- Workbook: 1 sheet `"Results"`. Header row bold, filled `FFE6E6FA` (light lavender) solid pattern.
- **Exact 21 headers, in order:** `Applicant ID, NMMS Number, Student Name, Father Name, GMAT Score, SAT Score, PP Exam Score, PP Exam Cleared, Interview Status, Interview Result, Interview Remarks, Verification Status, Verification Remarks, Rejection Reasons, Contact Number, School DISE Code, Medium, School Name, Division, District, Block`.
- Data row mapping: `gmat_score`/`sat_score`/`pp_exam_score` → `Number(x || 0)` (never null in the file — 0 default); most text fields → `x || 'N/A'` fallback (`pp_exam_cleared, interview_status, interview_result, interview_remarks, verification_status, verification_remarks, rejection_reasons, division_name, district_name, block_name` all get `'N/A'` if falsy); `contact_no1`, `current_institute_dise_code`, `medium`, `school_name`, `applicant_id`, `nmms_reg_number`, `student_name`, `father_name` are passed through **as-is with no fallback** (can be `null`/blank in the sheet cell).
- Column width: auto-computed per column = `min(max(maxCellTextLength+2, 15), 50)` (min 15, max 50, based on longest stringified cell in that column across all rows incl. header).
- **Filename generation** (non-trivial, must reproduce): starts `"results"`; if `division` truthy, appends `_<firstUniqueDivisionNameFromResults, alnum/space chars only, spaces→underscore>` else falls back to `Division_<id>` if no rows had a division_name; else (`division` falsy) appends `_All_Divisions`. Same pattern for `district`→`_All_Districts` using `uniqueDistricts[0]`/`District_<id>`. For `blocks`: if `blocks && blocks.length>0`: if exactly 1 unique block name in results → `_<blockName>`; if >1 unique → `_<N>_Blocks`; if the (impossible in practice since blocks were requested but produced 0 unique names) `else` branch → `_Selected_Blocks`; if `blocks` falsy/empty → `_All_Blocks`. Then `+= '.xlsx'`, then a final global cleanup `fileName.replace(/_+/g, '_')` (collapses repeated underscores from empty name-parts).
- Headers set: `Content-Disposition: attachment; filename="<fileName>"`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. Written via `workbook.xlsx.write(res)` directly to the response stream — **no disk write, no temp file**.
- `500 {message:"Failed to generate excel file"}` on any exception during generation (caught around the whole handler).

### `downloadByExam` (`resultandrankingController.js:299-439`)
- Body: `{exam_id}` — `400 {message:"Exam ID is required"}` if missing.
- Reuses `searchStudentsByExam(exam_id)`; `404 {message:"No data found for this exam"}` if empty.
- Workbook: 1 sheet `"Exam Results"`. Header fill `FFE6F5E6` (light green) — **different color from downloadByBlocks**.
- **Exact 23 headers** (21 shared + 2 more): same first 21 as downloadByBlocks, **plus** `Exam Name, Exam Date`.
- Same cell-value fallback rules as downloadByBlocks for the shared 21 columns. Additional: `exam_name || 'N/A'`; `exam_date` → `result.exam_date ? new Date(result.exam_date).toLocaleDateString() : 'N/A'` — **locale-dependent date formatting** (Node server locale, typically `en-US` → `M/D/YYYY`); must pin the exact format in Java (e.g. `MM/dd/yyyy` — verify server locale/timezone assumption; `exam_date` is a `date` column with no time component, so no TZ shift risk from the date itself, but `toLocaleDateString()`'s format depends on the Node process locale — confirm and hardcode rather than relying on JVM default locale).
- Same auto column-width logic (15–50).
- **Filename generation:** `"results"` + `_<examName sanitized, alnum/space only, spaces→underscore, truncated to 50 chars>` from `results[0].exam_name` (falls back to `_Exam` if missing) + optionally `_<examDate as YYYY_MM_DD>` from `results[0].exam_date` via `toISOString().split('T')[0].replace(/-/g,'_')` (**note: ISO/UTC-based, unlike the human-readable column value which uses `toLocaleDateString()`** — two different date formats used in the same handler, one for the filename (ISO/UTC) one for the cell (locale) — reproduce both distinctly) + `.xlsx`, then same `_+`→`_` cleanup.
- Same headers/content-type; same `workbook.xlsx.write(res)` direct-to-stream, no disk write.
- Same `500 {message:"Failed to generate excel file"}` catch-all.

## 6. Transactions

**All 9 endpoints are single autocommit reads** (`pool.query(...)` once per request, no `pool.connect()`/`BEGIN`/`COMMIT` anywhere in this module). No writes at all — the entire Results & Ranking module is read-only against existing data (search, list, export). Confirmed by grep: zero occurrences of `INSERT`/`UPDATE`/`DELETE`/`BEGIN` in `resultandrankingController.js` or `resultandrankingModel.js`.

## 7. Quirks & Complexity Warnings

1. **No ranking/percentile logic actually runs** (`resultandrankingController.js:121-143`, `calcPercentRank`). A full `PercentRank.INC`-style implementation exists (sort ascending, linear interpolation between bracketing values, `0` below min / `100` at/above max) but **is never called** — dead code. Despite the module/file names ("resultandranking"), this is purely a **filtered search + XLSX export** module; do not port any ranking computation here (that logic, if needed, lives in the separate shortlisting module per `RESUME-migration.md`'s PERCENT_RANK note for Phase 2c/shortlist, a different codebase area entirely — confirm no cross-reference needed). Decide explicitly whether to port the dead function at all (recommend: omit, note removal in the plan).
2. **Three dead-from-frontend routes**: `/divisions-by-state/:stateId`, `/education-districts-by-division/:divisionId`, `/blocks-by-district/:districtId` under `/api/results` are byte-identical in behavior to routes already implemented (presumably) under `/api/exams` (`examRoutes.js:42-44`) and `/api/jurisdiction` (`jurisdictionRoutes.js:7-9`). Confirm with whoever owns Phase 1/3a whether those were already ported; if so, this phase can either (a) re-implement identically for contract parity (cheap, ~3 GET handlers, no new SQL) or (b) explicitly document as intentionally omitted/redirected. Recommend (a) for strict parity since nothing forbids a client from calling `/api/results/...` directly.
3. **`filter-options/:field` dispatch key** (`resultandrankingController.js:89-118`) — closed set of 4 literal `field` values (`interview_status`, `interview_result`, `verification_status`, `pp_exam_cleared`), each mapped to a hardcoded, parameterless SQL string; unmatched → `200 []` (not an error). Port as a Java enum/`switch` with the same 4 branches + default-empty-list behavior; **do not** parameterize/build the column name dynamically even though it superficially looks like a "field" input — it must stay a closed enum mapping, never string-concatenated into SQL.
4. **`searchStudentsByExam`'s `div`/`dist` both join on `api.district`** (`resultandrankingModel.js:155-156`) — this is very likely an unintentional bug (search-by-blocks correctly resolves division via `dist.parent_juris`, line 86, but search-by-exam does not). The result: `division_name` in exam-search responses/downloads is actually the **district name**, duplicated. **Port this exact behavior** (do not silently fix) — flag it in code review and let the user decide whether to correct it as a deliberate improvement, matching the precedent set for the two known shortlisting bugs noted in `RESUME-migration.md`.
5. **Fan-out risk via non-unique joins** on `pp.exam_results`, `pp.student_interview`, `pp.home_verification` (§3) — none of these tables has a uniqueness constraint on `applicant_id`. If any applicant has multiple interview rounds or multiple verification attempts, both search queries return duplicate applicant rows (one per interview/verification row, cross-joined). This is schema-legal today (CHECK constraints even anticipate multi-round interviews via `'ANOTHER INTERVIEW REQUIRED'`). Reproduce the plain `LEFT JOIN` faithfully; do not add `DISTINCT`/`ROW_NUMBER() OVER (PARTITION BY applicant_id ORDER BY ...)` filtering unless explicitly requested, since that would change output cardinality versus Node.
6. **Dynamic WHERE-clause construction** in `searchStudentsByBlocks` (string concatenation with incrementing `$N` placeholders based on which of `division`/`education_district`/`blocks` are present) is parameterized correctly (values always bound via `params.push(...)`, never interpolated) — safe from SQL injection, but the **positional parameter count varies per call shape**; in Java, build this with a query builder (e.g. StringBuilder + `MapSqlParameterSource`) rather than positional `?` to avoid off-by-one param errors across the 8 possible filter-presence combinations (2^3).
7. **`app_state` default `= 1`** applies to both `search-by-blocks` and `download-by-blocks` (destructured with `= 1` default at the controller, `resultandrankingController.js:62` and `:148`) — always bound as a real WHERE parameter, never optional-out. Confirm `1` is a real, meaningful `juris_code` (likely a specific state) before hardcoding in Java — check with `pp.jurisdiction` data if available, otherwise treat as an opaque default matching Node.
8. **`downloadByBlocks` body key mismatch**: the download endpoint's body field is `district` (not `education_district` as the `/search-by-blocks` endpoint uses) — frontend explicitly renames it (`ResultandrankHooks.js:214`, `district: formData.education_district`). Both ultimately bind to the same 2nd positional model parameter (`education_district`) inside `searchStudentsByBlocks`. Java controllers for the two POST endpoints will need **different request DTOs** (or a shared DTO with both field name and an alias) to keep exact wire compatibility — do not unify the JSON key across both endpoints.
9. **Two different date formats in `downloadByExam`** for the same underlying `exam_date` value: sheet cell uses `toLocaleDateString()` (locale-dependent, e.g. `M/D/YYYY` under default Node locale), filename uses `toISOString().split('T')[0]` (`YYYY-MM-DD` → `YYYY_MM_DD` after the global underscore substitution). Pin both formats explicitly and independently in Java; do not let one derive from the other.
10. **Inconsistent error-body key convention across this module**: `GET` cascade/list endpoints and both search endpoints' 500s use `{error:...}`; `search-by-exam`'s 400 and **both download endpoints' 400/404/500** use `{message:...}`. Preserve per-endpoint, not module-wide — this is an existing Node inconsistency, not a deliberate contract.
11. **No pagination anywhere** in this module — `all-exams`, `search-by-blocks`, `search-by-exam` all return full result sets in one response; large applicant tables could mean large payloads/large XLSX files generated in-memory (`exceljs` `Workbook` built fully in memory, then streamed) — acceptable to port as-is (matches Node), but note as an operational risk if data volume grows (out of scope to fix here).
