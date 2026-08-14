# EVALUATION + EVALUATION-DASHBOARD Module — Ground Truth (for Plan 3b)

Captured from a full read of the Node source. Two mounts in `server/index.js`:
- `app.use("/api/evaluation", evaluationRoutes)` (index.js:313) — routes `server/routes/evaluationRoutes.js`, backed by **two** controllers: `controllers/customListController.js` (custom-list/PDF/XLSX endpoints) + `controllers/evaluationController.js` (exam_names/bulk-upload endpoints).
- `app.use("/api/evaluation-dashboard", evaluationDashboardRoutes)` (index.js:314) — `server/routes/evaluationDashboardRoutes.js` → `controllers/evaluationDashboardController.js` → `models/evaluationDashboardModel.js`.

**Quirk up front:** `evaluationRoutes.js` lines 6-15 are byte-for-byte identical to `server/routes/customListRoutes.js` (also mounted separately at `app.use("/api/custom-list", customListRoutes)`, index.js:301) — same controller, same handlers, two live base paths (`/api/custom-list/*` and `/api/evaluation/*`) serve the exact same custom-list/PDF/XLSX functionality. Not a bug per se, but means `/api/evaluation/lists`, `/download-pdf/:listId`, etc. are 100% duplicates of `/api/custom-list/...` — decide in migration whether to keep both paths or consolidate.

No files were commented out in the routers/controllers read (all lines are live). `models/evaluationModels.js`, `models/customListModel.js`, `models/evaluationDashboardModel.js` are fully live too.

## 1. Endpoint Inventory

### `/api/evaluation` (evaluationRoutes.js) — 11 registered handlers, but only 10 reachable

| # | Method | Path | Handler | Controller/Model | Notes |
|---|--------|------|---------|-------------------|-------|
| 1 | GET | `/lists` | `customListController.getAllLists` | `customListModel.getAllLists` | |
| 2 | GET | `/batches` | `customListController.getAllBatches` | `customListModel.getAllBatches` | `?cohortId=` optional |
| 3 | GET | `/available-fields` | `customListController.getAvailableFields` | `customListModel.getAvailableFields` | dynamic `information_schema` query |
| 4 | GET | `/students-by-list/:listId` | `customListController.getStudentsByListId` | `customListModel.getStudentsByListId` | |
| 5 | GET | `/students-by-cohort/:cohortId` | `customListController.getStudentsByCohort` | `customListModel.getStudentsByCohort` | `?batchId&stateId&divisionId&districtId&blockId` |
| 6 | GET | `/download-pdf/:listId` | `customListController.downloadListPDF` | | `pdfkit-table` |
| 7 | GET | `/download-xlsx/:listId` | `customListController.downloadListXLS` | | `exceljs` |
| 8 | POST | `/save-list-full` | `customListController.saveListFull` | `customListModel.saveListFull` | dynamic list save (create-or-replace) |
| 9 | DELETE | `/list/:id` | `customListController.deleteList` | `customListModel.deleteList` | |
| 10 | GET | `/exam_names` (**registered twice**, line 20 wins) | `evaluationController.fetchExamNames` | `evaluationModels.getExamNames` | see quirk below |
| — | GET | `/exam_names` (line 21, **dead/unreachable**) | `evaluationController.fetchStudents` | `evaluationModels.getStudents` | never executes — see quirk |
| 11 | POST | `/download_excel` | `evaluationController.downloadStudentExcel` | `evaluationModels.getStudents` | body `{exam_name}` |
| 12 | POST | `/bulk-upload` | `evaluationController.uploadBulkData` (after `upload.single('excelFile')`) | `evaluationModels.insertBulkData` | multipart, writes file to disk |

**Duplicate `/exam_names` route quirk (evaluationRoutes.js:20-21):**
```js
router.get("/exam_names",fetchExamNames)
router.get("/exam_names", fetchStudents);
```
Express matches routes in registration order and stops at the first handler that ends the response (doesn't call `next()`). `fetchExamNames` is wrapped in `asyncHandler` and always calls `res.status(200).json(...)` (or throws, routed to `next(err)`) — it never calls `next()` on success. **Result: the line-21 registration (`fetchStudents`) is 100% dead code; `GET /api/evaluation/exam_names` always resolves via `fetchExamNames`, regardless of query params.** `fetchStudents`'s only live invocation path in the app is none — it is not reachable from any route (the frontend never calls it either; see §7).

**Frontend/backend mismatch:** `client/src/hooks/EvalutionHooks.js:40` calls `POST /api/evaluation/exam_query` — **no such route exists anywhere in `evaluationRoutes.js`.** This frontend call always 404s (dead call site) — treat as a known-broken feature, not something to port a working equivalent of.

### `/api/evaluation-dashboard` (evaluationDashboardRoutes.js) — 3 routes

| # | Method | Path | Handler | Model |
|---|--------|------|---------|-------|
| 1 | GET | `/overall/:year` | `getOverallCounts` | `DashboardModel.getOverallCounts` |
| 2 | GET | `/jurisdictions/:year` | `getJurisdictionalProgress` | `DashboardModel.getJurisdictionStatus` |
| 3 | GET | `/overall-progress/:year` | `getOverallProgress` | `DashboardModel.getOverallProgress` |

`:year` is a path param (not query string) on all three, parsed via `parseInt(req.params.year, 10)`; if omitted/non-numeric, `DashboardController.getYear` falls back to `new Date().getFullYear()` only when `req.params.year` is falsy — but Express won't match the route at all if `:year` segment is missing (it's a required path segment), so the fallback path is effectively dead in normal routing (only triggers if year is `"0"`/empty-string edge cases via `parseInt` returning `NaN`... actually `req.params.year` will always be a non-empty string if the route matched, so `getYear` always returns `parseInt(...)`, possibly `NaN` for non-numeric segments like `/overall/abc`). A non-numeric `:year` produces `NaN` bound as a query param — Postgres will error (invalid input syntax), causing a 500 via the catch block.

## 2. Exact SQL (verbatim)

### customListModel.js

