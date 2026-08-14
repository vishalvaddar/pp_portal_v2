# NMMS MERGE Module — Ground Truth (for Plan 2b)

Captured from a full read of the Node source. Base mount: `app.use('/api/merge', mergeRoutes)` (server/index.js:284). Router uses `multer({ storage: memoryStorage() })` — CSVs processed as in-memory buffers, never written to disk. Files: `controllers/mergeController.js`, `models/mergeModel.js`, `routes/mergeRoutes.js`. **Note:** roughly half of `mergeModel.js` (lines 668-1233) and a duplicate router block are commented-out ES-module legacy code — ignore it; the live code is CommonJS lines 1-666.

## 1. Endpoint Inventory (17 routes under `/api/merge`)

| # | Method | Path | Purpose |
|---|--------|------|---------|
| 1 | GET | `/jurisdiction` | Dropdown data; `?type=STATE|DIVISION|EDUCATION DISTRICT&parent=<code>` |
| 2 | GET | `/merged-status` | Committed-district summary from `applicant_primary_info` (getMergedDistricts) |
| 3 | GET | `/district/:districtId/download-csv` | Blob CSV of `std_applicant_primary_info` for a district |
| 4 | POST | `/upload-p1` | Multipart CSV → stage Phase-1 applications (`upload.single("file")`) |
| 5 | POST | `/upload-p2` | Multipart CSV → stage Phase-2 results (`upload.single("file")`) |
| 6 | GET | `/applications` | Paginated Phase-1 staged rows (limit 50) |
| 7 | GET | `/results` | Paginated Phase-2 staged rows (limit 50) |
| 8 | POST | `/preview-merge` | Fuzzy match preview; returns summary + block-wise conflicts |
| 9 | POST | `/bulk-auto-map` | Copy all unique (1:1) matches into draft `std_applicant_primary_info` |
| 10 | POST | `/resolve-lively` | Resolve one manual conflict (app_id ↔ res_id) + domino auto-match |
| 11 | POST | `/commit-to-primary` | Freeze draft → `applicant_primary_info` |
| 12 | GET | `/draft-districts` | Draft-area district list w/ merged counts |
| 13 | GET | `/draft-district-students` | Student rows for a draft district (`?district&year`) |
| 14 | DELETE | `/delete-district-data` | Delete staged/draft data for a district (guarded) |
| 15 | GET | `/download-template` | Blob CSV header-only template (`?phase=p1|p2`) |
| 16 | GET | `/commit-status` | Per-district committed vs total (`?year`) |
| 17 | GET | `/merge-status` | Per-district merged vs total + `ismerged` flag (`?year`) |

## 2. Merge Workflow End-to-End

**Staging (upload-p1).** Body: multipart `file`, `year`, `state_id`, `district_id`. Parser: `csv-parse/sync` with a header transform that **strips BOM (`﻿`) and trims** each column name. Steps: (a) reject if `COUNT(*) > 0` for `(district, year)` — no re-upload; (b) look up state/district `juris_name`; (c) `loadBlocks` → Map of `normalizeText(juris_name) → juris_code` for blocks where `parent_juris = district_id`; (d) pre-fetch valid DISE codes via `SELECT dise_code FROM pp.institute WHERE dise_code = ANY($1)`. Per-row validation: year must equal `String(year)`; `app_state` must match selected state (`normalizeText`); `district` must match (dots stripped); block must resolve via blockMap else a `string-similarity` suggestion is emitted; DISE must be in valid set. **All-or-nothing:** if any `logs` entry exists → ROLLBACK, return `{success:false, logs}`. Else batch-insert 5000/query into `stg_nmms_phase1_applications`, storing computed `student_name_key`.

**Staging (upload-p2).** Body: multipart `file`, `year`, `district_id` (no state). Parser: `csv-parse/sync` with `columns:true` (no BOM strip — quirk). Duplicate guard same. Per-row: block resolve (+suggestion), `nmms_reg_number` must match `/^\d{8,12}$/`, `student_name` must match `/^[A-Za-z\s.]+$/`. Note: reg/name failures set `rowError` but push **no log**, so if all failures are reg/name-only, `logs` stays empty and the batch silently inserts only valid rows (subtle bug to preserve or fix deliberately). Batch-insert into `stg_nmms_phase2_results` with `match_status` defaulting to `'PENDING'`.

**The name key (matching primitive).** `generateStudentNameKey(name) = name.toLowerCase().replace(/[^a-z0-9]/g,"")`. Stored as `student_name_key` on both staging tables. **This is the deterministic join key — not a fuzzy score.**

**Preview (preview-merge).** Body `{year, district}`. LEFT JOIN phase1↔phase2 on **`student_name_key` AND `nmms_block` AND `district` AND `nmms_year` AND `r.match_status IS DISTINCT FROM 'MATCHED'`**. Groups rows by `phase1_id` into `candidates[]`. Counts: `candidates.length === 1` → `mapped`; `> 1` → `conflicts`; `0` → unmatched. Response: `{ summary:{ total_students, mapped, conflicts }, blockWise:{ <block_name>: [app,...] } }` where each app carries `phase1_id, student_name, father_name, students_sats_id, contact_no1, institute_name, nmms_block, block_name, candidates[]` and each candidate `{ result_stg_id, nmms_reg_number, student_name, gmat_score, sat_score }`.

