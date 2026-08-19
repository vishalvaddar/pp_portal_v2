# SHORTLISTING Module — Ground Truth (for Plan 2c)

Captured from a full read of the Node source. Route mounting (`server/index.js`): `app.use("/api/shortlist/generate", generateShortlistRoutes)` and `app.use("/api/shortlist-info", shortlistInfoRoutes)`. DB is PostgreSQL, schema `pp`, via `pg` `pool`. Frontend base: `${REACT_APP_BACKEND_API_URL}/api/shortlist-info` and `.../api/shortlist/generate`.

## 1. Endpoint Inventory

**`/api/shortlist/generate`** (generateShortlistRoutes.js):
- `GET /allstates` — list all `juris_type='state'` rows.
- `GET /divisions/:stateName` — divisions whose parent is the named state.
- `GET /districts/:divisionName` — `juris_type='education district'` under the named division.
- `GET /blocks/:stateName/:divisionName/:districtName/:year` — blocks under the district, each flagged `is_frozen_block` if it belongs to a frozen batch for that year.
- `GET /criteria` — all `shortlist_criteria` rows.
- `POST /start-shortlist` — run the shortlisting algorithm and persist a batch.

(Note: `getTotalApplicantsByYear` and `getShortlistedStudentsByBatch` controller methods exist but are **not** wired to any route.)

**`/api/shortlist-info`** (shortlistInfoRoutes.js):
- `GET /names?year=` — all batch names for a year.
- `GET /non-frozen-names?year=` — `{name,id}` for `frozen_yn='N'` batches.
- `GET /counts?year=` — `{totalApplicants, totalShortlisted}`.
- `POST /freeze` — medium-filter + freeze (returns conflicts if any).
- `DELETE /delete?year=` — delete a batch (cascade).
- `GET /show-data/:shortlistName?year=` — JSON of shortlisted applicants for display.
- `GET /download-data/:shortlistName?year=` — XLSX download.
- `GET /:shortlistName?year=` — batch detail summary. **Order-sensitive: this catch-all is declared last**, after `/names`, `/counts`, etc.
- `POST /bulk-update-mediums` — apply manual medium decisions then freeze.
- `POST /reset-mediums` — null out mediums for a non-medium-filtered batch.

## 2. The Shortlisting Algorithm (`createShortlistBatch`, generateShortlistModel.js)

**Criteria storage:** `shortlist_criteria(criteria_id, criteria varchar(500))`. Criteria is a free-text string; the threshold is derived by **substring match on the lowercased text**: `top 4%`→0.04, `top 6%`→0.06, `top 8%`→0.08. Anything else → `throw "Criteria ... logic not implemented"`. There is no stored numeric cutoff column — the percentile is parsed out of English prose.

**Scoring/ranking query** (run once per block, in a loop):
```sql
WITH ApplicantRanked AS (
  SELECT applicant_id, app_state, district, nmms_block AS block,
    (gmat_score * 0.7 + sat_score * 0.3) AS weighted_score,
    PERCENT_RANK() OVER (
       PARTITION BY nmms_block
       ORDER BY (gmat_score * 0.7 + sat_score * 0.3) DESC, applicant_id ASC
    ) AS percentile_rank
  FROM pp.applicant_primary_info WHERE nmms_year = $4)
SELECT ar.applicant_id FROM ApplicantRanked ar
JOIN pp.jurisdiction sj ON ar.app_state = sj.juris_code
JOIN pp.jurisdiction dj ON ar.district   = dj.juris_code
JOIN pp.jurisdiction bj ON ar.block      = bj.juris_code
WHERE LOWER(TRIM(sj.juris_name))=LOWER(TRIM($1))
  AND LOWER(TRIM(dj.juris_name))=LOWER(TRIM($2))
  AND LOWER(TRIM(bj.juris_name))=LOWER(TRIM($3))
  AND ar.percentile_rank <= <threshold>   -- interpolated, NOT a bind param
ORDER BY ar.weighted_score DESC;
```
Weighted score = **0.7·GMAT + 0.3·SAT**. Window partitions by `nmms_block` (block-wise). `PERCENT_RANK()` gives 0 for the top row and `(rank-1)/(N-1)` thereafter; `<= threshold` keeps the top ~4/6/8%. Tie-break is deterministic: descending weighted score, then ascending `applicant_id`. **The threshold is string-interpolated into SQL, not a bound parameter** (`ar.percentile_rank <= ${threshold}`). Keep this as a native SQL window query in Java (do NOT reimplement in code).

