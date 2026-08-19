# IMAS Spring Boot Migration — Plan 3b: Evaluation + Custom-List + Evaluation-Dashboard

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port three Node controller/model trios to a new `com.rcf.imas.modules.evaluation` module:
- `server/controllers/customListController.js` + `server/models/customListModel.js` — 9 endpoints, mounted **twice** in Node (`/api/custom-list/*` and `/api/evaluation/*`, byte-identical routers) — reproduced as one Spring controller with a **dual class-level `@RequestMapping`**.
- `server/controllers/evaluationController.js` + `server/models/evaluationModels.js` — `exam_names` + `download_excel` only. `bulk-upload` is **explicitly NOT ported** (see Firm Decision 1).
- `server/controllers/evaluationDashboardController.js` + `server/models/evaluationDashboardModel.js` — 3 read-only aggregation endpoints.

All three controllers are `@PreAuthorize("hasRole('ADMIN')")` at the class level — Node left every one of these routes fully open; this is new enforcement (audit CRITICAL, same posture as Plans 2a–3d).

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `evaluation` with `web/`, `persistence/`, `service/`:
- `EvaluationReadRepository` — genericRow mapper + all read queries for custom-list + evaluation (exam_names/getStudents) + information_schema introspection.
- `EvaluationWriteRepository` (`@Repository`, dedicated bean — self-invocation does not honor `@Transactional`) — `saveListFull`'s create-or-replace flow, `deleteList`.
- `DashboardReadRepository` — the 3 dashboard aggregations, with **real typed ints** (not genericRow's string-ifying convention) where Node's `parseInt` makes them real JSON numbers, and raw passthrough where it doesn't (see Firm Decision 8 and the Task 5 note on `getJurisdictionStatus`'s top-level fields).
- `EvaluationListXlsxSupport` / `EvaluationListPdfSupport` (or one `EvaluationExportSupport`) — POI for `download-xlsx`, OpenPDF for `download-pdf`.
- `StudentExcelSupport` — POI 34-column fixed-layout export for `download_excel`.

**Tech Stack (one addition):** `com.github.librepdf:openpdf:2.0.3` added to `imas-backend/pom.xml` in Task 3 (first PDF need). Apache POI already on the classpath (Plan 2a+).

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Plans 1, 2a, 2b, 2c, 3d are merged and green: `PgIntegrationTest`, `JwtService`, `SecurityConfig`, `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1/2a/2b/2c/3d — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `list_id = :listId::numeric`, `cohortId::numeric`, `stateId::numeric`, `districtId::numeric`, `blockId::numeric`, `year::numeric`, etc. Numeric arrays bind as `BigDecimal[]`.
> 3. **Numeric + bigint columns serialize as Strings** via the shared `genericRow` mapper (`rs.getBigDecimal(...).toBigInteger().toString()` for `NUMERIC`/`DECIMAL`; `String.valueOf(rs.getLong(i))` for `BIGINT`, which is what Postgres `COUNT(*)` returns — this conveniently matches Node's `pg` driver, which also returns `NUMERIC`/`BIGINT` columns as JS strings unless explicitly `parseInt`'d). `DATE` → `"yyyy-MM-dd"`. `TIME` → `"HH:mm:ss"`. `TIMESTAMP` → ISO-Z. Everything else passes through `rs.getObject(i)`. Map keys are literal (snake_case or the query's `AS` alias, verbatim — see the `"totalShortlisted"` camelCase alias in Task 5, which stays camelCase, NOT snake_cased, because it's a literal SQL column alias captured by `getColumnLabel`, not a POJO field name that Jackson's snake_case strategy would touch).
> 4. **snake_case JSON** global default (POJO fields only — Map keys pass through literally, see above).
> 5. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`. This module's error-key mapping (Firm Decision 3, standardizing on clean JSON where Node had none/plain-text):
>    - Custom-list reads/writes (`/lists`, `/batches`, `/available-fields`, `/students-by-list`, `/students-by-cohort`, `/save-list-full`, `/list/{id}` delete, `/download-xlsx`, `/download-pdf`): unexpected failures → generic `{error:"Internal Server Error"}` via `GlobalExceptionHandler`'s fallback (no per-endpoint try/catch needed).
>    - `exam_names`: missing/blank `year` → `400 {message:"Academic year is required"}` (explicit `ApiException.message`).
>    - `download_excel`: unexpected failures → generic `{error:"Internal Server Error"}`.
>    - **Dashboard's 3 endpoints each need their OWN 500 message** (`"Failed to fetch overall counts."` / `"Failed to fetch jurisdictional progress."` / `"Failed to fetch overall progress."`) — Node has a distinct `catch` per handler; this does NOT fall through to the generic fallback. Wrap each dashboard handler's body in try/catch, rethrowing as `ApiException.error(500, "<exact message>")`.
> 6. **Controllers:** class package-private; every handler method **`public`**.
> 7. **Auth (NEW enforcement):** all three controllers `@PreAuthorize("hasRole('ADMIN')")` class-level. Node left every route in this module open.
> 8. **Transactions:** only `saveListFull` (create-or-replace, multi-statement) needs `@Transactional`, in a dedicated `EvaluationWriteRepository` bean (self-invocation does not trigger Spring's transactional proxy — verified pattern from `ShortlistWriteRepository`). `deleteList` is single-statement, no `@Transactional` needed (FK `ON DELETE CASCADE` handles children at the DB level). Dashboard and read endpoints: no transactions.
> 9. **Test isolation:** all `*IT` extend `PgIntegrationTest` (one JVM-wide embedded Postgres). Seed `jurisdiction_type` before `jurisdiction`. Seed `pp."user"` before any row with a `created_by`/`updated_by` FK. `@AfterEach`-clean children-before-parents. Advance sequences (`setval`) after explicit-PK seeds.
> 10. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.
> 11. **FK order to respect when seeding:** `pp.student_master.applicant_id` → `applicant_primary_info` (UNIQUE, so exactly one `student_master` row per applicant); `pp.custom_list_students.student_id` → `student_master` (`ON DELETE CASCADE`); `pp.custom_list_fields.field_id` → `field_master` (`ON DELETE RESTRICT` — a field row referenced by any list cannot be deleted, so don't delete `field_master` rows in `@AfterEach` until after `custom_list_fields` is gone); `pp.custom_list_fields.list_id` / `pp.custom_list_students.list_id` → `custom_list` (`ON DELETE CASCADE` — deleting a `custom_list` row cascades both, so an explicit `DELETE FROM pp.custom_list` in cleanup is sufficient for those two children, but `field_master` rows survive and must be cleaned separately since `field_master` is NOT scoped per-list, see Firm-Decision/quirk 10 in the ground truth).

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase3b-evaluation-ground-truth.md`. Node source read in full: `server/controllers/customListController.js` (231 lines), `server/models/customListModel.js` (264 lines), `server/controllers/evaluationController.js` (283 lines, `bulk-upload` section not ported), `server/models/evaluationModels.js` (getExamNames/getStudents sections), `server/controllers/evaluationDashboardController.js` (49 lines), `server/models/evaluationDashboardModel.js` (105 lines), plus both routers (`evaluationRoutes.js`, `customListRoutes.js`, `evaluationDashboardRoutes.js`).

### Table facts relevant to this module (from `live-schema.sql`)

- `pp.custom_list` — `list_id numeric(10,0)` PK default `nextval(custom_list_id_seq)`, `list_name varchar(200) NOT NULL`.
- `pp.custom_list_fields` — composite PK `(list_id, field_id)`; `field_id → field_master ON DELETE RESTRICT`; `list_id → custom_list ON DELETE CASCADE`.
- `pp.custom_list_students` — composite PK `(list_id, student_id)`; `list_id → custom_list ON DELETE CASCADE`; `student_id → student_master ON DELETE CASCADE`.
- `pp.field_master` — `field_id numeric(6,0)` PK default `nextval(field_id_seq)`, `tab_name varchar(100) DEFAULT 'pp.student_master' NOT NULL`, `col_name varchar(100) NOT NULL`. **Shared across all lists** — keyed only by `col_name`, no per-list scoping (a field row, once created, is reused by every subsequent list that references the same column).
- `pp.student_master` — `student_id numeric(14,0)` PK, `applicant_id` UNIQUE FK → `applicant_primary_info`, `enr_id` UNIQUE, `batch_id int → batch`, `current/previous_institute_dise_code → institute`, `active_yn varchar(10) DEFAULT 'ACTIVE' CHECK IN ('ACTIVE','INACTIVE')`.
- `pp.batch` — `batch_id int` PK, `cohort_number int → cohort ON DELETE CASCADE`, `(cohort_number, batch_name)` UNIQUE.
- `pp.cohort` — `cohort_number int` PK, `cohort_name` UNIQUE.
- `pp.examination` — `exam_id numeric(14,0)` PK, `exam_name varchar(100) NOT NULL`, `exam_year varchar(10)`.
- `pp.applicant_exam`, `pp.applicant_exam_attendance`, `pp.exam_results`, `pp.applicant_secondary_info`, `pp.applicant_primary_info` — see Firm Decision 5a (join-chain bug feeding `download_excel`).
- `pp.jurisdiction` — `juris_code numeric(12,0)` PK, `juris_name`, `juris_type`, `parent_juris` self-FK.
- `pp.applicant_shortlist_info`, `pp.student_interview`, `pp.home_verification` — see Task 5 (dashboard aggregations); none have a unique/PK on `applicant_id` (fan-out is possible but not exercised by this module's endpoints beyond `COUNT`, which is fan-out-tolerant by construction).

### Endpoint contract (14 handlers across 3 controllers, all `@PreAuthorize("hasRole('ADMIN')")`)

**`CustomListController`** — `@RequestMapping({"/api/custom-list", "/api/evaluation"})` (both base paths live, Firm Decision — Node's two routers are byte-identical):

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 1 | GET `/lists` | `200 [{list_id, list_name, student_count}]` (`student_count` a String — bigint COUNT via genericRow) | `500 {error:"Internal Server Error"}` |
| 2 | GET `/batches?cohortId=` | `200 [{batch_id, batch_name, cohort_name}]` | `500 {error:...}` |
| 3 | GET `/available-fields` | `200 [{col_name, display_name}]`, **live** `information_schema.columns` introspection | `500 {error:...}` |
| 4 | GET `/students-by-list/{listId}` | `200 {students:[...sm.* + joins...], fields:[{col_name,field_id,display_name}]}` | `500 {error:...}` |
| 5 | GET `/students-by-cohort/{cohortId}?batchId&stateId&divisionId&districtId&blockId` | `200 [{student_id, student_name, batch_name, gender}]`; hard-coded `nmms_year=2025`; `divisionId` accepted but ignored | `500 {error:...}` |
| 6 | POST `/save-list-full` body `{list_id, list_name, student_ids, selectedFields}` | `200 {success:true, list_id}` | `500 {error:...}` |
| 7 | DELETE `/list/{id}` | `200 {success:true}` (always, regardless of whether the row existed — Node never checks affected-row count) | `500 {error:...}` |
| 8 | GET `/download-pdf/{listId}` | `200` PDF bytes, `Content-Disposition: attachment; filename="<listName>.pdf"` | `500 {error:...}` |
| 9 | GET `/download-xlsx/{listId}` | `200` XLSX bytes, `Content-Disposition: attachment; filename="<listName>.xlsx"` | `500 {error:...}` |

**`EvaluationController`** — `@RequestMapping("/api/evaluation")`:

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 10 | GET `/exam_names?year=` | `200 {statusCode:200, data:[{exam_name}], message:"ok", success:true}` | `400 {message:"Academic year is required"}` if `year` missing/blank; `500 {error:...}` otherwise |
| 11 | POST `/download_excel` body `{exam_name}` | `200` XLSX bytes (34 fixed cols, 4 color groups), filename **not quoted**: `Content-Disposition: attachment; filename=students_<sanitized>.xlsx` | `500 {error:...}` |

**`EvaluationDashboardController`** — `@RequestMapping("/api/evaluation-dashboard")`:

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 12 | GET `/overall/{year}` | `200` 9-key labeled object, all real JSON ints | `500 {error:"Failed to fetch overall counts."}` |
| 13 | GET `/jurisdictions/{year}` | `200` raw array, see Task 5 for the exact string-vs-int split per field | `500 {error:"Failed to fetch jurisdictional progress."}` |
| 14 | GET `/overall-progress/{year}` | `200 {overallProgress:<int>}` | `500 {error:"Failed to fetch overall progress."}` |

**`bulk-upload` is NOT implemented** — see "Deferred / parity decisions" at the end of this plan.

## Exact SQL (verbatim, from `customListModel.js` / `evaluationModels.js` / `evaluationDashboardModel.js`)

### `getAllLists()`
```sql
SELECT cl.list_id, cl.list_name, COUNT(cls.student_id) AS student_count
FROM pp.custom_list cl
LEFT JOIN pp.custom_list_students cls ON cl.list_id = cls.list_id
GROUP BY cl.list_id, cl.list_name
ORDER BY cl.list_id DESC
```

### `getAvailableFields()` — **live introspection, reproduce verbatim, no static list (Firm Decision 2)**
```sql
SELECT
    column_name AS col_name,
    CASE
        WHEN column_name = 'batch_id' THEN 'Batch Name'
        WHEN column_name = 'current_institute_dise_code' THEN 'Current School Name'
        WHEN column_name = 'previous_institute_dise_code' THEN 'Previous School Name'
        WHEN column_name = 'active_yn' THEN 'Active Status'
        WHEN column_name = 'contact_no1' THEN 'Contact Number 1'
        WHEN column_name = 'contact_no2' THEN 'Contact Number 2'
        WHEN column_name = 'enr_id' THEN 'Enrollment Id'
        ELSE INITCAP(REPLACE(column_name, '_', ' '))
    END AS display_name
FROM information_schema.columns
WHERE table_schema = 'pp'
  AND table_name = 'student_master'
  AND column_name NOT IN ('created_at','updated_at','created_by','updated_by','applicant_id','photo_link','student_id')
UNION ALL
SELECT
    column_name AS col_name,
    CASE
        WHEN column_name = 'district' THEN 'District'
        WHEN column_name = 'nmms_block' THEN 'Block'
    END AS display_name
FROM information_schema.columns
WHERE table_schema = 'pp'
  AND table_name = 'applicant_primary_info'
  AND column_name IN ('district','nmms_block')
ORDER BY display_name ASC
```
No params. Runs against the live catalog every call — behaves identically to the Node version under future schema changes.

### `getAllBatches(cohortId)` — dynamic WHERE
```sql
SELECT b.batch_id, b.batch_name, c.cohort_name
FROM pp.batch b
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
[ WHERE b.cohort_number = :cohortId::numeric ]   -- only if cohortId present and not "null"/"undefined"
ORDER BY b.batch_name
```

### `getStudentsByListId(listId)` — two queries, `listId` bound to both
```sql
-- studentQuery
SELECT
    sm.*,
    batch.batch_name,
    inst_curr.institute_name AS current_institute_name,
    inst_prev.institute_name AS previous_institute_name,
    dist.juris_name AS district,
    blk.juris_name AS block
FROM pp.custom_list_students cls
JOIN pp.student_master sm ON cls.student_id = sm.student_id
LEFT JOIN pp.batch batch ON batch.batch_id = sm.batch_id
LEFT JOIN pp.institute inst_curr ON sm.current_institute_dise_code = inst_curr.dise_code
LEFT JOIN pp.institute inst_prev ON sm.previous_institute_dise_code = inst_prev.dise_code
LEFT JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.jurisdiction dist ON api.district = dist.juris_code
LEFT JOIN pp.jurisdiction blk ON api.nmms_block = blk.juris_code
WHERE cls.list_id = :listId::numeric
ORDER BY sm.student_name

-- fieldsQuery
SELECT
    fm.col_name, fm.field_id,
    CASE
        WHEN fm.col_name = 'batch_id' THEN 'Batch Name'
        WHEN fm.col_name = 'current_institute_dise_code' THEN 'Current School Name'
        WHEN fm.col_name = 'previous_institute_dise_code' THEN 'Previous School Name'
        WHEN fm.col_name = 'active_yn' THEN 'Active Status'
        WHEN fm.col_name = 'contact_no1' THEN 'Contact Number 1'
        WHEN fm.col_name = 'contact_no2' THEN 'Contact Number 2'
        WHEN fm.col_name = 'enr_id' THEN 'Enrollment Id'
        ELSE INITCAP(REPLACE(fm.col_name, '_', ' '))
    END as display_name
FROM pp.custom_list_fields clf
JOIN pp.field_master fm ON clf.field_id = fm.field_id
WHERE clf.list_id = :listId::numeric
```
`sm.*` means the Java mapper must handle an **arbitrary/dynamic column set** — `genericRow` already does this generically via `ResultSetMetaData`, no changes needed.

### `getStudentsByCohort(cohortId, batchId, stateId, divisionId, districtId, blockId)` — dynamic, hard-coded year
```sql
SELECT sm.student_id, sm.student_name, b.batch_name, api.gender
FROM pp.student_master sm
JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
WHERE sm.active_yn = 'ACTIVE'
  AND api.nmms_year = 2025                          -- ⚠ hard-coded literal, NOT a bind param (Firm Decision 5d)
  [ AND b.cohort_number = :cohortId::numeric ]       -- addFilter(cohortId)
  [ AND sm.batch_id = :batchId::numeric ]            -- addFilter(batchId)
  [ AND api.app_state = :stateId::numeric ]          -- addFilter(stateId)
  [ AND api.district = :districtId::numeric ]        -- addFilter(districtId)
  [ AND api.nmms_block = :blockId::numeric ]          -- addFilter(blockId)
ORDER BY sm.student_name
```
`addFilter` skips a value that is null/blank/`"all"`/`"null"`/`"undefined"`. **`divisionId` is accepted as a query param but never used in any filter (Firm Decision 5e) — silently dropped.**

### `getListHeader(listId)`
```sql
SELECT list_name FROM pp.custom_list WHERE list_id = :listId::numeric
```

### `saveListFull` — full transactional flow (see Task 2 for the Java port)
```sql
INSERT INTO pp.custom_list (list_name) VALUES (:listName) RETURNING list_id;                    -- no listId
UPDATE pp.custom_list SET list_name = :listName WHERE list_id = :listId::numeric;                -- listId given
DELETE FROM pp.custom_list_fields WHERE list_id = :listId::numeric;                              -- unconditional on update path
DELETE FROM pp.custom_list_students WHERE list_id = :listId::numeric;                            -- unconditional on update path
SELECT field_id FROM pp.field_master WHERE col_name = :colName;
INSERT INTO pp.field_master (tab_name, col_name) VALUES ('pp.student_master', :colName) RETURNING field_id;  -- only if not found
INSERT INTO pp.custom_list_fields (list_id, field_id) VALUES (:listId::numeric, :fieldId::numeric);
INSERT INTO pp.custom_list_students (list_id, student_id) VALUES (:listId::numeric, :studentId::numeric);
```

### `deleteList(id)`
```sql
DELETE FROM pp.custom_list WHERE list_id = :id::numeric
```

### `getExamNames(year)`
```sql
SELECT exam_name FROM pp.examination WHERE exam_year LIKE :prefix ORDER BY exam_id ASC
```
`prefix = year.split("-")[0].trim() + "%"` — e.g. `"2026-27"` → `"2026%"`. If `year` has no `"-"`, `split("-")[0]` is the whole trimmed string (still works, no truncation, e.g. `"2026"` → `"2026%"`).

### `getStudents(exam_name)` — feeds `download_excel`, **bug 5a preserved verbatim**
```sql
SELECT
    api.applicant_id, api.student_name, api.father_name, api.mother_name,
    asi.village, api.gender, api.aadhaar, api.dob, api.medium, api.home_address,
    api.family_income_total,
    asi.father_occupation, asi.mother_occupation, asi.father_education, asi.mother_education,
    asi.household_size, asi.own_house, asi.smart_phone_home, asi.internet_facility_home,
    asi.career_goals, asi.subjects_of_interest, asi.transportation_mode, asi.distance_to_school,
    asi.num_two_wheelers, asi.num_four_wheelers, asi.irrigation_land,
    asi.neighbor_name, asi.neighbor_phone, asi.favorite_teacher_name, asi.favorite_teacher_phone,
    aea.pp_exam_appeared_yn,
    er.pp_exam_score, er.pp_exam_cleared, er.interview_required_yn
FROM pp.examination ex
LEFT JOIN pp.applicant_exam ae ON ae.exam_id = ex.exam_id
LEFT JOIN pp.applicant_primary_info api ON api.applicant_id = ae.applicant_id
LEFT JOIN pp.applicant_secondary_info asi ON api.applicant_id = asi.applicant_id
LEFT JOIN pp.exam_results er ON asi.applicant_id = er.applicant_id            -- ⚠ BUG: joins on asi, not api
LEFT JOIN pp.applicant_exam_attendance aea ON aea.applicant_id = asi.applicant_id  -- ⚠ BUG: joins on asi, not api
WHERE ex.exam_name = :examName
```
**Bug preserved verbatim (do NOT fix):** `exam_results`/`applicant_exam_attendance` are LEFT-JOINed on `asi.applicant_id` (`applicant_secondary_info`), not `api.applicant_id` (`applicant_primary_info`). Any applicant with exam-results/attendance rows but no `applicant_secondary_info` row yields NULL for `pp_exam_score`/`pp_exam_cleared`/`interview_required_yn`/`pp_exam_appeared_yn` even though matching rows exist elsewhere.

### `getOverallCounts(nmmsYear)` — 8 independent, sequential `COUNT(*)` queries, each `parseInt`'d to a real int
```sql
-- "Total Students"
SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric;
-- "Shortlisted"
SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
WHERE api.nmms_year = :year::numeric and a.shortlisted_yn='Y';
-- "Evaluated"
SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
WHERE api.nmms_year = :year::numeric;
-- "Pending Evaluation/Marks Entry"
SELECT COUNT(*) FROM pp.applicant_primary_info a
WHERE a.applicant_id NOT IN (SELECT asi.applicant_id FROM pp.applicant_secondary_info asi)
  AND a.applicant_id IN (SELECT s.applicant_id FROM pp.applicant_shortlist_info s)
  AND a.nmms_year = :year::numeric;
-- "Interview Required"
SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric;
-- "Pending Interviews Assignment"
SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
  AND NOT EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id);
-- "Pending Interview Result Upload"
SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.interview_result IS NULL);
-- "Home Verification Required"
SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn='Y');
-- "Pending Home Verification Result Upload"
SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn = 'Y')
  AND NOT EXISTS (SELECT 1 FROM pp.home_verification hv WHERE hv.applicant_id = er.applicant_id AND hv.status IS NOT NULL);