**`getAllLists()`**
```sql
SELECT cl.list_id, cl.list_name, COUNT(cls.student_id) AS student_count
FROM pp.custom_list cl
LEFT JOIN pp.custom_list_students cls ON cl.list_id = cls.list_id
GROUP BY cl.list_id, cl.list_name
ORDER BY cl.list_id DESC;
```
No params.

**`getAvailableFields()`**
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
  AND column_name NOT IN (
        'created_at', 'updated_at', 'created_by', 'updated_by',
        'applicant_id', 'photo_link', 'student_id'
  )
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
ORDER BY display_name ASC;
```
Introspects live `information_schema.columns` at request time — **schema-driven, not a fixed field list.** Any column added/removed on `pp.student_master` (except the excluded list) automatically appears/disappears here. Java port must either replicate the live introspection (query `information_schema.columns` the same way) or accept a functionally-frozen field list — pick deliberately, it changes behavior on future schema changes.

**`getAllBatches(cohortId)`** — dynamic WHERE
```sql
SELECT b.batch_id, b.batch_name, c.cohort_name
FROM pp.batch b
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
[ WHERE b.cohort_number = $1 ]   -- only if cohortId not null/"null"/"undefined"
ORDER BY b.batch_name;
```

**`getStudentsByListId(listId)`** — two queries, both `$1 = listId`:
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
WHERE cls.list_id = $1
ORDER BY sm.student_name;

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
WHERE clf.list_id = $1;
```
Returns `{ students: rows, fields: rows }` — used directly by both JSON API and the PDF/XLSX exporters (they re-run these same two queries themselves rather than sharing a cached result).

**`getStudentsByCohort(cohortId, batchId, stateId, divisionId, districtId, blockId)`** — dynamic, string-built:
```sql
SELECT sm.student_id, sm.student_name, b.batch_name, api.gender
FROM pp.student_master sm
JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
WHERE sm.active_yn = 'ACTIVE'
  AND api.nmms_year = 2025          -- ⚠ hard-coded literal year, not a param, not $-bound
  [ AND b.cohort_number = $n ]      -- addFilter(cohortId, "b.cohort_number")
  [ AND sm.batch_id = $n ]          -- addFilter(batchId, "sm.batch_id")
  [ AND api.app_state = $n ]        -- addFilter(stateId, "api.app_state")
  [ AND api.district = $n ]         -- addFilter(districtId, "api.district")
  [ AND api.nmms_block = $n ]       -- addFilter(blockId, "api.nmms_block")
ORDER BY sm.student_name;
```
`addFilter` skips a column if the incoming value is falsy, `"all"`, `"null"`, `"undefined"`, or `""`. **`divisionId` parameter is accepted by the function signature but never used in any filter — silently dropped.** The `2025` in `nmms_year = 2025` is a literal in the SQL string, not parameterized and not derived from any year context — a stale hard-code from a previous NMMS cycle; must decide whether to keep frozen at 2025 or parameterize on migration (recommend flagging to product).

**`getListHeader(listId)`**
```sql
SELECT list_name FROM pp.custom_list WHERE list_id = $1
```

**`saveListFull(listId, listName, studentIds, selectedFields)`** — see §7 "saveListFull" for full transactional flow; key statements:
```sql
INSERT INTO pp.custom_list (list_name) VALUES ($1) RETURNING list_id;                         -- when no listId
UPDATE pp.custom_list SET list_name = $1 WHERE list_id = $2;                                   -- when listId given
DELETE FROM pp.custom_list_fields WHERE list_id = $1;
DELETE FROM pp.custom_list_students WHERE list_id = $1;
SELECT field_id FROM pp.field_master WHERE col_name = $1;
INSERT INTO pp.field_master (tab_name, col_name) VALUES ('pp.student_master', $1) RETURNING field_id;
INSERT INTO pp.custom_list_fields (list_id, field_id) VALUES ($1, $2);
INSERT INTO pp.custom_list_students (list_id, student_id) VALUES ($1, $2);
```

**`deleteList(id)`**
```sql
DELETE FROM pp.custom_list WHERE list_id = $1
```
Single-statement, autocommit (no explicit transaction) — but `pp.custom_list_fields`/`pp.custom_list_students` have FK `ON DELETE CASCADE` to `custom_list(list_id)` (see §3), so cascade handles cleanup at the DB level.

### evaluationModels.js

**`getExamNames(year)`**
```sql
SELECT exam_name
FROM pp.examination
WHERE exam_year LIKE $1
ORDER BY exam_id ASC
```
`year` input like `"2026-27"` → `yearPrefix = year.split("-")[0].trim()` → param bound is `` `${yearPrefix}%` `` (e.g. `"2026%"`). **If `year` has no `"-"`, `split("-")[0]` is the whole string** — still works, just no truncation. Returns raw rows `[{exam_name}, ...]`.

**`getStudents(exam_name)`**
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
LEFT JOIN pp.exam_results er ON asi.applicant_id = er.applicant_id
LEFT JOIN pp.applicant_exam_attendance aea ON aea.applicant_id = asi.applicant_id
WHERE ex.exam_name = $1
```
Note the chain: `er` joins on `asi.applicant_id` (not `api.applicant_id`) and `aea` joins on `asi.applicant_id` too — so if a student has no `applicant_secondary_info` row, `asi.applicant_id` is NULL and **both `er` and `aea` LEFT JOINs will fail to match even if `exam_results`/`applicant_exam_attendance` rows exist for that applicant** (NULL = anything is never true). This is a real join-chain bug (should join `er`/`aea` on `api.applicant_id`) — reproduce it verbatim for parity, flag for product/QA as a candidate fix.

**`insertBulkData(...)`** — see §5/§6 for full flow and exact per-table statements.

### evaluationDashboardModel.js

**`getOverallCounts(nmmsYear)`** — 8 independent `COUNT(*)` queries, each bound `$1 = nmmsYear`, executed sequentially in a `for` loop (not `Promise.all`):
```sql
-- Total Students
SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = $1;

-- Shortlisted
SELECT COUNT(*) FROM pp.applicant_shortlist_info a
JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
WHERE api.nmms_year = $1 and a.shortlisted_yn='Y';

-- Evaluated
SELECT COUNT(*) FROM pp.applicant_secondary_info asi
JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
WHERE api.nmms_year = $1;