**Jurisdiction scoping:** state/district from `locations.state`/`locations.district`; blocks iterated one-by-one (`blockNamesToSearch`, each lowercased+trimmed). `PERCENT_RANK` computed over the whole year's block population first, then filtered by name — so ranking is over the full block, correct.

**Bulk insert of results:**
```js
let vals=[], params=[], counter=1;
for (const id of applicantIds) {
  vals.push(`($${counter++}, 'Y', $${counter++}, $${counter++}, $${counter++})`);
  params.push(id, shortlistBatchId, userId, userId);
}
await pool.query(
 `INSERT INTO pp.applicant_shortlist_info (applicant_id, shortlisted_yn, shortlist_batch_id, created_by, updated_by) VALUES ${vals.join(', ')}`, params);
```
Builds `($1,'Y',$2,$3,$4),($5,'Y',$6,$7,$8),…` — reproduce in Java (batched INSERT or `jdbcTemplate.batchUpdate`).

## 3. Per-Endpoint Contract

**`POST /start-shortlist`** — Body: `{criteriaId, name, description, year, locations:{state,district,blocks[]}, userId}`. Validates state/district/criteriaId/name/year/blocks non-empty → 400 `{error:"Required fields missing."}`. Transaction: `BEGIN`; **duplicate check** (any non-frozen batch already covering one of these blocks for the year → `throw "Shortlists already exist for these blocks in <year>. Please delete them first."`, returned as **409** because controller checks `error.message.includes("already exist")`); insert `shortlist_batch` (`RETURNING shortlist_batch_id`); insert `shortlist_batch_jurisdiction` rows via `SELECT juris_code ... WHERE juris_name = ANY($2) AND juris_type='block'`; run ranking query per block; bulk-insert results; `COMMIT`. Success 200:
```json
{ "message":"Shortlist created successfully!\nShortlisted N students...",
  "shortlistBatchId":.., "shortlistedCountInBatch":N,
  "totalApplicantsCount":"..", "totalShortlistedInBlocks":".." }
```
(`totalApplicantsCount`/`totalShortlistedInBlocks` computed by separate post-commit queries; counts come back as **strings** from `COUNT()`.) Errors: 409 (duplicate) / 500 `{error}`.

**`GET /:shortlistName?year=`** (`getShortlistDetails`→`getShortlistInfo`) — 404 `{message:"Shortlist not found"}` if none. 200:
```json
{ "id":.., "name":"..", "description":"..", "criteria":"..",
  "blocks":["..",..], "totalStudents":int, "shortlistedCount":int, "isFrozen":"Yes"|"No" }
```
`totalStudents`/`shortlistedCount` are `parseInt`-ed to numbers; `isFrozen` mapped from `frozen_yn`.

**`GET /counts?year=`** — `{totalApplicants:int, totalShortlisted:int}`. `totalShortlisted` counts only rows in **frozen** batches (`sb.frozen_yn='Y'`) with `shortlisted_yn='Y'`.

**`GET /show-data/:shortlistName?year=`** — `{name, data:[{applicant_id, nmms_reg_number, nmms_block, student_name, gmat_score, sat_score, medium, weighted_score}]}`, ordered by `student_name ASC`. `weighted_score = gmat*0.70+sat*0.30`.

**`GET /names`, `/non-frozen-names`** — plain arrays (of strings / of `{name,id}`).

## 4. shortlist-info Operations

