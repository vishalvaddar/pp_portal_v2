# ACTIVE-TIMETABLE + TRACKING Modules — Ground Truth (for Phase 4c)

Captured from a full read of the live Node source. Two independent mounts, both live in `server/index.js`:
`app.use("/api/activetimetable", activetimetableRoutes)` (index.js:307) and `app.use("/api/tracking", trackingRoutes)` (index.js:308).
`app.use("/api/timetable", timetableRoutes)` is **commented out** (index.js:306) — the old `timeTableRoutes.js`/`timetableController.js` files (if present) are dead code, not read, and out of scope.
**No auth middleware is mounted anywhere before these routers** (only `cors` and `express.json` run globally) — both modules are effectively open endpoints at the Express layer; any access control is purely client-side (`useAuth`/`useSystemConfig` gating in the React pages).

Files read in full:
- `server/routes/activeTimeTableRoutes.js` (17 lines, all live)
- `server/controllers/activeTimeTableController.js` (183 lines, all live)
- `server/models/activeTimeTableModel.js` (150 lines, all live)
- `server/routes/trackingRoutes.js` (37 lines, all live)
- `server/controllers/trackingController.js` (199 lines, all live)
- `server/models/trackingModel.js` (259 lines, all live)
- Frontend: `client/src/pages/Admin/TimeTable/ActiveTimeTable.js`, `client/src/pages/Admin/Evaluation/EvaluationTracking.js`

## 1. Endpoint Inventory

### `/api/activetimetable` (activeTimeTableRoutes.js — 7 routes, no ordering hazards, no dead routes)

| # | Method | Path | Controller fn | Purpose |
|---|--------|------|----------------|---------|
| 1 | GET | `/dropdowns` | `getDropdownData` | Cohorts (open only) + all teachers |
| 2 | GET | `/batches` | `getBatchesByCohort` | Batches for `?cohortName=` |
| 3 | GET | `/fetch` | `getTimetableData` | `?type=combined\|teacher\|batch&id=&cohort=` |
| 4 | POST | `/subject/add` | `addSubject` | Insert into `pp.subject` |
| 5 | GET | `/teacher-skills/:teacherId` | `getTeacherSkills` | Skills for one teacher + full subject list |
| 6 | POST | `/teacher-skills/manage` | `manageTeacherSkill` | Add/delete a `pp.teacher_subject` row |
| 7 | POST | `/download-pdf` | `downloadTimetablePDF` | Server-rendered PDF, data passed in from client (not re-queried) |

### `/api/tracking` (trackingRoutes.js — 6 routes)

| # | Method | Path | Controller fn | Purpose |
|---|--------|------|----------------|---------|
| 1 | GET | `/interviewers` | `getAllInterviewers` | Dropdown list |
| 2 | GET | `/students` | `getStudents` | Paginated latest-round-per-student list, status/result/home-verification filters |
| 3 | GET | `/students/interviewer/:interviewerId` | `getStudentsByInterviewer` | Paginated list filtered by interviewer (NOT latest-round-only — see §7) |
| 4 | GET | `/students/:applicantId/details` | `getStudentDetails` | Latest-round detail (both `filtered=true` and default branch call the *same* model fn — see §7) |
| 5 | GET | `/students/:applicantId/interviews/all` | `getAllInterviewRounds` | All interview rounds for a student |
| 6 | GET | `/students/:applicantId/home/all` | `getAllHomeVerificationRounds` | All home-verification rounds for a student |
| 7 | GET | `/document/:applicantId/:cohortId` | `downloadDocument` | `?type=interview\|home` → 302 redirect into `/Data` static mount |

**Route-ordering note:** `/students/interviewer/:interviewerId` (route 3) is registered *before* `/students/:applicantId/details` etc. (routes 4-6). Since `/students/interviewer/X` and `/students/:applicantId/details` differ in segment count/shape, Express's path-to-regexp resolves them unambiguously regardless of order — **not actually a hazard**, but flag it because it looks like one at a glance (a request to `/students/interviewer/5` could in principle be shadowed by a badly-written `/students/:applicantId` catch-all; this codebase avoids that only because every generic-`:applicantId` route has a required suffix segment, e.g. `/details`, `/interviews/all`, `/home/all`). If Spring MVC path variables are declared in a different order this constraint must be preserved (i.e. don't add a bare `/students/{applicantId}` route).

`getStudentDetails` is dead-weird, not dead: both `isFilteredView` branches call `trackingModel.getStudentdetailforFilter(applicantId, nmmsYear)` — the `filtered=true` query flag has **no effect on server behavior**; it's a vestigial no-op (frontend actually never calls this endpoint at all — grep of `EvaluationTracking.js` shows only `/interviews/all` and `/home/all` are used for the detail view; `/students/:applicantId/details` appears to be dead/unused from the current frontend, though it is live routing).