-- Pending Evaluation/Marks Entry
SELECT COUNT(*) FROM pp.applicant_primary_info a
WHERE a.applicant_id NOT IN (SELECT asi.applicant_id FROM pp.applicant_secondary_info asi)
  AND a.applicant_id IN (SELECT s.applicant_id FROM pp.applicant_shortlist_info s)
  AND a.nmms_year = $1;

-- Interview Required
SELECT COUNT(*) FROM pp.exam_results er
JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1;

-- Pending Interviews Assignment
SELECT COUNT(*) FROM pp.exam_results er
JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1
  AND NOT EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id);

-- Pending Interview Result Upload
SELECT COUNT(*) FROM pp.exam_results er
JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.interview_result IS NULL);

-- Home Verification Required
SELECT COUNT(*) FROM pp.exam_results er
JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn='Y');

-- Pending Home Verification Result Upload
SELECT COUNT(*) FROM pp.exam_results er
JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id
WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1
  AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn = 'Y')
  AND NOT EXISTS (SELECT 1 FROM pp.home_verification hv WHERE hv.applicant_id = er.applicant_id AND hv.status IS NOT NULL);
```
Result object keys are the exact label strings above (`"Total Students"`, `"Shortlisted"`, `"Evaluated"`, `"Pending Evaluation/Marks Entry"`, `"Interview Required"`, `"Pending Interviews Assignment"`, `"Pending Interview Result Upload"`, `"Home Verification Required"`, `"Pending Home Verification Result Upload"`), each value `parseInt(count,10)`.

**`getJurisdictionStatus(nmmsYear)`** — single query, `$1 = nmmsYear`:
```sql
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
WHERE a.nmms_year = $1
GROUP BY j.juris_code, j.juris_name
ORDER BY j.juris_name ASC;
```
**Alias `asi` here is `applicant_shortlist_info`** (unlike `getStudents`, where `asi` = `applicant_secondary_info` — don't cross-reference aliases between files). `"totalShortlisted"` is really a COUNT of `applicant_shortlist_info` rows joined per jurisdiction (can double-count if a jurisdiction/applicant has multiple shortlist rows — no dedup). `si.status = 'Completed'` — note the DB CHECK constraint on `pp.student_interview.status` only allows `'SCHEDULED'|'COMPLETED'|'CANCELLED'|'RESCHEDULED'` (all upper-case) — **`'Completed'` (mixed case) can never match any row**, so `completedInterview` is **always 0** in practice. Reproduce verbatim (do not "fix" the case) unless directed.

Then in JS: `progress = total>0 ? Math.round((done/total)*100) : 0` where `total = parseInt(row.totalShortlisted)`, `done = parseInt(row.evaluated)`; response row = `{...row, progress, counts:{pendingEvaluation, totalInterviewRequired, completedInterview}}` (spread keeps original camelCase keys **and** duplicates them inside `counts`, except `progress` isn't duplicated).

**`getOverallProgress(nmmsYear)`** — two queries, both `$1 = nmmsYear`:
```sql
SELECT COUNT(*) FROM pp.applicant_shortlist_info a
JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id
WHERE api.nmms_year = $1;