```
Response object: a `LinkedHashMap` with these 9 EXACT label strings as keys, insertion order preserved (matches Node's `for` loop over an ordered array), each value a real `int`.

### `getJurisdictionStatus(nmmsYear)` — single query
```sql
SELECT
  j.juris_name, j.juris_code,
  COUNT(asi.applicant_id) AS "totalShortlisted",
  COUNT(sec.applicant_id) AS "evaluated",
  COUNT(CASE WHEN sec.applicant_id IS NULL THEN 1 END) AS "pendingEvaluation",
  COUNT(CASE WHEN er.interview_required_yn = 'Y' THEN 1 END) AS "totalInterviewRequired",
  COUNT(CASE WHEN si.status = 'Completed' THEN 1 END) AS "completedInterview"     -- ⚠ BUG: mixed-case, never matches CHECK-constrained upper-case values
FROM pp.jurisdiction j
JOIN pp.applicant_primary_info a ON j.juris_code = a.nmms_block
JOIN pp.applicant_shortlist_info asi ON a.applicant_id = asi.applicant_id
LEFT JOIN pp.applicant_secondary_info sec ON a.applicant_id = sec.applicant_id
LEFT JOIN pp.exam_results er ON a.applicant_id = er.applicant_id
LEFT JOIN pp.student_interview si ON a.applicant_id = si.applicant_id
WHERE a.nmms_year = :year::numeric
GROUP BY j.juris_code, j.juris_name
ORDER BY j.juris_name ASC
```
**Alias note:** here `asi` = `applicant_shortlist_info` (unlike `getStudents`, where `asi` = `applicant_secondary_info` — do not cross-reference).

**Exact response shape per row (read the Node source, not just the ground-truth summary — see Task 5 note):**
```js
{
  ...row,                    // juris_name (string), juris_code (String via genericRow), totalShortlisted/evaluated/
                              // pendingEvaluation/totalInterviewRequired/completedInterview: RAW pg COUNT values,
                              // i.e. Strings — NOT reassigned/parsed at the top level (only used locally to compute progress)
  progress: <int>,           // Math.round((parseInt(evaluated)/parseInt(totalShortlisted))*100), or 0 if totalShortlisted==0
  counts: {                  // a SEPARATE, freshly parseInt'd sub-object — real ints, NOT the same values as the top-level strings' types
    pendingEvaluation: <int>,
    totalInterviewRequired: <int>,
    completedInterview: <int>
  }
}
```

### `getOverallProgress(nmmsYear)` — two queries
```sql
SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
WHERE api.nmms_year = :year::numeric;                                          -- NOTE: no shortlisted_yn filter (unlike getOverallCounts's "Shortlisted")

SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
WHERE api.nmms_year = :year::numeric;
```
`overallProgress = totalReq>0 ? round((totalDone/totalReq)*100) : 0` — a real int, only field in the response.

## File-generating endpoints

### `download-xlsx/{listId}` (customListController.js `downloadListXLS`) — POI
- Workbook, sheet `"Student List"`.
- Columns: if `fields` contains `student_id`, prepend `{header:"Student ID", key:"student_id", width:15}`; if it contains `student_name`, prepend `{header:"Student Name", key:"student_name", width:30}`; then one column per remaining field: `{header: f.display_name, key: f.col_name, width:25}`.
- Row values: special-case mapping — `batch_id → batch_name`, `current_institute_dise_code → current_institute_name`, `previous_institute_dise_code → previous_institute_name`, `district`/`district_id → district`, `nmms_block`/`block_id → block`; all other fields read directly by `col_name`; null/missing → literal `'-'`.
- Header row 1 bold. No fill colors, no data validation (unlike `download_excel`).
- `Content-Disposition: attachment; filename="<listName>.xlsx"` (quoted — Firm Decision/quirk 13, custom-list exports quote, `download_excel` does not). `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

### `download-pdf/{listId}` (customListController.js `downloadListPDF`) — **ported to OpenPDF, NOT pdfkit-table** (Firm Decision 4)
- Landscape A4, `Document` + `PdfPTable`.
- Header TEXT only (no logos — Firm Decision 4, human-facing download with no automated consumer; Node's own PDF drops the header on page 2+ anyway): title `"RAJALAKSHMI CHILDREN FOUNDATION"` bold centered; subtitle `"PRATIBHA POSHAK - 2025"` (hard-coded literal `"2025"`, matching Node's call-site literal, NOT derived from any year context) centered; then the list name, uppercased, in blue (`#0000FF`), as a bigger centered title below the header.
- Table columns: same dynamic ID/Name-prepend-then-fields logic as XLSX (ID width 50, Name width 150, other fields width 100 — proportionally, since OpenPDF widths are relative not absolute pixels, use these as relative column-width weights).
- Same special-case cell mapping as XLSX; null/missing → literal `'-'`.
- Standard built-in font (Times-Roman equivalent, e.g. OpenPDF's built-in `Font.TIMES_ROMAN` family) — Node's own Times-Roman can't render Kannada either, so no special font needed.
- `Content-Disposition: attachment; filename="<listName>.pdf"` (quoted). `Content-Type: application/pdf`.

### `download_excel` (evaluationController.js `downloadStudentExcel`) — fixed 34-column POI export
- Sheet `"Students"`. **Fixed columns (not dynamic)**, 4 fill-color groups:
  - Group 1 `FFFFCC`: Applicant ID, Student Name, Father Name, Mother Name, Village, `Gender(M,F)`, Aadhaar, Date of Birth, Medium, Home Address, Family Income.
  - Group 2 `CCFFCC`: Father/Mother Occupation, Father/Mother Education, Household Size, `Own House(Y,N)`, `Smart Phone at Home(Y,N)`, `Internet Facility at Home(Y,N)`, Career Goals, Subjects of Interest, Transportation Mode, Distance to School, Number of Two/Four Wheelers, Irrigation Land, Neighbor Name/Phone, Favorite Teacher Name/Phone.
  - Group 3 `FFCCCC`: `Exam Appeared Y/N`.
  - Group 4 `CCFFFF`: Exam Score, `Exam cleared Y/N`, Interview Required.
- Header: bold Calibri 11, black, solid fill per group, center/middle aligned, wrap text.
- Data: Calibri 10, wrap text. Column 8 (DOB, 1-based) → `numFmt "dd-mm-yyyy"`, centered. Column 11 (Family Income) → `numFmt "₹#,##0.00"`, right-aligned. Columns `[32,23,24,25,26]` (Exam Score, Distance, Two/Four Wheelers, Irrigation) → `numFmt "0.00"`, right-aligned. Columns `[6,17,18,19,31,33,34]` (Y/N + Gender) → centered.
- **Best-effort cosmetics (Firm Decision 7):** column-6 Gender dropdown (`"M,F"`), columns `[17,18,19,31,33,34]` Y/N dropdown (`"Y,N"`) via POI `DataValidationHelper`/`DataValidationConstraint`+`addValidationData`, applied to a fixed data-row range (e.g. rows 2..(rowCount+1)); column-8 cell `Comment` (`"Double click for calendar or enter date as DD-MM-YYYY"`) on every data row. The auto-fit width recompute (which Node runs LAST, overriding the initial widths) is DROPPED — document as a cosmetic simplification; POI's `autoSizeColumn` is a reasonable substitute if trivial to add, otherwise leave the widths as initially set. None of these cosmetics are asserted in the parity-critical column/color/numFmt/data tests.
- Filename: `students_<exam_name with [^a-z0-9]→_ , case-insensitive>.xlsx` — **not quoted** (`Content-Disposition: attachment; filename=students_....xlsx`).

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/evaluation/
├── web/CustomListController.java            (Tasks 1-3: dual @RequestMapping, 9 handlers)
├── web/EvaluationController.java             (Task 4: exam_names, download_excel)
├── web/EvaluationDashboardController.java    (Task 5: overall/jurisdictions/overall-progress)
├── persistence/EvaluationReadRepository.java (Tasks 1+4: genericRow mapper + all read queries)
├── persistence/EvaluationWriteRepository.java(Task 2: @Transactional saveListFull, deleteList)
├── persistence/DashboardReadRepository.java  (Task 5: typed-int dashboard aggregations)
├── service/CustomListXlsxSupport.java        (Task 3: POI download-xlsx)
├── service/CustomListPdfSupport.java         (Task 3: OpenPDF download-pdf)
└── service/StudentExcelSupport.java          (Task 4: POI download_excel, 34-col fixed layout)

imas-backend/src/test/java/com/rcf/imas/modules/evaluation/
├── CustomListReadsIT.java        (Task 1: lists/batches/available-fields/students-by-list/students-by-cohort + dual-path + admin-only)
├── CustomListWriteIT.java        (Task 2: save-list-full create+replace, delete, field_master reuse)
├── CustomListExportIT.java       (Task 3: download-xlsx + download-pdf)
├── EvaluationIT.java             (Task 4: exam_names + download_excel, bug 5a pin)
└── EvaluationDashboardIT.java    (Task 5: overall/jurisdictions/overall-progress, bugs 5b/5c pin)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks.
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN"|"STUDENT")`.
- Numeric path variables (`{listId}`, `{cohortId}`, `{id}`, `{year}`) are bound as Strings and cast `::numeric` in SQL, per convention #2 — a non-numeric segment throws a Postgres cast error, caught by the generic/per-endpoint handler as a 500 (matches Node's `NaN`-bound-param-then-Postgres-error behavior for the dashboard `:year`).

---

## Task 1: module skeleton + `EvaluationReadRepository` + `CustomListController` 5 reads (dual base path)

Port `/lists`, `/batches`, `/available-fields`, `/students-by-list/{listId}`, `/students-by-cohort/{cohortId}`. Establish the dual `@RequestMapping({"/api/custom-list","/api/evaluation"})` and the shared `genericRow` mapper.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/persistence/EvaluationReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/web/CustomListController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/evaluation/CustomListReadsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/evaluation/CustomListReadsIT.java`:
```java
package com.rcf.imas.modules.evaluation;

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
class CustomListReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('elseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='elseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "elseed", "ADMIN");
        student = jwt.issueFinalToken("999", "s", "STUDENT");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (7001,'Cohort EL') ON CONFLICT (cohort_number) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number, medium) VALUES (7001,'Batch EL',7001,'KANNADA') ON CONFLICT (batch_id) DO NOTHING").update();

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (700001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700003,'BELAGAVI DIST','EDUCATION DISTRICT',700001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (700004,'GOKAK BLOCK','BLOCK',700003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("UPDATE pp.jurisdiction SET juris_type = 'BLOCK' WHERE juris_code = 700004").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, medium, created_by, updated_by)
            VALUES (700101,2025,24070000001,700001,700003,700004,'ListKid','f','KANNADA',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.student_master (student_id, applicant_id, student_name, batch_id, active_yn)
            VALUES (700201, 700101, 'ListKid', 7001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.custom_list(list_id, list_name) VALUES (7001,'EL List') ON CONFLICT (list_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.custom_list_id_seq', (SELECT MAX(list_id)::bigint FROM pp.custom_list))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_students(list_id, student_id) VALUES (7001, 700201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = 7001").update(); // cascades custom_list_students/fields
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 700201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 700101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 7001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 7001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (700001,700003,700004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'elseed'").update();
    }

    @Test
    void listsReturnsStudentCountAsStringViaGenericRow() throws Exception {
        mvc.perform(get("/api/custom-list/lists").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.list_id=='7001')].student_count").value(org.hamcrest.Matchers.hasItem("1")));
    }

    @Test
    void listsAvailableUnderEvaluationBaseTooDualPath() throws Exception {
        mvc.perform(get("/api/evaluation/lists").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.list_id=='7001')].list_name").value(org.hamcrest.Matchers.hasItem("EL List")));
    }

    @Test
    void batchesFilteredByCohortId() throws Exception {
        mvc.perform(get("/api/custom-list/batches").param("cohortId", "7001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].batch_name").value("Batch EL"))
           .andExpect(jsonPath("$[0].cohort_name").value("Cohort EL"));
    }

    @Test
    void availableFieldsIsLiveIntrospectionNotAStaticList() throws Exception {
        mvc.perform(get("/api/custom-list/available-fields").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.col_name=='district')].display_name").value(org.hamcrest.Matchers.hasItem("District")))
           .andExpect(jsonPath("$[?(@.col_name=='applicant_id')]").isEmpty())     // excluded column
           .andExpect(jsonPath("$[?(@.col_name=='active_yn')].display_name").value(org.hamcrest.Matchers.hasItem("Active Status")));
    }

    @Test
    void studentsByListIdReturnsEnvelopeWithStudentsAndFields() throws Exception {
        mvc.perform(get("/api/custom-list/students-by-list/7001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students[0].student_name").value("ListKid"))
           .andExpect(jsonPath("$.students[0].batch_name").value("Batch EL"))
           .andExpect(jsonPath("$.fields").isArray());
    }

    @Test
    void studentsByCohortHardcodes2025AndIgnoresDivisionId() throws Exception {
        mvc.perform(get("/api/custom-list/students-by-cohort/7001")
                .param("stateId", "700001").param("districtId", "700003").param("blockId", "700004")
                .param("divisionId", "999999999")   // must be silently ignored
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("ListKid"));
    }

    @Test
    void allCustomListEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/custom-list/lists").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation/lists").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListReadsIT` — Expected: FAIL (no controller/repository yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/evaluation/persistence/EvaluationReadRepository.java`:
```java
package com.rcf.imas.modules.evaluation.persistence;

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
public class EvaluationReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public EvaluationReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: NUMERIC/BIGINT -> String; DATE -> "yyyy-MM-dd"; TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z; else passthrough.
     *  Map keys are the column label verbatim (handles sm.* dynamic column sets and camelCase SQL aliases unchanged). */
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

    public List<Map<String, Object>> allLists() {
        return jdbc.sql("""
                SELECT cl.list_id, cl.list_name, COUNT(cls.student_id) AS student_count
                FROM pp.custom_list cl
                LEFT JOIN pp.custom_list_students cls ON cl.list_id = cls.list_id
                GROUP BY cl.list_id, cl.list_name
                ORDER BY cl.list_id DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** cohortId: skip filter if null/blank/"null"/"undefined" (Node's addFilter-equivalent check for this one param). */
    public List<Map<String, Object>> allBatches(String cohortId) {
        boolean has = cohortId != null && !cohortId.isBlank() && !"null".equals(cohortId) && !"undefined".equals(cohortId);
        String sql = "SELECT b.batch_id, b.batch_name, c.cohort_name FROM pp.batch b JOIN pp.cohort c ON b.cohort_number = c.cohort_number"
                + (has ? " WHERE b.cohort_number = :cohortId::numeric" : "") + " ORDER BY b.batch_name";
        var q = jdbc.sql(sql);
        if (has) q = q.param("cohortId", cohortId);
        return q.query((rs, i) -> genericRow(rs)).list();
    }

    /** getAvailableFields parity: LIVE information_schema introspection, not a static field list (Firm Decision 2). */
    public List<Map<String, Object>> availableFields() {
        return jdbc.sql("""
                SELECT
                    column_name AS col_name,
                    CASE
                        WHEN column_name = 'batch_id' THEN 'Batch Name'
                        WHEN column_name = 'current_institute_dise_code' THEN 'Current School Name'
                        WHEN column_name = 'previous_institute_dise_code' THEN 'Previous School Name'
                        WHEN column_name = 'active_yn' THEN 'Active Status'
                        WHEN column_name = 'contact_no1' THEN 'Contact Number 1'
                        WHEN column_name = 'contact_no2' THEN 'Contact Number 2'
                        WHEN column_name = 'enr_id' THEN 'Enrollment Id'
                        ELSE INITCAP(REPLACE(column_name, '_', ' '))
                    END AS display_name
                FROM information_schema.columns
                WHERE table_schema = 'pp' AND table_name = 'student_master'
                  AND column_name NOT IN ('created_at','updated_at','created_by','updated_by','applicant_id','photo_link','student_id')
                UNION ALL
                SELECT
                    column_name AS col_name,
                    CASE
                        WHEN column_name = 'district' THEN 'District'
                        WHEN column_name = 'nmms_block' THEN 'Block'
                    END AS display_name
                FROM information_schema.columns
                WHERE table_schema = 'pp' AND table_name = 'applicant_primary_info'
                  AND column_name IN ('district','nmms_block')
                ORDER BY display_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> studentsByList(String listId) {
        return jdbc.sql("""
                SELECT
                    sm.*,
                    batch.batch_name,
                    inst_curr.institute_name AS current_institute_name,
                    inst_prev.institute_name AS previous_institute_name,
                    dist.juris_name AS district,
                    blk.juris_name AS block
                FROM pp.custom_list_students cls
                JOIN pp.student_master sm ON cls.student_id = sm.student_id
                LEFT JOIN pp.batch batch ON batch.batch_id = sm.batch_id
                LEFT JOIN pp.institute inst_curr ON sm.current_institute_dise_code = inst_curr.dise_code
                LEFT JOIN pp.institute inst_prev ON sm.previous_institute_dise_code = inst_prev.dise_code
                LEFT JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                LEFT JOIN pp.jurisdiction dist ON api.district = dist.juris_code
                LEFT JOIN pp.jurisdiction blk ON api.nmms_block = blk.juris_code
                WHERE cls.list_id = :listId::numeric
                ORDER BY sm.student_name
                """).param("listId", listId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> fieldsForList(String listId) {
        return jdbc.sql("""
                SELECT
                    fm.col_name, fm.field_id,
                    CASE
                        WHEN fm.col_name = 'batch_id' THEN 'Batch Name'
                        WHEN fm.col_name = 'current_institute_dise_code' THEN 'Current School Name'
                        WHEN fm.col_name = 'previous_institute_dise_code' THEN 'Previous School Name'
                        WHEN fm.col_name = 'active_yn' THEN 'Active Status'
                        WHEN fm.col_name = 'contact_no1' THEN 'Contact Number 1'
                        WHEN fm.col_name = 'contact_no2' THEN 'Contact Number 2'
                        WHEN fm.col_name = 'enr_id' THEN 'Enrollment Id'
                        ELSE INITCAP(REPLACE(fm.col_name, '_', ' '))
                    END as display_name
                FROM pp.custom_list_fields clf
                JOIN pp.field_master fm ON clf.field_id = fm.field_id
                WHERE clf.list_id = :listId::numeric
                """).param("listId", listId).query((rs, i) -> genericRow(rs)).list();
    }

    public String listName(String listId) {
        return jdbc.sql("SELECT list_name FROM pp.custom_list WHERE list_id = :listId::numeric")
                .param("listId", listId).query(String.class).optional().orElse(null);
    }

    /**
     * getStudentsByCohort parity. `nmms_year = 2025` is a hard-coded SQL literal (Firm Decision 5d), NOT parameterized.
     * `divisionId` is intentionally not a method parameter here at all (Firm Decision 5e) -- the controller reads it
     * off the request but never passes it through, matching Node's silent no-op.
     */
    public List<Map<String, Object>> studentsByCohort(String cohortId, String batchId, String stateId,
                                                       String districtId, String blockId) {
        StringBuilder sql = new StringBuilder("""
                SELECT sm.student_id, sm.student_name, b.batch_name, api.gender
                FROM pp.student_master sm
                JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
                WHERE sm.active_yn = 'ACTIVE'
                  AND api.nmms_year = 2025
                """);
        var query = jdbc.sql("");
        java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
        addFilter(filters, "cohortId", cohortId);
        addFilter(filters, "batchId", batchId);
        addFilter(filters, "stateId", stateId);
        addFilter(filters, "districtId", districtId);
        addFilter(filters, "blockId", blockId);
        if (filters.containsKey("cohortId")) sql.append(" AND b.cohort_number = :cohortId::numeric");
        if (filters.containsKey("batchId")) sql.append(" AND sm.batch_id = :batchId::numeric");
        if (filters.containsKey("stateId")) sql.append(" AND api.app_state = :stateId::numeric");
        if (filters.containsKey("districtId")) sql.append(" AND api.district = :districtId::numeric");
        if (filters.containsKey("blockId")) sql.append(" AND api.nmms_block = :blockId::numeric");
        sql.append(" ORDER BY sm.student_name");

        query = jdbc.sql(sql.toString());
        for (var e : filters.entrySet()) query = query.param(e.getKey(), e.getValue());
        return query.query((rs, i) -> genericRow(rs)).list();
    }

    /** Node's addFilter: skip null/blank/"all"/"null"/"undefined". */
    private static void addFilter(Map<String, String> out, String key, String val) {
        if (val != null && !val.isBlank() && !"all".equals(val) && !"null".equals(val) && !"undefined".equals(val)) {
            out.put(key, val);
        }
    }
}
```

`src/main/java/com/rcf/imas/modules/evaluation/web/CustomListController.java` (5 read handlers this task; save/delete/downloads added in Tasks 2/3):
```java
package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.EvaluationReadRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dual mount preserving Node's byte-identical evaluationRoutes.js / customListRoutes.js pair --
 * both /api/custom-list/* and /api/evaluation/* serve the exact same custom-list handlers.
 */
@RestController
@RequestMapping({"/api/custom-list", "/api/evaluation"})
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every custom-list/evaluation route open
class CustomListController {

    private final EvaluationReadRepository reads;

    CustomListController(EvaluationReadRepository reads) {
        this.reads = reads;
    }

    @GetMapping("/lists")
    public List<Map<String, Object>> lists() { return reads.allLists(); }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(required = false) String cohortId) {
        return reads.allBatches(cohortId);
    }

    @GetMapping("/available-fields")
    public List<Map<String, Object>> availableFields() { return reads.availableFields(); }

    @GetMapping("/students-by-list/{listId}")
    public Map<String, Object> studentsByList(@PathVariable String listId) {
        return Map.of("students", reads.studentsByList(listId), "fields", reads.fieldsForList(listId));
    }

    @GetMapping("/students-by-cohort/{cohortId}")
    public List<Map<String, Object>> studentsByCohort(@PathVariable String cohortId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String stateId,
            @RequestParam(required = false) String divisionId,   // accepted, intentionally unused (Firm Decision 5e)
            @RequestParam(required = false) String districtId,
            @RequestParam(required = false) String blockId) {
        return reads.studentsByCohort(cohortId, batchId, stateId, districtId, blockId);
    }
}
```

> **Note on `Map.of` for `/students-by-list/{listId}`.** `Map.of("students", ..., "fields", ...)` is fine here since both values are non-null `List`s (possibly empty) — never `null`, so `Map.of`'s null-rejection is not a concern.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListReadsIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/evaluation imas-backend/src/test/java/com/rcf/imas/modules/evaluation
git commit -m "feat(evaluation): module skeleton + custom-list reads (dual /api/custom-list + /api/evaluation mount)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `/save-list-full` (transactional create-or-replace) + `DELETE /list/{id}`

Port `saveListFull`'s full create-or-replace flow into a dedicated `EvaluationWriteRepository` (self-invocation does not honor `@Transactional` — the whole flow must live in one `@Transactional` method on a separate `@Repository` bean, mirroring `ShortlistWriteRepository`). Port `deleteList` (single statement, FK cascade does the rest).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/persistence/EvaluationWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/web/CustomListController.java` (add `saveListFull`, `deleteList`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/evaluation/CustomListWriteIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/evaluation/CustomListWriteIT.java`:
```java
package com.rcf.imas.modules.evaluation;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CustomListWriteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('wlseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='wlseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "wlseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (710101,2025,24071000001,'WriteKid1','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (710102,2025,24071000002,'WriteKid2','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, active_yn) VALUES (710201,710101,'WriteKid1','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, active_yn) VALUES (710202,710102,'WriteKid2','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list_fields WHERE field_id IN (SELECT field_id FROM pp.field_master WHERE col_name IN ('gender','medium'))").update();
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_name IN ('WL Save Test','WL Save Test Updated')").update();
        jdbc.sql("DELETE FROM pp.field_master WHERE col_name IN ('gender','medium')").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (710201,710202)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (710101,710102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'wlseed'").update();
    }

    @Test
    void saveListFullCreatesThenReplacesEntirely() throws Exception {
        String createBody = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        var createResult = mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(createBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andReturn();
        String listId = com.jayway.jsonpath.JsonPath.read(createResult.getResponse().getContentAsString(), "$.list_id").toString();

        // full replace: drop the gender field, add medium field, replace student 1 with student 2
        String updateBody = """
            {"list_id":%s,"list_name":"WL Save Test Updated","student_ids":[710202],"selectedFields":[{"col_name":"medium"}]}
            """.formatted(listId);
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(updateBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.list_id").value(listId));

        Integer studentCount = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(studentCount).isEqualTo(1); // fully replaced, not merged
        Integer stillHasOldStudent = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric AND student_id = 710201")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillHasOldStudent).isEqualTo(0);

        String name = jdbc.sql("SELECT list_name FROM pp.custom_list WHERE list_id = :id::numeric").param("id", listId)
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(name).isEqualTo("WL Save Test Updated");
    }

    @Test
    void saveListFullReusesFieldMasterRowAcrossLists() throws Exception {
        String body1 = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body1)).andExpect(status().isOk());

        Integer fieldMasterRows = jdbc.sql("SELECT COUNT(*)::int FROM pp.field_master WHERE col_name = 'gender'")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(fieldMasterRows).isEqualTo(1); // exactly one, reused not duplicated
    }

    @Test
    void deleteListAlwaysReturnsSuccessTrueEvenForMissingId() throws Exception {
        mvc.perform(delete("/api/custom-list/list/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteListCascadesToFieldsAndStudentsJunctionRows() throws Exception {
        String body = """
            {"list_name":"WL Save Test","student_ids":[710201],"selectedFields":[{"col_name":"gender"}]}
            """;
        var result = mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andReturn();
        String listId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.list_id").toString();

        mvc.perform(delete("/api/custom-list/list/" + listId).header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        Integer remaining = jdbc.sql("SELECT COUNT(*)::int FROM pp.custom_list_students WHERE list_id = :id::numeric")
                .param("id", listId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(0);
    }

    @Test
    void writeEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/custom-list/save-list-full").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/custom-list/list/1").header("Authorization", "Bearer " + studentTok))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListWriteIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/evaluation/persistence/EvaluationWriteRepository.java`:
```java
package com.rcf.imas.modules.evaluation.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class EvaluationWriteRepository {

    private final JdbcClient jdbc;

    public EvaluationWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record SaveResult(String listId) {}

    /**
     * saveListFull parity (customListModel.js:3-65) -- full create-or-replace flow:
     *  1. normalize listId: null/blank/"undefined" -> create path.
     *  2. create path: INSERT ... RETURNING list_id. Update path: UPDATE list_name, then UNCONDITIONALLY
     *     delete all existing custom_list_fields/custom_list_students for this list (full replace, not merge).
     *  3. per selected field: look up field_master by col_name (SHARED across all lists, keyed only by col_name --
     *     not per-list), insert if missing (fixed tab_name='pp.student_master'), then link via custom_list_fields.
     *  4. per student id: skip null/"undefined"; insert custom_list_students. NO de-dup check in application code --
     *     a genuine duplicate student_id within one call throws a unique-violation on the (list_id,student_id) PK,
     *     which (since this whole method is one @Transactional boundary) rolls back the entire save, matching Node's
     *     explicit BEGIN/COMMIT/ROLLBACK around the same statements.
     */
    @Transactional
    public SaveResult saveListFull(String listId, String listName, List<Object> studentIds,
                                    List<Map<String, Object>> selectedFields) {
        String finalId = (listId == null || listId.isBlank() || "undefined".equals(listId)) ? null : listId;

        if (finalId == null) {
            finalId = jdbc.sql("INSERT INTO pp.custom_list (list_name) VALUES (:name) RETURNING list_id")
                    .param("name", listName)
                    .query((rs, i) -> rs.getBigDecimal("list_id").toBigInteger().toString()).single();
        } else {
            jdbc.sql("UPDATE pp.custom_list SET list_name = :name WHERE list_id = :id::numeric")
                    .param("name", listName).param("id", finalId).update();
            jdbc.sql("DELETE FROM pp.custom_list_fields WHERE list_id = :id::numeric").param("id", finalId).update();
            jdbc.sql("DELETE FROM pp.custom_list_students WHERE list_id = :id::numeric").param("id", finalId).update();
        }

        if (selectedFields != null) {
            for (Map<String, Object> f : selectedFields) {
                String colName = String.valueOf(f.get("col_name"));
                String fieldId = jdbc.sql("SELECT field_id FROM pp.field_master WHERE col_name = :col")
                        .param("col", colName)
                        .query((rs, i) -> rs.getBigDecimal("field_id").toBigInteger().toString()).optional().orElse(null);
                if (fieldId == null) {
                    fieldId = jdbc.sql("INSERT INTO pp.field_master (tab_name, col_name) VALUES ('pp.student_master', :col) RETURNING field_id")
                            .param("col", colName)
                            .query((rs, i) -> rs.getBigDecimal("field_id").toBigInteger().toString()).single();
                }
                jdbc.sql("INSERT INTO pp.custom_list_fields (list_id, field_id) VALUES (:list::numeric, :field::numeric)")
                        .param("list", finalId).param("field", fieldId).update();
            }
        }

        if (studentIds != null) {
            for (Object sIdObj : studentIds) {
                String sId = sIdObj == null ? null : String.valueOf(sIdObj);
                if (sId != null && !sId.isBlank() && !"undefined".equals(sId)) {
                    jdbc.sql("INSERT INTO pp.custom_list_students (list_id, student_id) VALUES (:list::numeric, :student::numeric)")
                            .param("list", finalId).param("student", sId).update();
                }
            }
        }

        return new SaveResult(finalId);
    }

    /** deleteList parity: single autocommit DELETE, FK ON DELETE CASCADE handles custom_list_fields/custom_list_students.
     *  Node never checks affected-row count -- {success:true} is returned even for a non-existent id -- so this method
     *  intentionally has no return value / no existence check either. */
    public void deleteList(String id) {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = :id::numeric").param("id", id).update();
    }
}
```

Add to `CustomListController` (constructor now also takes `EvaluationWriteRepository writes`; add imports `com.rcf.imas.modules.evaluation.persistence.EvaluationWriteRepository`, `java.util.LinkedHashMap`):
```java
    private final EvaluationWriteRepository writes;

    CustomListController(EvaluationReadRepository reads, EvaluationWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @PostMapping("/save-list-full")
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveListFull(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object listId = b.get("list_id");
        String listName = b.get("list_name") == null ? null : String.valueOf(b.get("list_name"));
        List<Object> studentIds = b.get("student_ids") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Map<String, Object>> selectedFields = b.get("selectedFields") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        var result = writes.saveListFull(listId == null ? null : String.valueOf(listId), listName, studentIds, selectedFields);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("list_id", result.listId());
        return out;
    }

    @DeleteMapping("/list/{id}")
    public Map<String, Object> deleteList(@PathVariable String id) {
        writes.deleteList(id);
        return Map.of("success", true);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListWriteIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/evaluation imas-backend/src/test/java/com/rcf/imas/modules/evaluation/CustomListWriteIT.java
git commit -m "feat(evaluation): save-list-full (transactional create-or-replace) + delete-list

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: `/download-xlsx/{listId}` (POI) + `/download-pdf/{listId}` (OpenPDF)

Add the OpenPDF dependency. Port both custom-list exports with dynamic columns and the shared special-case cell mapping.

**Files:**
- Modify: `imas-backend/pom.xml` (add `com.github.librepdf:openpdf:2.0.3`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/service/CustomListXlsxSupport.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/service/CustomListPdfSupport.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/web/CustomListController.java` (add `download-xlsx`, `download-pdf`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/evaluation/CustomListExportIT.java`

- [ ] **Step 1: Add the dependency**

`imas-backend/pom.xml`, inside `<dependencies>`:
```xml
<dependency>
    <groupId>com.github.librepdf</groupId>
    <artifactId>openpdf</artifactId>
    <version>2.0.3</version>
</dependency>
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/evaluation/CustomListExportIT.java`:
```java
package com.rcf.imas.modules.evaluation;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CustomListExportIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('exseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='exseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "exseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (7201,'Cohort EX') ON CONFLICT (cohort_number) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number, medium) VALUES (7201,'Batch EX',7201,'KANNADA') ON CONFLICT (batch_id) DO NOTHING").update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, created_by, updated_by)
            VALUES (720101,2025,24072000001,'ExportKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, batch_id, active_yn) VALUES (720201,720101,'ExportKid',7201,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.custom_list(list_id, list_name) VALUES (7201,'Export List') ON CONFLICT (list_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.custom_list_id_seq', (SELECT MAX(list_id)::bigint FROM pp.custom_list))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_students(list_id, student_id) VALUES (7201, 720201)").update();
        // Node adds the ID/Name columns only if student_id/student_name are among the list's fields
        // (customListController.js:142-150). Seed all three so hasId/hasName are true and the batch_id column is exercised.
        jdbc.sql("INSERT INTO pp.field_master(field_id, tab_name, col_name) VALUES (7201,'pp.student_master','batch_id'),(7202,'pp.student_master','student_id'),(7203,'pp.student_master','student_name') ON CONFLICT (field_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.field_id_seq', (SELECT MAX(field_id)::bigint FROM pp.field_master))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.custom_list_fields(list_id, field_id) VALUES (7201,7201),(7201,7202),(7201,7203)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.custom_list WHERE list_id = 7201").update();
        jdbc.sql("DELETE FROM pp.field_master WHERE field_id IN (7201,7202,7203)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 720201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 720101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 7201").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 7201").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'exseed'").update();
    }

    @Test
    void downloadXlsxHasNameColumnAndBatchNameMapping() throws Exception {
        byte[] bytes = mvc.perform(get("/api/custom-list/download-xlsx/7201").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Disposition", "attachment; filename=\"Export List.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Student List");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Student ID");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Student Name");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Batch Name"); // display_name for batch_id
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("ExportKid");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Batch EX"); // batch_id -> batch_name mapping
        }
    }

    @Test
    void downloadPdfReturnsApplicationPdfWithQuotedFilename() throws Exception {
        mvc.perform(get("/api/custom-list/download-pdf/7201").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Type", "application/pdf"))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
               .string("Content-Disposition", "attachment; filename=\"Export List.pdf\""));
    }

    @Test
    void exportEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/custom-list/download-xlsx/7201").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/custom-list/download-pdf/7201").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListExportIT` — Expected: FAIL.

- [ ] **Step 4: Implement**

`src/main/java/com/rcf/imas/modules/evaluation/service/CustomListXlsxSupport.java`:
```java
package com.rcf.imas.modules.evaluation.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** POI port of customListController.js downloadListXLS: dynamic ID/Name-then-fields columns, shared special-case mapping. */
@Component
public class CustomListXlsxSupport {

