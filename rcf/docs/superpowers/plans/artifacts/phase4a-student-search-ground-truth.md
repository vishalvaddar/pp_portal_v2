# STUDENT PORTAL + STUDENT SEARCH + SEARCH — Ground Truth (for Plan 4a)

Captured from a full read of the live Node source. Three independent route files, three mounts in `server/index.js`:

| Mount order | Line | Mount | Router file |
|---|---|---|---|
| 1 | `server/index.js:287` | `app.use("/api/student", studentRoutes)` | `server/routes/studentRoutes.js` |
| 2 | `server/index.js:293` | `app.use("/api", studentSearchRoutes)` | `server/routes/studentSearchRoutes.js` |
| 3 | `server/index.js:320` | `app.use("/api", searchRoutes)` | `server/routes/searchRoutes.js` |

Controllers: `server/controllers/coordinator/studentController.js` (student self-service), `server/controllers/studentSearchController.js` (admin student search), `server/controllers/searchController.js` (applicant/NMMS search + cohort/batch dropdowns).
Models: `server/models/coordinator/studentModel.js`, `server/models/studentSearchModel.js`, `server/models/searchModel.js`.

`studentRoutes.js` is a live CommonJS block at lines 36-96; **lines 1-31 are a fully commented-out earlier version of the same router** — ignore. `studentController.js` and `studentModel.js` similarly have long commented-out prior drafts above the live code (studentController.js live block starts line 188 of 470; studentModel.js live block starts line 420 of 956) — ignore all `//`-prefixed code.

## 1. Endpoint Inventory (15 routes, 3 files)

| # | Method | Path (full) | Auth | Handler | Purpose |
|---|--------|------|------|---------|---------|
| 1 | GET | `/api/student/` | none | inline `(req,res)=>res.send("Student API Working")` | health-check text, not JSON |
| 2 | GET | `/api/student/profile` | `authenticate` | `getStudentProfile` | logged-in student's own profile (by `req.user.user_id`) |
| 3 | GET | `/api/student/timetable` | `authenticate` | `getMySchedule` | logged-in student's weekly timetable |
| 4 | GET | `/api/student/performance` | `authenticate` | `getStudentSubjectPerformance` | **alias of #6** — same handler as `/subjects` |
| 5 | GET | `/api/student/summary` | `authenticate` | `getStudentSummary` | overall attendance % + exam score |
| 6 | GET | `/api/student/subjects` | `authenticate` | `getStudentSubjectPerformance` | per-subject attendance % |
| 7 | GET | `/api/student/monthly` | `authenticate` | `getStudentMonthlyAttendance` | attendance % grouped by month |
| 8 | GET | `/api/student/weekly` | `authenticate` | `getStudentWeeklyAttendance` | attendance % grouped by ISO week |
| 9 | GET | `/api/student/custom` | `authenticate` | `getStudentCustomAttendance` | attendance % per subject in a date range (`fromDate`,`toDate` query) |
| 10 | GET | `/api/student/:id/inactive-history` | `authenticate` | `getInactiveHistoryController` | inactive-reason log rows for a `student_id` |
| 11 | GET | `/api/search-students` | **none** | `studentSearchController` | admin paginated student search (batch/cohort/name/enr/gender/location/special-condition filters) |
| 12 | GET | `/api/student/:student_id` | **none** | `getStudentById` | admin lookup — full `student_master` row by id, `SELECT *` |
| 13 | GET | `/api/search` | **none** | `SearchController.search` | applicant/NMMS search over `applicant_primary_info` |
| 14 | GET | `/api/cohorts` | **none** | `SearchController.getCohorts` | all cohorts (`SELECT * FROM pp.cohort`) |
| 15 | GET | `/api/batches/cohort/:cohortNumber` | **none** | `SearchController.getBatches` | batches for a cohort (`SELECT * FROM pp.batch WHERE cohort_number=$1`) |