## 2. Exact SQL (verbatim)

### activeTimeTableModel.js

```sql
-- getCohorts
SELECT cohort_number, cohort_name FROM pp.cohort WHERE end_date IS NULL ORDER BY cohort_name

-- getBatches($1 = cohortName)
SELECT b.batch_id, b.batch_name
FROM pp.batch b
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
WHERE c.cohort_name = $1

-- getTeachers
SELECT teacher_id, teacher_name FROM pp.teacher ORDER BY teacher_name

-- getCombined($1 = cohortName)  [type=combined]
SELECT
  t.teacher_name, s.subject_name, b.batch_name,
  tt.day_of_week, tt.start_time, tt.end_time
FROM pp.timetable tt
LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
WHERE ch.cohort_name = $1
ORDER BY
  CASE
    WHEN TRIM(LOWER(tt.day_of_week)) = 'sunday' THEN 1
    WHEN TRIM(LOWER(tt.day_of_week)) = 'monday' THEN 2
    WHEN TRIM(LOWER(tt.day_of_week)) = 'tuesday' THEN 3
    WHEN TRIM(LOWER(tt.day_of_week)) = 'wednesday' THEN 4
    WHEN TRIM(LOWER(tt.day_of_week)) = 'thursday' THEN 5
    WHEN TRIM(LOWER(tt.day_of_week)) = 'friday' THEN 6
    WHEN TRIM(LOWER(tt.day_of_week)) = 'saturday' THEN 7
  END, tt.start_time;

-- getTeacherWise($1 = teacherId)  [type=teacher]
SELECT t.teacher_name, s.subject_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
FROM pp.timetable tt
LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
WHERE t.teacher_id = $1
ORDER BY tt.day_of_week, tt.start_time;
-- NOTE: no cohort filter at all here, and ORDER BY tt.day_of_week is plain
-- alphabetical text order (Friday, Monday, Saturday, Sunday, Thursday,
-- Tuesday, Wednesday) — NOT the Sun-Sat CASE ordering used by getCombined.

-- getBatchWise($1 = batchName, $2 = cohortName)  [type=batch]
SELECT s.subject_name, t.teacher_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
FROM pp.timetable tt
LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
WHERE b.batch_name = $1 AND ch.cohort_name = $2
ORDER BY tt.day_of_week, tt.start_time;
-- Same alphabetical day-order quirk as getTeacherWise.

-- getTeacherSkills($1 = teacherId)
SELECT ts.subject_id, s.subject_name, ts.medium
FROM pp.teacher_subject ts
JOIN pp.subject s ON ts.subject_id = s.subject_id
WHERE ts.teacher_id = $1

-- addTeacherSkill($1=teacherId, $2=subjectId, $3=medium.toUpperCase())
INSERT INTO pp.teacher_subject (teacher_id, subject_id, medium)
VALUES ($1, $2, $3)
RETURNING *

-- deleteTeacherSkill($1=teacherId, $2=subjectId, $3=medium)  -- NOT upper-cased here (quirk, see §7)
DELETE FROM pp.teacher_subject
WHERE teacher_id = $1 AND subject_id = $2 AND medium = $3

-- getSubjects
SELECT subject_id, subject_name FROM pp.subject ORDER BY subject_name

-- addSubject($1=subject_code, $2=subject_name, $3=created_by used twice)
INSERT INTO pp.subject
(subject_code, subject_name, created_by, updated_by)
VALUES ($1, $2, $3, $3)
RETURNING *;
```

### trackingModel.js