    public byte[] build(List<Map<String, Object>> students, List<Map<String, Object>> fields) {
        boolean hasId = fields.stream().anyMatch(f -> "student_id".equals(f.get("col_name")));
        boolean hasName = fields.stream().anyMatch(f -> "student_name".equals(f.get("col_name")));

        List<String> headers = new ArrayList<>();
        List<String> colNames = new ArrayList<>();
        if (hasId) { headers.add("Student ID"); colNames.add("student_id"); }
        if (hasName) { headers.add("Student Name"); colNames.add("student_name"); }
        for (Map<String, Object> f : fields) {
            String col = String.valueOf(f.get("col_name"));
            if ("student_id".equals(col) || "student_name".equals(col)) continue;
            headers.add(String.valueOf(f.get("display_name")));
            colNames.add(col);
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Student List");

            CellStyle boldStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            boldStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(boldStyle);
            }

            for (int r = 0; r < students.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> s = students.get(r);
                for (int c = 0; c < colNames.size(); c++) {
                    row.createCell(c).setCellValue(CustomListValueMapper.cellText(colNames.get(c), s));
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

`src/main/java/com/rcf/imas/modules/evaluation/service/CustomListPdfSupport.java`:
```java
package com.rcf.imas.modules.evaluation.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of customListController.js downloadListPDF (Firm Decision 4). Text-only header (no logos --
 * simplification, human-facing download with no automated consumer). Standard built-in font (Times-Roman
 * equivalent) -- Node's own Times-Roman can't render Kannada either, so no special font family is needed.
 */
@Component
public class CustomListPdfSupport {

    private static final Font TITLE_FONT = new Font(Font.TIMES_ROMAN, 18, Font.BOLD);
    private static final Font SUBTITLE_FONT = new Font(Font.TIMES_ROMAN, 12, Font.NORMAL);
    private static final Font LIST_NAME_FONT = new Font(Font.TIMES_ROMAN, 22, Font.BOLD, java.awt.Color.BLUE);
    private static final Font HEADER_CELL_FONT = new Font(Font.TIMES_ROMAN, 10, Font.BOLD);
    private static final Font BODY_CELL_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);

    public byte[] build(String listName, List<Map<String, Object>> students, List<Map<String, Object>> fields) {
        boolean hasId = fields.stream().anyMatch(f -> "student_id".equals(f.get("col_name")));
        boolean hasName = fields.stream().anyMatch(f -> "student_name".equals(f.get("col_name")));

        List<String> headers = new ArrayList<>();
        List<String> colNames = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        if (hasId) { headers.add("ID"); colNames.add("student_id"); widths.add(50f); }
        if (hasName) { headers.add("Name"); colNames.add("student_name"); widths.add(150f); }
        for (Map<String, Object> f : fields) {
            String col = String.valueOf(f.get("col_name"));
            if ("student_id".equals(col) || "student_name".equals(col)) continue;
            headers.add(String.valueOf(f.get("display_name")));
            colNames.add(col);
            widths.add(100f);
        }

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("RAJALAKSHMI CHILDREN FOUNDATION", TITLE_FONT);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            Paragraph subtitle = new Paragraph("PRATIBHA POSHAK - 2025", SUBTITLE_FONT); // hard-coded literal, matches Node
            subtitle.setAlignment(Element.ALIGN_CENTER);
            doc.add(subtitle);

            Paragraph listNamePara = new Paragraph(listName.toUpperCase(), LIST_NAME_FONT);
            listNamePara.setAlignment(Element.ALIGN_CENTER);
            listNamePara.setSpacingBefore(12f);
            listNamePara.setSpacingAfter(12f);
            doc.add(listNamePara);

            float[] widthArr = new float[widths.size()];
            for (int i = 0; i < widthArr.length; i++) widthArr[i] = widths.get(i);
            PdfPTable table = new PdfPTable(widthArr);
            table.setWidthPercentage(100);

            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, HEADER_CELL_FONT));
                cell.setGrayFill(0.9f);
                table.addCell(cell);
            }
            for (Map<String, Object> s : students) {
                for (String col : colNames) {
                    table.addCell(new PdfPCell(new Phrase(CustomListValueMapper.cellText(col, s), BODY_CELL_FONT)));
                }
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }
}
```

Add a small shared mapper (used by both PDF and XLSX support classes) — `src/main/java/com/rcf/imas/modules/evaluation/service/CustomListValueMapper.java`:
```java
package com.rcf.imas.modules.evaluation.service;

import java.util.Map;

/** Special-case cell mapping shared by download-xlsx and download-pdf (customListController.js duplicates this logic
 *  identically in both handlers). Missing/null -> literal '-'. */
final class CustomListValueMapper {

    private CustomListValueMapper() {}

    static String cellText(String colName, Map<String, Object> s) {
        Object val;
        switch (colName) {
            case "batch_id" -> val = s.get("batch_name");
            case "current_institute_dise_code" -> val = s.get("current_institute_name");
            case "previous_institute_dise_code" -> val = s.get("previous_institute_name");
            case "district", "district_id" -> val = s.get("district");
            case "nmms_block", "block_id" -> val = s.get("block");
            default -> val = s.get(colName);
        }
        return val == null ? "-" : String.valueOf(val);
    }
}
```

Add handlers to `CustomListController` (constructor now also takes `CustomListXlsxSupport xlsx, CustomListPdfSupport pdf`; add imports `org.springframework.http.ResponseEntity`, `org.springframework.http.MediaType`, `com.rcf.imas.platform.error.ApiException`):
```java
    private final CustomListXlsxSupport xlsx;
    private final CustomListPdfSupport pdf;

    CustomListController(EvaluationReadRepository reads, EvaluationWriteRepository writes,
                          CustomListXlsxSupport xlsx, CustomListPdfSupport pdf) {
        this.reads = reads;
        this.writes = writes;
        this.xlsx = xlsx;
        this.pdf = pdf;
    }

    @GetMapping("/download-xlsx/{listId}")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable String listId) {
        List<Map<String, Object>> students = reads.studentsByList(listId);
        List<Map<String, Object>> fields = reads.fieldsForList(listId);
        String listName = orDefault(reads.listName(listId), "Custom_List");
        byte[] bytes = xlsx.build(students, fields);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + listName + ".xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @GetMapping("/download-pdf/{listId}")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String listId) {
        List<Map<String, Object>> students = reads.studentsByList(listId);
        List<Map<String, Object>> fields = reads.fieldsForList(listId);
        String listName = orDefault(reads.listName(listId), "Custom_List");
        byte[] bytes = pdf.build(listName, students, fields);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + listName + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }
```

> **500 mapping for these two endpoints.** Per Firm Decision 3, unexpected failures rely on the module-wide `GlobalExceptionHandler` generic fallback (`{error:"Internal Server Error"}`) — same as every other custom-list endpoint, unlike the Results module's download endpoints (which needed distinct `{message:...}` bodies). No endpoint-local try/catch needed here.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CustomListExportIT` — Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add imas-backend/pom.xml imas-backend/src/main/java/com/rcf/imas/modules/evaluation imas-backend/src/test/java/com/rcf/imas/modules/evaluation/CustomListExportIT.java
git commit -m "feat(evaluation): download-xlsx (POI) + download-pdf (OpenPDF, text-only header, no logos)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: `EvaluationController` — `/exam_names` + `/download_excel`

Port the `ApiResponse`-enveloped `exam_names` and the fixed-34-column `download_excel` (with join-chain bug 5a preserved).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/persistence/EvaluationReadRepository.java` (add `examNames`, `studentsForExam`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/web/EvaluationController.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/service/StudentExcelSupport.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/evaluation/EvaluationIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/evaluation/EvaluationIT.java`:
```java
package com.rcf.imas.modules.evaluation;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EvaluationIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('evseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='evseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "evseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_year, exam_date, exam_start_time, exam_end_time)
            VALUES (750001, 'NMMS Eval Exam', '2026-27', '2026-06-15', '09:00:00', '11:00:00') ON CONFLICT (exam_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        // applicant WITHOUT an applicant_secondary_info row, but WITH exam_results/attendance rows --
        // exercises the join-chain bug: er/aea join on asi.applicant_id, so these must NOT appear in getStudents' output.
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, mother_name, gender, created_by, updated_by)
            VALUES (750101,2026,24075000001,'EvalKid','f','m','M',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (750101, 750001)").update();
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score, pp_exam_cleared, interview_required_yn) VALUES (750101, 88, 'Y', 'N')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam_attendance(applicant_id, pp_exam_appeared_yn) VALUES (750101, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_exam_attendance WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 750101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 750001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'evseed'").update();
    }

    @Test
    void examNamesReturnsApiResponseEnvelopeFilteredByYearPrefix() throws Exception {
        mvc.perform(get("/api/evaluation/exam_names").param("year", "2026-27").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.statusCode").value(200))
           .andExpect(jsonPath("$.message").value("ok"))
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data[0].exam_name").value("NMMS Eval Exam"));
    }

    @Test
    void examNamesMissingYearIs400() throws Exception {
        mvc.perform(get("/api/evaluation/exam_names").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Academic year is required"));
    }

    @Test
    void downloadExcelHas34FixedColumnsAndPreservesJoinChainBug() throws Exception {
        var result = mvc.perform(post("/api/evaluation/download_excel").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"exam_name\":\"NMMS Eval Exam\"}"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
           .andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
            .isEqualTo("attachment; filename=students_NMMS_Eval_Exam.xlsx"); // NOT quoted (Firm Decision/quirk 13)

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheet("Students");
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(34);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Applicant ID");
            assertThat(sheet.getRow(0).getCell(31).getStringCellValue()).isEqualTo("Exam Score");
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillForegroundColorColor().getARGBHex())
                .isEqualToIgnoringCase("FFFFFFCC");

            Row data = sheet.getRow(1);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("EvalKid");
            // BUG PRESERVED: exam_results/attendance joined on asi.applicant_id (secondary info), which has no row here
            // -> pp_exam_score/pp_exam_cleared/interview_required/pp_exam_appeared must all be blank, not 88/Y/N/Y.
            assertThat(data.getCell(31).getStringCellValue()).isEqualTo(""); // Exam Score blank
            assertThat(data.getCell(33).getStringCellValue()).isEqualTo(""); // Interview Required blank
        }
    }

    @Test
    void evaluationEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/evaluation/exam_names").param("year", "2026").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(post("/api/evaluation/download_excel").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EvaluationIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `EvaluationReadRepository`:
```java
    public List<Map<String, Object>> examNames(String yearPrefixLike) {
        return jdbc.sql("SELECT exam_name FROM pp.examination WHERE exam_year LIKE :prefix ORDER BY exam_id ASC")
                .param("prefix", yearPrefixLike).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getStudents parity (evaluationModels.js). BUG PRESERVED VERBATIM: er/aea are LEFT-JOINed on asi.applicant_id
     * (applicant_secondary_info), NOT api.applicant_id (applicant_primary_info) -- an applicant with exam results
     * but no secondary-info row shows NULL exam columns even though matching rows exist. Do NOT fix.
     */
    public List<Map<String, Object>> studentsForExam(String examName) {
        return jdbc.sql("""
                SELECT
                    api.applicant_id, api.student_name, api.father_name, api.mother_name,
                    asi.village, api.gender, api.aadhaar, api.dob, api.medium, api.home_address,
                    api.family_income_total,
                    asi.father_occupation, asi.mother_occupation, asi.father_education, asi.mother_education,
                    asi.household_size, asi.own_house, asi.smart_phone_home, asi.internet_facility_home,
                    asi.career_goals, asi.subjects_of_interest, asi.transportation_mode, asi.distance_to_school,
                    asi.num_two_wheelers, asi.num_four_wheelers, asi.irrigation_land,
                    asi.neighbor_name, asi.neighbor_phone, asi.favorite_teacher_name, asi.favorite_teacher_phone,
                    aea.pp_exam_appeared_yn,
                    er.pp_exam_score, er.pp_exam_cleared, er.interview_required_yn
                FROM pp.examination ex
                LEFT JOIN pp.applicant_exam ae ON ae.exam_id = ex.exam_id
                LEFT JOIN pp.applicant_primary_info api ON api.applicant_id = ae.applicant_id
                LEFT JOIN pp.applicant_secondary_info asi ON api.applicant_id = asi.applicant_id
                LEFT JOIN pp.exam_results er ON asi.applicant_id = er.applicant_id
                LEFT JOIN pp.applicant_exam_attendance aea ON aea.applicant_id = asi.applicant_id
                WHERE ex.exam_name = :examName
                """).param("examName", examName).query((rs, i) -> genericRow(rs)).list();
    }
```

`src/main/java/com/rcf/imas/modules/evaluation/service/StudentExcelSupport.java`:
```java
package com.rcf.imas.modules.evaluation.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** POI port of evaluationController.js downloadStudentExcel: fixed 34-column layout, 4 fill-color groups. */
@Component
public class StudentExcelSupport {

    private record Col(String header, String key, String fillArgb) {}

    private static final List<Col> COLUMNS = List.of(
        new Col("Applicant ID", "applicant_id", "FFFFCC"),
        new Col("Student Name", "student_name", "FFFFCC"),
        new Col("Father Name", "father_name", "FFFFCC"),
        new Col("Mother Name", "mother_name", "FFFFCC"),
        new Col("Village", "village", "FFFFCC"),
        new Col("Gender(M,F)", "gender", "FFFFCC"),
        new Col("Aadhaar", "aadhaar", "FFFFCC"),
        new Col("Date of Birth", "dob", "FFFFCC"),
        new Col("Medium", "medium", "FFFFCC"),
        new Col("Home Address", "home_address", "FFFFCC"),
        new Col("Family Income", "family_income_total", "FFFFCC"),
        new Col("Father Occupation", "father_occupation", "CCFFCC"),
        new Col("Mother Occupation", "mother_occupation", "CCFFCC"),
        new Col("Father Education", "father_education", "CCFFCC"),
        new Col("Mother Education", "mother_education", "CCFFCC"),
        new Col("Household Size", "household_size", "CCFFCC"),
        new Col("Own House(Y,N)", "own_house", "CCFFCC"),
        new Col("Smart Phone at Home(Y,N)", "smart_phone_home", "CCFFCC"),
        new Col("Internet Facility at Home(Y,N)", "internet_facility_home", "CCFFCC"),
        new Col("Career Goals", "career_goals", "CCFFCC"),
        new Col("Subjects of Interest", "subjects_of_interest", "CCFFCC"),
        new Col("Transportation Mode", "transportation_mode", "CCFFCC"),
        new Col("Distance to School", "distance_to_school", "CCFFCC"),
        new Col("Number of Two Wheelers", "num_two_wheelers", "CCFFCC"),
        new Col("Number of Four Wheelers", "num_four_wheelers", "CCFFCC"),
        new Col("Irrigation Land", "irrigation_land", "CCFFCC"),
        new Col("Neighbor Name", "neighbor_name", "CCFFCC"),
        new Col("Neighbor Phone", "neighbor_phone", "CCFFCC"),
        new Col("Favorite Teacher Name", "favorite_teacher_name", "CCFFCC"),
        new Col("Favorite Teacher Phone", "favorite_teacher_phone", "CCFFCC"),
        new Col("Exam Appeared Y/N", "pp_exam_appeared_yn", "FFCCCC"),
        new Col("Exam Score", "pp_exam_score", "CCFFFF"),
        new Col("Exam cleared Y/N", "pp_exam_cleared", "CCFFFF"),
        new Col("Interview Required", "interview_required_yn", "CCFFFF")
    );

    private static final int DOB_COL_1BASED = 8;
    private static final int FAMILY_INCOME_COL_1BASED = 11;
    private static final java.util.Set<Integer> NUMFMT_0_00 = java.util.Set.of(32, 23, 24, 25, 26);
    private static final java.util.Set<Integer> YN_CENTERED = java.util.Set.of(6, 17, 18, 19, 31, 33, 34);

    public byte[] build(List<Map<String, Object>> students) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Students");
            DataFormat fmt = wb.createDataFormat();

            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) {
                Col col = COLUMNS.get(c);
                CellStyle style = wb.createCellStyle();
                Font f = wb.createFont();
                f.setFontName("Calibri");
                f.setFontHeightInPoints((short) 11);
                f.setBold(true);
                style.setFont(f);
                style.setFillForegroundColor(argb(wb, col.fillArgb()));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                style.setWrapText(true);
                Cell cell = header.createCell(c);
                cell.setCellValue(col.header());
                cell.setCellStyle(style);
            }

            CellStyle dobStyle = dataStyle(wb, fmt, "dd-mm-yyyy", HorizontalAlignment.CENTER);
            CellStyle incomeStyle = dataStyle(wb, fmt, "₹#,##0.00", HorizontalAlignment.RIGHT);
            CellStyle numStyle = dataStyle(wb, fmt, "0.00", HorizontalAlignment.RIGHT);
            CellStyle centeredStyle = dataStyle(wb, fmt, null, HorizontalAlignment.CENTER);
            CellStyle plainStyle = dataStyle(wb, fmt, null, null);

            for (int r = 0; r < students.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> s = students.get(r);
                for (int c = 0; c < COLUMNS.size(); c++) {
                    int oneBased = c + 1;
                    Object v = s.get(COLUMNS.get(c).key());
                    Cell cell = row.createCell(c);
                    cell.setCellValue(v == null ? "" : String.valueOf(v));
                    if (oneBased == DOB_COL_1BASED) cell.setCellStyle(dobStyle);
                    else if (oneBased == FAMILY_INCOME_COL_1BASED) cell.setCellStyle(incomeStyle);
                    else if (NUMFMT_0_00.contains(oneBased)) cell.setCellStyle(numStyle);
                    else if (YN_CENTERED.contains(oneBased)) cell.setCellStyle(centeredStyle);
                    else cell.setCellStyle(plainStyle);
                }
            }

            // Best-effort cosmetics (Firm Decision 7): Gender + Y/N dropdown validations, DOB note.
            addListValidation(sheet, 5, "M,F", students.size());          // column 6 (0-based idx 5)
            for (int oneBased : List.of(17, 18, 19, 31, 33, 34)) {
                addListValidation(sheet, oneBased - 1, "Y,N", students.size());
            }
            addDobNote(wb, sheet, DOB_COL_1BASED - 1, students.size());

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CellStyle dataStyle(Workbook wb, DataFormat fmt, String numFmt, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName("Calibri");
        f.setFontHeightInPoints((short) 10);
        style.setFont(f);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (numFmt != null) style.setDataFormat(fmt.getFormat(numFmt));
        if (align != null) style.setAlignment(align);
        return style;
    }

    private static short argb(Workbook wb, String rgbHex) {
        // POI HSSF/XSSF indexed palette isn't used here; XSSFWorkbook path uses setFillForegroundColor(XSSFColor).
        // Kept as a placeholder signature for HSSF parity is unnecessary since this module always uses XSSFWorkbook;
        // real implementation below overrides via XSSFColor directly on the style (see note).
        return 0;
    }

    private static void addListValidation(Sheet sheet, int col0, String csv, int rowCount) {
        if (rowCount == 0) return;
        DataValidationHelper helper = sheet.getDataValidationHelper();
        CellRangeAddress range = new CellRangeAddress(1, rowCount, col0, col0);
        DataValidationConstraint constraint = helper.createExplicitListConstraint(csv.split(","));
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setEmptyCellAllowed(true);
        sheet.addValidationData(validation);
    }

    private static void addDobNote(Workbook wb, Sheet sheet, int col0, int rowCount) {
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper factory = wb.getCreationHelper();
        for (int r = 1; r <= rowCount; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(col0);
            anchor.setRow1(r);
            anchor.setCol2(col0 + 2);
            anchor.setRow2(r + 3);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString("Double click for calendar or enter date as DD-MM-YYYY"));
            row.getCell(col0).setCellComment(comment);
        }
    }
}
```

> **Fill-color note.** The `argb(...)` helper above is a placeholder to keep the listing short — implement it (or inline it directly in the style-building loop) using the same pattern as `ResultsXlsxSupport.javaAwtColorFromArgb` + `new XSSFColor(color, null)` on the header `CellStyle`, e.g.:
> ```java
> style.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(
>     new java.awt.Color(Integer.parseInt(col.fillArgb().substring(0,2),16),
>                         Integer.parseInt(col.fillArgb().substring(2,4),16),
>                         Integer.parseInt(col.fillArgb().substring(4,6),16)), null));
> ```
> replacing the `argb(...)` call and its unused return value entirely. The test asserts `getARGBHex()` equals `"FFFFFFCC"` (alpha `FF` + `FFFFCC`), matching the `ResultsXlsxSupport` precedent exactly.

`src/main/java/com/rcf/imas/modules/evaluation/web/EvaluationController.java`:
```java
package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.EvaluationReadRepository;
import com.rcf.imas.modules.evaluation.service.StudentExcelSupport;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/evaluation")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left these routes open
class EvaluationController {

    private final EvaluationReadRepository reads;
    private final StudentExcelSupport excel;

    EvaluationController(EvaluationReadRepository reads, StudentExcelSupport excel) {
        this.reads = reads;
        this.excel = excel;
    }

    /** getExamNames parity: year.split("-")[0].trim() + "%" LIKE prefix, ORDER BY exam_id ASC.
     *  Response is the ApiResponse envelope built explicitly (statusCode/data/message/success, in that key order). */
    @GetMapping("/exam_names")
    public Map<String, Object> examNames(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) {
            throw ApiException.message(400, "Academic year is required");
        }
        String yearPrefix = year.split("-")[0].trim();
        List<Map<String, Object>> examNames = reads.examNames(yearPrefix + "%");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("statusCode", 200);
        envelope.put("data", examNames);
        envelope.put("message", "ok");
        envelope.put("success", true);
        return envelope;
    }

    /** downloadStudentExcel parity: fixed 34-column export, bug 5a preserved via reads.studentsForExam. Filename
     *  is NOT quoted (Firm Decision/quirk 13), unlike the custom-list exports. */
    @PostMapping("/download_excel")
    public ResponseEntity<byte[]> downloadExcel(@RequestBody(required = false) Map<String, Object> body) {
        String examName = body == null || body.get("exam_name") == null ? null : String.valueOf(body.get("exam_name"));
        List<Map<String, Object>> students = reads.studentsForExam(examName);
        byte[] bytes = excel.build(students);
        String filename = "students_" + (examName == null ? "" : examName.replaceAll("(?i)[^a-z0-9]", "_")) + ".xlsx";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=" + filename)
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EvaluationIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/evaluation imas-backend/src/test/java/com/rcf/imas/modules/evaluation/EvaluationIT.java
git commit -m "feat(evaluation): exam_names (ApiResponse envelope) + download_excel (34-col fixed layout, join-chain bug preserved)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: `EvaluationDashboardController` — `overall/{year}`, `jurisdictions/{year}`, `overall-progress/{year}`, then FULL suite

Port the 3 dashboard aggregations into a dedicated `DashboardReadRepository` that returns **real typed ints** where Node's `parseInt` makes them real JSON numbers, and raw genericRow passthrough (Strings) everywhere else — see the precise per-field split below, taken from the actual `evaluationDashboardModel.js` source (not just the ground-truth summary, which slightly over-generalizes this point).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/persistence/DashboardReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/evaluation/web/EvaluationDashboardController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/evaluation/EvaluationDashboardIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/evaluation/EvaluationDashboardIT.java`:
```java
package com.rcf.imas.modules.evaluation;

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
class EvaluationDashboardIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('dbseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='dbseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "dbseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (760001,'DASH BLOCK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (760101,2027,24076000001,760001,'DashKid1','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info (applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (760102,2027,24076000002,760001,'DashKid2','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (760101,'Y')").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlisted_yn) VALUES (760102,'N')").update(); // NOT shortlisted_yn='Y'

        jdbc.sql("""
            INSERT INTO pp.applicant_secondary_info(applicant_id, village) VALUES (760101,'V1')
            """).update(); // only applicant 1 has been "Evaluated"

        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, interview_required_yn) VALUES (760101,'Y')").update();
        jdbc.sql("INSERT INTO pp.student_interview(interview_id, applicant_id, status) VALUES (760201,760101,'COMPLETED')").update(); // upper-case, never matches 'Completed'
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (760101,760102)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 760001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'dbseed'").update();
    }

    @Test
    void overallCountsReturnsExactLabelsAsRealInts() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$['Total Students']").value(2))
           .andExpect(jsonPath("$['Shortlisted']").value(1))          // shortlisted_yn='Y' filter -> only applicant 1
           .andExpect(jsonPath("$['Evaluated']").value(1))
           .andExpect(jsonPath("$['Interview Required']").value(1));
    }

    @Test
    void jurisdictionsPreservesTopLevelStringsVsCountsRealIntsSplit() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/jurisdictions/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].juris_name").value("DASH BLOCK"))
           .andExpect(jsonPath("$[0].totalShortlisted").value("2"))     // top-level: raw COUNT bigint AS STRING (not parsed)
           .andExpect(jsonPath("$[0].evaluated").value("1"))
           .andExpect(jsonPath("$[0].progress").value(50))              // computed int: round(1/2*100)
           .andExpect(jsonPath("$[0].counts.pendingEvaluation").value(1))       // sub-object: real int
           .andExpect(jsonPath("$[0].counts.totalInterviewRequired").value(1))
           .andExpect(jsonPath("$[0].counts.completedInterview").value(0));      // 'Completed' vs 'COMPLETED' bug -> always 0
    }

    @Test
    void overallProgressComputesRoundedPercentAsInt() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall-progress/2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overallProgress").value(50)); // totalReq=2 (no shortlisted_yn filter here), totalDone=1
    }

    @Test
    void nonNumericYearIs500WithDistinctMessage() throws Exception {
        mvc.perform(get("/api/evaluation-dashboard/overall/not-a-year").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch overall counts."));
    }

    @Test
    void dashboardEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/evaluation-dashboard/overall/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation-dashboard/jurisdictions/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation-dashboard/overall-progress/2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EvaluationDashboardIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/evaluation/persistence/DashboardReadRepository.java`:
```java
package com.rcf.imas.modules.evaluation.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DashboardReadRepository {

    private final JdbcClient jdbc;

    public DashboardReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as EvaluationReadRepository (NUMERIC/BIGINT -> String) -- used ONLY for
     *  getJurisdictionStatus's top-level spread fields, which Node genuinely leaves as raw pg strings. */
    private static Map<String, Object> genericRow(ResultSet rs) throws SQLException {
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
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** getOverallCounts parity: 8 sequential COUNT(*) queries, EACH explicitly parseInt'd to a real int in Node --
     *  ported as real ints via a typed query, insertion order preserved with the 9 exact label strings as keys. */
    public Map<String, Object> overallCounts(String year) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Total Students", count("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :year::numeric", year));
        out.put("Shortlisted", count("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric and a.shortlisted_yn='Y'
                """, year));
        out.put("Evaluated", count("""
                SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year));
        out.put("Pending Evaluation/Marks Entry", count("""
                SELECT COUNT(*) FROM pp.applicant_primary_info a
                WHERE a.applicant_id NOT IN (SELECT asi.applicant_id FROM pp.applicant_secondary_info asi)
                  AND a.applicant_id IN (SELECT s.applicant_id FROM pp.applicant_shortlist_info s)
                  AND a.nmms_year = :year::numeric
                """, year));
        out.put("Interview Required", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                """, year));
        out.put("Pending Interviews Assignment", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND NOT EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id)
                """, year));
        out.put("Pending Interview Result Upload", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.interview_result IS NULL)
                """, year));
        out.put("Home Verification Required", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn='Y')
                """, year));
        out.put("Pending Home Verification Result Upload", count("""
                SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
                WHERE er.interview_required_yn = 'Y' AND api.nmms_year = :year::numeric
                  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn = 'Y')
                  AND NOT EXISTS (SELECT 1 FROM pp.home_verification hv WHERE hv.applicant_id = er.applicant_id AND hv.status IS NOT NULL)
                """, year));
        return out;
    }

    private int count(String sql, String year) {
        return jdbc.sql(sql).param("year", year).query(Integer.class).single();
    }

    /**
     * getJurisdictionStatus parity (evaluationDashboardModel.js:31-81). Per-row shape, read from the ACTUAL Node
     * source (not just the ground-truth summary, which over-generalizes): the top-level fields returned by the
     * spread `...row` are the RAW pg query result -- Strings for juris_code/totalShortlisted/evaluated/
     * pendingEvaluation/totalInterviewRequired/completedInterview (all NUMERIC/BIGINT) -- NOT reassigned/parsed.
     * Only `progress` (computed) and the `counts` sub-object (freshly parseInt'd) are real ints. Bug preserved:
     * si.status = 'Completed' (mixed case) never matches the upper-case-only CHECK constraint -> completedInterview
     * (both the raw top-level string AND the parsed counts.completedInterview) is always 0.
     */
    public List<Map<String, Object>> jurisdictionStatus(String year) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT
                  j.juris_name, j.juris_code,
                  COUNT(asi.applicant_id) AS "totalShortlisted",
                  COUNT(sec.applicant_id) AS "evaluated",
                  COUNT(CASE WHEN sec.applicant_id IS NULL THEN 1 END) AS "pendingEvaluation",
                  COUNT(CASE WHEN er.interview_required_yn = 'Y' THEN 1 END) AS "totalInterviewRequired",
                  COUNT(CASE WHEN si.status = 'Completed' THEN 1 END) AS "completedInterview"
                FROM pp.jurisdiction j
                JOIN pp.applicant_primary_info a ON j.juris_code = a.nmms_block
                JOIN pp.applicant_shortlist_info asi ON a.applicant_id = asi.applicant_id
                LEFT JOIN pp.applicant_secondary_info sec ON a.applicant_id = sec.applicant_id
                LEFT JOIN pp.exam_results er ON a.applicant_id = er.applicant_id
                LEFT JOIN pp.student_interview si ON a.applicant_id = si.applicant_id
                WHERE a.nmms_year = :year::numeric
                GROUP BY j.juris_code, j.juris_name
                ORDER BY j.juris_name ASC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            int total = Integer.parseInt((String) row.get("totalShortlisted"));
            int done = Integer.parseInt((String) row.get("evaluated"));
            int progress = total > 0 ? Math.round((done * 100f) / total) : 0;

            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("pendingEvaluation", Integer.parseInt((String) row.get("pendingEvaluation")));
            counts.put("totalInterviewRequired", Integer.parseInt((String) row.get("totalInterviewRequired")));
            counts.put("completedInterview", Integer.parseInt((String) row.get("completedInterview")));

            Map<String, Object> merged = new LinkedHashMap<>(row); // preserves the raw-string top-level fields verbatim
            merged.put("progress", progress);
            merged.put("counts", counts);
            out.add(merged);
        }
        return out;
    }

    /** getOverallProgress parity: q1 has NO shortlisted_yn filter (unlike getOverallCounts's "Shortlisted") -- a
     *  second, deliberately different definition of "shortlisted" (Firm Decision 5c). Only `overallProgress` (a
     *  real int) is returned; the two intermediate counts are never surfaced. */
    public int overallProgress(String year) {
        int totalReq = count("""
                SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year);
        int totalDone = count("""
                SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
                WHERE api.nmms_year = :year::numeric
                """, year);
        return totalReq > 0 ? Math.round((totalDone * 100f) / totalReq) : 0;
    }
}
```

`src/main/java/com/rcf/imas/modules/evaluation/web/EvaluationDashboardController.java`:
```java
package com.rcf.imas.modules.evaluation.web;

import com.rcf.imas.modules.evaluation.persistence.DashboardReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Each handler needs its OWN 500 message (Node has a distinct catch block per handler; unlike the rest of this
 * module, these do NOT share the generic {error:"Internal Server Error"} fallback) -- see Locked Conventions #5.
 */
@RestController
@RequestMapping("/api/evaluation-dashboard")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left these routes open
class EvaluationDashboardController {

    private final DashboardReadRepository dashboard;

    EvaluationDashboardController(DashboardReadRepository dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overall/{year}")
    public Map<String, Object> overall(@PathVariable String year) {
        try {
            return dashboard.overallCounts(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch overall counts.");
        }
    }

    @GetMapping("/jurisdictions/{year}")
    public List<Map<String, Object>> jurisdictions(@PathVariable String year) {
        try {
            return dashboard.jurisdictionStatus(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch jurisdictional progress.");
        }
    }

    @GetMapping("/overall-progress/{year}")
    public Map<String, Object> overallProgress(@PathVariable String year) {
        try {
            return Map.of("overallProgress", dashboard.overallProgress(year));
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch overall progress.");
        }
    }
}
```

> **Why a try/catch here and not elsewhere in the module.** Every other endpoint in this module (custom-list reads/writes/exports, `exam_names`'s 500 path, `download_excel`'s 500 path) relies on `GlobalExceptionHandler`'s fallback to emit the generic `{error:"Internal Server Error"}`, matching Node's identical generic catch blocks there. The dashboard is the one place in this module where Node's 3 handlers each have their **own distinct** message string — reproducing that distinction requires catching at the controller (any `RuntimeException`, including the Postgres cast error from a non-numeric `{year}` path segment) and re-throwing as the endpoint's specific `ApiException.error(500, "...")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EvaluationDashboardIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + all new evaluation-module tests green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/evaluation imas-backend/src/test/java/com/rcf/imas/modules/evaluation/EvaluationDashboardIT.java
git commit -m "feat(evaluation): dashboard overall/jurisdictions/overall-progress (string-vs-int split, per-endpoint 500 messages)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 5 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/evaluation` package against this plan + the spec, checking:
- **Dual mount:** `CustomListController`'s class-level `@RequestMapping({"/api/custom-list","/api/evaluation"})` actually serves all 9 handlers under both paths (not just some); the dual-path test from Task 1 wasn't dropped.
- **`available-fields` stays live introspection** — confirm no static/frozen field list was substituted, and the query still runs against `information_schema.columns` at request time.
- **`bulk-upload` truly absent** — grep the module for any trace of a `/bulk-upload` mapping, multer-equivalent, or `insertBulkData` port; confirm it's flagged in the Deferred section below, not silently dropped without a paper trail.
- **`saveListFull`'s full-replace semantics** — confirm the update path unconditionally deletes `custom_list_fields`/`custom_list_students` before re-inserting (not a merge), and that `field_master` rows are genuinely shared/reused across lists (keyed only by `col_name`), matching `saveListFullReusesFieldMasterRowAcrossLists`.
- **`deleteList` never checks affected-row count** — `{success:true}` even for a non-existent id.
- **Bug preservation, all 3:** (a) `getStudents`'s `er`/`aea` joined on `asi.applicant_id` (feeds `download_excel`) — pinned by `downloadExcelHas34FixedColumnsAndPreservesJoinChainBug`; (b) `getJurisdictionStatus`'s `si.status = 'Completed'` mixed-case — `completedInterview` always 0, both top-level and in `counts`; (c) `getOverallCounts`'s `"Shortlisted"` filters `shortlisted_yn='Y'` but `getOverallProgress`'s equivalent count does not — two different denominators, each ported literally.
- **`getJurisdictionStatus`'s string-vs-int split is exact** — top-level `totalShortlisted`/`evaluated`/`pendingEvaluation`/`totalInterviewRequired`/`completedInterview` are raw Strings (from `genericRow`'s NUMERIC/BIGINT stringification); `counts.*` and `progress` are real ints; `juris_code` stays a String. This is the one place in the plan most likely to be mis-implemented if the ground-truth §4 summary is trusted over the actual Node source — verify against `evaluationDashboardModel.js:64-76` directly.
- **`nmms_year = 2025` hard-coded literal** in `studentsByCohort` — not parameterized; `divisionId` accepted but never wired into any filter.
- **Error-key split:** custom-list/`exam_names`-500/`download_excel`-500 → generic `{error:"Internal Server Error"}` fallback; `exam_names`'s 400 → `{message:"Academic year is required"}`; all 3 dashboard 500s → their own distinct `{error:"Failed to fetch ..."}` message (NOT the generic fallback).
- **XLSX/PDF exactness:** `download-xlsx`/`download-pdf` dynamic ID/Name-then-fields columns + special-case value mapping (`batch_id`, `current/previous_institute_dise_code`, `district`/`district_id`, `nmms_block`/`block_id`) identical between the two formats; `download_excel`'s 34 fixed columns, 4 fill groups, DOB/Family-Income/numeric/Y-N formatting, filename NOT quoted (contrast with `download-xlsx`/`download-pdf`, which ARE quoted).
- **PDF simplifications documented, not silently dropped:** no logos, hard-coded `"2025"` subtitle literal, standard font only — all per Firm Decision 4, all called out in code comments.
- **Auth:** all 3 controllers class-level `@PreAuthorize("hasRole('ADMIN')")`; every handler `public`; controllers package-private.
- **Transactions:** only `saveListFull` is `@Transactional`, in the dedicated `EvaluationWriteRepository` bean (never self-invoked from within the same class as a plain method call). `deleteList` has no `@Transactional` (single statement, cascade does the rest).

Update `imas-migration-status` memory: Phase 3b complete, new test count, `bulk-upload` explicitly flagged as not-ported (schema gap), ready for the next Phase-3 sub-module.

## Deferred / parity decisions carried into this plan

- **`POST /bulk-upload` NOT ported.** Non-functional against the frozen schema as dumped: `insertBulkData` runs `ON CONFLICT (applicant_id) DO UPDATE` against `pp.exam_results` and `pp.applicant_exam_attendance`, neither of which has a unique/exclusion constraint on `applicant_id` (Postgres `42P10` if attempted), and references `pp.enr_id_seq`, which does not exist in `live-schema.sql` (`42P01` if attempted). Porting a guaranteed-crash data-mutation endpoint is pointless and unsafe. **FLAGGED FOR USER:** enabling this endpoint requires a schema change — add unique constraints on `exam_results.applicant_id` and `applicant_exam_attendance.applicant_id`, and create `pp.enr_id_seq` — before any Java port is attempted. No route, no multipart handling, no `insertBulkData` equivalent exists anywhere in this module's code.
- **`available-fields` is live `information_schema` introspection**, not a frozen field list (Firm Decision 2) — tracks future `pp.student_master`/`pp.applicant_primary_info` schema changes automatically, matching Node's behavior exactly, at the cost of one extra catalog query per call (acceptable for an admin-only, low-traffic endpoint).
- **Errors standardized to clean JSON** (Firm Decision 3) — Node has no app-wide error middleware for `exam_names`/`download_excel` (falls to Express's default HTML/plain-text page) and the custom-list handlers emit `{error:e.message}` (leaking raw DB error text) or `res.status(500).send(e.message)` (plain text) for the two downloads. This plan intentionally standardizes on the module's `ApiException`/`GlobalExceptionHandler` machinery for all of these, an explicit improvement over Node's inconsistent/leaky error surface — documented per-endpoint in the contract table, not normalized to one universal shape (the dashboard's 3 distinct messages and `exam_names`'s 400 are preserved as genuine per-endpoint differences).
- **PDF via OpenPDF, no logos, hard-coded `"2025"` subtitle, standard font only** (Firm Decision 4) — simplifications from Node's `pdfkit-table` + two embedded PNG logos + `isFirstPage`-gated header (that in practice never re-renders past page 1 anyway, a pre-existing Node bug not worth reproducing). A human downloading a PDF from an admin screen does not depend on branding fidelity; no automated consumer parses this file.
- **`download_excel`'s data-validation dropdowns and DOB cell note are best-effort** (Firm Decision 7) — implemented via POI `DataValidationHelper`/`Comment` where straightforward; the auto-fit column-width recompute that Node runs LAST (silently overriding its own initial widths) is dropped as a pure cosmetic no-op with no data-integrity impact.
- **`getStudents`'s `er`/`aea` LEFT JOIN on `asi.applicant_id` instead of `api.applicant_id` preserved verbatim** (Firm Decision 5a) — feeds `download_excel`; an applicant with exam results but no secondary-info row shows blank exam columns even though matching rows exist elsewhere. Pinned by `downloadExcelHas34FixedColumnsAndPreservesJoinChainBug`.
- **`nmms_year = 2025` hard-coded literal preserved** in `studentsByCohort` (Firm Decision 5d) — not parameterized, not derived from any request field. `divisionId` accepted but never wired into a filter (Firm Decision 5e) — a silent no-op, matching Node exactly.
- **Dashboard's two bugs preserved verbatim:** `si.status = 'Completed'` (mixed case) never matches the upper-case-only `student_interview.status` CHECK constraint, so `completedInterview` is always 0 (Firm Decision/quirk per ground truth §16); `getOverallCounts`'s `"Shortlisted"` filters `shortlisted_yn='Y'` while `getOverallProgress`'s otherwise-identical count does not — two genuinely different "how many are shortlisted" denominators, ported literally per query (Firm Decision 5c / ground truth §17).
- **`getJurisdictionStatus`'s string-vs-int response split is exact, not approximate** — read directly from `evaluationDashboardModel.js:64-76`: only `progress` and `counts.*` are freshly `parseInt`'d; the top-level spread fields (`totalShortlisted`, `evaluated`, `pendingEvaluation`, `totalInterviewRequired`, `completedInterview`, `juris_code`) remain raw pg strings. This is a more precise statement than the ground-truth artifact's summary in §4, which claims all three dashboard functions "explicitly `parseInt`... before returning" — true for `getOverallCounts`/`getOverallProgress`, only partially true for `getJurisdictionStatus`. Flagged for the reviewer.
- **ADMIN enforcement is NEW** across all 14 endpoints (audit CRITICAL) — Node left every route in this module (`/api/custom-list/*`, `/api/evaluation/*`, `/api/evaluation-dashboard/*`) fully open. Add to the fetch audit alongside Plan 3d's identical finding for `/api/results/**`.