**Route-ordering hazards:**
- `studentRoutes` (path #1-10) is mounted at `/api/student` **before** `studentSearchRoutes` (path #12, also under `/api/student/:student_id`) is mounted at bare `/api`. Because `studentRoutes` has no bare `/:id` route (only `/:id/inactive-history` and named literals `profile`,`timetable`,`performance`,`summary`,`subjects`,`monthly`,`weekly`,`custom`), a request like `GET /api/student/42` falls through `studentRoutes` (no match) and is correctly picked up by `studentSearchRoutes`'s `/student/:student_id`. Verified no accidental collision, but it is fragile — adding a bare `/:id` route to `studentRoutes.js` in the future would silently shadow endpoint #12 for every numeric-looking segment.
- `/api/batches/cohort/:cohortNumber` (endpoint #15, from `searchRoutes`, mounted last at line 320) does **not** collide with `batchRoutes` (mounted at `/api/batches`, line 305, earlier) despite the shared `/api/batches` prefix — `batchRoutes` has no `"/cohort/:x"` two-segment pattern that matches (`/:cohort_number/batches` requires the literal segment `batches` second, not a numeric id). Confirmed no shadowing, but this is two different route files answering under the same `/api/batches/*` prefix — a Java router must replicate the same disjoint-path property, not just merge them into one controller.
- **Duplicate route alias:** `/api/student/performance` and `/api/student/subjects` (endpoints #4, #6) are wired to the exact same controller function `getStudentSubjectPerformance`. `/performance` was added later as "fix your error" (see comment at studentRoutes.js:69) — keep both paths mapped to one Java handler, don't treat as two features.
- **Dead imports, no route:** `studentController.js` exports `getStudentsController`, `updateStudentController`, `markInactiveController` (and model exports `getStudentsByCohortAndBatch`, `getActiveStudentsForAttendance`) — these are **not wired into `studentRoutes.js`** at all. They are consumed by `coordinatorRoutes.js` / `teacherStudentRoutes.js` instead (out of scope for this phase; do not port them here).
- **No auth on admin search endpoints:** endpoints #11-15 have zero `authenticate` middleware — any caller can hit them. This mirrors Node's actual (likely unintentional) behavior; flag for a decision in the Java port (add auth vs. preserve parity).
- Root health-check (`GET /api/student/`) returns plain text `"Student API Working"`, not JSON — inconsistent with every other endpoint in this module; note it if porting index/health routes.

## 2. Exact SQL (verbatim, per query)

### 2a. `studentModel.js` (coordinator self-service — used by endpoints #2,#3,#5-#9)

`STUDENT_SELECT` shared column list (used by profile query):
```sql
sm.student_id, sm.applicant_id, sm.enr_id, sm.student_name, sm.gender,
sm.father_name, sm.father_occupation, sm.mother_name, sm.mother_occupation,
sm.student_email, sm.student_email_password, sm.parent_email,
sm.contact_no1, sm.contact_no2, sm.home_address,
/* keep DISE codes for edit */
sm.current_institute_dise_code, sm.previous_institute_dise_code,
/* institute names for display */
ci.institute_name AS current_institute, pi.institute_name AS previous_institute,
sm.sim_name, sm.teacher_name, sm.teacher_mobile_number,
sm.active_yn, sm.recharge_status, sm.sponsor, sm.photo_link,
sm.batch_id, b.batch_name, c.cohort_number, c.cohort_name,
ins.inactive_reason, sm.created_at, sm.updated_at
```

**`getStudentProfileByUserId(user_id)`** — endpoint #2, also used internally by #3:
```sql
SELECT <STUDENT_SELECT>
FROM pp.student_master sm
JOIN pp.batch b ON sm.batch_id = b.batch_id
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
LEFT JOIN pp.inactive_students ins
  ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
WHERE sm.user_id = $1
LIMIT 1;
```
Param: `[user_id]` (from `req.user.user_id`, JWT). Note: joins to `batch`/`cohort` are **INNER JOIN** — a student with `batch_id IS NULL` gets no profile row (silent empty result, not an error).

**`getStudentTimetableModel(batchId)`** — endpoint #3:
```sql
SELECT
    tt.timetable_id, tt.day_of_week, tt.start_time, tt.end_time,
    c.classroom_name, c.class_link,
    s.subject_name, t.teacher_name, p.platform_name
FROM pp.timetable tt
JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
WHERE cb.batch_id = $1
ORDER BY
    CASE tt.day_of_week
        WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3
        WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6
        WHEN 'SATURDAY' THEN 7
    END,
    tt.start_time ASC;
```
Param: `[batchId]` (from profile lookup, not directly from the request). Note: `timetable` joins `classroom` **not filtered by `active_yn`** — inactive classrooms still show. Also joins `classroom_batch` many-to-many without deduplication — if a classroom is linked to the batch more than once (shouldn't happen given the composite PK, but a `classroom` linked to two batches is fine and irrelevant here since filtered by `batch_id`).

**`getStudentSummaryModel(user_id)`** — endpoint #5, **two separate queries, no transaction**:
```sql
-- attendance aggregate
SELECT
  COUNT(cs.session_id) AS total_classes,
  COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_classes,
  ROUND(
    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
    / NULLIF(COUNT(cs.session_id),0) * 100
  ,2) AS attendance_percent
FROM pp.student_master sm
JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
LEFT JOIN pp.student_attendance sa
  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
WHERE sm.user_id = $1
```
```sql
-- exam score, separate round-trip
SELECT er.pp_exam_score
FROM pp.exam_results er
JOIN pp.student_master sm ON sm.applicant_id = er.applicant_id
WHERE sm.user_id = $1
```
Result merge in JS: `{ ...rows[0], exam_score: examRes.rows[0]?.pp_exam_score || "-" }`. **Quirk:** if the attendance query returns zero rows (e.g. `sm.user_id` matches no student, or student has no batch), `rows[0]` is `undefined` and the spread `{...undefined}` yields `{}` — response becomes `{ exam_score: "-" }` with **no error, no 404**, and none of `total_classes`/`attended_classes`/`attendance_percent` keys present at all (not even as `null`). Also note: because of the `JOIN`s (not `LEFT JOIN`) from `student_master`→`classroom_batch`→`classroom`→`class_session`, `COUNT(cs.session_id)` always returns exactly one row (COUNT over zero matches is still one row of `0`) *unless* `sm.user_id=$1` matches zero `student_master` rows, in which case zero rows come back — that's the only path to the `{}` case above.

**`getStudentSubjectPerformanceModel(user_id)`** — endpoint #6/#4 (alias):
```sql
SELECT
  subj.subject_name,
  COUNT(cs.session_id) AS total_classes,
  COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_classes,
  ROUND(
    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
    / NULLIF(COUNT(cs.session_id),0) * 100
  ,2) AS attendance_percent
FROM pp.student_master sm
JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
JOIN pp.subject subj ON subj.subject_id = c.subject_id
LEFT JOIN pp.student_attendance sa
  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
WHERE sm.user_id = $1
GROUP BY subj.subject_name
ORDER BY subj.subject_name;
```
Note: `JOIN pp.subject` (inner) — classrooms with `subject_id IS NULL` are silently dropped from subject-level breakdowns (unlike the timetable query, which `LEFT JOIN`s subject).

**`getStudentMonthlyAttendanceModel(user_id)`** — endpoint #7:
```sql
SELECT
  TO_CHAR(cs.session_date, 'YYYY-MM') AS month,
  ROUND(
    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
    / COUNT(cs.session_id) * 100
  ,2) AS percent
FROM pp.student_master sm
JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
LEFT JOIN pp.student_attendance sa
  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
WHERE sm.user_id = $1
GROUP BY month
ORDER BY month;
```
**Quirk vs. summary/subject queries:** no `NULLIF(..., 0)` guard here — if a month group's `COUNT(cs.session_id)` were ever 0 this would divide by zero, but `GROUP BY month` derived from `cs.session_date` guarantees `COUNT(cs.session_id) >= 1` per group, so it never actually fires (safe in practice, but the missing `NULLIF` is an inconsistency to preserve or fix deliberately).

**`getStudentWeeklyAttendanceModel(user_id)`** — endpoint #8, identical shape keyed by week:
```sql
SELECT
  TO_CHAR(DATE_TRUNC('week', cs.session_date), 'YYYY-MM-DD') AS week_start,
  ROUND(
    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
    / COUNT(cs.session_id) * 100
  ,2) AS percent
FROM pp.student_master sm
JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
LEFT JOIN pp.student_attendance sa
  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
WHERE sm.user_id = $1
GROUP BY week_start
ORDER BY week_start;
```
Same no-`NULLIF` note as monthly.

**`getStudentCustomAttendanceModel(user_id, fromDate, toDate)`** — endpoint #9:
```sql
SELECT
  subj.subject_name,
  COUNT(cs.session_id) AS total_classes,
  COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_classes,
  ROUND(
    COUNT(sa.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE'))::numeric
    / NULLIF(COUNT(cs.session_id),0) * 100
  ,2) AS attendance_percent
FROM pp.student_master sm
JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
JOIN pp.subject subj ON subj.subject_id = c.subject_id
LEFT JOIN pp.student_attendance sa
  ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
WHERE sm.user_id = $1
  AND cs.session_date BETWEEN $2 AND $3
GROUP BY subj.subject_name
ORDER BY subj.subject_name;
```
Params: `[user_id, fromDate, toDate]` — `fromDate`/`toDate` are **raw request query strings passed straight into `BETWEEN`** with no format validation in the model (controller only checks `!fromDate || !toDate` truthiness, not date-shape). An invalid date string here would surface as a raw Postgres error → 500.

**`getInactiveHistoryByStudentId(student_id)`** — endpoint #10:
```sql
SELECT inactive_reason, inactive_date, created_by, updated_by
FROM pp.inactive_students
WHERE student_id = $1
ORDER BY inactive_date DESC;
```
Param: `[student_id]` = `req.params.id` (raw string, not cast — relies on implicit PG coercion against `numeric(14,0)` column; a non-numeric `:id` segment throws a PG type-conversion error → uncaught in try/catch as a generic 500).

### 2b. `studentSearchModel.js` (admin search — endpoints #11, #12)

**`searchStudents(filters)`** — endpoint #11, dynamic WHERE built by appending to arrays, fully parameterized:
```js
// filters destructured: batch_id, cohort_number, name, enr_id, gender,
// state_id, district_id, block_id, spl_health_cond, spl_family_cond, limit=50, offset=0
```
```sql
-- data query (whereClause assembled conditionally, see below)
SELECT
  sm.student_id, sm.student_name, sm.enr_id, sm.gender,
  b.batch_name, c.cohort_name,
  api.nmms_year, api.nmms_reg_number,
  j_state.juris_name AS state, j_dist.juris_name AS district,
  COALESCE(asi.spl_health_cond, 'N') AS spl_health_cond,
  COALESCE(asi.spl_family_cond, 'N') AS spl_family_cond
FROM pp.student_master sm
JOIN pp.batch b ON sm.batch_id = b.batch_id
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
LEFT JOIN pp.jurisdiction j_state ON api.app_state = j_state.juris_code
LEFT JOIN pp.jurisdiction j_dist ON api.district = j_dist.juris_code
WHERE 1=1
  [AND sm.batch_id = $n]                                  -- if batch_id
  [AND c.cohort_number = $n]                               -- if cohort_number
  [AND sm.student_name ILIKE $n]  -- '%name%'               -- if name
  [AND CAST(sm.enr_id AS TEXT) ILIKE $n]  -- '%enr_id%'      -- if enr_id
  [AND UPPER(sm.gender) = $n]  -- uppercased                -- if gender
  [AND api.app_state = $n]                                  -- if state_id
  [AND api.district = $n]                                   -- if district_id
  [AND api.nmms_block = $n]                                 -- if block_id
  [AND COALESCE(asi.spl_health_cond, 'N') = $n]              -- if spl_health_cond
  [AND COALESCE(asi.spl_family_cond, 'N') = $n]              -- if spl_family_cond
ORDER BY sm.student_name ASC
LIMIT $n OFFSET $n
```
```sql
-- count query, same joins minus the jurisdiction LEFT JOINs, same WHERE clause, same values (no limit/offset)
SELECT COUNT(*) AS total
FROM pp.student_master sm
JOIN pp.batch b ON sm.batch_id = b.batch_id
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
WHERE 1=1 [same AND clauses]
```
Numeric coercions in JS before binding: `batch_id`→`Number()`, `cohort_number`→`Number()`, `state_id`/`district_id`/`block_id`→`Number()`; `limit` clamped `Math.min(Math.max(Number(limit)||50, 1), 100)`, `offset` clamped `Math.max(Number(offset)||0, 0)`. `gender` uppercased in JS before bind. All values fully parameterized ($n placeholders) — **no SQL injection risk**, filters are just conditionally appended, not string-interpolated.
**Quirk:** `JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id` is an **INNER JOIN** — a `student_master` row with `applicant_id IS NULL` (or pointing to a non-existent applicant) is silently excluded from search results entirely, even with no filters applied.

**`getStudentById(student_id)`** — endpoint #12:
```sql
SELECT * FROM pp.student_master WHERE student_id = $1
```
Param: `[Number(student_id)]`. `SELECT *` returns every `student_master` column verbatim including `student_email_password` (plaintext-looking column name) — flag for the Java DTO: do not blindly project `SELECT *` into a response object without deciding whether to redact `student_email_password`.

### 2c. `searchModel.js` (applicant/NMMS search — endpoints #13-15)

**`searchStudents(filters, pagination, sorting)`** — endpoint #13, **dynamic ORDER BY column and LIMIT/OFFSET position computed via string math**:
```sql
-- shared base
FROM pp.applicant_primary_info a
LEFT JOIN pp.institute i ON a.current_institute_dise_code = i.dise_code
LEFT JOIN pp.jurisdiction js ON a.app_state = js.juris_code
LEFT JOIN pp.jurisdiction jd ON a.district = jd.juris_code
LEFT JOIN pp.jurisdiction jb ON a.nmms_block = jb.juris_code
WHERE 1=1
  -- if nmms_reg_number present: ONLY this filter is applied (all others ignored)
  [AND a.nmms_reg_number = $n]
  -- else, all of:
  [AND a.student_name ILIKE $n]   -- '%student_name%'
  [AND a.nmms_year = $n]           -- parseInt(nmms_year)
  [AND UPPER(a.medium) = $n]       -- uppercased
  [AND a.app_state = $n]
  [AND a.district = $n]
  [AND a.nmms_block = $n]
  [AND a.current_institute_dise_code = $n]
```
```sql
-- count
SELECT COUNT(*) <base+whereClause>
```
```sql
-- data (only run if count > 0)
SELECT a.*, i.institute_name,
  js.juris_name AS state_name, jd.juris_name AS district_name, jb.juris_name AS block_name
<base+whereClause>
ORDER BY a.${sortBy} ${sortOrder}
LIMIT $${dataValues.length - 1} OFFSET $${dataValues.length}
```
**`sortBy`/`sortOrder` are directly string-interpolated into `ORDER BY`, not parameterized** — but the controller (`searchController.js:29-50`) whitelists `sortBy` against a fixed array (`applicant_id, student_name, nmms_year, nmms_reg_number, medium, district, nmms_block, app_state, current_institute_dise_code, spl_health_cond, spl_family_cond`) before it reaches the model, defaulting to `applicant_id` if not in the list, and `sortOrder` is coerced to exactly `"ASC"` or `"DESC"`. **Not currently exploitable given the controller gate, but the model itself has no defense** — for Java, enforce the whitelist as a real `enum` at the point the value enters the query builder, not just in the controller layer (mirrors the `type` dynamic-table risk called out in the Phase-2b merge doc). Note also: `spl_health_cond`/`spl_family_cond` are in the controller's sortable whitelist but `searchModel.searchStudents` never reads those two filter keys from `filters` at all (they're silently dropped, dead filter params) — only `sortableFields` include them, not `filters` destructuring.
Params for LIMIT/OFFSET: `dataValues = [...values, limit, offset]`; `LIMIT` binds to `dataValues.length-1`, `OFFSET` to `dataValues.length` — verified arithmetically correct positions.
Response shape: `a.*` means **every `applicant_primary_info` column** is returned raw (numeric `nmms_year`, `nmms_reg_number`, `app_state`, `district`, `nmms_block`, `gmat_score`, `sat_score` all as JS numbers via `pg`'s numeric parsing — verify Java driver behavior matches).

**`getAllCohorts()`** — endpoint #14:
```sql
SELECT * FROM pp.cohort ORDER BY cohort_number ASC
```

**`getBatchesByCohort(cohortNumber)`** — endpoint #15:
```sql
SELECT * FROM pp.batch WHERE cohort_number = $1 ORDER BY batch_id ASC
```
Param: `[cohortNumber]` = `req.params.cohortNumber`, **passed as raw string, no `Number()` cast** — relies on implicit PG coercion against `cohort_number integer` column (unlike endpoint #11's `cohort_number` which is `Number()`-cast in JS). A non-numeric path segment throws a PG error → uncaught 500.

## 3. Table DDL Facts (columns/types/PK/UNIQUE/FK/sequences touched)

| Table | PK | Notable UNIQUE | Notable FK | Sequence |
|---|---|---|---|---|
| `pp.student_master` | `student_id` (`student_master_pkey`) | `applicant_id` (`student_master_applicant_id_key`), `enr_id` (`student_master_enr_id_key`) | `applicant_id→applicant_primary_info`, `batch_id→batch`, `current/previous_institute_dise_code→institute(dise_code)` ON DELETE SET NULL, `user_id→"user"` | `pp.student_id_seq` |
| `pp.batch` | `batch_id` (`batch_pkey`) | — | `cohort_number` (no FK constraint found; logical link only) | `pp.batch_id_seq` |
| `pp.cohort` | `cohort_number` (`cohort_pkey`) | — | — | `pp.cohort_seq` |
| `pp.batch_coordinator_batches` | composite `(user_id, batch_id)` | — | `batch_id→batch`, `user_id→"user"` | none (junction table) |
| `pp.institute` | `institute_id` | — | — | `pp.institute_id_seq` |
| `pp.inactive_students` | **none declared** (no PK!) | — | `student_id→student_master`, `created_by/updated_by→"user"` | none |
| `pp.timetable` | `timetable_id` (`timetable_pkey`) | — | `classroom_id→classroom`, `created_by/updated_by→"user"` | `pp.timetable_id_seq` |
| `pp.classroom` | `classroom_id` (`classroom_pkey`) | — | `subject_id/teacher_id/platform_id→...` ON DELETE SET NULL | `pp.classroom_id_seq` |
| `pp.classroom_batch` | composite `(classroom_id, batch_id)` | — | both FKs ON DELETE CASCADE | none (junction) |
| `pp.subject` | `subject_id` (`subject_pkey`) | — | — | `pp.subject_id_seq` |
| `pp.teacher` | `teacher_id` (`teacher_pkey`) | — | `user_id→"user"` (no FK constraint listed but column present) | `pp.teacher_id_seq` |
| `pp.teaching_platform` | `platform_id` | `platform_name` (`teaching_platform_platform_name_key`) | — | `pp.platform_id_seq` |
| `pp.class_session` | `session_id` (`class_session_pkey`) | `(classroom_id, session_date, start_time, end_time)` | `classroom_id→classroom`, `teacher_id→teacher` SET NULL, `timetable_id→timetable` SET NULL | `pp.class_session_seq` |
| `pp.student_attendance` | `attendance_id` | `(session_id, student_id)` | `session_id→class_session` CASCADE, `student_id→student_master` CASCADE | `pp.attendance_id_seq` |
| `pp.exam_results` | **none declared** (no PK) | — | `applicant_id→applicant_primary_info` | none |
| `pp.applicant_primary_info` | `applicant_id` (`applicant_primary_info_pkey`) | `nmms_reg_number` (`applicant_primary_info_nmms_reg_number_key`, `numeric(11,0) NOT NULL`) | `app_state/district/nmms_block→jurisdiction`, `current/previous_institute_dise_code→institute` SET NULL | `pp.applicant_id_seq` |
| `pp.applicant_secondary_info` | `applicant_id` (`applicant_secondary_info_pkey`, also FK) | — | `applicant_id→applicant_primary_info` ON DELETE CASCADE | none (1:1 extension) |
| `pp.jurisdiction` | `juris_code` (`jurisdiction_pkey`) | — | `parent_juris` self-referential (no FK constraint enforced in DDL, logical only) | `pp.jurisdiction_code_seq` |

Key type facts affecting Java field types:
- `student_master.student_id`, `applicant_id`: `numeric(14,0)` — use `Long`/`BigInteger`, not `int`.
- `student_master.gender`, `applicant_primary_info.gender`: `character(1)` (bpchar) CHECK IN `('M','F','O')`.
- `student_master.active_yn`: `varchar(10)` CHECK IN `('ACTIVE','INACTIVE')` — default `'ACTIVE'`.
- `applicant_primary_info.nmms_reg_number`: `numeric(11,0) NOT NULL` with UNIQUE — the natural key used across search/sort.
- `applicant_primary_info.app_state/district/nmms_block`: `numeric(12,0)` referencing `jurisdiction.juris_code` — search filters bind these as `Number()` in JS; Java should bind as numeric/long, not String.
- `student_attendance.status`: `varchar(20)` CHECK IN `('PRESENT','ABSENT','LATE JOINED','LEAVE')` — the attendance-% queries filter on `IN ('PRESENT','LATE JOINED','LEAVE')`, i.e. **`ABSENT` is the only status that does NOT count as attended**; there is no explicit "not yet held" state distinct from absent at the DB level (a session with no `student_attendance` row at all is excluded by the `LEFT JOIN ... AND sa.student_id=$id` + `COUNT(sa.session_id)` pattern, which is different from an explicit `ABSENT` row).
- `pp.inactive_students` and `pp.exam_results` have **no primary key at all** in the live DDL — multiple identical rows are permitted; `getInactiveHistoryByStudentId` can return duplicate rows if the app ever inserts duplicates (it doesn't guard against re-marking inactive with the same reason).

## 4. Response Shapes & Status Codes

| # | Success | Failure |
|---|---|---|
| 1 | `200` plain text `"Student API Working"` (`res.send`, not `res.json`) | — |
| 2 | `200` — full row object (see `STUDENT_SELECT`) via `res.json(student)` | `404 { message: "Student profile not found" }`; `500 { error: "Server error" }` |
| 3 | `200` — JSON array of timetable rows | `404 { message: "Student profile not found." }`; `400 { message: "No batch assigned." }`; `500 { message: "Internal Server Error" }` (note: this handler uses `message` key for its 500, all its siblings below use `error`) |
| 4/6 | `200` — JSON array `[{subject_name, total_classes, attended_classes, attendance_percent}]` | `500 { error: "Failed to fetch subject performance" }` |
| 5 | `200` — JSON object `{total_classes, attended_classes, attendance_percent, exam_score}` (or `{exam_score:"-"}` only, see §2a quirk) | `500 { error: "Failed to fetch summary" }` |
| 7 | `200` — JSON array `[{month, percent}]` | `500 { error: "Failed to fetch monthly data" }` |
| 8 | `200` — JSON array `[{week_start, percent}]` | `500 { error: "Failed to fetch weekly data" }` |
| 9 | `200` — JSON array `[{subject_name, total_classes, attended_classes, attendance_percent}]` | `400 { error: "Date range required" }` (missing fromDate/toDate); `500 { error: "Failed to fetch custom data" }` |
| 10 | `200` — JSON array `[{inactive_reason, inactive_date, created_by, updated_by}]` (empty array if none, not 404) | `500 { error: "Failed to fetch inactive history" }` |
| 11 | `200 { success:true, data:[...], pagination:{ total, limit, offset, page, totalPages, hasMore } }` | `500 { success:false, error: "Internal Server Error" }` |
| 12 | `200 { success:true, data: {...student_master row...} }` | `404 { success:false, message: "Student not found" }`; `500 { success:false }` (no `error`/`message` key at all on 500 — bare object) |
| 13 | `200 { data:[...], pagination:{ total, limit, offset, totalPages, currentPage, nextOffset, prevOffset }, sort:{sortBy, sortOrder} }` | `404 { message: "No applications found matching the criteria." }` (**only** when `pageOffset===0` and zero rows — later pages with zero rows return `200` with empty `data`); `500 { error: "Internal Server Error", details: error.message }` |
| 14 | `200 { data:[...cohort rows...] }` | `500 { error: "Internal Server Error", details: error.message }` |
| 15 | `200 { data:[...batch rows...] }` | `500 { error: "Internal Server Error", details: error.message }` |

**Cross-endpoint inconsistency to flag:** error envelopes vary by handler — some use `{error}`, some `{message}`, one (`getMySchedule`'s 500) uses `{message}` where its own 404/400 also use `{message}`. `getStudentById`'s 500 has neither key, just `{success:false}`. Endpoint #11's pagination uses `page`/`totalPages`/`hasMore`; endpoint #13's pagination uses `currentPage`/`totalPages`/`nextOffset`/`prevOffset` — **two different pagination DTO shapes for conceptually the same pattern**, must be modeled as two distinct Java response classes, not unified.

**Numeric-as-string / type notes for response DTOs:**
- Endpoint #13's `data` rows are `a.*` from `applicant_primary_info` — `node-postgres` returns `numeric` columns as JS strings by default unless a custom type parser is registered; check the Node `pg` config (`server/config/db.js`) for `pg.types.setTypeParser` overrides before assuming numeric fields serialize as JSON numbers vs strings in the actual wire response. This determines whether the Java DTO should type these fields as `String` or `BigDecimal`/`Long` to match byte-for-byte JSON parity.
- `attendance_percent`/`percent` come from `ROUND(...::numeric, 2)` — same numeric-vs-string serialization caveat applies.

## 5. File-Generating Endpoints

**None.** No endpoint in `studentRoutes.js`, `studentSearchRoutes.js`, or `searchRoutes.js` streams a file, sets `Content-Disposition`/`res.attachment`, or writes to disk. All 15 endpoints return JSON (or plain text for the health check). Confirmed by grep — no `multer`, `csv`, `xlsx`, `pdf`, `res.attachment`, or `res.download` references anywhere in these three route/controller/model files.

## 6. Transactions

**None.** Every query in all three models (`studentModel.js`, `studentSearchModel.js`, `searchModel.js`) is a single `pool.query(...)` call — plain autocommit, no `pool.connect()`, no `BEGIN`/`COMMIT`/`ROLLBACK` anywhere in this module group. (Contrast with the Phase-2b merge module, which does use explicit transactions for multi-statement writes — this module group has no multi-statement writes at all; it is entirely read-only except for the coordinator CRUD functions that exist in `studentController.js`/`studentModel.js` but are not routed here.)

## 7. Quirks & Complexity Warnings

1. **Unauthenticated admin endpoints** — `studentSearchRoutes.js:5-6` and all of `searchRoutes.js` (endpoints #11-15) have zero `authenticate` middleware, unlike every route in `studentRoutes.js`. Decide explicitly in the Java port whether to preserve this (parity) or require auth (security fix) — flag for product/security sign-off, don't silently change behavior.
2. **`ORDER BY a.${sortBy}`** in `searchModel.js:89` — string-interpolated column name. Safe today only because `searchController.js:29-41` whitelists `sortBy` against a fixed array before calling the model; the model itself has no guard. **Port the whitelist as a Java `enum`** at the query-builder boundary (same pattern as the Phase-2b merge module's `deleteDistrictDataModel` table-name risk) — do not let a future caller invoke the model function directly with an unvalidated string.
3. **`spl_health_cond`/`spl_family_cond` dead filter params in endpoint #13** — the controller accepts and forwards them, and they're in the sortable-fields whitelist, but `searchModel.searchStudents` never destructures or applies them as WHERE filters. This is either an incomplete feature or intentionally dropped — confirm with product before deciding whether Java should implement the filter or intentionally omit it too.
4. **`getStudentSummaryModel` empty-result quirk (§2a)** — when the attendance aggregate query returns zero rows (student has no matching `student_master.user_id`), the spread `{...undefined}` produces `{}`, so the JSON response degrades to `{exam_score:"-"}` with all attendance keys silently absent rather than `null` or a 404. Byte-for-byte parity requires replicating this exact partial-object shape, not filling in zeros/nulls.
5. **Inconsistent error envelopes across the module** — `{error}` vs `{message}` vs bare `{success:false}` with neither key (`getStudentById`'s 500, `studentSearchController.js:42`). A generic Java `@ExceptionHandler` must NOT normalize these into one shape if strict parity is required — document per-endpoint envelope in the implementation plan.
6. **Two incompatible pagination DTOs** — endpoint #11 (`success/page/hasMore`) vs endpoint #13 (`currentPage/nextOffset/prevOffset`) must become two distinct Java response classes, not a shared `PageResponse<T>`.
7. **`getBatchesByCohort(cohortNumber)`** (`searchModel.js:104-113`, endpoint #15) binds `req.params.cohortNumber` **as a raw string** with no `Number()`/`parseInt` cast, relying on implicit PG coercion. A non-numeric path segment (`/api/batches/cohort/abc`) throws an uncaught PG type error inside the try/catch → generic `500 {error, details}`. Same class of issue for endpoint #10 (`getInactiveHistoryByStudentId`, `req.params.id` uncast against `numeric(14,0)`). Decide whether Java should return `400` for non-numeric path params instead (recommended) or preserve the 500.
8. **`SELECT *` on `student_master` in endpoint #12** (`studentSearchModel.js:142-147`) returns every column including `student_email_password` — verify with product whether this should be redacted in the Java DTO; currently it is exposed verbatim to any unauthenticated caller (compounds concern #1).
9. **Endpoint #12 is dead in the frontend** — no live `client/src` call to `GET /api/student/:student_id` was found (only a commented-out reference in `client/src/pages/Student/StudentProfile.js:45` for the unrelated `:id/inactive-history` path). Confirm with the team whether to still port it (some other unaudited caller, e.g. Postman/manual testing, could depend on it) or drop it.
10. **`getStudentMonthlyAttendanceModel`/`getStudentWeeklyAttendanceModel`** omit the `NULLIF(COUNT(cs.session_id),0)` guard present in the summary/subject/custom queries (§2a) — currently safe only because `GROUP BY` on a date-derived column guarantees at least one session per group, but it's an inconsistency worth normalizing (or explicitly preserving) rather than assuming it's intentional.
11. **`getStudentCustomAttendanceModel`** (endpoint #9) does no date-format validation beyond truthiness (`!fromDate || !toDate`) before binding directly into `BETWEEN $2 AND $3` — malformed date strings surface as a raw Postgres error → generic 500. Java should validate/parse dates before querying and return `400` on bad input (behavior change to consider, or preserve-and-document if parity is required).
12. **Numeric column JSON serialization is undetermined without checking `pg` type-parser config** (`server/config/db.js`, not read as part of this module) — every response DTO with `numeric` source columns (endpoint #13's `a.*`, all attendance-percent fields) needs this resolved before finalizing Java field types (String vs BigDecimal) to guarantee byte-identical JSON.
13. **`inactive_students` and `exam_results` have no primary key** in the live schema — Java/JPA-style entity mapping (if ever used elsewhere) would need a synthetic key or composite natural key; for this module's plain-JDBC read paths it's not blocking, but flag it since duplicate rows are structurally possible and `ORDER BY inactive_date DESC` doesn't guarantee stable ordering for same-date duplicates.