```sql
-- getAllInterviewers
SELECT interviewer_id, interviewer_name
FROM pp.interviewer
ORDER BY interviewer_name ASC;

-- getStudentsWithLatestStatus — dynamically built (verbatim structure; see §7 for param-index construction)
WITH RankedInterviews AS (
    SELECT
        a.applicant_id, a.student_name,
        s.interview_round, s.status, s.interview_result,
        MAX(s.home_verification_req_yn) OVER (PARTITION BY a.applicant_id) as persistent_verification_req,
        ROW_NUMBER() OVER (PARTITION BY a.applicant_id ORDER BY s.interview_round DESC) as rn
    FROM pp.student_interview s
    JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
    WHERE a.nmms_year = $1                      -- yearPlaceholder, always $1
),
LatestInterviews AS (
    SELECT * FROM RankedInterviews WHERE rn = 1
)
SELECT applicant_id, student_name, interview_round, status,
       interview_result AS result, persistent_verification_req as home_verification_req_yn
FROM LatestInterviews
[WHERE <dynamic AND-joined conditions>]          -- see below
ORDER BY student_name ASC
LIMIT $n OFFSET $n+1;
-- countQuery = same CTE + SELECT COUNT(*) FROM LatestInterviews [WHERE ...] (no LIMIT/OFFSET)

-- Dynamic WHERE conditions (each optional, ANDed together):
--   UPPER(TRIM(status)) IN ($k, $k+1, ...)                      -- one placeholder per status
--   (UPPER(TRIM(interview_result)) IN ($m, ...) OR UPPER(TRIM(persistent_verification_req)) = 'Y')
--     -- the OR sub-clause is built from `results[]` and/or the literal 'HOME VERIFICATION REQUIRED'
--     -- pseudo-value in `results` filter (peeled off client-side, see §7)

-- getStudentsByInterviewer($1=interviewerId, $2=limit, $3=offset, $4=nmmsYear)
SELECT
    a.applicant_id, a.student_name, s.interview_round, s.status, s.interview_result AS interview_result
FROM pp.student_interview s
JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
WHERE s.interviewer_id = $1 AND a.nmms_year = $4
ORDER BY a.student_name ASC, s.interview_round DESC
LIMIT $2 OFFSET $3;
-- countQuery ($1=interviewerId, $2=nmmsYear):
SELECT COUNT(s.applicant_id)
FROM pp.student_interview s
JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
WHERE s.interviewer_id = $1 AND a.nmms_year = $2;
-- NOTE: this endpoint returns ALL rounds per student (not deduped to latest,
-- unlike getStudentsWithLatestStatus) — a student with 3 rounds appears as
-- 3 separate rows/"students" in this listing. Intentional-looking but a
-- behavioral divergence from the plain /students list. See §7.

-- getAllInterviewRounds($1=applicantId, $2=nmmsYear)
SELECT
    a.student_name, s.applicant_id, s.interview_round,
    TO_CHAR(s.interview_date, 'YYYY-MM-DD') AS interview_date,
    s.interview_time, s.interview_mode, s.status, s.life_goals_and_zeal,
    s.commitment_to_learning, s.integrity, s.communication_skills,
    s.interview_result AS interview_result, s.home_verification_req_yn,
    s.doc_name, s.doc_type, i.interviewer_name AS interviewer
FROM pp.student_interview s
JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
LEFT JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
WHERE s.applicant_id = $1 AND a.nmms_year = $2
ORDER BY s.interview_round ASC;

-- getStudentdetailforFilter($1=applicantId, $2=nmmsYear)  -- used by BOTH branches of getStudentDetails
SELECT
    a.student_name, s.interview_round,
    TO_CHAR(s.interview_date, 'YYYY-MM-DD') AS interview_date,
    s.interview_time, s.interview_mode, s.status, s.life_goals_and_zeal,
    s.commitment_to_learning, s.integrity, s.communication_skills,
    s.interview_result AS interview_result, s.home_verification_req_yn,
    i.interviewer_name AS interviewer
FROM pp.student_interview s
JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
LEFT JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
WHERE a.applicant_id = $1 AND a.nmms_year = $2
AND s.interview_round = (
    SELECT MAX(interview_round)
    FROM pp.student_interview
    WHERE applicant_id = a.applicant_id      -- NOTE: sub-select does NOT filter by nmms_year (see §7)
)
ORDER BY s.interview_round DESC;

-- getInterviewDocument($1=applicantId)
SELECT doc_name, doc_type, interview_round
FROM pp.student_interview
WHERE applicant_id = $1 AND doc_name IS NOT NULL
ORDER BY interview_round DESC LIMIT 1;

-- getHomeVerificationDocument($1=applicantId)
SELECT doc_name, doc_type
FROM pp.home_verification
WHERE applicant_id = $1 AND doc_name IS NOT NULL
ORDER BY date_of_verification DESC, verification_id DESC LIMIT 1;

-- getAllHomeVerificationRounds($1=applicantId)
SELECT
    h.verification_id,
    TO_CHAR(h.date_of_verification, 'YYYY-MM-DD') AS date_of_verification,
    h.status AS home_verification_status, h.verified_by,
    h.verification_type AS home_verification_type,
    h.doc_name AS home_verification_doc_name, h.doc_type AS home_verification_doc_type,
    h.remarks
FROM pp.home_verification h
WHERE h.applicant_id = $1
ORDER BY h.date_of_verification ASC;
```

## 3. Table DDL (from `live-schema.sql`, relevant columns/constraints)