**Freeze (`POST /freeze`)** — Body `{shortlistBatchId, filterMediums[]}`. 400 if either missing. Steps: (1) `autoUpdateSingleMediumStudents` — sets `medium` from `institute_medium` for schools with exactly one distinct medium (`HAVING COUNT(DISTINCT medium)=1`) where student medium is null/empty, then **auto-rejects** (`shortlisted_yn='N'`) students violating management-type rules: ENGLISH requires `management_type='GOVERNMENT'`; KANNADA/MARATHI require `IN ('GOVERNMENT','PRIVATE AIDED')`. (2) `getInvalidMediumStudents` — returns multi-medium-school conflicts. (3) If conflicts remain → **400** `{requiresCorrection:true, message, students:[{applicant_id, student_name, institute_name, dise_code, contact_no1, contact_no2, selected_medium, supported_mediums[]}]}`. (4) Else `freezeShortlist` sets `frozen_yn='Y'` → `{message:"Shortlist filtered and frozen successfully"}`, or 404 if no row.
**Arg-count bug to preserve/decide:** controller calls `autoUpdateSingleMediumStudents(shortlistBatchId, filterMediums)` but the active model fn takes only `(batchId)` — the medium arg is ignored; the auto-reject rules are hard-coded, not driven by `filterMediums`.

**Bulk update (`POST /bulk-update-mediums`)** — Body `{updates[], batchId, allowedMediums[]}`. **Controller destructures only `{updates, batchId}`** and calls `bulkUpdateMediumsAndStatus(updates, batchId)` → the model's 3rd param `allowedMediums` is `undefined`, so its Step-2 final-validation block (`if (allowedMediums && length>0)`) is **skipped at runtime** even though the model contains it. Uses a real pooled client with `BEGIN`/`COMMIT`/`ROLLBACK`: per-student `UPDATE applicant_primary_info SET medium`, `UPDATE applicant_shortlist_info SET shortlisted_yn`, then (when allowedMediums present) the combined UI-filter + management-type rejection, then `UPDATE shortlist_batch SET frozen_yn='Y', medium_filtered_yn='Y'`. Returns `{message:"Medium decisions updated successfully"}`.

**Reset (`POST /reset-mediums`)** — nulls `medium` for applicants in the batch **only if** `SB.MEDIUM_FILTERED_YN='N'`. `{message:"Medium filtering reset successfully."}` or 400.

**Delete (`DELETE /delete?year=`)** — Body `{shortlistBatchId}`. Three sequential deletes (info → jurisdiction → batch), **not wrapped in a transaction**. `{message:"Shortlist deleted successfully"}` / 404. Note FK `applicant_shortlist_info_shortlist_batch_id_fkey ... ON DELETE CASCADE` exists, so deleting the batch alone would cascade — the manual deletes are partly redundant.