**Frontend orchestration (NMMSMerge.js `runMergeLookup`):** calls `/preview-merge`; if `summary.mapped > 0`, auto-calls `/bulk-auto-map`, then re-calls `/preview-merge` to refresh. So auto-map fires implicitly on lookup — only true 1:1 uniques get moved; multi-candidate blocks remain for manual resolution.

**Bulk auto-map.** `moveMappedToStdModel(district, year, userId)` — INSERT…SELECT into `std_applicant_primary_info` joining on `LOWER(REGEXP_REPLACE(student_name,'[^a-zA-Z0-9]','','g'))` + `nmms_block`, filtered to phase1 ids where the same-name group `HAVING COUNT(*) = 1` (uniqueness guarantee), `r.match_status != 'MATCHED'`, `ON CONFLICT (nmms_reg_number) DO NOTHING`. Then marks matched phase2 rows `'MATCHED'`. `user_id = req.user?.user_id || 1`.

**Manual resolve (resolve-lively).** Body `{app_id, res_id}`. Loads the two staged rows, INSERTs one `std_applicant_primary_info` row, marks that phase2 `'MATCHED'`, then a **domino check**: re-queries remaining same-name+block app/result rows; if exactly one each remain, auto-inserts that pair too and marks it MATCHED. Wrapped in BEGIN/COMMIT/ROLLBACK. Frontend loops calling this once per link in `localLinks`.

**Commit (commit-to-primary).** Body `{district, year}`. INSERT…SELECT from `std_applicant_primary_info` → `applicant_primary_info` for the district/year, `ON CONFLICT (nmms_reg_number) DO NOTHING`. **`applicant_secondary_info` is NOT touched by this module** — merge only populates primary; secondary is filled by other flows keyed on `applicant_id`.

## 3. Transactions & Dynamic-Table Risk

`deleteDistrictDataModel(district, type)` (mergeModel.js:378-383) — the auditor flag:
```js
let table = type === "p1" ? "pp.stg_nmms_phase1_applications"
          : type === "p2" ? "pp.stg_nmms_phase2_results"
          : type === "merge" ? "pp.std_applicant_primary_info" : null;
if (!table) throw new Error("Invalid type for deletion");
await pool.query(`DELETE FROM ${table} WHERE district = $1`, [district]);
```
Table name is string-interpolated into SQL. It is switch-guarded to exactly **three tables** (never the `type` from the request directly), so not currently exploitable — but for Java use a fixed `enum`/whitelist mapping (`p1→stg_nmms_phase1_applications`, `p2→stg_nmms_phase2_results`, `merge→std_applicant_primary_info`) and parameterize district. Controller guards (`deleteDistrictData`): requires `district`+`year`; for `section==="merge"` refuses if `checkApplicantPrimaryModel` (already committed); else refuses if `checkStdPrimaryModel` (already drafted). **Transactional models** (use `pool.connect()` + BEGIN/COMMIT/ROLLBACK): uploadPhase1, uploadPhase2, resolveMatch, moveMappedToStd, commitToPrimary. All others are single autocommit queries.

## 4. File Handling

- **Multipart field name:** `file` (both uploads). Multer memory storage → `req.file.buffer`.
- **Extra body fields:** p1 = `year, state_id, district_id`; p2 = `year, district_id`.
- **Parse lib:** `csv-parse/sync` (`parse`). P1 header transform trims + strips BOM; P2 uses `columns:true` (no BOM strip).
- **CSV writer:** `json2csv` `Parser` for template + district CSV downloads.
- **Templates (`/download-template?phase=`):** header-only CSV (`csvData=[{}]`). p1 fields: `nmms_year, Exam, app_state, district, nmms_block, current_institute_dise_code, students_sats_id, student_name, father_name, institute_name, institute_type, category_name, disability_status, contact_no1, contact_no2, date_of_application`. p2 fields: `nmms_year, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, total`. Sent with `Content-Type: text/csv`, `res.attachment("NMMS_<phase>_Template.csv")`. Frontend consumes as `responseType:"blob"`.
- **Note mismatch:** template advertises p1 columns (`institute_type, category_name, disability_status, date_of_application`) that the p1 insert never persists; p2 template has `total` which is never read.
- **Storage:** all data lives in Postgres staging tables; **no filesystem** persistence of uploads.

## 5. Response Shapes & Status Codes