```sql
CREATE TABLE pp.cohort (
    cohort_number integer DEFAULT nextval('pp.cohort_seq'::regclass) NOT NULL,
    cohort_name character varying(100),
    start_date date, end_date date, description text,
    created_at/updated_at timestamp, created_by/updated_by numeric(8,0)
);
-- PK (cohort_number); UNIQUE (cohort_name)

CREATE TABLE pp.batch (
    batch_id integer DEFAULT nextval('pp.batch_id_seq'::regclass) NOT NULL,
    batch_name character varying(100), cohort_number integer,
    medium character varying(20) DEFAULT 'KANNADA' CHECK (medium IN ('ENGLISH','KANNADA','HINDI','MARATHI')),
    house_name character varying(100), created_at/updated_at, created_by/updated_by
);
-- PK (batch_id); UNIQUE (cohort_number, batch_name)

CREATE TABLE pp.classroom (
    classroom_id integer DEFAULT nextval(...) NOT NULL,
    classroom_name character varying(100) NOT NULL,
    subject_id integer, teacher_id integer, platform_id integer,
    description character varying(200),
    active_yn character(1) DEFAULT 'Y' CHECK (active_yn IN ('Y','N')),
    class_link character varying(150), created_at/updated_at, created_by/updated_by
);
-- PK (classroom_id); FKs: subject_id -> subject ON DELETE SET NULL,
-- teacher_id -> teacher ON DELETE SET NULL, platform_id -> teaching_platform ON DELETE SET NULL

CREATE TABLE pp.classroom_batch (
    classroom_id integer NOT NULL,
    batch_id integer NOT NULL
);
-- PK (classroom_id, batch_id); FK classroom_id -> classroom ON DELETE CASCADE;
-- FK batch_id -> batch ON DELETE CASCADE

CREATE TABLE pp.teacher (
    teacher_id integer DEFAULT nextval(...) NOT NULL,
    user_id numeric(8,0), teacher_name character varying(150),
    qualification character varying(150), experience_yrs integer CHECK (>= 0),
    doj date, contact_no character varying(12), created_at/updated_at, created_by/updated_by
);
-- PK (teacher_id); UNIQUE (user_id); FK user_id -> "user" ON DELETE CASCADE

CREATE TABLE pp.subject (
    subject_id integer DEFAULT nextval(...) NOT NULL,
    subject_code character varying(5) NOT NULL,
    subject_name character varying(100) NOT NULL,
    created_at/updated_at, created_by/updated_by
);
-- PK (subject_id); UNIQUE (subject_name)  -> maps to the 23505 handled by addSubject

CREATE TABLE pp.teacher_subject (
    teacher_id integer NOT NULL, subject_id integer NOT NULL,
    medium character varying(20) DEFAULT 'KANNADA' NOT NULL
      CHECK (medium IN ('ENGLISH','KANNADA','HINDI','MARATHI'))
);
-- PK (teacher_id, subject_id, medium); FKs teacher_id/subject_id ON DELETE CASCADE
-- => INSERT of a duplicate (teacher_id, subject_id, medium) throws 23505.
--    manageTeacherSkill/addTeacherSkill does NOT special-case 23505
--    (unlike addSubject) -> falls to generic 500 "Database error: ...".

CREATE TABLE pp.timetable (
    timetable_id integer DEFAULT nextval(...) NOT NULL,
    classroom_id integer, day_of_week character varying(10),
    start_time time NOT NULL, end_time time NOT NULL,
    created_at/updated_at, created_by/updated_by,
    CHECK (day_of_week IN ('SUNDAY','MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY'))
);
-- PK (timetable_id); FK classroom_id -> classroom (no ON DELETE action -> RESTRICT default)
-- Stored day_of_week is UPPERCASE per CHECK constraint; app-layer queries
-- do TRIM(LOWER(...)) comparisons, consistent with this.

CREATE TABLE pp.interviewer (
    interviewer_id numeric(10,0) DEFAULT nextval(...) NOT NULL,
    interviewer_name character varying(100), email character varying(100),
    mobile1/mobile2 character varying(12),
    active_status character(1) CHECK (active_status IN ('Y','N')),
    created_at/updated_at, created_by/updated_by
);
-- PK (interviewer_id).  NOTE: getAllInterviewers does NOT filter by active_status —
-- inactive interviewers still appear in the dropdown (quirk, §7).

CREATE TABLE pp.student_interview (
    interview_id numeric(12,0) DEFAULT nextval(...) NOT NULL,
    applicant_id numeric(14,0), interviewer_id numeric(10,0),
    interview_date date, interview_time time, interview_mode character varying(20)
      CHECK (interview_mode IN ('ONLINE','OFFLINE')),
    interview_round integer,
    status character varying(15) CHECK (status IN ('SCHEDULED','COMPLETED','CANCELLED','RESCHEDULED')),
    life_goals_and_zeal/commitment_to_learning/integrity/communication_skills numeric(3,1),
    interview_result character varying(50) CHECK (interview_result IN ('SELECTED','REJECTED','ANOTHER INTERVIEW REQUIRED')),
    home_verification_req_yn character(1) DEFAULT 'N' CHECK (IN ('Y','N')),
    remarks character varying(500), doc_name character varying(100), doc_type character varying(50),
    created_at/updated_at, created_by/updated_by
);
-- PK (interview_id).  NOTE: 'HOME VERIFICATION REQUIRED' is NOT a value of
-- interview_result — it's a synthetic filter value derived from
-- home_verification_req_yn (see controller logic, §1/§7).

CREATE TABLE pp.home_verification (
    verification_id numeric(12,0) DEFAULT nextval(...) NOT NULL,
    applicant_id numeric(14,0), date_of_verification date,
    remarks character varying(200),
    status character varying(10) CHECK (status IN ('PENDING','SCHEDULED','REJECTED','ACCEPTED')),
    verified_by character varying(100), rejection_reason_id numeric(4,0),
    verification_type character varying(20) CHECK (verification_type IN ('PHYSICAL','VIRTUAL')),
    doc_name character varying(100), doc_type character varying(50),
    created_at/updated_at, created_by/updated_by
);
-- PK (verification_id); FK applicant_id -> applicant_primary_info;
-- FK rejection_reason_id -> rejection_reasons

-- applicant_primary_info (relevant columns only)
--   applicant_id, nmms_year numeric(4,0), student_name character varying(100)
```