SELECT COUNT(*) FROM pp.applicant_secondary_info asi
JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id
WHERE api.nmms_year = $1;
```
`overallProgress = totalReq>0 ? Math.round((totalDone/totalReq)*100) : 0`. Note this "Shortlisted" count (q1) here does **not** filter `shortlisted_yn='Y'` (unlike `getOverallCounts`'s "Shortlisted" query which does) — a second inconsistency in what "shortlisted" means across the two dashboard endpoints; reproduce each literally.

## 3. Table DDL Facts (from `live-schema.sql`)

| Table | Key columns | PK | Notable UNIQUE | Notable FK | Sequence |
|---|---|---|---|---|---|
| `pp.examination` | `exam_id numeric(14,0)`, `exam_name varchar(100) NOT NULL`, `exam_year varchar(10)`, `frozen_yn char(1) DEFAULT 'N'` | `exam_id` | — | `pp_exam_centre_id→pp_exam_centre`, `created_by/updated_by→user` | `examination_seq` (default on `exam_id`) |
| `pp.applicant_exam` | `applicant_id numeric(14,0)`, `exam_id numeric(14,0)`, `pp_hall_ticket_no varchar(20)` | `(applicant_id, exam_id)` composite (`pk_applicant_exam`) | — | `applicant_id→applicant_primary_info`, `exam_id→examination` | — |
| `pp.applicant_exam_attendance` | `applicant_id numeric(14,0)`, `pp_exam_appeared_yn char(1) CHECK IN ('Y','N')` | **none** | **none** | `applicant_id→applicant_primary_info` | — |
| `pp.applicant_primary_info` | `applicant_id numeric(14,0)` default `nextval(applicant_id_seq)`, `nmms_year numeric(4,0)`, `nmms_reg_number numeric(11,0) NOT NULL`, `app_state/district/nmms_block numeric(12,0)`, demographic cols, `gender CHECK IN ('M','F','O')` | `applicant_id` | `nmms_reg_number` UNIQUE | `app_state/district/nmms_block→jurisdiction`, `current/previous_institute_dise_code→institute` | `applicant_id_seq` |
| `pp.applicant_secondary_info` | `applicant_id numeric(14,0) NOT NULL`, village/occupation/education cols, `own_house/smart_phone_home/internet_facility_home CHECK IN ('Y','N')`, `num_two_wheelers numeric(2,0) DEFAULT 0 NOT NULL`, `num_four_wheelers numeric(2,0) DEFAULT 0 NOT NULL`, `irrigation_land numeric(6,2) DEFAULT 0 NOT NULL`, plus `spl_health_cond`/`spl_family_cond` (`DEFAULT 'N'`) | `applicant_id` (`applicant_secondary_info_pkey`) | — | `applicant_id→applicant_primary_info ON DELETE CASCADE` | — |
| `pp.exam_results` | `applicant_id numeric(14,0)`, `pp_exam_score numeric(3,0)`, `pp_exam_cleared CHECK IN ('Y','N')`, `interview_required_yn CHECK IN ('Y','N')` | **none** | **none** | `applicant_id→applicant_primary_info` | — |
| `pp.applicant_shortlist_info` | `shortlist_info_id numeric(14,0)` default `nextval(shortlist_info_seq)`, `applicant_id`, `shortlisted_yn char(1)`, `shortlist_batch_id` | `shortlist_info_id` | — | `applicant_id→applicant_primary_info`, `shortlist_batch_id→shortlist_batch ON DELETE CASCADE` | `shortlist_info_seq` |
| `pp.student_interview` | `interview_id numeric(12,0)` default `nextval(interview_id_seq)`, `applicant_id`, `status CHECK IN ('SCHEDULED','COMPLETED','CANCELLED','RESCHEDULED')` (upper-case only), `interview_result CHECK IN ('SELECTED','REJECTED','ANOTHER INTERVIEW REQUIRED')`, `home_verification_req_yn CHECK IN ('Y','N') DEFAULT 'N'` | `interview_id` | — | `applicant_id→applicant_primary_info`, `interviewer_id→interviewer` | `interview_id_seq` |
| `pp.home_verification` | `verification_id numeric(12,0)` default `nextval(verification_id_seq)`, `applicant_id`, `status CHECK IN ('PENDING','SCHEDULED','REJECTED','ACCEPTED')`, `verification_type CHECK IN ('PHYSICAL','VIRTUAL')` | `verification_id` | — | `applicant_id→applicant_primary_info`, `rejection_reason_id→rejection_reasons` | `verification_id_seq` |
| `pp.student_master` | `student_id numeric(14,0)` default `nextval(student_id_seq)`, `applicant_id`, `enr_id numeric(11,0)`, demographic/contact cols, `active_yn varchar(10) DEFAULT 'ACTIVE' CHECK IN ('ACTIVE','INACTIVE')`, `batch_id int` | `student_id` | `applicant_id` UNIQUE, `enr_id` UNIQUE | `applicant_id→applicant_primary_info`, `batch_id→batch`, `current/previous_institute_dise_code→institute` | `student_id_seq` |
| `pp.batch` | `batch_id int` default `nextval(batch_id_seq)`, `batch_name`, `cohort_number int`, `medium DEFAULT 'KANNADA' CHECK IN (ENGLISH,KANNADA,HINDI,MARATHI)` | `batch_id` | `(cohort_number, batch_name)` UNIQUE | `cohort_number→cohort ON DELETE CASCADE` | `batch_id_seq` |
| `pp.cohort` | `cohort_number int` default `nextval(cohort_seq)`, `cohort_name` | `cohort_number` | `cohort_name` UNIQUE | — | `cohort_seq` |
| `pp.institute` | `institute_id numeric(14,0)`, `dise_code varchar(15)`, `institute_name` | `institute_id` | `dise_code` UNIQUE | `juris_code→jurisdiction` | `institute_id_seq` |
| `pp.jurisdiction` | `juris_code numeric(12,0) NOT NULL`, `juris_name varchar(100)`, `juris_type`, `parent_juris` | `juris_code` | — | `juris_type→jurisdiction_type`, `parent_juris→jurisdiction` (self) | `jurisdiction_code_seq` (not the PK default — PK has no default in DDL, values are app-assigned) |
| `pp.custom_list` | `list_id numeric(10,0)` default `nextval(custom_list_id_seq)`, `list_name varchar(200) NOT NULL` | `list_id` | — | — | `custom_list_id_seq` |
| `pp.custom_list_fields` | `list_id numeric(10,0) NOT NULL`, `field_id numeric(6,0) NOT NULL` | `(list_id, field_id)` composite | — | `field_id→field_master ON DELETE RESTRICT`, `list_id→custom_list ON DELETE CASCADE` | — |
| `pp.custom_list_students` | `list_id numeric(10,0) NOT NULL`, `student_id numeric(14,0) NOT NULL` | `(list_id, student_id)` composite | — | `list_id→custom_list ON DELETE CASCADE`, `student_id→student_master ON DELETE CASCADE` | — |
| `pp.field_master` | `field_id numeric(6,0)` default `nextval(field_id_seq)`, `tab_name varchar(100) DEFAULT 'pp.student_master' NOT NULL`, `col_name varchar(100) NOT NULL` | `field_id` | — | — | `field_id_seq` |

**Two schema facts that break live application code (see §7 for full detail):**
1. `pp.exam_results` and `pp.applicant_exam_attendance` have **no PK and no UNIQUE constraint on `applicant_id`**, yet `insertBulkData` runs `INSERT ... ON CONFLICT (applicant_id) DO UPDATE ...` against both — Postgres requires a unique/exclusion constraint matching the conflict target; **this statement cannot succeed against the live schema as dumped** (would throw `42P10: there is no unique or exclusion constraint matching the ON CONFLICT specification`).
2. `pp.enr_id_seq` referenced by `insertBulkData` (`SELECT setval('pp.enr_id_seq', ...)`, `nextval('pp.enr_id_seq')`) **does not exist anywhere in `live-schema.sql`** — this statement would throw `42P01: relation "pp.enr_id_seq" does not exist` if run against the schema as captured.

## 4. Response Shapes & Status Codes

| Endpoint | Success shape | Status | Error shape/status |
|---|---|---|---|
| GET `/lists` | raw array `[{list_id, list_name, student_count}]` (`student_count` numeric-as-string from `COUNT`) | 200 | `{error: msg}` 500 |
| GET `/batches` | raw array `[{batch_id, batch_name, cohort_name}]` | 200 | `{error: msg}` 500 |
| GET `/available-fields` | raw array `[{col_name, display_name}]` | 200 | `{error: msg}` 500 |
| GET `/students-by-list/:listId` | `{students:[...], fields:[...]}` envelope | 200 | `{error: msg}` 500 |
| GET `/students-by-cohort/:cohortId` | raw array `[{student_id, student_name, batch_name, gender}]` | 200 | `{error: msg}` 500 |
| POST `/save-list-full` | `{success:true, list_id}` | 200 | `{error: msg}` 500 |
| DELETE `/list/:id` | `{success:true}` | 200 | `{error: msg}` 500 |
| GET `/download-pdf/:listId` | binary `application/pdf`, `Content-Disposition: attachment; filename="<listName>.pdf"` | 200 | `res.status(500).send(e.message)` (plain text, not JSON) |
| GET `/download-xlsx/:listId` | binary xlsx, `Content-Disposition: attachment; filename="<listName>.xlsx"` | 200 | `res.status(500).send(e.message)` (plain text) |
| GET `/exam_names` | `ApiResponse` envelope: `{statusCode:200, data:[{exam_name}], message:"ok", success:true}` | 200 | thrown `ApiError` → **no error middleware registered app-wide (see §7)**, falls to Express's default handler |
| POST `/download_excel` | binary xlsx (`Content-Disposition: attachment; filename=students_<exam_name-sanitized>.xlsx`) | 200 | thrown `ApiError` (500) → same unhandled-middleware quirk |
| POST `/bulk-upload` | `{message:"Bulk upload successful", result:{primaryInfoUpdated, secondaryInfoCount, examResultsCount, examAttendanceCount, eligibleStudentsCount}}` | 200 | row-validation: `res.status(400).json({errors:[{row, error}]})`; DB/other: `res.status(500).send(error.message)` (plain text, caught explicitly in this handler unlike the other two `evaluationController` handlers) |
| GET `/overall/:year` | `{"Total Students":n, "Shortlisted":n, "Evaluated":n, "Pending Evaluation/Marks Entry":n, "Interview Required":n, "Pending Interviews Assignment":n, "Pending Interview Result Upload":n, "Home Verification Required":n, "Pending Home Verification Result Upload":n}` (all ints) | 200 | `{error:"Failed to fetch overall counts."}` 500 |
| GET `/jurisdictions/:year` | raw array of `{juris_name, juris_code, totalShortlisted, evaluated, pendingEvaluation, totalInterviewRequired, completedInterview, progress, counts:{pendingEvaluation, totalInterviewRequired, completedInterview}}` (first three counts appear both top-level and duplicated inside `counts`) | 200 | `{error:"Failed to fetch jurisdictional progress."}` 500 |
| GET `/overall-progress/:year` | `{overallProgress:n}` | 200 | `{error:"Failed to fetch overall progress."}` 500 |

**`fetchStudents` response bug (evaluationController.js:39, dead code but must be captured for exact-parity docs since it shares a model with the reachable path):**
```js
res.status(200).json(new ApiResponse(200,"ok",StudentNames))
```
`ApiResponse` constructor is `(statusCode, data, message)`. This call passes `data="ok"` and `message=StudentNames` (the array) — **arguments are swapped relative to correct usage** (compare `fetchExamNames`'s correct `new ApiResponse(200, ExamNames, "ok")`). Resulting JSON would be `{statusCode:200, data:"ok", message:[...rows], success:true}`. Since this handler is unreachable via routing (see §1), it only matters if a Java port ever exposes `fetchStudents`' logic on a live route — do not "fix" the swap if porting this dead branch for completeness, since it was never observably live in Node.

**Numeric-as-string:** all `COUNT(*)` results (`student_count`, `overallProgress`'s inputs, dashboard counts) come back from `pg` as strings; `getOverallCounts`/`getOverallProgress`/`getJurisdictionStatus` explicitly `parseInt(...,10)` before returning — but `getAllLists`'s `student_count` and `getStudentsByCohort` are **not** parsed, so those numeric-looking fields stay JSON strings in the response (`"3"` not `3`).

## 5. File-Generating Endpoints

### PDF — `downloadListPDF` (customListController.js:98-177)
- Library: `pdfkit-table` (`PDFDocument` from that package, not raw `pdfkit`).
- Doc opts: `margins:{top:30,bottom:30,left:30,right:30}`, `size:'A4'`, `layout:'landscape'`.
- Custom header via `drawReportHeader(doc, isFirstPage, nmmsYear)` (customListController.js:12-44): draws RCF logo (`server/public/assets/rcf_logo-removebg-preview.png`) top-left and PP logo (`server/public/assets/logo.png`) top-right (both `fit:[LOGO_SIZE,LOGO_SIZE]`, `LOGO_SIZE` 80px on first page / 50px on subsequent — but this function is only ever invoked once, with `isFirstPage=true`, from `downloadListPDF`; the `pdfkit-table` library's own internal page-break handling does NOT re-invoke this custom header on overflow pages, so page 2+ of a long list has **no header at all**, just table continuation), title `"RAJALAKSHMI CHILDREN FOUNDATION"` (Times-Bold, centered), subtitle `` `PRATIBHA POSHAK - ${nmmsYear}` `` where **`nmmsYear` is the hard-coded literal string `"2025"`** passed at the call site (`drawReportHeader(doc, true, "2025")`, customListController.js:118) — not derived from any list/year data — plus static address/contact lines, then a horizontal rule.
- Below header: list name as a big blue (`#0000FF`) centered title (`.toUpperCase()`).
- Table columns are **dynamic**, built from the `fields` array (from `getStudentsByListId`): always prepends `ID` (`student_id`, width 50) and `Name` (`student_name`, width 150) if those fields are present (checked via `fields.some(f=>f.col_name===...)`), then one column per remaining field (`label: f.display_name, property: f.col_name, width:100`).
- Special-cased cell value mapping when rendering rows (applies for both PDF and XLSX, duplicated logic): `batch_id → s.batch_name`, `current_institute_dise_code → s.current_institute_name`, `previous_institute_dise_code → s.previous_institute_name`, `district`/`district_id → s.district`, `nmms_block`/`block_id → s.block`; all other fields read `s[f.col_name]` directly. Missing/null values render as the literal string `'-'`.
- Table rendered via `doc.table(table, {...})` with light-gray (`#cccccc`) vertical divider lines drawn per cell (`prepareRow` callback), header row bold `Times-Bold` size 10, body `Times-Roman` size 9.
- Headers: `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="<listName>.pdf"`. Streamed via `doc.pipe(res)` then `doc.end()` — no disk write.

