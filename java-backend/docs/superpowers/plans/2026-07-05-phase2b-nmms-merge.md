# IMAS Spring Boot Migration — Plan 2b of 6: Admission — NMMS Merge & Reconciliation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `/api/merge` router (17 endpoints — CSV staging uploads, fuzzy-suggestion validation, deterministic name-key preview, bulk/manual/commit merge writes, draft/status reads, CSV downloads, guarded delete) to a new `com.rcf.imas.modules.merge` module, preserving exact SQL, response shapes, and status codes, while adding the locked ADMIN authorization and a whitelisted-table delete guard.

**Architecture:** Continues the Phase-1/2a modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `merge` with `web/`, `service/`, `persistence/`. Reads live in `MergeReadRepository`; all multi-statement transactional writes (uploads, bulk-auto-map, resolve, commit) live in a dedicated `MergeWriteRepository` `@Repository` bean (Spring does NOT intercept self-invoked `@Transactional` — locked convention #8). Matching primitives (`normalizeText`, `generateStudentNameKey`, `suggestValue`) are pure functions in `MergeMatching`, golden-unit-tested. CSV parse/write is isolated in `CsvSupport` (Apache Commons CSV, already a dependency from Plan 2a).

**Tech Stack (no additions over Plan 2a):** Apache Commons CSV for parsing uploads and writing template/district CSVs. `spring-boot-starter-web` `MultipartFile`. No new runtime infra, no Docker.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Plans 1 + 2a are merged and green (77 tests): `PgIntegrationTest`, `JwtService` (`issueFinalToken(userId,userName,role)` + `FinalToken` principal with `.userId()`), `SecurityConfig` (method security), `ApiException`, `GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1/2a — apply in every task below):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM, not the column** — `WHERE a.district = :district::numeric` (NOT `a.district::text = :district`). Keeps indexes; matches Node's pg param-coercion (garbage id → 500). **Per-table type awareness:** on the **staging** tables `nmms_year` / `nmms_reg_number` / `students_sats_id` / `gmat_score` / `sat_score` are `text` (bind `String`, NO cast); `district` / `app_state` / `nmms_block` are `numeric(12,0)` (cast `:p::numeric`). On the **primary/std** tables `nmms_year` / `nmms_reg_number` are `numeric` (cast). Where Node writes explicit casts inside INSERT…SELECT (`a.nmms_year::numeric`, `::text`), **port them verbatim** — they bridge the text↔numeric impedance between staging and primary.
> 3. **Numeric + bigint ids serialize as Strings** via `rs.getBigDecimal(...).toBigInteger().toString()` (numeric) / `String.valueOf(rs.getLong(...))` (bigint). `dise_code` is `varchar(15)` → `getString`. **Exceptions (Node coerces with `Number()`):** the `/draft-districts`, `/merge-status`, and `/merged-status` endpoints emit `district_id`, `year`, and the count fields as **JSON numbers** (see each task). Map keys are literal snake_case (`SnakeCaseStrategy` only transforms POJO fields, not `Map` keys).
> 4. **snake_case JSON** is the global default.
> 5. **Errors:** throw `ApiException.error(status,msg)` for `{error:...}` bodies (this module's dominant shape) or `.message(status,msg)` for `{message:...}`. Match each endpoint's exact Node body key. Do NOT remove the `GlobalExceptionHandler` AccessDenied re-throw (gives 403 not 500 for `@PreAuthorize`).
> 6. **Controllers:** class package-private; every handler method **`public`** (else `@PreAuthorize` can be skipped).
> 7. **Auth (NEW enforcement — audit CRITICAL):** every `/api/merge/**` endpoint is `@PreAuthorize("hasRole('ADMIN')")`. In Node these were completely open (no `authenticate`) yet they bulk-mutate student PII. Class-level annotation on the controller. Record in the fetch audit that the frontend merge screens send an admin token.
> 8. **Transactions:** multi-statement writes live in `MergeWriteRepository` (a dedicated `@Repository` bean) with `@Transactional` — never self-invoked from the same class.
> 9. **Test isolation:** all `*IT` extend `PgIntegrationTest` (one JVM-wide embedded Postgres). Each IT `@AfterEach`-deletes exactly the rows it seeds, **children before parents**: `std_applicant_primary_info` / `applicant_primary_info` → staging tables → `jurisdiction` → `jurisdiction_type` → `institute` → `"user"`. Seed `jurisdiction_type` before `jurisdiction` (`ON CONFLICT DO NOTHING`). After explicit-PK seeds advance the sequence: `SELECT setval('pp.<seq>', (SELECT MAX(<col>)::bigint FROM pp.<table>))`.
> 10. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the schema dot) is accepted.

---

## Ground truth used by this plan (verified against Node source + live pg_dump)

Node source read for this plan (live CommonJS is lines 1–666; **everything below 666 in `mergeModel.js` and the top block of `mergeRoutes.js` is commented-out ES-module legacy — ignore it**):
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/routes/mergeRoutes.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/controllers/mergeController.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/models/mergeModel.js`
- Mount point (`index.js`): `app.use("/api/merge", mergeRoutes)`.

### Table facts (from `docs/superpowers/plans/artifacts/live-schema.sql`)

- **`pp.stg_nmms_phase1_applications`** — `id bigint` PK (seq `stg_nmms_phase1_applications_id_seq`), `nmms_year text`, `exam text`, `district numeric(12,0)`, `app_state numeric(12,0)`, `nmms_block numeric(12,0)`, `current_institute_dise_code text`, `students_sats_id text`, `student_name text`, `father_name text`, `institute_name text`, `institute_type text`, `category_name text`, `disability_status text`, `contact_no1 text`, `contact_no2 text`, `date_of_application text`, `created_at text DEFAULT CURRENT_TIMESTAMP::text`, `student_name_key text`. **No FK constraints** (staging accepts any district/block number). Match indexes on `(nmms_year, district, nmms_block, student_name_key)`.
- **`pp.stg_nmms_phase2_results`** — `result_stg_id bigint` PK (seq), `nmms_year text`, `district numeric(12,0)`, `nmms_block numeric(12,0)`, `nmms_reg_number text`, `student_name text`, `gmat_score text`, `sat_score text`, `match_status varchar(30) DEFAULT 'PENDING'`, `remarks text`, `created_at timestamp`, `student_name_key text`. **No FK constraints.**
- **`pp.std_applicant_primary_info`** (draft area) — `applicant_id numeric(14,0)` PK (seq `applicant_id_seq`, shared with `applicant_primary_info`), **UNIQUE `nmms_reg_number` numeric(11,0) NOT NULL** (`std_applicant_primary_info_nmms_reg_number_key` → `ON CONFLICT (nmms_reg_number) DO NOTHING` works), `nmms_year numeric(4,0)`, `app_state/district/nmms_block numeric(12,0)`, `student_name/father_name varchar(100)`, `gmat_score/sat_score numeric(2,0)`, `contact_no1/contact_no2 varchar(12)`, `current_institute_dise_code varchar(15)`, `created_by/updated_by numeric(8,0)`, `students_sats_id numeric(11,0)`. **FKs:** `district/app_state/nmms_block → jurisdiction(juris_code)`, `created_by/updated_by → "user"(user_id)`, `*_dise_code → institute(dise_code)`. Gender CHECK ∈ (M,F,O).
- **`pp.applicant_primary_info`** — same shared `applicant_id_seq`, UNIQUE `nmms_reg_number`, same FK families. Commit writes here.

### The two matching notions (do NOT conflate)

1. **Deterministic join key (the REAL match):** `student_name_key`, computed at upload time as `generateStudentNameKey(name) = name.toLowerCase().replace(/[^a-z0-9]/g,"")`, stored on both staging tables. Preview + auto-map + domino all match on this (plus `nmms_block` [+ `district`, `nmms_year` in preview]). **Not fuzzy.**
2. **Fuzzy suggestion text (advisory only, never auto-decides):**
   - `suggestValue(input, options)` — hand-rolled **prefix-char-match ratio > 0.4**. Used to enrich the p1 "Block not found" log line. **This is the only fuzzy code on the live path.** Port it exactly (golden test).
   - `getSuggestion` / `stringSimilarity.findBestMatch` (Dice-coefficient, `string-similarity@4.0.4`) — **DEAD CODE.** The controller passes `getSuggestion` into `uploadPhase1Model`/`uploadPhase2Model`, but those models destructure `{ file, year, state_id, district_id }` and never reference it. `p2`'s own `suggestValue` result is computed then discarded (not put in its log). **Do NOT port the Dice algorithm** — it is unreachable; porting it would add complexity for zero behavioral parity. Recorded as an intentional simplification.

### `normalizeText` and `suggestValue` (port verbatim)

```js
const normalizeText = (text) => text?.toUpperCase().replace(/[^A-Z]/g, "");   // uppercase, KEEP only A–Z (drops digits, spaces, dots)

const suggestValue = (input, options) => {                 // options are ALREADY normalizeText'd block keys
  const key = normalizeText(input);
  let best = null, score = 0;
  for (const option of options) {
    const optionKey = normalizeText(option);
    let match = 0;
    for (let i = 0; i < Math.min(optionKey.length, key.length); i++) if (optionKey[i] === key[i]) match++;
    const ratio = match / Math.max(optionKey.length, key.length);   // 0/0 → NaN; NaN > score is false
    if (ratio > score) { score = ratio; best = option; }
  }
  return score > 0.4 ? best : null;                        // returns the (normalized) option string
};
```

### Endpoint inventory & exact contract (17 routes under `/api/merge`, ALL `@PreAuthorize("hasRole('ADMIN')")`)

| # | Method + Path | Body/Query | Success (200 unless noted) | Errors |
|---|---|---|---|---|
| 1 | GET `/jurisdiction` | `?type&parent` | bare array `[{juris_code:"<str>", juris_name}]` (DISTINCT, ORDER BY juris_name) | 500 `{error:"Failed to fetch jurisdictions"}` |
| 2 | GET `/merged-status` | — | array `[{district_name, district_id:<int>, year:<int>, total_applicants:null, total_merged_applicants:null, remaining_applicants:null}]` — **Node bug preserved** (see Task 2) | 500 `{error:"Failed to fetch merged list"}` |
| 3 | GET `/district/{districtId}/download-csv` | — | raw CSV body (`Content-Type text/csv`, attachment) | 404 `{message:"No data found for this district."}`; 500 `{message:"Error generating CSV"}` |
| 4 | POST `/upload-p1` | multipart `file` + `year,state_id,district_id` | `{success:true, logs:["Successfully inserted N records."]}` | 400 `{error:"No CSV file provided"}`; 400 `{success:false, logs:[...]}` (dup or validation); 500 `{logs:["Critical Server Error during Application Upload"]}` |
| 5 | POST `/upload-p2` | multipart `file` + `year,district_id` | `{success:true, logs:["Successfully inserted N results."]}` | 400 `{error:"No CSV file provided"}`; **200** `{success:false, logs:[...]}` (dup — Node `res.json(result)` is unconditional 200, UNLIKE p1); 500 `{logs:["Result upload failed"]}` |
| 6 | GET `/applications` | `?year&district&search&page` | `{rows:[...], totalPages:N}` (limit 50) | 500 `{error:"Failed to fetch applications"}` |
| 7 | GET `/results` | `?year&district&search&page` | `{rows:[...], totalPages:N}` (limit 50) | 500 `{error:"Failed to fetch results"}` |
| 8 | POST `/preview-merge` | `{year,district}` | `{summary:{total_students,mapped,conflicts}, blockWise:{<block_name>:[app,...]}}` | 500 `{error:"Merge preview failed"}` |
| 9 | POST `/bulk-auto-map` | `{year,district}` | `{message:"Bulk mapping successful. Records copied to draft."}` | 500 `{error:"Failed to process bulk mapping"}` |
| 10 | POST `/resolve-lively` | `{app_id,res_id}` | `{message:"Mapped successfully"}` | 500 `{error:"Mapping failed"}` |
| 11 | POST `/commit-to-primary` | `{district,year}` | `{message:"Successfully committed to Primary Table."}` | 500 `{error:"Failed to finalize merge."}` |
| 12 | GET `/draft-districts` | — | array `[{district_name, district_id:<int>, year:<int>, total_applicants:<int>, total_merged_applicants:<int>, remaining_applicants:<int>}]` | 500 `{error:"Failed to fetch draft districts"}` |
| 13 | GET `/draft-district-students` | `?district&year` | bare array `[{sl_no:"<str>", student_name, district_name, block_name, current_institute_dise_code, nmms_reg_number:"<str>", gmat_score:"<str>", sat_score:"<str>"}]` | 500 `{error:"Failed to fetch details"}` |
| 14 | DELETE `/delete-district-data` | `{district,year,phase,section}` | `{message:"<n> ... deleted ..."}` | 400 `{error:"District is required"}` / `{error:"Year is required"}` / `{error:"Invalid phase or section"}` / guard `{error:"Deletion not allowed!! ..."}`; 500 `{error:<msg>}` |
| 15 | GET `/download-template` | `?phase=p1\|p2` | raw CSV (header-only), attachment `NMMS_<phase>_Template.csv` | 400 `{error:"Invalid phase"}`; 500 `{error:"Failed to generate template"}` |
| 16 | GET `/commit-status` | `?year` | `{data:[{district_name, district_id:"<str>", year:"<str>", total_applicants:"<str>", total_committed:"<str>", is_committed:<bool>}]}` | 400 `{error:"Year is required"}`; 500 `{error:"Failed to fetch merged status"}` |
| 17 | GET `/merge-status` | `?year` | `{data:[{district_name, district_id:<int>, year:<int>, total_applicants:<int>, total_merged_applicants:<int>, remaining_applicants:<int>, ismerged:<bool>}]}` | 400 `{error:"Year is required"}`; 500 `{error:"Failed to fetch merged status"}` |

### Upload behavior details (Tasks 3)

- **Multipart field:** `file` (both). Extra fields: p1 = `year, state_id, district_id`; p2 = `year, district_id`. No file → **400** `{error:"No CSV file provided"}`.
- **p1 header parse:** trim + strip leading BOM (`﻿`) from each header name. Keys read (case-sensitive, lowercase): `nmms_year, exam, app_state, district, nmms_block, current_institute_dise_code, students_sats_id, student_name, father_name, institute_name, contact_no1, contact_no2`. (The p1 template ships a capital `"Exam"` header that will NOT populate `row.exam` — Node quirk; do not "fix" by lowercasing.)
- **p2 header parse:** headers verbatim (**no** BOM strip, no trim — `columns:true`). Keys: `nmms_year, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score`.
- **Duplicate guard (both):** `SELECT COUNT(*) ... WHERE district=$1 AND nmms_year=$2` > 0 → ROLLBACK, return `{success:false, logs:["Upload Rejected: ..."]}` (p1 msg: `Data for Year <y> already uploaded for this district.`; p2: `Results for Year <y> have already been uploaded for this district.`).
- **p1 per-row validation** (order matters; each pushes at most one log per distinct value via `reported*` sets): (a) `nmms_year` trimmed must `=== String(year)` → `Row N: Year Mismatch (File has "<v>", expected "<year>")`; (b) `normalizeText(app_state) === normalizeText(selectedStateName)` else `Row N: State Mismatch (File: "<v>", Expected: "<name>")`; (c) district with dots stripped, `normalizeText` equal else `Row N: District Mismatch (...)`; (d) block via `blockMap.get(normalizeText(rawBlock))` else `Row N: Block "<raw>" not found. Did you mean "<suggestValue>"?` / `Please check spelling.`; (e) DISE cleaned to digits must be in the pre-fetched valid set (`SELECT dise_code FROM pp.institute WHERE dise_code = ANY(:codes)`) else `Row N: Invalid DISE Code "<clean>"`.
- **p2 per-row validation:** block resolve (log `Row N: Block "<raw>" invalid for <districtName>.` — suggestion computed but NOT included); `nmms_reg_number` matches `/^\d{8,12}$/` (no log, just `rowError`); `student_name` matches `/^[A-Za-z\s.]+$/` (no log). **Silent-partial quirk:** because reg/name failures push no log, if ALL failures are reg/name-only, `logs` stays empty → the batch commits only the valid rows. Preserve.
- **All-or-nothing (both):** after the loop, `if (logs.length > 0) ROLLBACK; return {success:false, logs}`. Else batch-insert (Node BATCH_SIZE 5000) and COMMIT.
- **p1 insert** (13 cols): `INSERT INTO pp.stg_nmms_phase1_applications (nmms_year, exam, district, app_state, nmms_block, current_institute_dise_code, students_sats_id, student_name, father_name, institute_name, contact_no1, contact_no2, student_name_key) VALUES ...` with values `(year, r.exam, district_id, state_id, blockId, cleanDise, r.students_sats_id, r.student_name, r.father_name, r.institute_name, r.contact_no1, r.contact_no2, generateStudentNameKey(r.student_name))`. Note `district ← district_id`, `app_state ← state_id`.
- **p2 insert** (8 cols): `INSERT INTO pp.stg_nmms_phase2_results (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key) VALUES ...` — `match_status` defaults `'PENDING'`.
- **`loadBlocks(districtId)`** = `SELECT juris_code, juris_name FROM pp.jurisdiction WHERE parent_juris = :district`, keyed `normalizeText(juris_name) → juris_code`. `blockNames = keys` (already normalized).

### Write SQL (Task 5) — port verbatim

- **`moveMappedToStdModel(district, year, userId)`** — one transactional INSERT…SELECT (unique 1:1 name-key matches) + one UPDATE:
  ```sql
  INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
  SELECT a.nmms_year::numeric, r.nmms_reg_number::numeric, NULLIF(regexp_replace(a.students_sats_id, '\D', '', 'g'), '')::numeric,
         a.student_name, a.father_name, a.app_state::numeric, a.district::numeric, a.nmms_block::numeric,
         (CASE WHEN r.gmat_score = 'AB' OR r.gmat_score IS NULL THEN '0' ELSE r.gmat_score END)::numeric,
         (CASE WHEN r.sat_score  = 'AB' OR r.sat_score  IS NULL THEN '0' ELSE r.sat_score  END)::numeric,
         a.contact_no1, a.contact_no2, a.current_institute_dise_code, :userId::numeric
  FROM pp.stg_nmms_phase1_applications a
  JOIN pp.stg_nmms_phase2_results r
    ON LOWER(REGEXP_REPLACE(a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(r.student_name, '[^a-zA-Z0-9]', '', 'g'))
   AND a.nmms_block = r.nmms_block
  WHERE a.district = :district AND a.nmms_year = :year AND r.match_status != 'MATCHED'
    AND a.id IN (
      SELECT sub_a.id FROM pp.stg_nmms_phase1_applications sub_a
      JOIN pp.stg_nmms_phase2_results sub_r
        ON LOWER(REGEXP_REPLACE(sub_a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(sub_r.student_name, '[^a-zA-Z0-9]', '', 'g'))
       AND sub_a.nmms_block = sub_r.nmms_block
      WHERE sub_a.district = :district GROUP BY sub_a.id HAVING COUNT(*) = 1)
  ON CONFLICT (nmms_reg_number) DO NOTHING;

  UPDATE pp.stg_nmms_phase2_results r SET match_status = 'MATCHED'
  FROM pp.std_applicant_primary_info s
  WHERE r.nmms_reg_number::numeric = s.nmms_reg_number AND r.district = :district;
  ```
  **Param binding note:** Node binds `district`/`year` as JS strings against `numeric`/`text` staging columns and pg coerces. With JdbcClient, bind `district` as `:district` where the SQL compares `a.district = :district` (numeric column) → must cast: use `a.district = :district::numeric` in the two `WHERE ... district =` and `sub_a.district = :district::numeric`. `a.nmms_year = :year` compares text column → bind String, no cast. Keep the `::numeric`/`::text` casts already inside the SELECT list verbatim.
- **`resolveMatchModel(appId, resId, userId)`** — transactional: load app row (`WHERE id = :appId`) + result row (`WHERE result_stg_id = :resId`); if either missing `throw "Records not found."`; INSERT one std row (14 cols incl. `contact_no2`) with `gmat/sat 'AB'→'0'`, `students_sats_id` via `NULLIF(regexp_replace(:sats,'\D','','g'),'')::numeric`; `UPDATE ... SET match_status='MATCHED' WHERE result_stg_id=:resId`; **domino:** re-query remaining same-name(`LOWER(REGEXP_REPLACE(...))`)+block app rows and remaining unmatched result rows; if **exactly one each**, INSERT that pair too and mark it MATCHED. COMMIT. (Full column list identical to the move INSERT's target columns.)
- **`commitToPrimaryModel(district, year)`** — transactional single INSERT…SELECT `std_applicant_primary_info → applicant_primary_info` for `WHERE district=:district AND nmms_year=:year`, columns `(nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, created_by, current_institute_dise_code, contact_no1, contact_no2)`, `ON CONFLICT (nmms_reg_number) DO NOTHING`. (Both tables numeric here; no per-column casts in Node — but `WHERE district=:district AND nmms_year=:year` compares numeric columns → cast params `:district::numeric`, `:year::numeric`.)

### Read SQL (Tasks 2, 4)

- **`/jurisdiction`:** `SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = :type [AND parent_juris = :parent::numeric] ORDER BY juris_name ASC`. Bare array.
- **`/applications`** (`/results` identical with `stg_nmms_phase2_results r`): `SELECT a.*, d.juris_name AS district_name, b.juris_name AS nmms_block_name FROM pp.stg_nmms_phase1_applications a LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code LEFT JOIN pp.jurisdiction b ON a.nmms_block = b.juris_code WHERE a.nmms_year = :year AND a.district = :district::numeric [AND a.student_name ILIKE :search] LIMIT :limit OFFSET :offset`; second query `SELECT COUNT(*) ... WHERE nmms_year=:year AND district=:district::numeric`; `totalPages = ceil(count/limit)`, `limit=50`, `offset=(page-1)*50`, `page` default 1, `search` → `%<search>%`.
- **`/draft-district-students`:** `SELECT ROW_NUMBER() OVER (ORDER BY s.student_name) AS sl_no, s.student_name, j1.juris_name AS district_name, j2.juris_name AS block_name, s.current_institute_dise_code, s.nmms_reg_number, s.gmat_score, s.sat_score FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction j1 ON s.district = j1.juris_code LEFT JOIN pp.jurisdiction j2 ON s.nmms_block = j2.juris_code WHERE s.district = :district::numeric AND s.nmms_year = :year::numeric ORDER BY s.student_name`. Bare array.
- **`/draft-districts`, `/merge-status`, `/commit-status`, `/merged-status`:** exact SQL in Task 2 code below.
- **`/preview-merge`:** exact SQL in Task 4 code below.
- **`/district/{id}/download-csv`** data: `SELECT s.student_name, s.father_name, s.nmms_reg_number, s.students_sats_id, d.juris_name AS district_name, b.juris_name AS block_name, s.gmat_score, s.sat_score, s.contact_no1 FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction d ON s.district = d.juris_code LEFT JOIN pp.jurisdiction b ON s.nmms_block = b.juris_code WHERE s.district = :district::numeric ORDER BY s.student_name`.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/merge/
├── web/MergeController.java                 (17 handlers, @PreAuthorize("hasRole('ADMIN')") at class level)
├── service/MergeMatching.java               (normalizeText, generateStudentNameKey, suggestValue) — pure, unit-tested
├── service/CsvSupport.java                  (parse bytes→List<Map<String,String>>; write rows→CSV string)
├── service/MergeService.java                (upload validation orchestration, preview grouping, delete guard)
├── persistence/MergeReadRepository.java     (all reads + generic numeric/bigint→String row mapper)
└── persistence/MergeWriteRepository.java    (@Repository; @Transactional uploadP1/P2, moveMappedToStd, resolveMatch, commitToPrimary, deleteDistrictData)

imas-backend/src/test/java/com/rcf/imas/modules/merge/
├── MergeMatchingTest.java                   (unit — golden, no DB)
├── MergeReadIT.java                         (jurisdiction, applications, results, draft-districts, draft-district-students, merged/commit/merge-status)
├── MergeUploadIT.java                       (upload-p1, upload-p2)
├── MergePreviewIT.java                      (preview-merge)
├── MergeWriteIT.java                        (bulk-auto-map, resolve-lively domino, commit-to-primary)
└── MergeDownloadDeleteIT.java               (download-template, district download-csv, delete-district-data)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. Run one test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks (no parallel implementers — git index races).
- Build tokens via `jwt.issueFinalToken("<userId>", "<name>", "ADMIN")`. The FK `created_by` user must exist in `pp."user"` with `user_id` = the token subject for write tests.
- Bare-array endpoints return `List<...>` directly from the handler (Jackson serializes the list). Envelope endpoints build a `LinkedHashMap` explicitly.

---

## Task 1: `MergeMatching` pure helpers + golden unit tests

Port `normalizeText`, `generateStudentNameKey`, and `suggestValue` (prefix-ratio) as pure, DB-free functions. Golden-test against hand-computed Node values. (Dice/`getSuggestion` intentionally omitted — dead code.)

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/service/MergeMatching.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeMatchingTest.java`

- [ ] **Step 1: Write the failing unit test**

`src/test/java/com/rcf/imas/modules/merge/MergeMatchingTest.java`:
```java
package com.rcf.imas.modules.merge;

import com.rcf.imas.modules.merge.service.MergeMatching;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeMatchingTest {

    private final MergeMatching m = new MergeMatching();

    @Test
    void normalizeTextUppercasesAndKeepsOnlyLetters() {
        assertThat(m.normalizeText("Belagavi City-1")).isEqualTo("BELAGAVICITY");  // drops space, dash, digit
        assertThat(m.normalizeText("bagalkot.")).isEqualTo("BAGALKOT");
        assertThat(m.normalizeText("")).isEqualTo("");
        assertThat(m.normalizeText(null)).isNull();
    }

    @Test
    void studentNameKeyLowercasesAndKeepsAlphanumeric() {
        assertThat(m.generateStudentNameKey("Asha  Rani.")).isEqualTo("asharani");
        assertThat(m.generateStudentNameKey("R@vi-123")).isEqualTo("rvi123");  // keeps digits, drops symbols
        assertThat(m.generateStudentNameKey(null)).isEqualTo("");
        assertThat(m.generateStudentNameKey("")).isEqualTo("");
    }

    @Test
    void suggestValueReturnsBestPrefixMatchAboveThreshold() {
        // options are pre-normalized block keys (as loadBlocks stores them)
        List<String> opts = List.of("BELAGAVI", "BAILHONGAL", "GOKAK");
        // "BELGAVI" vs "BELAGAVI": prefix matches B,E,L then A!=G ... compute: key=BELGAVI(7), opt=BELAGAVI(8)
        // positions 0..6: B=B,E=E,L=L,G!=A,A!=G,V!=A,I!=V,... wait min len 7 → i0 B,i1 E,i2 L,i3 G vs A(no)...
        // matches=3, ratio=3/8=0.375 → NOT > 0.4 → for this input best stays null unless another beats it
        assertThat(m.suggestValue("BELGAVI", opts)).isNull();
    }

    @Test
    void suggestValueMatchesOnStrongPrefix() {
        List<String> opts = List.of("BELAGAVI", "GOKAK");
        // "BELAGAVIX"(9) vs "BELAGAVI"(8): min 8, all 8 match → ratio 8/9=0.888 > 0.4 → BELAGAVI
        assertThat(m.suggestValue("BELAGAVIX", opts)).isEqualTo("BELAGAVI");
    }

    @Test
    void suggestValueNoOptionsOrNoMatchIsNull() {
        assertThat(m.suggestValue("ANYTHING", List.of())).isNull();
        assertThat(m.suggestValue("ZZZZ", List.of("AAAA"))).isNull();  // 0 matches → 0.0 not > 0.4
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeMatchingTest`
Expected: FAIL — `MergeMatching` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/merge/service/MergeMatching.java`:
```java
package com.rcf.imas.modules.merge.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parity port of the live matching primitives in mergeModel.js.
 * Only the deterministic key + the prefix-ratio suggestion are ported;
 * the Dice-coefficient getSuggestion is dead code in Node (never invoked) and is omitted.
 */
@Component
public class MergeMatching {

    /** Node: text?.toUpperCase().replace(/[^A-Z]/g,"") — uppercase then keep only A–Z. Null-safe (null → null). */
    public String normalizeText(String text) {
        if (text == null) return null;
        return text.toUpperCase().replaceAll("[^A-Z]", "");
    }

    /** Node: (name||"").toLowerCase().replace(/[^a-z0-9]/g,"") — the deterministic join key. */
    public String generateStudentNameKey(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Node suggestValue: prefix-char-match ratio over normalized strings; returns the best option
     * whose ratio > 0.4, else null. `options` are already normalizeText'd block keys.
     */
    public String suggestValue(String input, List<String> options) {
        String key = normalizeText(input);
        if (key == null) key = "";
        String best = null;
        double score = 0.0;
        for (String option : options) {
            String optionKey = normalizeText(option);
            if (optionKey == null) optionKey = "";
            int match = 0;
            int min = Math.min(optionKey.length(), key.length());
            for (int i = 0; i < min; i++) if (optionKey.charAt(i) == key.charAt(i)) match++;
            int max = Math.max(optionKey.length(), key.length());
            double ratio = max == 0 ? 0.0 : (double) match / max;   // JS 0/0 → NaN, never > score; use 0.0
            if (ratio > score) { score = ratio; best = option; }
        }
        return score > 0.4 ? best : null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeMatchingTest` — Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge
git commit -m "feat(merge): MergeMatching pure helpers (name key + prefix-ratio suggestion); Dice omitted as dead code"
```

---

## Task 2: read endpoints — `MergeReadRepository` + read handlers + IT

Port the 8 read endpoints: `/jurisdiction`, `/applications`, `/results`, `/draft-districts`, `/draft-district-students`, `/merged-status`, `/commit-status`, `/merge-status`. All ADMIN-only. Generic row mapper (numeric+bigint→String, timestamp→ISO-Z) for the `a.*` / raw-row shapes; explicit `Number()`-parity coercion for the three mapped endpoints.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/web/MergeController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeReadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/merge/MergeReadIT.java`:
```java
package com.rcf.imas.modules.merge;

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
class MergeReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        cleanup();

        // jurisdiction: district 950001 (EDUCATION DISTRICT) + block 950010 (BLOCK, parent=district)
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (950001,'BELAGAVI','EDUCATION DISTRICT',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (950010,'GOKAK','BLOCK',950001) ON CONFLICT (juris_code) DO NOTHING").update();

        // one phase-1 staged row and one phase-2 staged row (year 2025, district 950001, block 950010)
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, student_name, father_name, student_name_key)
            VALUES (7001, '2025', 950001, 29, 950010, 'Asha Rani', 'Ravi', 'asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (8001, '2025', 950001, 950010, '24010000001', 'Asha Rani', '55', '60', 'asharani')
            """).update();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase1_applications_id_seq', (SELECT MAX(id)::bigint FROM pp.stg_nmms_phase1_applications))").query(Long.class).single();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase2_results_result_stg_id_seq', (SELECT MAX(result_stg_id)::bigint FROM pp.stg_nmms_phase2_results))").query(Long.class).single();

        // one draft (std) row so draft-districts / merge-status show a merged count
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mseed'").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, created_by)
            VALUES (2025, 24010000001, 950001, 950010, 'Asha Rani', 'Ravi', :u)
            """).param("u", uid).update();
        // and one committed row for commit-status
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, medium, contact_no1, created_by, updated_by)
            VALUES (2025, 24010000001, 950001, 950010, 'Asha Rani', 'Ravi', 'Kannada', '9876543210', :u, :u)
            """).param("u", uid).update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 950001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (950001, 950010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mseed'").update();
    }

    @Test
    void jurisdictionListByType() throws Exception {
        mvc.perform(get("/api/merge/jurisdiction?type=BLOCK&parent=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].juris_code").value("950010"))
           .andExpect(jsonPath("$[0].juris_name").value("GOKAK"));
    }

    @Test
    void applicationsPaginated() throws Exception {
        mvc.perform(get("/api/merge/applications?year=2025&district=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows.length()").value(1))
           .andExpect(jsonPath("$.rows[0].id").value("7001"))
           .andExpect(jsonPath("$.rows[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.rows[0].nmms_block_name").value("GOKAK"))
           .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void resultsPaginated() throws Exception {
        mvc.perform(get("/api/merge/results?year=2025&district=950001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows.length()").value(1))
           .andExpect(jsonPath("$.rows[0].nmms_reg_number").value("24010000001"))
           .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void draftDistrictsHasNumericIdsAndCounts() throws Exception {
        mvc.perform(get("/api/merge/draft-districts").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$[0].district_id").value(950001))          // JSON number
           .andExpect(jsonPath("$[0].year").value(2025))
           .andExpect(jsonPath("$[0].total_applicants").value(1))
           .andExpect(jsonPath("$[0].total_merged_applicants").value(1))
           .andExpect(jsonPath("$[0].remaining_applicants").value(0));
    }

    @Test
    void draftDistrictStudents() throws Exception {
        mvc.perform(get("/api/merge/draft-district-students?district=950001&year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("Asha Rani"))
           .andExpect(jsonPath("$[0].block_name").value("GOKAK"))
           .andExpect(jsonPath("$[0].nmms_reg_number").value("24010000001"));
    }

    @Test
    void mergeStatusHasIsmergedBoolean() throws Exception {
        mvc.perform(get("/api/merge/merge-status?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].district_id").value(950001))
           .andExpect(jsonPath("$.data[0].total_applicants").value(1))
           .andExpect(jsonPath("$.data[0].total_merged_applicants").value(1))
           .andExpect(jsonPath("$.data[0].ismerged").value(true));
    }

    @Test
    void mergeStatusRequiresYear() throws Exception {
        mvc.perform(get("/api/merge/merge-status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Year is required"));
    }

    @Test
    void commitStatusWrapsDataArray() throws Exception {
        mvc.perform(get("/api/merge/commit-status?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].district_id").value("950001"))     // raw row → String
           .andExpect(jsonPath("$.data[0].total_applicants").value("1"))
           .andExpect(jsonPath("$.data[0].total_committed").value("1"))
           .andExpect(jsonPath("$.data[0].is_committed").value(true));
    }

    @Test
    void mergedStatusPreservesNodeNullCountsBug() throws Exception {
        mvc.perform(get("/api/merge/merged-status").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].district_id").value(950001))
           .andExpect(jsonPath("$[0].year").value(2025))
           .andExpect(jsonPath("$[0].total_applicants").doesNotExist())     // null (field present, value null)
           .andExpect(jsonPath("$[0].total_applicants").isEmpty());
    }

    @Test
    void readsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/merge/applications?year=2025&district=950001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/merge/draft-districts").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeReadIT` — Expected: FAIL (no controller).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/merge/persistence/MergeReadRepository.java`:
```java
package com.rcf.imas.modules.merge.persistence;

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
public class MergeReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public MergeReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: numeric/decimal AND bigint → String; timestamp → ISO-Z; text/boolean native. */
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
                    long v = rs.getLong(i);
                    val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i);
                    val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    // ---- 1) /jurisdiction ----
    public List<Map<String, Object>> jurisdictions(String type, String parent) {
        var spec = (parent == null || parent.isBlank())
                ? jdbc.sql("SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = :type ORDER BY juris_name ASC")
                       .param("type", type)
                : jdbc.sql("SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = :type AND parent_juris = :parent::numeric ORDER BY juris_name ASC")
                       .param("type", type).param("parent", parent);
        return spec.query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 6/7) /applications, /results (paginated 50) ----
    public Map<String, Object> stagedPage(String table, String alias, String year, String district, String search, int page) {
        int limit = 50;
        int offset = (page - 1) * limit;
        StringBuilder q = new StringBuilder("SELECT ").append(alias).append(".*, d.juris_name AS district_name, b.juris_name AS nmms_block_name FROM pp.")
                .append(table).append(' ').append(alias)
                .append(" LEFT JOIN pp.jurisdiction d ON ").append(alias).append(".district = d.juris_code")
                .append(" LEFT JOIN pp.jurisdiction b ON ").append(alias).append(".nmms_block = b.juris_code")
                .append(" WHERE ").append(alias).append(".nmms_year = :year AND ").append(alias).append(".district = :district::numeric");
        boolean hasSearch = search != null && !search.isBlank();
        if (hasSearch) q.append(" AND ").append(alias).append(".student_name ILIKE :search");
        q.append(" LIMIT :limit OFFSET :offset");

        var spec = jdbc.sql(q.toString()).param("year", year).param("district", district)
                .param("limit", limit).param("offset", offset);
        if (hasSearch) spec = spec.param("search", "%" + search + "%");
        List<Map<String, Object>> rows = spec.query((rs, i) -> genericRow(rs)).list();

        long count = jdbc.sql("SELECT COUNT(*) FROM pp." + table + " WHERE nmms_year = :year AND district = :district::numeric")
                .param("year", year).param("district", district).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totalPages", (int) Math.ceil((double) count / limit));
        return out;
    }

    // ---- 13) /draft-district-students ----
    public List<Map<String, Object>> draftDistrictStudents(String district, String year) {
        return jdbc.sql("""
                SELECT ROW_NUMBER() OVER (ORDER BY s.student_name) AS sl_no, s.student_name,
                       j1.juris_name AS district_name, j2.juris_name AS block_name,
                       s.current_institute_dise_code, s.nmms_reg_number, s.gmat_score, s.sat_score
                FROM pp.std_applicant_primary_info s
                LEFT JOIN pp.jurisdiction j1 ON s.district = j1.juris_code
                LEFT JOIN pp.jurisdiction j2 ON s.nmms_block = j2.juris_code
                WHERE s.district = :district::numeric AND s.nmms_year = :year::numeric
                ORDER BY s.student_name
                """).param("district", district).param("year", year)
                .query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 12) /draft-districts (Number()-coerced ids + counts) ----
    public List<Map<String, Object>> draftDistricts() {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants,
                       COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants
                FROM pp.stg_nmms_phase1_applications s
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m
                  ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged
                ORDER BY j.juris_name
                """).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));
                    d.put("year", rs.getLong("year"));
                    d.put("total_applicants", rs.getLong("total_applicants"));
                    d.put("total_merged_applicants", rs.getLong("total_merged_applicants"));
                    d.put("remaining_applicants", rs.getLong("remaining_applicants"));
                    return d;
                }).list();
    }

    // ---- 17) /merge-status (draftDistricts + ismerged) ----
    public List<Map<String, Object>> mergeStatus(String year) {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants,
                       COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants,
                       CASE WHEN COALESCE(m.total_merged, 0) = COUNT(*) THEN true ELSE false END AS ismerged
                FROM pp.stg_nmms_phase1_applications s
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m
                  ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                WHERE s.nmms_year = :year
                GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged
                ORDER BY j.juris_name
                """).param("year", year).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));
                    d.put("year", rs.getLong("year"));
                    d.put("total_applicants", rs.getLong("total_applicants"));
                    d.put("total_merged_applicants", rs.getLong("total_merged_applicants"));
                    d.put("remaining_applicants", rs.getLong("remaining_applicants"));
                    d.put("ismerged", rs.getBoolean("ismerged"));
                    return d;
                }).list();
    }

    // ---- 16) /commit-status (raw rows → generic mapper) ----
    public List<Map<String, Object>> commitStatus(String year) {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year,
                       COUNT(*) AS total_applicants, COALESCE(c.total_committed, 0) AS total_committed,
                       COALESCE(c.total_committed, 0) = COUNT(*) AS is_committed
                FROM pp.stg_nmms_phase1_applications s
                JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric
                LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_committed FROM pp.applicant_primary_info GROUP BY district, nmms_year) c
                  ON s.district::numeric = c.district::numeric AND s.nmms_year::text = c.nmms_year::text
                WHERE s.nmms_year = :year
                GROUP BY j.juris_name, s.district, s.nmms_year, c.total_committed
                ORDER BY j.juris_name
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 2) /merged-status (Node bug preserved: SQL selects only student_count; the three totals map to null) ----
    public List<Map<String, Object>> mergedStatus() {
        return jdbc.sql("""
                SELECT j.juris_name AS district_name, a.district AS district_id, a.nmms_year AS year,
                       COUNT(a.applicant_id) AS student_count
                FROM pp.applicant_primary_info a
                JOIN pp.jurisdiction j ON a.district = j.juris_code
                GROUP BY j.juris_name, a.district, a.nmms_year
                ORDER BY j.juris_name
                """).query((rs, i) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district_name", rs.getString("district_name"));
                    d.put("district_id", rs.getLong("district_id"));       // Number(d.district_id)
                    d.put("year", rs.getLong("year"));                     // Number(d.year)
                    // Node maps total_applicants/total_merged_applicants/remaining_applicants from columns the
                    // SQL never selects → Number(undefined) = NaN → JSON null. Preserve exactly.
                    d.put("total_applicants", null);
                    d.put("total_merged_applicants", null);
                    d.put("remaining_applicants", null);
                    return d;
                }).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/merge/web/MergeController.java` (read handlers this task; write/upload/download handlers added in Tasks 3–6 to the same file):
```java
package com.rcf.imas.modules.merge.web;

import com.rcf.imas.modules.merge.persistence.MergeReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/merge")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: NMMS merge mutates student PII → ADMIN only (Node left it open)
class MergeController {

    private final MergeReadRepository reads;

    MergeController(MergeReadRepository reads) {
        this.reads = reads;
    }

    @GetMapping("/jurisdiction")
    public List<Map<String, Object>> jurisdiction(@RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String parent) {
        return reads.jurisdictions(type, parent);
    }

    @GetMapping("/applications")
    public Map<String, Object> applications(@RequestParam(required = false) String year,
                                            @RequestParam(required = false) String district,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false, defaultValue = "1") int page) {
        return reads.stagedPage("stg_nmms_phase1_applications", "a", year, district, search, page);
    }

    @GetMapping("/results")
    public Map<String, Object> results(@RequestParam(required = false) String year,
                                       @RequestParam(required = false) String district,
                                       @RequestParam(required = false) String search,
                                       @RequestParam(required = false, defaultValue = "1") int page) {
        return reads.stagedPage("stg_nmms_phase2_results", "r", year, district, search, page);
    }

    @GetMapping("/draft-districts")
    public List<Map<String, Object>> draftDistricts() {
        return reads.draftDistricts();
    }

    @GetMapping("/draft-district-students")
    public List<Map<String, Object>> draftDistrictStudents(@RequestParam(required = false) String district,
                                                           @RequestParam(required = false) String year) {
        return reads.draftDistrictStudents(district, year);
    }

    @GetMapping("/merged-status")
    public List<Map<String, Object>> mergedStatus() {
        return reads.mergedStatus();
    }

    @GetMapping("/commit-status")
    public Map<String, Object> commitStatus(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", reads.commitStatus(year));
        return m;
    }

    @GetMapping("/merge-status")
    public Map<String, Object> mergeStatus(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", reads.mergeStatus(year));
        return m;
    }
}
```

> **Parity note (merged-status Node bug).** Node's `getMergedDistricts` maps `total_applicants`/`total_merged_applicants`/`remaining_applicants` off a query that selects only `student_count`, so `Number(undefined) → NaN → null` in the JSON. We reproduce the exact output (three `null`s) rather than "fixing" it — the frontend has always received these nulls. Documented in the fetch audit.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeReadIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeReadIT.java
git commit -m "feat(merge): 8 read endpoints (jurisdiction, applications, results, draft/commit/merge status) ADMIN-only"
```

---

## Task 3: staging uploads — `CsvSupport` + `MergeWriteRepository` (uploadP1/P2) + `MergeService` + upload handlers + IT

Port `/upload-p1` and `/upload-p2`: multipart parse, per-row validation (with `suggestValue` log enrichment on p1), all-or-nothing rollback, batch insert into staging with `student_name_key`. Transactional insert lives in `MergeWriteRepository`.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/service/CsvSupport.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/service/MergeService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/web/MergeController.java` (add upload handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeUploadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/merge/MergeUploadIT.java`:
```java
package com.rcf.imas.modules.merge;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class MergeUploadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    // state 29, district 960001 (BELAGAVI), block 960010 (GOKAK), institute dise 12345678901
    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (29,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (960001,'BELAGAVI','EDUCATION DISTRICT',29) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (960010,'GOKAK','BLOCK',960001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('12345678901','Test School') ON CONFLICT (dise_code) DO NOTHING").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 960001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 960001").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = '12345678901'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (29, 960001, 960010)").update();
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "u.csv", "text/csv", content.getBytes());
    }

    @Test
    void uploadP1InsertsValidRows() throws Exception {
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha Rani,Ravi,Test School,9876543210,9000000000\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.logs[0]").value("Successfully inserted 1 records."));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(1);
        String key = jdbc.sql("SELECT student_name_key FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(String.class).single();
        assertThat(key).isEqualTo("asharani");
    }

    @Test
    void uploadP1RejectsWholeBatchOnAnyValidationError() throws Exception {
        // second row has an unknown block → all-or-nothing rollback, nothing inserted
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha,Ravi,S,9876543210,9\n"
                + "2025,KARNATAKA,BELAGAVI,NOWHERE,12345678901,222222,Kiran,Suresh,S,9876543210,9\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.logs").isArray());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void uploadP1DuplicateGuard() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'), '2025', 960001, 29, 960010, 'X', 'x')").update();
        String content = "nmms_year,app_state,district,nmms_block,current_institute_dise_code,students_sats_id,student_name,father_name,institute_name,contact_no1,contact_no2\n"
                + "2025,KARNATAKA,BELAGAVI,GOKAK,12345678901,111111,Asha,Ravi,S,9876543210,9\n";
        mvc.perform(multipart("/api/merge/upload-p1").file(csv(content))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.logs[0]").value("Upload Rejected: Data for Year 2025 already uploaded for this district."));
    }

    @Test
    void uploadP1NoFileIs400() throws Exception {
        mvc.perform(multipart("/api/merge/upload-p1")
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("No CSV file provided"));
    }

    @Test
    void uploadP2InsertsValidRowsWithPendingStatus() throws Exception {
        String content = "nmms_year,nmms_block,nmms_reg_number,student_name,gmat_score,sat_score\n"
                + "2025,GOKAK,24010000001,Asha Rani,55,60\n";
        mvc.perform(multipart("/api/merge/upload-p2").file(csv(content))
                .param("year", "2025").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.logs[0]").value("Successfully inserted 1 results."));

        String status = jdbc.sql("SELECT match_status FROM pp.stg_nmms_phase2_results WHERE district = 960001").query(String.class).single();
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void uploadP2SilentlySkipsRegNameFailuresWithNoLog() throws Exception {
        // reg too short + bad name → rowError but NO log pushed → batch commits only the valid row (Node quirk)
        String content = "nmms_year,nmms_block,nmms_reg_number,student_name,gmat_score,sat_score\n"
                + "2025,GOKAK,24010000002,Valid Name,50,50\n"
                + "2025,GOKAK,123,Bad9Name,40,40\n";
        mvc.perform(multipart("/api/merge/upload-p2").file(csv(content))
                .param("year", "2025").param("district_id", "960001")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = 960001").query(Long.class).single();
        assertThat(n).isEqualTo(1);   // only the valid row
    }

    @Test
    void uploadsAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(multipart("/api/merge/upload-p1").file(csv("nmms_year\n2025\n"))
                .param("year", "2025").param("state_id", "29").param("district_id", "960001")
                .header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeUploadIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/merge/service/CsvSupport.java`:
```java
package com.rcf.imas.modules.merge.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CSV parse/write for the merge uploads and downloads (Apache Commons CSV). */
@Component
public class CsvSupport {

    /**
     * Parse CSV bytes into a list of ordered header→value maps.
     * @param stripBomTrimHeaders p1 semantics (trim + strip leading BOM from header names); p2 passes false (headers verbatim).
     */
    public List<Map<String, String>> parse(byte[] bytes, boolean stripBomTrimHeaders) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), fmt)) {
            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            if (stripBomTrimHeaders) {
                for (int i = 0; i < headers.size(); i++) {
                    headers.set(i, headers.get(i).trim().replace("﻿", ""));
                }
            }
            for (CSVRecord rec : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < rec.size() ? rec.get(i) : null);
                }
                out.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Write header + rows to a CSV string (json2csv parity: header-only when rows is a single empty map). */
    public String write(List<String> fields, List<Map<String, Object>> rows) {
        StringWriter sw = new StringWriter();
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader(fields.toArray(new String[0])).build();
        try (CSVPrinter printer = new CSVPrinter(sw, fmt)) {
            for (Map<String, Object> row : rows) {
                List<Object> vals = new ArrayList<>();
                for (String f : fields) vals.add(row.get(f));
                printer.printRecord(vals);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }
}
```

`src/main/java/com/rcf/imas/modules/merge/persistence/MergeWriteRepository.java` (upload methods this task; merge-write + delete methods added in Tasks 5–6):
```java
package com.rcf.imas.modules.merge.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Dedicated bean for all multi-statement transactional merge writes.
 * (Convention #8: Spring does not intercept self-invoked @Transactional — these must live in their own bean.)
 */
@Repository
public class MergeWriteRepository {

    private final JdbcClient jdbc;

    public MergeWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long countStagedP1(String districtId, String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = :d::numeric AND nmms_year = :y")
                .param("d", districtId).param("y", year).query(Long.class).single();
    }

    public long countStagedP2(String districtId, String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = :d::numeric AND nmms_year = :y")
                .param("d", districtId).param("y", year).query(Long.class).single();
    }

    /** Batch-insert validated phase-1 rows in one transaction. Each map holds the 13 insert values keyed by column. */
    @Transactional
    public void insertP1(List<Map<String, Object>> rows) {
        String sql = """
            INSERT INTO pp.stg_nmms_phase1_applications
              (nmms_year, exam, district, app_state, nmms_block, current_institute_dise_code, students_sats_id,
               student_name, father_name, institute_name, contact_no1, contact_no2, student_name_key)
            VALUES (:nmms_year, :exam, :district::numeric, :app_state::numeric, :nmms_block::numeric, :dise, :sats,
                    :student_name, :father_name, :institute_name, :contact_no1, :contact_no2, :name_key)
            """;
        for (Map<String, Object> r : rows) {
            jdbc.sql(sql)
                .param("nmms_year", r.get("nmms_year"))
                .param("exam", r.get("exam"))
                .param("district", r.get("district"))
                .param("app_state", r.get("app_state"))
                .param("nmms_block", r.get("nmms_block"))
                .param("dise", r.get("dise"))
                .param("sats", r.get("sats"))
                .param("student_name", r.get("student_name"))
                .param("father_name", r.get("father_name"))
                .param("institute_name", r.get("institute_name"))
                .param("contact_no1", r.get("contact_no1"))
                .param("contact_no2", r.get("contact_no2"))
                .param("name_key", r.get("name_key"))
                .update();
        }
    }

    /** Batch-insert validated phase-2 rows in one transaction (match_status defaults to PENDING). */
    @Transactional
    public void insertP2(List<Map<String, Object>> rows) {
        String sql = """
            INSERT INTO pp.stg_nmms_phase2_results
              (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (:nmms_year, :district::numeric, :nmms_block::numeric, :reg, :student_name, :gmat, :sat, :name_key)
            """;
        for (Map<String, Object> r : rows) {
            jdbc.sql(sql)
                .param("nmms_year", r.get("nmms_year"))
                .param("district", r.get("district"))
                .param("nmms_block", r.get("nmms_block"))
                .param("reg", r.get("reg"))
                .param("student_name", r.get("student_name"))
                .param("gmat", r.get("gmat"))
                .param("sat", r.get("sat"))
                .param("name_key", r.get("name_key"))
                .update();
        }
    }
}
```

> **Note on batch shape.** Node builds one multi-row `INSERT ... VALUES (...),(...)` per 5000 rows. Here we issue one parameterized `INSERT` per row inside a single `@Transactional` method. Same atomicity (all-or-nothing per call), simpler and index-identical; row counts in these uploads are per-district (hundreds–few thousand), well within a single transaction. This is a deliberate simplicity choice over hand-built placeholder strings.

`src/main/java/com/rcf/imas/modules/merge/service/MergeService.java` (upload orchestration this task; preview/delete added in Tasks 4/6):
```java
package com.rcf.imas.modules.merge.service;

import com.rcf.imas.modules.merge.persistence.MergeWriteRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MergeService {

    private final JdbcClient jdbc;
    private final MergeWriteRepository writes;
    private final MergeMatching match;

    public MergeService(JdbcClient jdbc, MergeWriteRepository writes, MergeMatching match) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.match = match;
    }

    public record UploadResult(boolean success, List<String> logs) {}

    private String jurisName(String jurisCode) {
        return jdbc.sql("SELECT juris_name FROM pp.jurisdiction WHERE juris_code = :c::numeric")
                .param("c", jurisCode).query(String.class).optional().orElse(null);
    }

    /** normalizeText(name) → juris_code, for blocks under a district. */
    private Map<String, String> loadBlocks(String districtId) {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE parent_juris = :d::numeric")
            .param("d", districtId)
            .query((rs, i) -> {
                m.put(match.normalizeText(rs.getString("juris_name")),
                      rs.getBigDecimal("juris_code").toBigInteger().toString());
                return null;
            }).list();
        return m;
    }

    // ---------- Phase 1 ----------
    public UploadResult uploadP1(List<Map<String, String>> records, String year, String stateId, String districtId) {
        List<String> logs = new ArrayList<>();

        if (writes.countStagedP1(districtId, String.valueOf(year)) > 0) {
            return new UploadResult(false, List.of("Upload Rejected: Data for Year " + year + " already uploaded for this district."));
        }

        String stateName = jurisName(stateId);
        String districtName = jurisName(districtId);
        Map<String, String> blockMap = loadBlocks(districtId);
        List<String> blockNames = new ArrayList<>(blockMap.keySet());

        Set<String> diseInFile = new LinkedHashSet<>();
        for (Map<String, String> r : records) {
            String c = digitsOnly(r.get("current_institute_dise_code"));
            if (!c.isEmpty()) diseInFile.add(c);
        }
        Set<String> validDise = new HashSet<>();
        if (!diseInFile.isEmpty()) {
            validDise.addAll(jdbc.sql("SELECT dise_code FROM pp.institute WHERE dise_code = ANY(:codes)")
                    .param("codes", diseInFile.toArray(new String[0]))
                    .query(String.class).list());
        }

        Set<String> reportedBlocks = new HashSet<>(), reportedDistricts = new HashSet<>(), reportedStates = new HashSet<>();
        List<Map<String, Object>> valid = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            Map<String, String> row = records.get(i);
            int rowNum = i + 1;
            boolean rowError = false;

            String cleanYear = trim(row.get("nmms_year"));
            if (!cleanYear.equals(String.valueOf(year))) {
                logs.add("Row " + rowNum + ": Year Mismatch (File has \"" + (cleanYear.isEmpty() ? "Empty" : cleanYear) + "\", expected \"" + year + "\")");
                rowError = true;
            }

            String inputState = trim(row.get("app_state"));
            if (!eq(match.normalizeText(inputState), match.normalizeText(stateName))) {
                if (reportedStates.add(inputState))
                    logs.add("Row " + rowNum + ": State Mismatch (File: \"" + inputState + "\", Expected: \"" + stateName + "\")");
                rowError = true;
            }

            String inputDist = trim(row.get("district")).replace(".", "");
            if (!eq(match.normalizeText(inputDist), match.normalizeText(districtName))) {
                if (reportedDistricts.add(inputDist))
                    logs.add("Row " + rowNum + ": District Mismatch (File: \"" + inputDist + "\", Expected: \"" + districtName + "\")");
                rowError = true;
            }

            String rawBlock = trim(row.get("nmms_block"));
            String blockKey = match.normalizeText(rawBlock);
            String blockId = blockMap.get(blockKey);
            if (blockId == null) {
                if (reportedBlocks.add(blockKey == null ? "" : blockKey)) {
                    String suggestion = match.suggestValue(rawBlock, blockNames);
                    logs.add("Row " + rowNum + ": Block \"" + rawBlock + "\" not found. "
                            + (suggestion != null ? "Did you mean \"" + suggestion + "\"?" : "Please check spelling."));
                }
                rowError = true;
            }

            String cleanDise = digitsOnly(row.get("current_institute_dise_code"));
            if (!validDise.contains(cleanDise)) {
                logs.add("Row " + rowNum + ": Invalid DISE Code \"" + cleanDise + "\"");
                rowError = true;
            }

            if (!rowError) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("nmms_year", year);
                v.put("exam", row.get("exam"));
                v.put("district", districtId);
                v.put("app_state", stateId);
                v.put("nmms_block", blockId);
                v.put("dise", cleanDise);
                v.put("sats", row.get("students_sats_id"));
                v.put("student_name", row.get("student_name"));
                v.put("father_name", row.get("father_name"));
                v.put("institute_name", row.get("institute_name"));
                v.put("contact_no1", row.get("contact_no1"));
                v.put("contact_no2", row.get("contact_no2"));
                v.put("name_key", match.generateStudentNameKey(row.get("student_name")));
                valid.add(v);
            }
        }

        if (!logs.isEmpty()) return new UploadResult(false, logs);

        writes.insertP1(valid);
        return new UploadResult(true, List.of("Successfully inserted " + valid.size() + " records."));
    }

    // ---------- Phase 2 ----------
    public UploadResult uploadP2(List<Map<String, String>> records, String year, String districtId) {
        List<String> logs = new ArrayList<>();

        if (writes.countStagedP2(districtId, String.valueOf(year)) > 0) {
            return new UploadResult(false, List.of("Upload Rejected: Results for Year " + year + " have already been uploaded for this district."));
        }

        String districtName = jurisName(districtId);
        Map<String, String> blockMap = loadBlocks(districtId);
        Set<String> reportedBlocks = new HashSet<>();
        List<Map<String, Object>> valid = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            Map<String, String> row = records.get(i);
            int rowNum = i + 1;
            boolean rowError = false;

            String blockKey = match.normalizeText(row.get("nmms_block"));
            String blockId = blockMap.get(blockKey);
            if (blockId == null) {
                if (reportedBlocks.add(blockKey == null ? "" : blockKey))   // Node computes suggestValue here but does NOT log it
                    logs.add("Row " + rowNum + ": Block \"" + row.get("nmms_block") + "\" invalid for " + districtName + ".");
                rowError = true;
            }

            String reg = row.get("nmms_reg_number");
            if (reg == null || !reg.matches("\\d{8,12}")) rowError = true;          // no log (Node quirk)
            String name = row.get("student_name");
            if (name == null || !name.matches("[A-Za-z\\s.]+")) rowError = true;    // no log

            if (!rowError) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("nmms_year", year);
                v.put("district", districtId);
                v.put("nmms_block", blockId);
                v.put("reg", reg);
                v.put("student_name", name);
                v.put("gmat", row.get("gmat_score"));
                v.put("sat", row.get("sat_score"));
                v.put("name_key", match.generateStudentNameKey(name));
                valid.add(v);
            }
        }

        if (!logs.isEmpty()) return new UploadResult(false, logs);

        writes.insertP2(valid);
        return new UploadResult(true, List.of("Successfully inserted " + valid.size() + " results."));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String digitsOnly(String s) { return s == null ? "" : s.replaceAll("[^0-9]", "").trim(); }
    private static boolean eq(String a, String b) { return Objects.equals(a, b); }
}
```

Add the upload handlers to `MergeController` (inject `MergeService service` + `CsvSupport csv` — add both as fields + constructor params). The success body AND the dup/validation-failure body are the SAME shape `{success:<bool>, logs:[...]}`, differing only in HTTP status (200 vs 400), so return a `ResponseEntity` directly — do NOT route the failure through `ApiException` (its body always carries an `error`/`message` key, which this shape must not have). The no-file case is a genuine `{error:...}` body → `ApiException.error`.
```java
    // fields to add: private final MergeService service; private final CsvSupport csv;
    // (thread both through the constructor alongside the existing reads/writes fields)

    @PostMapping("/upload-p1")
    public org.springframework.http.ResponseEntity<Map<String, Object>> uploadP1(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String state_id,
            @RequestParam(required = false) String district_id) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw ApiException.error(400, "No CSV file provided");
        var records = csv.parse(file.getBytes(), true);   // p1: strip BOM + trim headers
        var result = service.uploadP1(records, year, state_id, district_id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("logs", result.logs());
        return org.springframework.http.ResponseEntity.status(result.success() ? 200 : 400).body(body);
    }

    @PostMapping("/upload-p2")
    public org.springframework.http.ResponseEntity<Map<String, Object>> uploadP2(
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String district_id) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw ApiException.error(400, "No CSV file provided");
        var records = csv.parse(file.getBytes(), false);  // p2: headers verbatim (no BOM strip)
        var result = service.uploadP2(records, year, district_id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("logs", result.logs());
        return org.springframework.http.ResponseEntity.ok(body);   // p2 is ALWAYS 200 (Node res.json(result)), even on failure — UNLIKE p1
    }
```

> **Why not `ApiException` for the failure.** The verified `ApiException` (Phase-1 Task 4) always seeds its body with an `error` or `message` key; the upload failure body is exactly `{success:false, logs:[...]}` with neither key. Returning a `ResponseEntity` directly (status 200/400 by `result.success()`) is the simplest faithful path and needs zero new platform surface. The no-file branch legitimately uses `ApiException.error(400, "No CSV file provided")` → `{error:...}` (matches Node).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeUploadIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeUploadIT.java
git commit -m "feat(merge): staging uploads upload-p1/upload-p2 (validation, all-or-nothing, batch insert)"
```

---

## Task 4: `/preview-merge` — deterministic name-key preview

Port `getMergePreviewModel`: LEFT JOIN phase1↔phase2 on `student_name_key AND nmms_block AND district AND nmms_year AND r.match_status IS DISTINCT FROM 'MATCHED'`, group by `phase1_id` into candidates, count mapped/conflicts, return `{summary, blockWise}`.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeReadRepository.java` (add `previewRows`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/service/MergeService.java` (add `previewMerge` grouping)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/web/MergeController.java` (add handler)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergePreviewIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/merge/MergePreviewIT.java`:
```java
package com.rcf.imas.modules.merge;

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
class MergePreviewIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (970010,'GOKAK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        // phase1: two apps, block 970010, district 970001, year 2025
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, father_name, student_name_key)
            VALUES (7101,'2025',970001,970010,'Asha Rani','Ravi','asharani'),
                   (7102,'2025',970001,970010,'Kiran Kumar','Suresh','kirankumar')
            """).update();
        // phase2: one match for Asha (1:1), TWO matches for Kiran (conflict)
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (8101,'2025',970001,970010,'24010000001','Asha Rani','55','60','asharani'),
                   (8102,'2025',970001,970010,'24010000002','Kiran Kumar','50','50','kirankumar'),
                   (8103,'2025',970001,970010,'24010000003','Kiran Kumar','40','40','kirankumar')
            """).update();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase1_applications_id_seq', (SELECT MAX(id)::bigint FROM pp.stg_nmms_phase1_applications))").query(Long.class).single();
        jdbc.sql("SELECT setval('pp.stg_nmms_phase2_results_result_stg_id_seq', (SELECT MAX(result_stg_id)::bigint FROM pp.stg_nmms_phase2_results))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 970001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 970001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 970010").update();
    }

    @Test
    void previewCountsMappedAndConflicts() throws Exception {
        mvc.perform(post("/api/merge/preview-merge").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"970001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.summary.total_students").value(2))
           .andExpect(jsonPath("$.summary.mapped").value(1))         // Asha 1:1
           .andExpect(jsonPath("$.summary.conflicts").value(1))      // Kiran 2 candidates
           .andExpect(jsonPath("$.blockWise.GOKAK").isArray())
           .andExpect(jsonPath("$.blockWise.GOKAK.length()").value(2));
    }

    @Test
    void previewIsAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(post("/api/merge/preview-merge").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"970001\"}"))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergePreviewIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `MergeReadRepository`:
```java
    // ---- 8) /preview-merge raw joined rows (grouping happens in the service) ----
    public List<Map<String, Object>> previewRows(String year, String district) {
        return jdbc.sql("""
                SELECT a.id AS phase1_id, a.student_name, a.father_name, a.students_sats_id,
                       a.contact_no1, a.institute_name, a.nmms_block, j.juris_name AS block_name,
                       r.result_stg_id, r.nmms_reg_number, r.gmat_score, r.sat_score,
                       r.student_name AS result_student_name
                FROM pp.stg_nmms_phase1_applications a
                LEFT JOIN pp.jurisdiction j ON a.nmms_block = j.juris_code
                LEFT JOIN pp.stg_nmms_phase2_results r
                  ON a.student_name_key = r.student_name_key
                  AND a.nmms_block = r.nmms_block
                  AND a.district = r.district
                  AND a.nmms_year = r.nmms_year
                  AND r.match_status IS DISTINCT FROM 'MATCHED'
                WHERE a.nmms_year = :year AND a.district = :district::numeric
                ORDER BY a.student_name ASC
                """).param("year", year).param("district", district)
                .query((rs, i) -> MergeReadRepository.genericRow(rs)).list();
    }
```

Add to `MergeService` (grouping mirrors the Node `studentMap`/`blockWise` build):
```java
    public Map<String, Object> previewMerge(String year, String district, com.rcf.imas.modules.merge.persistence.MergeReadRepository reads) {
        List<Map<String, Object>> rows = reads.previewRows(year, district);

        // group by phase1_id preserving first-seen order (rows already ORDER BY student_name)
        Map<Object, Map<String, Object>> studentMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object pid = row.get("phase1_id");
            Map<String, Object> app = studentMap.computeIfAbsent(pid, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("phase1_id", row.get("phase1_id"));
                a.put("student_name", row.get("student_name"));
                a.put("father_name", row.get("father_name"));
                a.put("students_sats_id", row.get("students_sats_id"));
                a.put("contact_no1", row.get("contact_no1"));
                a.put("institute_name", row.get("institute_name"));
                a.put("nmms_block", row.get("nmms_block"));
                a.put("block_name", row.get("block_name"));
                a.put("candidates", new ArrayList<Map<String, Object>>());
                return a;
            });
            if (row.get("result_stg_id") != null) {
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("result_stg_id", row.get("result_stg_id"));
                cand.put("nmms_reg_number", row.get("nmms_reg_number"));
                cand.put("student_name", row.get("result_student_name"));
                cand.put("gmat_score", row.get("gmat_score"));
                cand.put("sat_score", row.get("sat_score"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cands = (List<Map<String, Object>>) app.get("candidates");
                cands.add(cand);
            }
        }

        Map<String, List<Map<String, Object>>> blockWise = new LinkedHashMap<>();
        int total = 0, mapped = 0, conflicts = 0;
        for (Map<String, Object> app : studentMap.values()) {
            total++;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cands = (List<Map<String, Object>>) app.get("candidates");
            if (cands.size() == 1) mapped++;
            else if (cands.size() > 1) conflicts++;
            String blockName = String.valueOf(app.get("block_name"));
            blockWise.computeIfAbsent(blockName, k -> new ArrayList<>()).add(app);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_students", total);
        summary.put("mapped", mapped);
        summary.put("conflicts", conflicts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("blockWise", blockWise);
        return out;
    }
```
(Add the missing imports `java.util.ArrayList`, `java.util.List`, `java.util.Map` already present.)

Add the handler to `MergeController` (inject `MergeReadRepository` is already a field):
```java
    @PostMapping("/preview-merge")
    public Map<String, Object> previewMerge(@RequestBody Map<String, Object> body) {
        String year = str(body.get("year"));
        String district = str(body.get("district"));
        return service.previewMerge(year, district, reads);
    }

    // helper for request-body scalar → String (numbers or strings both accepted)
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergePreviewIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge/MergePreviewIT.java
git commit -m "feat(merge): preview-merge (deterministic name-key join, mapped/conflict counts, blockWise)"
```

---

## Task 5: merge writes — bulk-auto-map, resolve-lively (domino), commit-to-primary

Port the three transactional write endpoints into `MergeWriteRepository` (`moveMappedToStd`, `resolveMatch`, `commitToPrimary`) + controller handlers. All ADMIN-only. `created_by` from the JWT principal (`|| 1` fallback).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/web/MergeController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeWriteIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/merge/MergeWriteIT.java`:
```java
package com.rcf.imas.modules.merge;

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
class MergeWriteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (29,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (980001,'BELAGAVI','EDUCATION DISTRICT',29) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (980010,'GOKAK','BLOCK',980001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mwseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mwseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "mwseed", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase2_results WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 980001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (29, 980001, 980010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mwseed'").update();
    }

    private void stageUnique() {
        // one unique 1:1 name-key pair
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, app_state, nmms_block, students_sats_id, student_name, father_name, contact_no1, contact_no2, current_institute_dise_code, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',980001,29,980010,'111111','Asha Rani','Ravi','9876543210','9000000000',NULL,'asharani')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.stg_nmms_phase2_results (result_stg_id, nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score, student_name_key)
            VALUES (nextval('pp.stg_nmms_phase2_results_result_stg_id_seq'),'2025',980001,980010,'24010000001','Asha Rani','55','60','asharani')
            """).update();
    }

    @Test
    void bulkAutoMapMovesUnique1to1IntoDraftAndMarksMatched() throws Exception {
        stageUnique();
        mvc.perform(post("/api/merge/bulk-auto-map").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"980001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Bulk mapping successful. Records copied to draft."));

        Long std = jdbc.sql("SELECT COUNT(*) FROM pp.std_applicant_primary_info WHERE district = 980001 AND nmms_reg_number = 24010000001").query(Long.class).single();
        assertThat(std).isEqualTo(1);
        String status = jdbc.sql("SELECT match_status FROM pp.stg_nmms_phase2_results WHERE district = 980001").query(String.class).single();
        assertThat(status).isEqualTo("MATCHED");
    }

    @Test
    void resolveLivelyInsertsPairAndMarksMatched() throws Exception {
        stageUnique();
        Long appId = jdbc.sql("SELECT id FROM pp.stg_nmms_phase1_applications WHERE district = 980001").query(Long.class).single();
        Long resId = jdbc.sql("SELECT result_stg_id FROM pp.stg_nmms_phase2_results WHERE district = 980001").query(Long.class).single();

        mvc.perform(post("/api/merge/resolve-lively").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"app_id\":" + appId + ",\"res_id\":" + resId + "}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Mapped successfully"));

        Long std = jdbc.sql("SELECT COUNT(*) FROM pp.std_applicant_primary_info WHERE district = 980001").query(Long.class).single();
        assertThat(std).isEqualTo(1);
    }

    @Test
    void commitToPrimaryFreezesDraftIntoPrimary() throws Exception {
        // put a draft row directly, then commit
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, app_state, nmms_block, student_name, father_name, contact_no1, created_by)
            VALUES (2025, 24010000009, 980001, 29, 980010, 'Asha', 'Ravi', '9876543210', :u)
            """).param("u", uid).update();

        mvc.perform(post("/api/merge/commit-to-primary").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"980001\",\"year\":\"2025\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Successfully committed to Primary Table."));

        Long prim = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE district = 980001 AND nmms_reg_number = 24010000009").query(Long.class).single();
        assertThat(prim).isEqualTo(1);
    }

    @Test
    void writesAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(post("/api/merge/bulk-auto-map").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"district\":\"980001\"}"))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeWriteIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `MergeWriteRepository`:
```java
    /** bulk-auto-map: copy unique 1:1 name-key matches into draft, then mark those phase-2 rows MATCHED. */
    @Transactional
    public void moveMappedToStd(String districtId, String year, String userId) {
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info
              (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
               nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
            SELECT a.nmms_year::numeric, r.nmms_reg_number::numeric,
                   NULLIF(regexp_replace(a.students_sats_id, '\\D', '', 'g'), '')::numeric,
                   a.student_name, a.father_name, a.app_state::numeric, a.district::numeric, a.nmms_block::numeric,
                   (CASE WHEN r.gmat_score = 'AB' OR r.gmat_score IS NULL THEN '0' ELSE r.gmat_score END)::numeric,
                   (CASE WHEN r.sat_score  = 'AB' OR r.sat_score  IS NULL THEN '0' ELSE r.sat_score  END)::numeric,
                   a.contact_no1, a.contact_no2, a.current_institute_dise_code, :userId::numeric
            FROM pp.stg_nmms_phase1_applications a
            JOIN pp.stg_nmms_phase2_results r
              ON LOWER(REGEXP_REPLACE(a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(r.student_name, '[^a-zA-Z0-9]', '', 'g'))
             AND a.nmms_block = r.nmms_block
            WHERE a.district = :district::numeric AND a.nmms_year = :year AND r.match_status != 'MATCHED'
              AND a.id IN (
                SELECT sub_a.id FROM pp.stg_nmms_phase1_applications sub_a
                JOIN pp.stg_nmms_phase2_results sub_r
                  ON LOWER(REGEXP_REPLACE(sub_a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(sub_r.student_name, '[^a-zA-Z0-9]', '', 'g'))
                 AND sub_a.nmms_block = sub_r.nmms_block
                WHERE sub_a.district = :district::numeric GROUP BY sub_a.id HAVING COUNT(*) = 1)
            ON CONFLICT (nmms_reg_number) DO NOTHING
            """).param("district", districtId).param("year", year).param("userId", userId).update();

        jdbc.sql("""
            UPDATE pp.stg_nmms_phase2_results r SET match_status = 'MATCHED'
            FROM pp.std_applicant_primary_info s
            WHERE r.nmms_reg_number::numeric = s.nmms_reg_number AND r.district = :district::numeric
            """).param("district", districtId).update();
    }

    private static final String STD_INSERT = """
        INSERT INTO pp.std_applicant_primary_info
          (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
           nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
        VALUES (:nmms_year::numeric, :reg::numeric, NULLIF(regexp_replace(:sats, '\\D', '', 'g'), '')::numeric,
                :student_name, :father_name, :app_state::numeric, :district::numeric, :nmms_block::numeric,
                :gmat::numeric, :sat::numeric, :contact_no1, :contact_no2, :dise, :userId::numeric)
        """;

    /** resolve-lively: manual pair insert + domino auto-match of the remaining unique pair. */
    @Transactional
    public void resolveMatch(String appId, String resId, String userId) {
        Map<String, Object> app = jdbc.sql("SELECT * FROM pp.stg_nmms_phase1_applications WHERE id = :id::bigint")
                .param("id", appId).query((rs, i) -> MergeReadRepository.genericRow(rs)).optional().orElse(null);
        Map<String, Object> res = jdbc.sql("SELECT * FROM pp.stg_nmms_phase2_results WHERE result_stg_id = :id::bigint")
                .param("id", resId).query((rs, i) -> MergeReadRepository.genericRow(rs)).optional().orElse(null);
        if (app == null || res == null) throw new IllegalStateException("Records not found.");

        insertStd(app, res, userId);
        markMatched(resId);

        // domino: same normalized-name + block remaining rows
        String name = (String) app.get("student_name");
        Object block = app.get("nmms_block");
        List<Map<String, Object>> remApps = jdbc.sql("""
                SELECT * FROM pp.stg_nmms_phase1_applications
                WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(:name, '[^a-zA-Z0-9]', '', 'g'))
                  AND nmms_block = :block::numeric
                """).param("name", name).param("block", block).query((rs, i) -> MergeReadRepository.genericRow(rs)).list();
        List<Map<String, Object>> remRes = jdbc.sql("""
                SELECT * FROM pp.stg_nmms_phase2_results
                WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(:name, '[^a-zA-Z0-9]', '', 'g'))
                  AND nmms_block = :block::numeric AND match_status != 'MATCHED'
                """).param("name", name).param("block", block).query((rs, i) -> MergeReadRepository.genericRow(rs)).list();

        if (remApps.size() == 1 && remRes.size() == 1) {
            insertStd(remApps.get(0), remRes.get(0), userId);
            markMatched(String.valueOf(remRes.get(0).get("result_stg_id")));
        }
    }

    private void insertStd(Map<String, Object> app, Map<String, Object> res, String userId) {
        jdbc.sql(STD_INSERT)
            .param("nmms_year", app.get("nmms_year"))
            .param("reg", res.get("nmms_reg_number"))
            .param("sats", app.get("students_sats_id"))
            .param("student_name", app.get("student_name"))
            .param("father_name", app.get("father_name"))
            .param("app_state", app.get("app_state"))
            .param("district", app.get("district"))
            .param("nmms_block", app.get("nmms_block"))
            .param("gmat", "AB".equals(res.get("gmat_score")) ? "0" : res.get("gmat_score"))
            .param("sat", "AB".equals(res.get("sat_score")) ? "0" : res.get("sat_score"))
            .param("contact_no1", app.get("contact_no1"))
            .param("contact_no2", app.get("contact_no2"))
            .param("dise", app.get("current_institute_dise_code"))
            .param("userId", userId)
            .update();
    }

    private void markMatched(String resId) {
        jdbc.sql("UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = :id::bigint")
            .param("id", resId).update();
    }

    /** commit-to-primary: freeze draft rows into the primary table for a district/year. */
    @Transactional
    public void commitToPrimary(String districtId, String year) {
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info
              (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
               nmms_block, gmat_score, sat_score, created_by, current_institute_dise_code, contact_no1, contact_no2)
            SELECT nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district,
                   nmms_block, gmat_score, sat_score, created_by, current_institute_dise_code, contact_no1, contact_no2
            FROM pp.std_applicant_primary_info WHERE district = :district::numeric AND nmms_year = :year::numeric
            ON CONFLICT (nmms_reg_number) DO NOTHING
            """).param("district", districtId).param("year", year).update();
    }
```
(Requires imports in `MergeWriteRepository`: `java.util.Map`, `com.rcf.imas.modules.merge.persistence.MergeReadRepository` is same package — `genericRow` is package-private static, directly accessible.)

Add handlers to `MergeController` (inject `MergeWriteRepository writes`):
```java
    @PostMapping("/bulk-auto-map")
    public Map<String, Object> bulkAutoMap(@RequestBody Map<String, Object> body,
                                           @org.springframework.security.core.annotation.AuthenticationPrincipal
                                           com.rcf.imas.platform.security.JwtService.FinalToken principal) {
        String userId = principal == null || principal.userId() == null ? "1" : principal.userId();
        writes.moveMappedToStd(str(body.get("district")), str(body.get("year")), userId);
        return Map.of("message", "Bulk mapping successful. Records copied to draft.");
    }

    @PostMapping("/resolve-lively")
    public Map<String, Object> resolveLively(@RequestBody Map<String, Object> body,
                                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.rcf.imas.platform.security.JwtService.FinalToken principal) {
        String userId = principal == null || principal.userId() == null ? "1" : principal.userId();
        try {
            writes.resolveMatch(str(body.get("app_id")), str(body.get("res_id")), userId);
        } catch (RuntimeException e) {
            throw ApiException.error(500, "Mapping failed");
        }
        return Map.of("message", "Mapped successfully");
    }

    @PostMapping("/commit-to-primary")
    public Map<String, Object> commitToPrimary(@RequestBody Map<String, Object> body) {
        writes.commitToPrimary(str(body.get("district")), str(body.get("year")));
        return Map.of("message", "Successfully committed to Primary Table.");
    }
```

> **Parity note (resolve-lively 500).** Node wraps the model in try/catch → `500 {error:"Mapping failed"}` on any failure (incl. "Records not found."). We mirror that: catch the repository exception and rethrow as `ApiException.error(500, "Mapping failed")`. `bulk-auto-map` and `commit-to-primary` rely on `GlobalExceptionHandler` → but their Node error bodies are `{error:"Failed to process bulk mapping"}` / `{error:"Failed to finalize merge."}`. Wrap those two the same way (try/catch → `ApiException.error(500, "<exact msg>")`) so the error body key matches. **Implementer: add the try/catch to all three for exact error-body parity.**

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeWriteIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeWriteIT.java
git commit -m "feat(merge): merge writes (bulk-auto-map, resolve-lively domino, commit-to-primary)"
```

---

## Task 6: downloads + guarded delete — download-template, district download-csv, delete-district-data

Port the two CSV downloads and the guarded delete. Delete uses a **whitelist enum** (never interpolate the request `phase`/`section`) and the controller guards (already-committed / already-drafted).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeReadRepository.java` (district CSV data + guard checks)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/persistence/MergeWriteRepository.java` (deleteDistrictData with whitelist)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/merge/web/MergeController.java` (3 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeDownloadDeleteIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/merge/MergeDownloadDeleteIT.java`:
```java
package com.rcf.imas.modules.merge;

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
class MergeDownloadDeleteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        cleanup();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (990001,'BELAGAVI','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (990010,'GOKAK','BLOCK',990001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('mdseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='mdseed'").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.std_applicant_primary_info WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = 990001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (990001, 990010)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'mdseed'").update();
    }

    @Test
    void downloadTemplateP1IsHeaderOnlyCsv() throws Exception {
        String body = mvc.perform(get("/api/merge/download-template?phase=p1").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("nmms_year").contains("current_institute_dise_code").contains("date_of_application");
    }

    @Test
    void downloadTemplateInvalidPhaseIs400() throws Exception {
        mvc.perform(get("/api/merge/download-template?phase=zzz").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid phase"));
    }

    @Test
    void districtCsvEmptyIs404() throws Exception {
        mvc.perform(get("/api/merge/district/990001/download-csv").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No data found for this district."));
    }

    @Test
    void districtCsvReturnsRows() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, contact_no1, created_by)
            VALUES (2025, 24010000001, 990001, 990010, 'Asha', 'Ravi', '9876543210', :u)
            """).param("u", uid).update();
        String body = mvc.perform(get("/api/merge/district/990001/download-csv").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("student_name").contains("Asha");
    }

    @Test
    void deleteP1DataWhenNotYetDrafted() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',990001,990010,'X','x')").update();
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("1 Phase 1 application records deleted for district 990001"));
        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = 990001").query(Long.class).single();
        assertThat(n).isEqualTo(0);
    }

    @Test
    void deleteBlockedWhenAlreadyDrafted() throws Exception {
        jdbc.sql("INSERT INTO pp.stg_nmms_phase1_applications (id, nmms_year, district, nmms_block, student_name, student_name_key) VALUES (nextval('pp.stg_nmms_phase1_applications_id_seq'),'2025',990001,990010,'X','x')").update();
        jdbc.sql("INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, district, nmms_block, student_name, created_by) VALUES (2025, 24010000002, 990001, 990010, 'X', :u)").param("u", uid).update();
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Deletion not allowed!! Data already merged. To continue with the deletion you need to delete the merged data"));
    }

    @Test
    void deleteRequiresDistrictAndYear() throws Exception {
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"year\":\"2025\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("District is required"));
        mvc.perform(delete("/api/merge/delete-district-data").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"district\":\"990001\",\"phase\":\"p1\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Year is required"));
    }

    @Test
    void downloadsAndDeleteAreAdminOnly() throws Exception {
        String student = jwt.issueFinalToken("2", "s", "STUDENT");
        mvc.perform(get("/api/merge/download-template?phase=p1").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeDownloadDeleteIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `MergeReadRepository`:
```java
    // ---- 3) district CSV data ----
    public List<Map<String, Object>> districtMergedData(String districtId) {
        return jdbc.sql("""
                SELECT s.student_name, s.father_name, s.nmms_reg_number, s.students_sats_id,
                       d.juris_name AS district_name, b.juris_name AS block_name,
                       s.gmat_score, s.sat_score, s.contact_no1
                FROM pp.std_applicant_primary_info s
                LEFT JOIN pp.jurisdiction d ON s.district = d.juris_code
                LEFT JOIN pp.jurisdiction b ON s.nmms_block = b.juris_code
                WHERE s.district = :district::numeric
                ORDER BY s.student_name
                """).param("district", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    // ---- 14) delete guards ----
    public boolean stdPrimaryExists(String districtId, String year) {
        return !jdbc.sql("SELECT 1 FROM pp.std_applicant_primary_info WHERE district = :d::numeric AND nmms_year = :y::numeric LIMIT 1")
                .param("d", districtId).param("y", year).query(Integer.class).list().isEmpty();
    }

    public boolean applicantPrimaryExists(String districtId, String year) {
        return !jdbc.sql("SELECT 1 FROM pp.applicant_primary_info WHERE district = :d::numeric AND nmms_year = :y::numeric LIMIT 1")
                .param("d", districtId).param("y", year).query(Integer.class).list().isEmpty();
    }
```

Add to `MergeWriteRepository` (whitelist enum — the ONLY place a table name is chosen, never from the raw request string):
```java
    /** Whitelisted deletable targets. The request's phase/section maps to one of these three; nothing else is deletable. */
    public enum DeleteTarget {
        P1("pp.stg_nmms_phase1_applications"),
        P2("pp.stg_nmms_phase2_results"),
        MERGE("pp.std_applicant_primary_info");
        final String table;
        DeleteTarget(String table) { this.table = table; }
    }

    public long deleteDistrictData(DeleteTarget target, String districtId) {
        // target.table is a compile-time constant from the enum — never the request value.
        return jdbc.sql("DELETE FROM " + target.table + " WHERE district = :d::numeric")
                .param("d", districtId).update();
    }
```

Add handlers to `MergeController` (inject `CsvSupport csv` already present from Task 3):
```java
    private static final List<String> P1_TEMPLATE = List.of(
        "nmms_year","Exam","app_state","district","nmms_block","current_institute_dise_code","students_sats_id",
        "student_name","father_name","institute_name","institute_type","category_name","disability_status",
        "contact_no1","contact_no2","date_of_application");
    private static final List<String> P2_TEMPLATE = List.of(
        "nmms_year","nmms_block","nmms_reg_number","student_name","gmat_score","sat_score","total");

    @GetMapping("/download-template")
    public org.springframework.http.ResponseEntity<String> downloadTemplate(@RequestParam(required = false) String phase) {
        List<String> fields;
        if ("p1".equals(phase)) fields = P1_TEMPLATE;
        else if ("p2".equals(phase)) fields = P2_TEMPLATE;
        else throw ApiException.error(400, "Invalid phase");
        String body = csv.write(fields, List.of(new LinkedHashMap<>()));   // header-only (single empty row → header line)
        return csvResponse(body, "NMMS_" + phase + "_Template.csv");
    }

    @GetMapping("/district/{districtId}/download-csv")
    public org.springframework.http.ResponseEntity<String> districtCsv(@PathVariable String districtId) {
        List<Map<String, Object>> data = reads.districtMergedData(districtId);
        if (data.isEmpty()) throw ApiException.message(404, "No data found for this district.");
        List<String> fields = new java.util.ArrayList<>(data.get(0).keySet());
        String body = csv.write(fields, data);
        return csvResponse(body, "district_" + districtId + "_merged.csv");
    }

    @DeleteMapping("/delete-district-data")
    public Map<String, Object> deleteDistrictData(@RequestBody Map<String, Object> body) {
        String district = str(body.get("district"));
        String year = str(body.get("year"));
        String phase = str(body.get("phase"));
        String section = str(body.get("section"));
        if (district == null || district.isBlank()) throw ApiException.error(400, "District is required");
        if (year == null || year.isBlank()) throw ApiException.error(400, "Year is required");

        if ("merge".equals(section)) {
            if (reads.applicantPrimaryExists(district, year))
                throw ApiException.error(400, "Deletion not allowed!! The currrent district merge process is already completed");
        } else {
            if (reads.stdPrimaryExists(district, year))
                throw ApiException.error(400, "Deletion not allowed!! Data already merged. To continue with the deletion you need to delete the merged data");
        }

        long n;
        if ("merge".equals(section)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.MERGE, district);
            return Map.of("message", n + " records deleted from Primary Table for district " + district);
        }
        if ("p1".equals(phase)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.P1, district);
            return Map.of("message", n + " Phase 1 application records deleted for district " + district);
        }
        if ("p2".equals(phase)) {
            n = writes.deleteDistrictData(MergeWriteRepository.DeleteTarget.P2, district);
            return Map.of("message", n + " Phase 2 result records deleted for district " + district);
        }
        throw ApiException.error(400, "Invalid phase or section");
    }

    private static org.springframework.http.ResponseEntity<String> csvResponse(String body, String filename) {
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Type", "text/csv")
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(body);
    }
```

> **Parity note (delete guard order).** Node checks the guard **before** deleting: for `section==="merge"` it refuses if `applicant_primary_info` already has the district/year (merge completed); otherwise (p1/p2) it refuses if `std_applicant_primary_info` already has drafted rows. Preserve this exact branch order and the exact (typo-carrying) messages, incl. `"currrent"`.
>
> **Security note.** The delete table is chosen only from `DeleteTarget` (a 3-value enum of constant table names). The request's `phase`/`section` selects an enum constant via `equals` comparisons — the raw string is never concatenated into SQL. This closes the auditor's string-interpolation flag while staying simple.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=MergeDownloadDeleteIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + the new merge tests green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/merge imas-backend/src/test/java/com/rcf/imas/modules/merge/MergeDownloadDeleteIT.java
git commit -m "feat(merge): downloads (template + district CSV) and guarded delete (whitelist enum)"
```

---

## Final review (after all 6 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/merge` package against this plan + the spec, checking:
- **Parity:** every endpoint's status codes + body keys match the contract table; the three `Number()`-coerced endpoints emit JSON numbers; `merged-status` emits the three `null`s; raw-row endpoints emit numeric/bigint ids as Strings.
- **Simplicity:** no gold-plating; the Dice algorithm was correctly omitted; per-row inserts (not hand-built placeholder strings) are acceptable given per-district volumes.
- **Auth:** class-level `@PreAuthorize("hasRole('ADMIN')")`; all handler methods `public`.
- **Transactions:** all multi-statement writes live in `MergeWriteRepository`; the delete uses the whitelist enum.
- **Isolation:** every IT `@AfterEach`-cleans children-before-parents; no cross-test bleed (full suite green).
- **`ApiException` usage:** the upload failure body (`{success:false,logs}`) and the error-key endpoints (`{error:...}`) each match Node exactly.

Update `imas-migration-status` memory: Phase 2b complete, new test count, then proceed to Plan 2c (shortlisting) using `phase2c-shortlisting-ground-truth.md`.

## Deferred / parity decisions carried into this plan

- **Dice `getSuggestion` omitted** — dead code in Node's live path (models never invoke the passed-in function). Removes the hardest parity risk; recorded as an intentional simplification.
- **`merged-status` null-counts bug preserved** — Node maps fields its SQL never selects. Reproduced faithfully (frontend already receives nulls). One-line fix available later if the frontend is confirmed to ignore it.
- **p1 `"Exam"` template header quirk** — the shipped template capitalizes `Exam` so `row.exam` never populates; not "fixed" (parity).
- **p2 silent-partial insert** — reg/name validation failures push no log, so an all-reg/name-failure file inserts only its valid rows. Preserved and pinned by a test.
- **Per-row insert vs Node's 5000-row multi-VALUES batch** — same atomicity within one `@Transactional`; simpler; per-district volumes make it a non-issue.
- **ADMIN enforcement is NEW** vs Node's fully-open merge routes (audit CRITICAL). Add to the fetch audit so the frontend admin-token assumption is confirmed.