## 4. Response Shapes & Status Codes

### activetimetable

| Endpoint | 200 shape | Error shape |
|---|---|---|
| GET `/dropdowns` | `{ cohorts:[{cohort_number,cohort_name}], teachers:[{teacher_id,teacher_name}] }` | `500 {error}` |
| GET `/batches` | `[{batch_id,batch_name}, ...]` (bare array) | `500 {error}` |
| GET `/fetch` | `type=combined`: `[{teacher_name,subject_name,batch_name,day_of_week,start_time,end_time}]`; `type=teacher`: same shape minus filtering by cohort; `type=batch`: `[{subject_name,teacher_name,batch_name,day_of_week,start_time,end_time}]`. **If `type` is none of combined/teacher/batch, `data` stays `undefined` and `res.json(undefined)` sends an empty body with `200`** (quirk, §7) | `500 {error}` |
| POST `/subject/add` | `201 {message:"Subject added successfully", data: <new subject row>}` | `400 {error:"Subject name already exists"}` (pg 23505) / `500 {error:"Failed to add subject to database"}` |
| GET `/teacher-skills/:teacherId` | `{ skills:[{subject_id,subject_name,medium}], allSubjects:[{subject_id,subject_name}] }` | `500 {error}` |
| POST `/teacher-skills/manage` | `{message:"Skill updated successfully"}` (200, add or delete) | `500 {error:"Database error: "+message}` (no 23505 special-case — see §3) |
| POST `/download-pdf` | `200`, `Content-Type: application/pdf`, `Content-Disposition: attachment; filename=<fileName or TIMETABLE_<cohort>.pdf>` — binary PDF stream | `500` plain text `"Error generating PDF"` |

### tracking

| Endpoint | 200 shape | Error shape |
|---|---|---|
| GET `/interviewers` | `[{interviewer_id,interviewer_name}, ...]` (bare array) | `500 {error:"Could not fetch interviewers."}` |
| GET `/students` | `{students:[...], currentPage, totalPages, totalStudents}` | `500 {error:"Could not fetch student tracking data."}` |
| GET `/students/interviewer/:id` | `{students:[...], currentPage, totalPages, totalStudents}` | `400 {error:"Invalid Interviewer ID provided."}` if `:id` not numeric; `500 {error:"Could not fetch students assigned to interviewer."}` |
| GET `/students/:id/details` | bare array of round rows | `400 {error:"Invalid Applicant ID."}`; `404 {error:"Student or interview data not found."}` if empty; `500 {error:"Could not fetch student interview details."}` |
| GET `/students/:id/interviews/all` | bare array | `400 {error:"Invalid Applicant ID."}`; `500 {error:"Could not fetch all interview rounds."}` |
| GET `/students/:id/home/all` | bare array | `400 {error:"Invalid Applicant ID."}`; `500 {error:"Could not fetch home verification records."}` |
| GET `/document/:applicantId/:cohortId` | `302 redirect` → `/Data/<Interview-data\|home-verification-data>/<cohortId>/<doc_name>` | `400` plain text `"Invalid parameters."` (bad id / missing cohortId / bad `type`); `404` plain text `"Document metadata not found."` or `"File not found on storage."`; `500` plain text `"Server Error."` |