**Download (`GET /download-data/:shortlistName?year=`)** — checks count>0, else `200 {status:"no_data", message:"No shortlisted students found."}`. Builds XLSX via `xlsx` lib, sheet "Applicants", columns (aliased in SQL): `S. No.`, `NMMS Registration No`, `Student Name`, `Contact No 1`, `Current School Name`, `medium` (alias `Medium`), `District`, `Block`, `GMAT Score`, `SAT Score`, ordered by `Student Name ASC`. Also **writes the file to disk** at `${FILE_STORAGE_PATH}/generated-shortlist-data/<name>_Applicants.xlsx`. Headers: `Content-Disposition: attachment; filename="<name>_Applicants.xlsx"`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`; body is the buffer. **No PDF/CSV path exists — XLSX only** (use Apache POI, already a dependency from Plan 2a).

## 5. Transactions & Batched Inserts

- `createShortlistBatch`: explicit `pool.query("BEGIN")`/`COMMIT`/`ROLLBACK` (uses the shared pool — a latent concurrency risk; in Java use a proper `@Transactional`/single connection in a dedicated `@Repository`).
- `bulkUpdateMediumsAndStatus`: **correct** pattern — `pool.connect()` client, `BEGIN`/`COMMIT`/`ROLLBACK`, `finally client.release()`.
- `deleteShortlist`: **no transaction** (three deletes; partial failure possible). FK cascade exists.
- Batched insert placeholder string (`($1,'Y',$2,$3,$4),(…)`) — reproduce in Java.

## 6. Data Quirks

- **numeric-as-string:** PG `COUNT()` returns strings; `startShortlisting` passes `totalApplicantsCount`/`totalShortlistedInBlocks` through as strings, while `getShortlistInfo` `parseInt`s. Match each field's type.
- **Scores are `numeric(2,0)`** (0–99, integer). Weighted score `0.7·GMAT+0.3·SAT` is fractional; do the math in `NUMERIC`/`double`, not int.
- **Percentile/ranking:** `PERCENT_RANK()` semantics (top=0), cutoff `<= threshold`; threshold **interpolated as a literal** (0.04/0.06/0.08). Reproduce Postgres `PERCENT_RANK` exactly (keep native SQL).
- **Tie-break:** `ORDER BY weighted_score DESC, applicant_id ASC` inside the window — deterministic, preserve verbatim.
- **Name matching everywhere via `LOWER(TRIM(juris_name))`** and blocks lowercased/trimmed in JS before binding — case/whitespace-insensitive joins.
- **Medium filtering** is string-compare heavy: `TRIM(UPPER(medium))` against `'ENGLISH'/'KANNADA'/'MARATHI'` and `management_type` `'GOVERNMENT'/'PRIVATE AIDED'`; `dise_code` compared as `TRIM(CAST(... AS TEXT))` (types mismatch between `varchar` code on applicant and institute).
- **No date formats** beyond `nmms_year`/`shortlisted_year` (`numeric(4,0)`, an integer year). `created_on`/`updated_at` default to `now()`.
- `shortlist_batch_name` has a **UNIQUE** constraint — batch names are globally unique across years.

## 7. Complexity / Parity Warnings

1. **Ranking determinism** — the single hardest parity item. `PERCENT_RANK()` with the exact `PARTITION BY nmms_block ORDER BY weighted_score DESC, applicant_id ASC` and `<= threshold` must produce identical membership. Keep as native SQL; golden-data tests comparing selected `applicant_id` sets per block/threshold, including tie boundaries where `percentile_rank` equals the threshold exactly.
2. **Criteria prose → threshold parsing** — brittle substring match (`includes("top 6%")`). Keep prose-matching for parity; test unknown-criteria → error path.
3. **Threshold as SQL literal vs bind** — safe (derived from a whitelist) but must remain a value the planner sees identically.
4. **The two argument-count bugs** (freeze's `filterMediums` ignored; bulk-update's `allowedMediums` dropped in the controller) — pin down ACTUAL current behavior in tests first, then decide whether to fix. Recommend: preserve current behavior for parity, note the intended-vs-actual gap.
5. **Non-transactional delete** and **shared-pool BEGIN/COMMIT** — wrap both in proper `@Transactional` units (dedicated `@Repository`).
6. **Medium/management-type rules** duplicated across two functions — centralize and test each rule (ENGLISH→GOVERNMENT only; KANNADA/MARATHI→GOVERNMENT or PRIVATE AIDED; null/empty/not-in-allowed → reject).
7. **Duplicate-batch 409** relies on `error.message.includes("already exist")` — replace with a typed exception in Java, preserving the 409 status.

## Files
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/controllers/generateShortlistController.js`, `models/generateShortlistModel.js`, `routes/generateShortlistRoutes.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/controllers/shortlistInfoController.js`, `models/shortlistInfoModel.js`, `routes/shortlistInfoRoutes.js`
- Schema tables: `shortlist_criteria`, `shortlist_batch`, `shortlist_batch_jurisdiction`, `applicant_shortlist_info` (in `docs/superpowers/plans/artifacts/live-schema.sql`)
- Frontend: `client/src/pages/Admin/GenerateShortlist.js`, `ShortlistInfo.js`