### XLSX #1 — `downloadListXLS` (customListController.js:179-231)
- Library: `exceljs` (`ExcelJS.Workbook`), sheet name `'Student List'`.
- Columns: same dynamic ID/Name-first-then-fields logic as PDF (`header: 'Student ID'/'Student Name'` vs field's `display_name`), widths 15/30/25 respectively.
- Same special-case value mapping as PDF for `batch_id`, `current/previous_institute_dise_code`, `district`/`district_id`, `nmms_block`/`block_id`.
- Header row 1 set bold. No cell coloring/validation (unlike `download_excel` below).
- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="<listName>.xlsx"`, written directly to `res` via `workbook.xlsx.write(res)` — no disk write.

### XLSX #2 — `downloadStudentExcel` (evaluationController.js:45-283, route `POST /download_excel`)
- Library: `exceljs`, sheet `'Students'`.
- **Fixed 34-column layout** (not dynamic, unlike the custom-list exporters) in 4 color groups:
  - Group 1 (fill `FFFFCC`): Applicant ID, Student Name, Father Name, Mother Name, Village, `Gender(M,F)`, Aadhaar, Date of Birth, Medium, Home Address, Family Income.
  - Group 2 (fill `CCFFCC`): Father/Mother Occupation, Father/Mother Education, Household Size, `Own House(Y,N)`, `Smart Phone at Home(Y,N)`, `Internet Facility at Home(Y,N)`, Career Goals, Subjects of Interest, Transportation Mode, Distance to School, Number of Two/Four Wheelers, Irrigation Land, Neighbor Name/Phone, Favorite Teacher Name/Phone.
  - Group 3 (fill `FFCCCC`): `Exam Appeared Y/N`.
  - Group 4 (fill `CCFFFF`): Exam Score, `Exam cleared Y/N`, Interview Required.
- Header row: bold Calibri 11, black text, solid fill per group, center/middle aligned, `wrapText:true`.
- Data rows: Calibri 10, `wrapText:true`; column 8 (DOB) gets `numFmt:'dd-mm-yyyy'` + centered (value passed through `formatDateForExcel` — handles Excel serial numbers via epoch `new Date(1899,11,30)`, JS `Date` objects, and parseable strings, all normalized to `YYYY-MM-DD` before being handed to the cell, then displayed per the `numFmt`); column 11 (Family Income) `numFmt:'₹#,##0.00'` + right-aligned; numeric columns `[32,23,24,25,26]` (Exam Score, Distance, Two/Four Wheelers, Irrigation) `numFmt:'0.00'` + right-aligned; Y/N columns `[6,17,18,19,31,33,34]` centered.
- Data validation (dropdowns) added post-hoc by iterating columns: column 6 (Gender) → list `"M,F"`; columns `[17,18,19,31,33,34]` → list `"Y,N"` (both `allowBlank:true`, applied to every row `>1` regardless of whether a row exists there yet — ExcelJS applies validation to the column's existing cell range as populated).
- Column 8 (DOB) gets a `.note` (`"Double click for calendar or enter date as DD-MM-YYYY"`) on every data row.
- Auto-fit pass at the end recomputes each column's width from `Math.min(Math.max(...cell text lengths)+2, 50)` — **this runs after and overrides the explicit `width` values set in the column definitions**, so the initial per-column widths above are effectively provisional/discarded.
- Headers: `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename=students_<exam_name with [^a-z0-9]→_>.xlsx` (case-insensitive regex, filename **not quoted** unlike the custom-list exporters which quote the filename). Written via `workbook.xlsx.write(res)` — no disk write.

### Multipart bulk upload — `POST /bulk-upload` (`upload.single('excelFile')` + `uploadBulkData`, evaluationController.js:318-500)
- **Multer disk storage** (unlike every other file endpoint in this module, which are stateless): `destination` = `path.join(process.env.FILE_STORAGE_PATH, "Admission", "Evaluation")`, created recursively if missing (`fs.mkdirSync(..., {recursive:true})`); **throws `Error("FILE_STORAGE_PATH not set")` (passed to multer's callback, not caught specially) if that env var is unset.** `filename` = `` `${file.fieldname}-${Date.now()}${ext}` `` (i.e. `excelFile-<epoch-ms>.xlsx`) — **the uploaded file is left on disk permanently; nothing in `uploadBulkData` ever deletes it after processing**, success or failure.
- `limits.fileSize`: 10 MB. `fileFilter`: accepts only mimetypes `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` or `application/vnd.ms-excel`, else rejects with `Error("Only Excel files are allowed")` (surfaces as a Multer/generic error, not an `ApiError`).
- Parse: `ExcelJS.Workbook().xlsx.readFile(req.file.path)` (reads back off disk, not from the in-memory buffer), then `workbook.getWorksheet('Students')` — **if the uploaded file's sheet isn't literally named `Students`, `worksheet` is `undefined` and the subsequent `worksheet.eachRow(...)` throws a raw `TypeError`**, caught by the outer `catch` → `res.status(500).send(error.message)`.
- Row loop: `worksheet.eachRow({includeEmpty:false}, ...)`, skips row 1 (header). Per row, columns read by **fixed 1-based index** matching the `downloadStudentExcel` export layout exactly (1 Applicant ID, 2 Student Name, 3 Father Name, 4 Mother Name, 5 Village, 6 Gender, 7 Aadhaar, 8 DOB, 9 Medium, 10 Home Address, 11 Family Income, 12 Father Occupation, 13 Mother Occupation, 14 Father Education, 15 Mother Education, 16 Household Size, 17 Own House, 18 Smart Phone, 19 Internet Facility, 20 Career Goals, 21 Subjects of Interest, 22 Transportation Mode, 23 Distance to School, 24 Two Wheelers, 25 Four Wheelers, 26 Irrigation Land, 27 Neighbor Name, 28 Neighbor Phone, 29 Favorite Teacher Name, 30 Favorite Teacher Phone, 31 Exam Appeared Y/N, 32 Exam Score, 33 Exam Cleared Y/N, 34 Interview Required).
- Per-row validation: `applicant_id` must `Number.isFinite(parseInt(...))` else `throw Error('Invalid Applicant ID')`; `student_name` (col 2) required via `safeString` else `throw Error('Student Name missing')`. Any thrown error for a row is captured into `errors[]` as `{row: rowNumber, error: err.message}` — **row processing continues for subsequent rows even after an error** (try/catch is per-row, not a hard stop).
- Helper coercions: `safeString` (trim, null on empty/missing), `safeInt`/`safeFloat` (parse, default `0` — **`0`, not `null`**, when missing or NaN), `safeYN` (upper-trim, must be exactly `'Y'` or `'N'` else default `'N'`), `safeDate` (handles `Date` objects, Excel serial numbers via 1899-12-30 epoch, `YYYY-MM-DD` strings passed through, `DD-MM-YYYY`/`DD/MM/YYYY` reordered to `YYYY-MM-DD`, anything else → `null`).
- **If `errors.length > 0` after the full row scan, the entire request returns `400 {errors:[...]}` and `insertBulkData` is never called — no partial DB writes for row-level validation failures** (this is an all-or-nothing gate purely in application code, before any SQL runs).
- Eligibility rule for `student_master` insertion (mirrors the exam-results "eligible" cohort): row included in `eligibleStudents` iff `pp_exam_cleared === 'Y' && interview_required_yn === 'N'` (both post-`safeYN` normalized).
- On success: `insertBulkData(...)` result wrapped `{message:'Bulk upload successful', result:{...counts}}`, `200`.
- On any error from `insertBulkData` (including the schema-mismatch issues in §3/§7): outer `catch` → `console.error(error)` then `res.status(500).send(error.message)` — **plain text, not JSON**, unlike the row-validation 400 path.

## 6. Transactions

| Function | Style |
|---|---|
| `customListModel.saveListFull` | `pool.connect()` → `BEGIN` ... `COMMIT` / `ROLLBACK` on error, `finally client.release()`. Multi-statement create-or-replace. |
| `customListModel.getAllLists/getAvailableFields/getAllBatches/getStudentsByListId/getStudentsByCohort/getListHeader/deleteList` | plain `pool.query`, autocommit, no explicit transaction. |
| `customListController.downloadListPDF/downloadListXLS` | read-only, no transaction (calls the above autocommit model functions). |
| `evaluationModels.getExamNames/getStudents` | plain `pool.query`, autocommit. |
| `evaluationModels.insertBulkData` | `pool.connect()` → `BEGIN` ... `COMMIT` / `ROLLBACK` on error, `finally client.release()`. Wraps: enrollment-id generator query, N `applicant_primary_info` UPDATEs (one per row, sequential `for...of`, not batched), N `applicant_secondary_info` upserts, N `exam_results` upserts, N `applicant_exam_attendance` upserts, sequence realignment (`setval`), then per-eligible-student existence check + `student_master` INSERT. **Entire multi-hundred-row loop is one transaction** — a failure on row 500 rolls back rows 1-499 too (all-or-nothing), and given the ON-CONFLICT/sequence issues in §3, this transaction is expected to fail deterministically against the live schema as dumped. |
| `evaluationDashboardModel.*` (all 3 functions) | plain `pool.query` calls in sequence (or a `for` loop over query definitions), autocommit, no explicit transaction — read-only aggregation endpoints. |
| `evaluationController.downloadStudentExcel/fetchExamNames/fetchStudents` | read-only, autocommit. |

## 7. Quirks & Complexity Warnings

1. **Duplicate mount: `/api/evaluation/*` (custom-list portion) === `/api/custom-list/*`.** `evaluationRoutes.js:1-15` is a verbatim copy of `customListRoutes.js:1-15`, both wired to `customListController`/`customListModel`. Two live base paths for identical behavior — decide whether Java exposes both or consolidates (breaking change for whichever frontend caller isn't updated).
2. **Duplicate route registration, `evaluationRoutes.js:20-21`:** `router.get("/exam_names", fetchExamNames)` then `router.get("/exam_names", fetchStudents)`. Express executes the **first** matching handler only when it terminates the response without calling `next()`; `fetchExamNames` (wrapped in `asyncHandler`) always terminates (either `res.json(...)` or throws → routed to error middleware). **`fetchStudents` on this path is permanently dead code** — verify in Java tests that only exam-names-by-year semantics are ported for `GET /exam_names`, and do not accidentally wire `fetchStudents`' student-listing behavior onto this path.
3. **No app-wide error-handling middleware is registered.** `server/middleware/errorHandler.js` defines an `(err,req,res,next)` handler but **it is never `app.use()`'d in `server/index.js`** (confirmed via full-file grep — no reference anywhere outside its own file). Consequently, any `ApiError` thrown inside `fetchExamNames`, `fetchStudents`, or `downloadStudentExcel` (all wrapped in `asyncHandler`, which does `.catch(err => next(err))`) falls through to **Express's built-in default error handler** — not JSON `{success:false,...}` as the `ApiError`/`ApiResponse` classes might imply, but Express's default HTML (or plain-text, depending on `Accept` header and `NODE_ENV`) error page with status `err.statusCode || 500` (Express's default handler reads `err.status`/`err.statusCode`, which `ApiError` does set, so the status code is honored, but the body shape is not the custom envelope). Java parity should decide explicitly whether to reproduce "no centralized handler" behavior (probably not desirable) or standardize — but document that **today's actual client-observed error body for these 3 endpoints is Express's default, not a JSON envelope**.
4. **`insertBulkData` targets tables/objects that don't exist as required in the live schema:** `pp.exam_results` and `pp.applicant_exam_attendance` have **no PK/UNIQUE on `applicant_id`**, yet both get `ON CONFLICT (applicant_id) DO UPDATE` upserts — will raise Postgres error `42P10` against schema as dumped. Separately, `pp.enr_id_seq` (used via `setval`/`nextval`) **does not exist** in `live-schema.sql` — will raise `42P01`. Net effect: as captured, **the bulk-upload's DB-write phase (`insertBulkData`) cannot succeed end-to-end against this schema dump** unless those objects were created out-of-band in the live DB (not visible to us). Flag this explicitly to product/QA before porting — decide whether Java's schema/migration should add the missing constraints+sequence, or whether the live production DB already has them (dump may be stale/partial).
5. **`getStudents` join-chain bug** (evaluationModels.js:58-68): `exam_results` (`er`) and `applicant_exam_attendance` (`aea`) are LEFT-JOINed on `asi.applicant_id` (`applicant_secondary_info`'s applicant_id) rather than `api.applicant_id` (`applicant_primary_info`). Any applicant with exam results/attendance but no secondary-info row yet will show `NULL` for those exam columns even though matching rows exist — reproduce verbatim; this feeds both `GET /exam_names`-adjacent flows (dead `fetchStudents`) and the live `POST /download_excel` export.
6. **`fetchStudents`' `ApiResponse` argument order is swapped** (`new ApiResponse(200,"ok",StudentNames)` — `data="ok"`, `message=<array>`), vs. `fetchExamNames`'s correct usage. Only matters if this dead code is ever exercised; document but don't need runtime parity since unreachable.
7. **Hard-coded year `2025`** in `customListModel.getStudentsByCohort` (`api.nmms_year = 2025`, literal in the SQL string, not parameterized) — every "students by cohort" call is implicitly scoped to NMMS year 2025 regardless of any year context in the request. Confirm with product whether this needs to become dynamic in the Java port or should stay pinned.
8. **`divisionId` parameter silently ignored** in `getStudentsByCohort` — accepted in the function signature and destructured from `req.query` in the controller, but never applied as a filter (`addFilter` is never called for it). No error, just a no-op filter.
9. **`getAvailableFields` is schema-introspecting** (`information_schema.columns`), not a static list — the field picker UI's available options track the live `pp.student_master`/`pp.applicant_primary_info` columns automatically (minus an exclusion list). A literal Java port would need either a live JDBC metadata query against the same tables/schema or an explicitly maintained mirror list — pick one and document the tradeoff (schema drift risk vs. code simplicity).
10. **`saveListFull`'s "dynamic list save" flow** (customListModel.js:3-65) — full sequence: (a) normalize `listId` (`"undefined"` string or falsy → `null`); (b) if no id, `INSERT INTO custom_list(list_name)` and capture new `list_id`; else `UPDATE custom_list SET list_name=...` **then unconditionally `DELETE FROM custom_list_fields WHERE list_id=$1`** and **`DELETE FROM custom_list_students WHERE list_id=$1`** (full replace-not-merge semantics — any field/student not resubmitted is dropped); (c) for each `selectedFields` entry, look up `field_master` by `col_name`, insert into `field_master` (fixed `tab_name='pp.student_master'`) if missing, then insert a `custom_list_fields` link row — **note this means a field's `field_master` row is shared/reused across all lists** (keyed by `col_name` only, no per-list scoping) while `custom_list_fields` is the per-list junction; (d) for each `studentIds` entry, skip falsy or the literal string `"undefined"`, else insert a `custom_list_students` row — **no de-dup check**, so resubmitting the same `student_id` twice in one call inserts it twice unless the DB PK `(list_id, student_id)` composite rejects the duplicate (it does — `custom_list_students_pkey` is `(list_id, student_id)` — so a genuine duplicate within one save call will throw a unique-violation and roll back the whole transaction, not silently dedupe).
11. **PDF header only ever renders on page 1** — `drawReportHeader` is called exactly once per `downloadListPDF` invocation (with `isFirstPage=true`), but the code contains dead logic (`LOGO_SIZE`/`MAIN_TITLE_FONT_SIZE` conditioned on `isFirstPage`) implying an original intent to re-draw a smaller header on subsequent pages that was never wired to `pdfkit-table`'s automatic pagination — long lists' overflow pages have a bare table continuation with no header/branding.
12. **`downloadListPDF`/`downloadListXLS` hard-code `nmmsYear="2025"`** at the PDF header call site only (`customListController.js:118`) — the XLSX exporter (`downloadListXLS`) has no year branding at all, so the two "same list" exports are visually inconsistent (PDF says "PRATIBHA POSHAK - 2025" always; XLSX has no such text anywhere).
13. **Filename quoting inconsistency across the three Excel/PDF export endpoints:** `download-pdf`/`download-xlsx` (custom-list) quote the filename (`filename="X.pdf"`); `download_excel` (evaluation, fixed-layout export) does **not** quote it (`filename=students_X.xlsx`) — cosmetic but affects filenames containing spaces/special characters when downloaded by some browsers/HTTP clients.
14. **Frontend calls a nonexistent route:** `client/src/hooks/EvalutionHooks.js:40` POSTs to `/api/evaluation/exam_query`, which has zero matching route in `evaluationRoutes.js` (only `/exam_names` GET is defined) — this call always 404s in the current app. Do not port a working handler for `/exam_query` under the assumption it's supposed to exist and work; it's already broken in Node.
15. **`bulk-upload` writes uploaded files permanently to disk** at `${FILE_STORAGE_PATH}/Admission/Evaluation/excelFile-<epoch>.xlsx` with no cleanup — contrast with every other file-producing endpoint in this module (PDF/XLSX generation), which are fully stateless/in-memory. If `FILE_STORAGE_PATH` env var is unset, multer's `destination` callback errors immediately (before any row parsing), surfacing as a Multer error.
16. **Dashboard `'Completed'` vs `'COMPLETED'` case mismatch** — `getJurisdictionStatus`'s `completedInterview` count filters `si.status = 'Completed'`, but `pp.student_interview.status`'s CHECK constraint only permits upper-case `'COMPLETED'` (among others) — this metric is **always 0** against real data. Reproduce the literal (broken) filter unless told to fix it.
17. **Inconsistent "Shortlisted" definition across dashboard endpoints:** `getOverallCounts`'s `"Shortlisted"` filters `a.shortlisted_yn='Y'`; `getOverallProgress`'s `totalReq` (same underlying table `applicant_shortlist_info`) does **not** filter on `shortlisted_yn` at all — two different denominators for conceptually related "how many students are shortlisted" numbers. Port each literally per its own query.
18. **`:year` path param, not query string,** on all 3 dashboard routes — a non-numeric segment (`/overall/abc`) parses to `NaN` via `parseInt(req.params.year,10)` and is bound as a query parameter, causing a Postgres `22P02 invalid input syntax` → caught → `500 {error:"..."}`. There's no input validation before the DB call.