## 5. File-Gen / File-Serving Endpoints

**`downloadTimetablePDF`** (`activeTimeTableController.js:89-182`): uses `pdfkit-table` (`PDFDocument` from `"pdfkit-table"`, NOT plain `pdfkit`). Streams directly to `res` via `doc.pipe(res)` (no temp file). Body-driven — the client sends the already-fetched `timetableData` array plus `cohortName, viewType, filterDetails{teacherName,batchName}, fileName`; **the server does not re-query the DB**, it only renders what the client posts. Layout: landscape A4, 30pt margin. Draws two logos from disk if present (`server/public/assets/rcf_logo-removebg-preview.png`, `server/public/assets/logo.png`) via `fs.existsSync` guard (silently skips if missing — no error). Static header text: "RAJALAKSHMI CHILDREN FOUNDATION", "PRATIBHA POSHAK EXAMINATION - 2025" (hard-coded year, §7), fixed address/contact lines. Subtitle varies by `viewType` (`teacher`→`TEACHER: <name>`, `batch`→`COHORT: <c> | BATCH: <b or "ALL BATCHES">`, else→`COHORT: <c>`). Table columns: DAY / TIME (`start-end`) / SUBJECT / TEACHER / BATCH, all `.toUpperCase()`'d from the posted row fields; column widths hard-coded (80/140/200/150/110). Uses `await doc.table(...)` (pdfkit-table's async table renderer) with `padding:12, minRowHeight:35`. `doc.end()` closes the stream — response finishes async as the PDF writer flushes.

**`downloadDocument`** (trackingController.js:154-196) is not a file-generation endpoint but a **redirect-based file server**: it resolves `doc_name`/`doc_type` from the DB, strips any path prefix from the stored `doc_name` (`split(/\\|\//)` then `.pop()` — defends against a path stored with backslashes or a full path rather than a bare filename), rebuilds an absolute path via `constructFilePath` using `process.env.FILE_STORAGE_PATH` (`PC_STORAGE_ROOT`) + a fixed subfolder (`'Interview-data'` or `'home-verification-data'`) + `String(cohortId)` + the cleaned filename, checks `fs.existsSync` on disk, then issues `res.redirect()` to the **public** static path `/Data/<folder>/<cohortId>/<doc_name>` (index.js:195 `app.use("/Data", express.static(PROJECT_ROOT_DIR))` where `PROJECT_ROOT_DIR` is the *same* `FILE_STORAGE_PATH` env var — so the existsSync check and the actual served file are guaranteed to be the same root). No auth on the `/Data` static mount either.

## 6. Transactions

**None.** Every model function in both `activeTimeTableModel.js` and `trackingModel.js` is a single `pool.query(...)` call (autocommit). No `pool.connect()` / `BEGIN` / `COMMIT` / `ROLLBACK` anywhere in either file. `addTeacherSkill`/`deleteTeacherSkill`/`addSubject` are each a lone INSERT/DELETE with no surrounding transaction — a Java port can use a single `JdbcClient` call per operation with no explicit transaction demarcation required (though wrapping in `@Transactional` for safety is fine, it changes nothing observable).

## 7. Quirks / Complexity (file:line references)

1. **`/api/timetable` is dead** (index.js:306, commented out) — do not port `timeTableRoutes.js`/`timetableController.js` under this phase; only `activeTimeTableController.js`/`activeTimeTableModel.js` are live.