- **upload-p1:** `200 {success:true, logs:[...]}` / `400 {success:false, logs:[...]}` (validation or dup) / `400 {error:"No CSV file provided"}` / `500 {logs:[...]}`.
- **upload-p2:** returns model result directly (`{success, logs}`), `200`; `500 {logs:["Result upload failed"]}`.
- **preview-merge:** `200 { summary:{total_students,mapped,conflicts}, blockWise:{...} }` / `500 {error:"Merge preview failed"}`.
- **bulk-auto-map:** `200 {message:"Bulk mapping successful. Records copied to draft."}`.
- **resolve-lively:** `200 {message:"Mapped successfully"}` / `500 {error:"Mapping failed"}`.
- **commit-to-primary:** `200 {message:"Successfully committed to Primary Table."}` / `500 {error:"Failed to finalize merge."}`.
- **download-template / district download-csv:** raw CSV body; district CSV `404 {message:"No data found..."}` when empty.
- **commit-status / merge-status:** `200 {data:[...]}`; `400 {error:"Year is required"}` if no year.
- **delete-district-data:** `200 {message}`; `400 {error}` (missing field, already-committed/merged guard).
- **applications/results:** `200 {rows:[...], totalPages:N}`.

## 6. Data Quirks

- **Numeric-as-string in staging:** `stg_nmms_phase1_applications.nmms_year` is `text`, `district/app_state/nmms_block` `numeric(12,0)`, `students_sats_id/dise_code/contact_no1/contact_no2` `text`. `stg_nmms_phase2_results.nmms_year` `text`, `nmms_reg_number/gmat_score/sat_score` `text`. Casts to numeric happen only at commit-time (`$::numeric`, `NULLIF(regexp_replace($, '\D','','g'),'')::numeric` for SATS).
- **Score sentinels:** `gmat_score`/`sat_score` may be literal `'AB'` (absent) → converted to `'0'`; `moveMappedToStd` also treats NULL → `'0'`. Target columns are `numeric(2,0)` (values 0-99 only). `isScoreValid` allows `'A'` but it's unused in the live path.
- **DISE code:** `varchar(15)` in primary; validated `/^\d{11}$/` conceptually but only checked against `pp.institute` membership after stripping non-digits.
- **Type joins:** cross-table joins cast both sides (`::numeric`, `::text`) because staging `nmms_year` is text vs primary `numeric(4,0)` — a persistent impedance mismatch to reproduce carefully in native SQL.
- **NMMS field mapping (commit → applicant_primary_info):** `nmms_year, nmms_reg_number (from phase2), students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by`. `nmms_reg_number` originates only in Phase-2; Phase-1 supplies demographics. `mother_name, gender, dob, aadhaar` etc. are left NULL by merge.
- **Conflict key:** `applicant_primary_info` / `std_applicant_primary_info` PK-conflict resolution is on `nmms_reg_number` (`numeric(11,0) NOT NULL`).
- **Validation regexes to port verbatim:** year `/^\d{4}$/`, DISE `/^\d{11}$/`, SATS `/^\d{8,12}$/`, name `/^[A-Za-z\s.]+$/`, phone `/^[6-9]\d{9}$/`, reg `/^\d{8,12}$/`.

## 7. Complexity Warnings (hardest to port + test focus)

1. **`string-similarity` determinism.** Used in two places with different thresholds: (a) `getSuggestion` uses `stringSimilarity.findBestMatch(input, validNames).bestMatch.rating > 0.5` (Dice coefficient over bigrams); (b) `suggestValue` (block suggestions in uploads) is a **hand-rolled prefix-char-match ratio > 0.4**, NOT the library. Java has no drop-in Dice/`string-similarity` equivalent — must reimplement the exact bigram Dice algorithm and the exact prefix-ratio heuristic, and golden-test both against Node output. These only produce *suggestion text* (never auto-decide), but log strings must match for test parity.
2. **The real match is deterministic** (`student_name_key` equality), not fuzzy — this is the safe part but the two matching notions (deterministic key join vs fuzzy suggestion) must not be conflated.
3. **Domino auto-match in resolve-lively** — re-queries after each manual resolve; order-dependent and easy to get subtly wrong under concurrency. Needs dedicated transactional tests.
4. **All-or-nothing upload semantics** (any log ⇒ full rollback) vs p2's silent partial-insert on reg/name failures — divergent behavior between the two uploads; decide intentionally.
5. **BOM/whitespace header handling** differs between p1 and p2 parsers — reproduce exactly or normalize both.
6. **Multi-cast joins** across text/numeric columns — replicate casts precisely; a naive mapping will break on `nmms_year` text↔numeric.
7. **`ON CONFLICT (nmms_reg_number) DO NOTHING`** idempotency at both auto-map and commit — must map to Postgres upsert semantics.
8. **`user_id` fallback `|| 1`** and `req.user` dependence — auth context wiring for `created_by`.

### Dice coefficient reference (for the golden-test reimplementation)
`string-similarity` (v4) `compareTwoStrings(a,b)`: if both length<2 special-cases; build a multiset of adjacent bigrams of `a`, then for each bigram of `b` consume a matching one from `a`; `dice = 2*matches / (a.length-1 + b.length-1)`. `findBestMatch` returns the target with the highest rating. Whitespace is NOT stripped by the library (bigrams include spaces). Reproduce exactly and golden-test with known name pairs from real data.