2. **No auth middleware on either router** (index.js 295-308 region, nothing between `express.json` at line ~140/156 and these mounts) — both APIs are open at the HTTP layer. `addSubject` reads `req.user ? req.user.user_id : req.body.admin_id` (activeTimeTableController.js:45) but since no middleware ever sets `req.user`, this always falls through to the client-supplied `req.body.admin_id` — i.e. **`created_by` is fully client-controlled, not server-verified**. Preserve or intentionally fix in Java (recommend: still trust a payload field for behavior parity, but flag for the team since it's a real integrity gap).

3. **`getTimetableData` silent no-op for unknown `type`** (activeTimeTableController.js:26-37): if `type` isn't exactly `combined`/`teacher`/`batch`, `data` is never assigned and `res.json(undefined)` returns HTTP 200 with an empty body (not `null`, not `[]`, not an error). A Java port should decide explicitly whether to preserve this (200 + empty body) or return 400 — flag as a decision point.

4. **Inconsistent day-of-week ordering across the three `/fetch` variants** (activeTimeTableModel.js): `getCombined` (type=combined) orders Sun→Sat via a hand-written `CASE`; `getTeacherWise` (type=teacher) and `getBatchWise` (type=batch) just do `ORDER BY tt.day_of_week` which is **plain alphabetical** (Friday, Monday, Saturday, Sunday, Thursday, Tuesday, Wednesday) since `day_of_week` is stored as text. This looks like a bug (teacher/batch views show days out of week-order) but is live production behavior — decide whether to fix or preserve.

5. **`getTeacherWise` has no cohort filter at all** (activeTimeTableModel.js:55-68) — even though the frontend sends `cohort` on every `/fetch` call (`ActiveTimeTable.js:76-78`), the controller only forwards it to `getBatchWise` (activeTimeTableController.js:32); teacher-view timetables show a teacher's classes across **all** cohorts, batches included.

6. **`deleteTeacherSkill` does not uppercase `medium`** (activeTimeTableModel.js:116-121) while `addTeacherSkill` does (`medium.toUpperCase()`, line 108). Since the frontend always sends uppercase medium literals (`KANNADA/ENGLISH/HINDI/MARATHI` — `ActiveTimeTable.js:282-285`) this is latent, not currently triggered, but a case-mismatched delete call (e.g. `medium=Kannada`) would silently delete 0 rows (no error, no affected-row check) rather than fail. Node doesn't check `rowCount` after the DELETE, so callers get `{message:"Skill updated successfully"}` even when nothing was deleted.

7. **`manageTeacherSkill` has no 23505 special-case** (activeTimeTableController.js:70-86) unlike `addSubject` — re-adding an existing `(teacher_id, subject_id, medium)` skill throws a raw postgres unique-violation caught by the generic `catch` and surfaced as `500 {error:"Database error: duplicate key value violates unique constraint..."}`. Frontend just shows a generic alert regardless (`ActiveTimeTable.js:102`), so behavior is masked client-side but the *server* status/shape is a leaky 500, not a clean 400.

8. **Hard-coded "2025" in the generated PDF header** (`activeTimeTableController.js:127`: `"PRATIBHA POSHAK EXAMINATION - 2025"`) — will be wrong for future cohorts/years unless parameterized. Flag for the team; likely worth making dynamic in the Java port even though it's "faithful" to reproduce as-is.

9. **`getStudentsWithLatestStatus` builds fully dynamic parameterized SQL** (trackingModel.js:28-116) — placeholder indices (`$1, $2, ...`) are assigned via a mutable `paramIndex` counter as conditions are appended (status IN-list, then result/home-verification OR-clause, then LIMIT/OFFSET last). This is **not string-interpolated SQL injection** (all values are still bound params, only the *placeholder count/positions* are dynamic) but it is fragile: any future edit to the condition-building order must keep `finalParams` construction (`[...baseParams, ...filterParams]`) in lockstep with the `paramIndex` increments, and the same `filterConditions`/`paramIndex` state is reused unchanged between the `dataQuery` (adds LIMIT/OFFSET at the end) and the `countQuery` (reuses the *same numbered but not present* placeholders since it's built from the same `filterConditions` string — verified consistent because `filterConditions` was captured as a string with baked-in placeholder numbers before either query template is built). Port to Java as an explicit query-builder (e.g. a `StringBuilder` + `List<Object>` args) with unit tests covering: (a) no filters, (b) statuses only, (c) results only, (d) home-verification only, (e) results+home-verification combined (OR), (f) statuses+results combined (AND of two independently-built blocks).

10. **`'HOME VERIFICATION REQUIRED'` is a synthetic filter value, not a DB enum value** (trackingController.js:39-43, model lines 66-78). The frontend's `RESULT_OPTIONS` includes it alongside real `interview_result` values (`SELECTED`, `REJECTED`); the controller peels it out of the `results[]` array (`resultsRaw.filter(r => r !== 'HOME VERIFICATION REQUIRED')`) and instead sets `homeVerificationSelected=true`, which the model turns into `UPPER(TRIM(persistent_verification_req)) = 'Y'` ORed against the real result-IN-list. A Java port must replicate this string-based special-casing exactly, not treat it as a plain `interview_result` filter value.

11. **`getStudentsByInterviewer` does NOT dedupe to latest round** (trackingModel.js:118-152) — unlike `getStudentsWithLatestStatus` (which uses `ROW_NUMBER() ... rn=1`), this query returns one row per `(applicant_id, interview_round)`, so a student with 3 interview rounds under one interviewer shows up 3 times in the paginated "student" list, and `totalCount`/`totalPages` are computed over **row count, not distinct-student count**. This is either an intentional design choice (interviewer view = "my scheduled sessions") or a bug — flag as a decision point; do not silently "fix" by adding `DISTINCT ON` without confirming with the team, since it changes pagination semantics.

12. **`getStudentDetails` (both branches) call the identical model function** (trackingController.js:104-109: `if (isFilteredView) {...} else {...}` both invoke `trackingModel.getStudentdetailforFilter`) — the `?filtered=true` query param is inert. Also this endpoint does not appear to be called by the current frontend (`EvaluationTracking.js` only hits `/interviews/all` and `/home/all` for the detail view) — confirm with the team whether to still port it (dead-but-routed) or drop it; recommend porting for parity/back-compat since it's a real, reachable, documented route.

13. **`getStudentdetailforFilter`'s `MAX(interview_round)` sub-select ignores `nmms_year`** (trackingModel.js:193-197: `SELECT MAX(interview_round) FROM pp.student_interview WHERE applicant_id = a.applicant_id` — no `nmms_year` filter) while the outer query *does* filter by `a.nmms_year = $2`. If a student has interview rounds spanning multiple `nmms_year` values, the "latest round" computed here could reference a round number from a different year than the one being queried, potentially returning zero rows (outer WHERE requires that exact round to also match the year) even though a valid latest round exists for the requested year. Edge case, likely rare in practice (probably one year per applicant) but worth a comment/test in the Java port.

14. **`getAllInterviewers` has no active/inactive filter** (trackingModel.js:10-23) despite `pp.interviewer.active_status` existing with a Y/N check constraint — inactive interviewers still populate the dropdown. Preserve as-is unless the team wants a fix.

15. **Document path handling defends against but doesn't strictly validate `doc_name`** (trackingController.js:179-183): `doc_name.split(/\\|\//).filter(s=>s)` then `.pop()` takes only the last path segment, which neutralizes `../` traversal *from the stored value* incidentally (any path separators collapse to just the basename), but there's no filename character allowlist — a `doc_name` containing e.g. `..` with no separator (`"..env"` is fine; a literal `".."` alone, after `.pop()`, becomes `".."` and `path.join` would walk up one directory) is a latent path-traversal risk worth a defensive fix in Java (validate the resolved path stays under the expected directory) even though it's not clear this is currently exploitable (the value originates from the DB, not directly from user input, but the DB can be seeded via other admin endpoints not covered in this phase).

16. **`cohortId`/`cohortFolder` convention is a derived string, not a DB column** — the frontend computes `cohort-${academic_year.split('-')[0]}` (`EvaluationTracking.js:42-46`) and passes it as the `:cohortId` path segment purely to build the on-disk folder path (`constructFilePath`); it is never validated against `pp.cohort.cohort_name` server-side. The Java port must treat this purely as an opaque path-segment string supplied by the caller, not attempt to resolve it via the `cohort` table.

17. **Default `nmms_year=2025` fallback is a hard-coded literal in three places** (trackingController.js:37,72,96,124: `req.query.nmms_year || 2025`) — will silently misbehave once the current admissions cycle moves past 2025 if a caller ever omits the param. Frontend always sends it when `isAdmissionsOpen`, so low real-world risk, but flag as a hard-coded value to parameterize (e.g. via config/system_config) in the Java port rather than copy the literal.

18. **PDF library dependency**: `pdfkit-table` (wraps `pdfkit`) is required for `/download-pdf` — this is a Node-only library; the Java equivalent needs a comparable "auto-flowing table" API (already established elsewhere in this migration — check whether OpenPDF/iText table helpers used in the exams module (`ee93f87` hall-ticket commit) can be reused for parity, since this module's table layout (fixed columns, header styling, row padding) is structurally similar).

19. **`getStudents` limit is hard-coded to 10** and **`getStudentsByInterviewer` limit is also hard-coded to 10** (trackingController.js:35,71) — not client-configurable, no query param for page size. Straightforward to port as a constant.

## Summary for parity decisions

- **Broken-in-Node behaviors to explicitly decide on:** (3) unknown `type` → empty 200 body; (4) inconsistent day-of-week ordering between combined vs teacher/batch views; (5) teacher-view timetable ignores cohort entirely; (6) case-sensitive skill delete; (11) interviewer-view pagination counts rows not distinct students; (13) year-agnostic MAX(round) subquery edge case.
- **Security-adjacent items to flag to the team (not silently fix):** (2) unauthenticated `created_by` trust; (15) weak doc_name path handling; both pre-existing in Node, likely out of scope to fix under a faithful-parity migration but worth a ticket.
