# TEACHER Portal Module — Ground Truth (for Plan 5c)

Captured from a full read of the live Node source. Mount: `app.use("/api/teacher", teacherStudentRoutes)` (`server/index.js:323-326`). Router file: `server/routes/teacherStudentRoutes.js`.

This module was **missed** in the original Node→Spring Boot migration and must now be ported. Unlike the coordinator module's `coordinatorRoutes.js` (566 lines, 3 commented predecessor versions), `teacherStudentRoutes.js` is **clean — no dead/commented code, 55 lines total, all 9 routes live**. The controller/model files (one class each, one function each) are similarly small and clean — no stacked historical copies.

## 0. Scoping Note

**Files read (full, to the bottom — no commented-out predecessors found anywhere in this module):**

| File | Role |
|---|---|
| `server/routes/teacherStudentRoutes.js` | router — 9 routes, all behind `auth` |
| `server/middleware/authMiddleware.js` | the `auth` middleware (JWT verify) |
| `server/controllers/teacher/TeacherStudentController.js` | cohorts, batches, students, inactive-history |
| `server/controllers/teacher/TeacherDashboardController.js` | dashboard |
| `server/controllers/teacher/TeacherProfileController.js` | profile |
| `server/controllers/teacher/TeacherReportController.js` | reports/my-classes |
| `server/controllers/teacher/TeacherCoordinatorController.js` | coordinators |
| `server/controllers/teacher/TeacherTimetableController.js` | timetable |
| `server/models/teacher/TeacherStudentModel.js` | SQL for cohorts/batches/students/inactive-history queries used by student controller |
| `server/models/teacher/TeacherDashboardModel.js` | SQL for dashboard (4 parallel queries) |
| `server/models/teacher/TeacherProfileModel.js` | SQL for profile |
| `server/models/teacher/TeacherReportModel.js` | SQL for reports/my-classes |
| `server/models/teacher/TeacherCoordinatorModel.js` | SQL for coordinators |
| `server/models/teacher/TeacherTimetableModel.js` | SQL for timetable |
| `client/src/pages/Teacher/TeacherDashboard.js` | consumes `/dashboard` |
| `client/src/pages/Teacher/MyProfile.js` | consumes `/profile` |
| `client/src/pages/Teacher/MyStudents.js` | consumes `/cohorts`, `/batches`, `/students`, `/students/:id/inactive-history` |
| `client/src/pages/Teacher/BatchCoordinators.js` | consumes `/coordinators` |
| `client/src/pages/Teacher/TeacherReports.js` | consumes `/reports/my-classes` |
| `client/src/pages/Teacher/TimeTable.js` | consumes `/cohorts`, `/batches`, `/timetable` |

Note: `getCohortsController`/`getBatchesController` actually live in `TeacherStudentController.js` (not a separate cohort/batch controller file — unlike the coordinator module, which splits these into `cohortController.js`/`batchController.js`).

**The `auth` middleware** (`server/middleware/authMiddleware.js`, module-level singleton, same file used by every other module including coordinator):
```js
const authenticate = (req, res, next) => {
  const authHeader = req.headers.authorization;
  if (!authHeader) {
    return res.status(401).json({ error: "No token provided" });
  }
  const token = authHeader.split(" ")[1];
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET);
    req.user = decoded; // { user_id, role, ... }
    next();
  } catch (err) {
    if (err?.name === "TokenExpiredError") {
      return res.status(401).json({ error: "Token expired", code: "TOKEN_EXPIRED" });
    }
    return res.status(401).json({ error: "Invalid token", code: "TOKEN_INVALID" });
  }
};
```
Pure JWT-signature/expiry verification. **No role check** — any authenticated user (any role) can call every one of these 9 routes; there is no `role_name === 'TEACHER'` guard anywhere in this router or its controllers. This matches the coordinator module's `authenticate` (no role enforcement there either).

**How "the teacher" is identified (the crux):** every one of the 9 handlers pulls `const userId = req.user.user_id;` from the decoded JWT payload, and every SQL query filters via `pp.teacher t ... WHERE t.user_id = $1` (or, for timetable, `tch.user_id = $1` where `tch` is aliased `pp.teacher`). **The JWT `user_id` is NOT a teacher_id — it is `pp."user".user_id`, and every query re-derives the teacher row(s) by joining `pp.teacher` on `t.user_id = $1` inline** (no separate "resolve teacher_id first" step; no handler ever does a standalone `SELECT teacher_id FROM pp.teacher WHERE user_id=$1` — it's always folded into the main query's JOIN/WHERE). Confirmed the JWT payload really carries `pp.user.user_id`: both `loginController.js:77` (`user_id: user.user_id`) and `authorizeRoleController.js:38` (`user_id: decoded.user_id`) sign it straight from the `pp."user"` row. See §4 for full detail per endpoint.

## 1. Endpoint Inventory (9 routes, all mounted at `/api/teacher`, all behind `auth`)

| # | Method | Path | Controller fn (file) | Purpose | Teacher scoping |
|---|--------|------|----------------------|---------|------------------|
| 1 | GET | `/cohorts` | `getCohortsController` (TeacherStudentController.js) | Distinct cohorts reachable through the teacher's classrooms | `JOIN pp.teacher t ... WHERE t.user_id = $1` |
| 2 | GET | `/batches` | `getBatchesController` (TeacherStudentController.js) | Distinct batches reachable through the teacher's classrooms, optional `?cohort_number=` filter | `JOIN pp.teacher t ... WHERE t.user_id = $1 [AND b.cohort_number = $2]` |
| 3 | GET | `/timetable` | `getTimetableController` (TeacherTimetableController.js) | Weekly timetable rows for the teacher's classrooms, optional `?batchId=` filter | `INNER JOIN pp.teacher tch ... WHERE tch.user_id = $1 [AND cb.batch_id = $2]` |
| 4 | GET | `/students` | `getStudentsController` (TeacherStudentController.js) | List the teacher's students, optional `?cohortNumber=&batchId=` filter (both required together to switch query) | `WHERE t.user_id = $1 [AND c.cohort_number=$2 AND b.batch_id=$3]` |
| 5 | GET | `/students/:id/inactive-history` | `getInactiveHistoryController` (TeacherStudentController.js) | Inactive-history log rows for one student | **NOT scoped to teacher at all** — queries `pp.inactive_students WHERE student_id=$1` only; any authenticated user can read any student's inactive history by guessing an id (see §7) |
| 6 | GET | `/profile` | `getTeacherProfileController` (TeacherProfileController.js) | The logged-in teacher's own profile + subjects/classrooms summary | `WHERE t.user_id = $1` |
| 7 | GET | `/coordinators` | `getTeacherCoordinatorsController` (TeacherCoordinatorController.js) | Batch coordinators sharing a batch with this teacher | `JOIN pp.teacher t ... WHERE t.user_id = $1` |
| 8 | GET | `/dashboard` | `getTeacherDashboardController` (TeacherDashboardController.js) | Aggregate stats: totals, subject breakdown, monthly trend, batch count | `WHERE t.user_id = $1` in all 4 sub-queries |
| 9 | GET | `/reports/my-classes` | `getMyClassReportsController` (TeacherReportController.js) | Session-level class list for the teacher, date-ranged, with attendance-marked flag | `JOIN pp.teacher t ... WHERE t.user_id = $1 AND cs.session_date BETWEEN $2 AND $3` |

**Route order note:** `/students/:id/inactive-history` sits after the static `/students` route in the router file — no Express param-vs-static collision (different path shapes, not an ordering bug like elsewhere in the codebase).

## 2. Exact SQL (verbatim)

### #1 GET /cohorts — `getCohortsController` → inline SQL in `TeacherStudentController.js:16-25`
```sql
SELECT DISTINCT c.cohort_number, c.cohort_name 
FROM pp.cohort c
JOIN pp.batch b ON c.cohort_number = b.cohort_number
JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
WHERE t.user_id = $1
ORDER BY c.cohort_number DESC
```
`$1` = `req.user.user_id` (JWT). No dynamic SQL.

### #2 GET /batches — `getBatchesController` → inline SQL in `TeacherStudentController.js:43-59`
```sql
SELECT DISTINCT b.batch_id, b.batch_name 
FROM pp.batch b
JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
WHERE t.user_id = $1
-- appended only if req.query.cohort_number is truthy:
  AND b.cohort_number = $2
ORDER BY b.batch_name ASC
```
`$1` = `req.user.user_id`. `$2` = `req.query.cohort_number` (raw query string value, bound as a parameter — **not** string-interpolated, so no injection surface despite the string-concatenation style; Postgres will implicitly cast the text param to integer for `cohort_number`). String-concatenation of the `AND` clause is structural (adds a whole clause), not value-interpolation — safe.

### #3 GET /timetable — `getTimetableController` → `getTimetableByBatchAndTeacher` in `TeacherTimetableModel.js:3-53`
```sql
SELECT DISTINCT
    t.timetable_id,
    t.day_of_week,
    t.start_time,
    t.end_time,
    c.classroom_id,
    c.classroom_name,
    c.class_link,
    s.subject_name,
    s.subject_code,
    tch.teacher_name,
    CASE t.day_of_week 
        WHEN 'SUNDAY' THEN 1
        WHEN 'MONDAY' THEN 2
        WHEN 'TUESDAY' THEN 3
        WHEN 'WEDNESDAY' THEN 4
        WHEN 'THURSDAY' THEN 5
        WHEN 'FRIDAY' THEN 6
        WHEN 'SATURDAY' THEN 7
    END as day_order
FROM pp.timetable t
JOIN pp.classroom c ON t.classroom_id = c.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
-- if batchId provided:
JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
WHERE tch.user_id = $1 AND cb.batch_id = $2
-- else (no batchId):
WHERE tch.user_id = $1
ORDER BY day_order, t.start_time
```
`$1` = `req.user.user_id`. `$2` = `req.query.batchId` (only bound when present — two distinct query strings built via string branching, not concatenated user input into the SQL text itself; safe, parameterized).

### #4 GET /students — `getStudentsController` → two model functions in `TeacherStudentModel.js`, chosen by controller logic
Controller (`TeacherStudentController.js:72-101`): if both `cohortNumber` AND `batchId` query params are present, calls `getStudentsByTeacherBatch`; otherwise (i.e. if either is missing) calls `getStudentsByTeacher` (all students, unfiltered by cohort/batch).

Shared column list `STUDENT_SELECT` (`TeacherStudentModel.js:6-40`):
```sql
sm.student_id,
sm.applicant_id,
sm.enr_id,
sm.student_name,
sm.gender,
sm.father_name,
sm.father_occupation,
sm.mother_name,
sm.mother_occupation,
sm.student_email,
sm.student_email_password,
sm.parent_email,
sm.contact_no1,
sm.contact_no2,
sm.home_address,
sm.current_institute_dise_code,
sm.previous_institute_dise_code,
ci.institute_name AS current_institute,
pi.institute_name AS previous_institute,
sm.sim_name,
sm.teacher_name,
sm.teacher_mobile_number,
sm.active_yn,
sm.recharge_status,
sm.sponsor,
sm.photo_link,
sm.batch_id,
b.batch_name,
c.cohort_number,
c.cohort_name,
ins.inactive_reason,
sm.created_at,
sm.updated_at
```

`getStudentsByTeacher(user_id)` (`TeacherStudentModel.js:46-91`):
```sql
SELECT DISTINCT
    <STUDENT_SELECT>
FROM pp.teacher t
JOIN pp.classroom cr ON cr.teacher_id = t.teacher_id
JOIN pp.classroom_batch cb ON cb.classroom_id = cr.classroom_id
JOIN pp.batch b ON b.batch_id = cb.batch_id
JOIN pp.cohort c ON c.cohort_number = b.cohort_number
JOIN pp.student_master sm ON sm.batch_id = b.batch_id
LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
LEFT JOIN pp.inactive_students ins
    ON ins.student_id = sm.student_id
   AND sm.active_yn='INACTIVE'
WHERE t.user_id = $1
ORDER BY c.cohort_number, b.batch_name, sm.student_name
```
`$1` = `user_id` (== `req.user.user_id`).

**Quirk:** the `LEFT JOIN pp.inactive_students ins` has no `ORDER BY inactive_date DESC LIMIT 1` and no dedup guard — a student with multiple `pp.inactive_students` rows (append-only log per §3) will **fan out into duplicate result rows**, one per inactive-history entry, each carrying a different `ins.inactive_reason`. The client (`MyStudents.js:104`) works around this by de-duplicating on `student_id` client-side (`new Map(data.map(item => [item.student_id, item]))`), silently keeping only the **last** row in array order (i.e. an arbitrary `inactive_reason` — whichever JOIN row Postgres returns last). Any Java port must either replicate the same "de-dupe by taking the last row" behavior or fix the query (e.g. `DISTINCT ON (sm.student_id)` / correlated subquery latest-reason) — **note this changes wire output only if there are ties in ordering, so a safe port is to add `DISTINCT ON` ordered the same way and document the behavior change**, or replicate byte-for-byte by not deduping in SQL and doing the same last-wins client reducer server-side.

`getStudentsByTeacherBatch(user_id, cohortNumber, batchId)` (`TeacherStudentModel.js:97-150`):
```sql
SELECT DISTINCT
    <STUDENT_SELECT>
FROM pp.teacher t
JOIN pp.classroom cr ON cr.teacher_id=t.teacher_id
JOIN pp.classroom_batch cb ON cb.classroom_id=cr.classroom_id
JOIN pp.batch b ON b.batch_id=cb.batch_id
JOIN pp.cohort c ON c.cohort_number=b.cohort_number
JOIN pp.student_master sm ON sm.batch_id=b.batch_id
LEFT JOIN pp.institute ci ON ci.dise_code=sm.current_institute_dise_code
LEFT JOIN pp.institute pi ON pi.dise_code=sm.previous_institute_dise_code
LEFT JOIN pp.inactive_students ins
    ON ins.student_id=sm.student_id
   AND sm.active_yn='INACTIVE'
WHERE
    t.user_id=$1
    AND c.cohort_number=$2
    AND b.batch_id=$3
ORDER BY sm.student_name
```
`$1` = `user_id`, `$2` = `cohortNumber`, `$3` = `batchId` — all bound params. Same fan-out quirk on `pp.inactive_students` as above.

### #5 GET /students/:id/inactive-history — `getInactiveHistoryController` → `getInactiveHistoryByStudentId` in `TeacherStudentModel.js:156-174`
```sql
SELECT
    inactive_reason,
    inactive_date,
    created_by,
    updated_by
FROM pp.inactive_students
WHERE student_id=$1
ORDER BY inactive_date DESC
```
`$1` = `req.params.id` (the student id from the URL path — **not the JWT user_id**). **No teacher-ownership check whatsoever** — see §7.1 landmine. Parameterized, so no SQL-injection risk, but an authorization/IDOR gap: any authenticated user (teacher or otherwise) can pull any student's inactive-history by id.

### #6 GET /profile — `getTeacherProfileController` → `getTeacherProfileByUserId` in `TeacherProfileModel.js:3-33`
```sql
SELECT 
    t.teacher_id,
    t.teacher_name,
    t.qualification,
    t.experience_yrs,
    t.doj,
    t.contact_no,
    u.user_name AS username,
    (
        SELECT string_agg(DISTINCT s.subject_name || ' (' || ts.medium || ')', ', ')
        FROM pp.teacher_subject ts
        JOIN pp.subject s ON ts.subject_id = s.subject_id
        WHERE ts.teacher_id = t.teacher_id
    ) AS subjects_taught,
    (
        SELECT string_agg(DISTINCT c.classroom_name, ', ')
        FROM pp.classroom c
        WHERE c.teacher_id = t.teacher_id
    ) AS assigned_classrooms
FROM pp.teacher t
JOIN pp.user u ON t.user_id = u.user_id
WHERE t.user_id = $1
```
`$1` = `req.user.user_id`. Note: query references `pp.user` (no schema-qualifier quoting issue in Node/pg since `user` is only a reserved word in raw DDL contexts, not as an unquoted identifier reference after `pp.` — Postgres requires it quoted as `pp."user"` only when creating/declaring, not always when referencing, but **verify at Java/JdbcClient level**; the DDL table is `pp."user"` per V1__baseline.sql:1221). Controller post-processes: sets `profile.photo_link = \`user-photos/${userId}.jpg\`` (hardcoded convention, not read from any DB column — `pp.teacher` has no photo_link column at all, see §3).

### #7 GET /coordinators — `getTeacherCoordinatorsController` → `getCoordinatorsForTeacher` in `TeacherCoordinatorModel.js:3-32`
```sql
SELECT 
    u.user_id,
    u.full_name,
    u.user_email,
    u.contact_no,
    u.active_yn,
    string_agg(DISTINCT b.batch_name, ', ') AS shared_batches
FROM pp.teacher t
JOIN pp.classroom cl ON t.teacher_id = cl.teacher_id
JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
JOIN pp.batch b ON cb.batch_id = b.batch_id
JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
JOIN pp.user u ON bcb.user_id = u.user_id
WHERE t.user_id = $1 
  AND u.active_yn = 'Y'
GROUP BY 
    u.user_id, 
    u.full_name, 
    u.user_email, 
    u.contact_no, 
    u.active_yn
ORDER BY u.full_name ASC
```
`$1` = `req.user.user_id`. Controller post-processes: injects `photo_link: \`user-photos/${coord.user_id}.jpg\`` per row (same hardcoded convention as profile — `pp."user"` has no photo_link column either, see §3).

### #8 GET /dashboard — `getTeacherDashboardController` → `getTeacherDashboardStats` in `TeacherDashboardModel.js:3-67` (4 queries run via `Promise.all`, i.e. concurrently but NOT in a DB transaction)

Query 1 — overall stats:
```sql
SELECT 
    COUNT(DISTINCT cs.session_id) as total_conducted,
    COALESCE(ROUND(AVG(sa.attendance_percent), 2), 0) as avg_attendance
FROM pp.teacher t
LEFT JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
LEFT JOIN pp.student_attendance sa ON cs.session_id = sa.session_id
WHERE t.user_id = $1
```

Query 2 — subject breakdown:
```sql
SELECT 
    s.subject_name,
    COUNT(cs.session_id) as classes_taken
FROM pp.teacher t
JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
JOIN pp.subject s ON c.subject_id = s.subject_id
WHERE t.user_id = $1
GROUP BY s.subject_name
ORDER BY classes_taken DESC
```

Query 3 — monthly trend (last 6 months by data, NOT calendar-clamped to "last 6 calendar months" — it's just `LIMIT 6` on ascending month order, i.e. the earliest 6 months of the teacher's session history, not the most recent 6; see §7.2):
```sql
SELECT 
    TO_CHAR(cs.session_date, 'Mon YYYY') as month_label,
    COUNT(cs.session_id) as classes_taken
FROM pp.teacher t
JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
WHERE t.user_id = $1
GROUP BY TO_CHAR(cs.session_date, 'Mon YYYY'), DATE_TRUNC('month', cs.session_date)
ORDER BY DATE_TRUNC('month', cs.session_date) ASC
LIMIT 6
```

Query 4 — active batch count:
```sql
SELECT COUNT(DISTINCT cb.batch_id) as total_batches
FROM pp.teacher t
JOIN pp.classroom c ON t.teacher_id = c.teacher_id
JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
WHERE t.user_id = $1
```
All 4: `$1` = `req.user.user_id` (same value bound independently in each of the 4 queries — no shared teacher_id resolution step). Results merged in JS:
```js
return {
  overview: { ...statsRes.rows[0], total_batches: batchRes.rows[0].total_batches },
  subjectAnalysis: subjectRes.rows,
  monthlyTrend: monthRes.rows
};
```

### #9 GET /reports/my-classes — `getMyClassReportsController` → `getMyClassReports` in `TeacherReportModel.js:3-39`
```sql
SELECT 
    cs.session_id,
    cs.session_date AS date,
    co.cohort_name,
    string_agg(DISTINCT b.batch_name, ', ') AS batch_name,
    c.classroom_name,
    s.subject_name,
    EXISTS (
        SELECT 1 
        FROM pp.student_attendance sa 
        WHERE sa.session_id = cs.session_id
    ) AS attendance_marked
FROM pp.class_session cs
JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
JOIN pp.subject s ON c.subject_id = s.subject_id
JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
JOIN pp.batch b ON cb.batch_id = b.batch_id
JOIN pp.cohort co ON b.cohort_number = co.cohort_number
WHERE t.user_id = $1
  AND cs.session_date >= $2 
  AND cs.session_date <= $3
GROUP BY 
    cs.session_id,
    cs.session_date,
    co.cohort_name,
    c.classroom_name,
    s.subject_name
ORDER BY cs.session_date ASC
```
`$1` = `req.user.user_id`, `$2` = `req.query.fromDate`, `$3` = `req.query.toDate`. Controller requires both `fromDate`/`toDate` present, else `400 {"error": "fromDate and toDate are required"}` — no server-side date format/range validation beyond presence (raw strings passed straight into the parameterized query; Postgres will error on genuinely malformed dates, not silently misbehave — but there's no min/max sanity check, e.g. `fromDate > toDate` is not rejected, it will just return 0 rows).

**No dynamic/string-interpolated SQL anywhere in this module** — every `$1..$n` is a bound parameter. The only string-building is structural (appending whole clauses like `AND b.cohort_number = $2` based on presence of a filter), which is the same safe pattern used throughout the coordinator module.

## 3. Table DDL (from `imas-backend/src/main/resources/db/migration/V1__baseline.sql`, authoritative)

### pp.teacher (PK: teacher_id) — V1__baseline.sql:1131-1144
```
teacher_id      integer DEFAULT nextval('pp.teacher_id_seq') NOT NULL   -- PK
user_id         numeric(8,0)                          -- the JWT→teacher link column (NO explicit FK constraint declared in this excerpt, but semantically -> pp."user".user_id)
teacher_name    varchar(150)
qualification   varchar(150)
experience_yrs  integer                                -- CHECK >= 0
doj             date
contact_no      varchar(12)
created_at/updated_at, created_by/updated_by
```
**Confirmed: `pp.teacher.user_id` EXISTS** — the JWT→teacher identity chain is valid. No `photo_link` column on `pp.teacher` (matches the hardcoded `user-photos/${userId}.jpg` convention in the profile controller — not a DB read).

### pp.teacher_subject (composite PK: teacher_id, subject_id) — V1__baseline.sql:1146-1151
```
teacher_id  integer NOT NULL   -- FK -> pp.teacher(teacher_id)
subject_id  integer NOT NULL   -- FK -> pp.subject(subject_id)
medium      varchar(20) DEFAULT 'KANNADA' NOT NULL   -- CHECK IN ('ENGLISH','KANNADA','HINDI','MARATHI')
```
Used by `/profile`'s `subjects_taught` subquery.

### pp.classroom (PK: classroom_id) — V1__baseline.sql:229-243
```
classroom_id   integer DEFAULT nextval('pp.classroom_id_seq') NOT NULL  -- PK
classroom_name varchar(100) NOT NULL
subject_id     integer     -- FK -> pp.subject(subject_id)
teacher_id     integer     -- FK -> pp.teacher(teacher_id) -- the "owning teacher" of the classroom
platform_id    integer
description    varchar(200)
active_yn      char(1) DEFAULT 'Y'  -- CHECK IN ('Y','N')
class_link     varchar(150)
created_at/updated_at, created_by/updated_by
```
Note: `/cohorts`, `/batches`, `/students`, `/coordinators` all filter classrooms only by `teacher_id`, with **no `active_yn='Y'` filter** — inactive (soft-deleted) classrooms still surface the teacher's cohorts/batches/students/coordinators. Differs from the coordinator module's `/classrooms/:batchId` route which does filter `active_yn='Y'` for active-only classrooms.

### pp.classroom_batch (composite key: classroom_id, batch_id — pure junction table, no PK declared beyond the two columns) — V1__baseline.sql:245-248
```
classroom_id  integer NOT NULL   -- FK -> pp.classroom(classroom_id)
batch_id      integer NOT NULL   -- FK -> pp.batch(batch_id)
```

### pp.batch (PK: batch_id) — V1__baseline.sql:182-193
```
batch_id       integer DEFAULT nextval('pp.batch_id_seq') NOT NULL  -- PK
batch_name     varchar(100)
cohort_number  integer   -- FK -> pp.cohort(cohort_number)
medium         varchar(20) DEFAULT 'KANNADA'  -- CHECK IN ('ENGLISH','KANNADA','HINDI','MARATHI')
house_name     varchar(100)
created_at/updated_at, created_by/updated_by
```
**No `coordinator_id` column** — see §7.4 (dead-code landmine, not reachable from this module but flagged for completeness).

### pp.cohort (PK: cohort_number) — V1__baseline.sql:257-271
```
cohort_number  integer DEFAULT nextval('pp.cohort_seq') NOT NULL  -- PK
cohort_name    varchar(100)
start_date/end_date date
description    text
status         varchar(20)   -- CHECK IN ('ACTIVE','COMPLETED')
current_grade  integer       -- CHECK IN (9,10,11,12)
created_at/updated_at, created_by/updated_by
```

### pp.student_master (PK: student_id) — V1__baseline.sql:983-1017
```
student_id                      numeric(14,0) DEFAULT nextval('pp.student_id_seq') NOT NULL  -- PK
applicant_id                    numeric(14,0)
enr_id                           numeric(11,0)
student_name                     varchar(100)
father_name / father_occupation  varchar(100)
mother_name / mother_occupation  varchar(100)
gender                           char(1)  -- CHECK IN ('M','F','O')
batch_id                         integer  -- FK -> pp.batch(batch_id)
sim_name                         varchar(10)
student_email                    varchar(150)
student_email_password           varchar(100)
parent_email                     varchar(150)
photo_link                       text
home_address                     varchar(200)
contact_no1/contact_no2          varchar(12)
current_institute_dise_code      varchar(15)  -- FK -> pp.institute(dise_code)
previous_institute_dise_code     varchar(15)  -- FK -> pp.institute(dise_code)
active_yn                        varchar(10) DEFAULT 'ACTIVE'  -- CHECK IN ('ACTIVE','INACTIVE')
recharge_status                  varchar(20)  -- CHECK IN ('GRANTED','NOT GRANTED')
sponsor                          varchar(100)
teacher_name                     varchar(100)
teacher_mobile_number             varchar(12)
created_at/updated_at, created_by/updated_by
user_id                          numeric
```
All columns referenced by `STUDENT_SELECT` exist. Note `student_master.teacher_name`/`teacher_mobile_number` are free-text snapshot columns (not a live FK join to `pp.teacher`) — matches `STUDENT_SELECT`'s use of `sm.teacher_name`/`sm.teacher_mobile_number` directly.

### pp.inactive_students (NO PK, append-only log) — V1__baseline.sql:542-548
```
student_id       numeric(14,0)   -- FK -> pp.student_master(student_id) (semantically; no PK/unique on this table)
inactive_reason  varchar(200)
inactive_date    date
created_by/updated_by numeric(8,0)
```
Confirmed: multiple rows per student are legal (no unique constraint) — this is what causes the fan-out quirk noted in §2 for `getStudentsByTeacher`/`getStudentsByTeacherBatch`.

### pp.institute (PK: institute_id) — V1__baseline.sql:557-586
Relevant columns used: `dise_code varchar(15)`, `institute_name varchar(200)`. Both exist; used for `ci`/`pi` LEFT JOINs.

### pp.subject (PK: subject_id) — V1__baseline.sql:1054-1062
```
subject_id    integer DEFAULT nextval('pp.subject_id_seq') NOT NULL  -- PK
subject_code  varchar(5) NOT NULL
subject_name  varchar(100) NOT NULL
created_at/updated_at, created_by/updated_by
```

### pp.timetable (PK: timetable_id) — V1__baseline.sql:1201-1212
```
timetable_id  integer DEFAULT nextval('pp.timetable_id_seq') NOT NULL  -- PK
classroom_id  integer  -- FK -> pp.classroom(classroom_id)
day_of_week   varchar(10)  -- CHECK IN ('SUNDAY'..'SATURDAY')
start_time/end_time  time without time zone NOT NULL
created_at/updated_at, created_by/updated_by
```

### pp.class_session (PK: session_id) — V1__baseline.sql:207-220
```
session_id        integer DEFAULT nextval('pp.class_session_seq') NOT NULL  -- PK
classroom_id       integer NOT NULL   -- FK -> pp.classroom(classroom_id)
session_date        date NOT NULL
start_time/end_time  time without time zone NOT NULL
timetable_id         integer
created_at/updated_at, created_by/updated_by
duration_minutes     integer
teacher_id            integer   -- direct FK -> pp.teacher(teacher_id)
```
**Confirmed: `pp.class_session.teacher_id` EXISTS as its own column** (not derived through `classroom.teacher_id`) — the dashboard and reports queries join `pp.teacher t ON cs.teacher_id = t.teacher_id` directly against this column, which is valid. This means a session's teacher can, in principle, differ from its classroom's "default" teacher (per the coordinator module's own note about this same duality) — the teacher-portal dashboard/reports correctly use `cs.teacher_id`, not `c.teacher_id`, for session attribution.

### pp.student_attendance (PK: attendance_id) — V1__baseline.sql:880-895
```
attendance_id       integer DEFAULT nextval('pp.attendance_id_seq') NOT NULL  -- PK
session_id           integer   -- FK -> pp.class_session(session_id)
student_id            numeric(14,0)   -- FK -> pp.student_master(student_id)
status                varchar(20) NOT NULL  -- CHECK IN ('PRESENT','ABSENT','LATE JOINED','LEAVE')
time_joined/time_exited  time without time zone
attendance_percent     numeric(5,2)
remarks                varchar(200)
created_at/updated_at, created_by/updated_by
duration_minutes       integer
```
`attendance_percent` is `numeric(5,2)` — node-pg returns numeric as a **string**, not a JS number (see §5 serialization note); `AVG(sa.attendance_percent)` in the dashboard query also returns numeric-as-string via `pg`.

### pp.batch_coordinator_batches (composite key: user_id, batch_id) — V1__baseline.sql:195-198
```
user_id   numeric(8,0) NOT NULL   -- FK -> pp."user".user_id (semantically)
batch_id  integer NOT NULL        -- FK -> pp.batch(batch_id)
```

### pp."user" (PK: user_id) — V1__baseline.sql:1221-1238
```
user_id        numeric(8,0) DEFAULT nextval('pp.user_id_seq') NOT NULL  -- PK
user_name       varchar(100) NOT NULL
enc_password    varchar(300)
locked_yn       char(1)   -- CHECK IN ('Y','N')
full_name       varchar(150)
user_email      varchar(150)
contact_no      varchar(15)
active_yn       char(1) DEFAULT 'Y'  -- CHECK IN ('Y','N')
last_login_at/password_changed_at timestamp
created_at/updated_at, created_by/updated_by
```
All columns referenced by `/profile` (`u.user_name`) and `/coordinators` (`u.user_id, u.full_name, u.user_email, u.contact_no, u.active_yn`) exist. **No `photo_link` column** — matches the hardcoded `user-photos/${...}.jpg` convention used for both teacher and coordinator photos (not DB-backed; a static file-path convention the client resolves against `${BACKEND}/user-photos/{id}.jpg`).

**Cross-check result: every table and column referenced anywhere in this module's live SQL exists in `V1__baseline.sql`. No schema-vs-code mismatch found (no `event_students`-style landmine in this module).**

## 4. Identity/Scoping Detail — JWT → Teacher chain

Exact chain, confirmed end-to-end:

1. **Login** (`loginController.js:75-80`) signs a pre-auth JWT with `user_id: user.user_id` (from `pp."user"`).
2. **Role selection** (`authorizeRoleController.js:36-41`) signs the **final** access token, again with `user_id: decoded.user_id` (carried through unchanged from step 1) plus `role_name`.
3. **`auth` middleware** (`authMiddleware.js`) verifies the final token's signature/expiry and sets `req.user = decoded` — so `req.user.user_id` is `pp."user".user_id` of whoever logged in, regardless of `role_name`.
4. **Every one of the 8 scoped handlers** (all except `/students/:id/inactive-history`) takes `req.user.user_id` and joins it directly against `pp.teacher.user_id` inline in the main query — there is **no separate "resolve teacher_id from user_id" pre-step**; the join `JOIN pp.teacher t ON ... WHERE t.user_id = $1` (or equivalent) does double duty as both the identity resolution and the ownership filter, in one round trip.
5. If the logged-in user has **no matching row** in `pp.teacher` (e.g. a coordinator or admin account hitting these endpoints — nothing stops them, since there's no role guard), every query silently returns **zero rows** (or, for `/dashboard`, `NULL`/0-ish aggregates via the `LEFT JOIN` in query 1, but **empty arrays/errors** for the `JOIN`-only queries 2-4 and for `/cohorts`, `/batches`, `/students`, `/coordinators`, `/reports/my-classes`, `/timetable`, `/profile` which all use `JOIN pp.teacher`, not `LEFT JOIN`). `/profile` specifically will return `404 {"error": "Teacher profile not found"}` since `rows[0]` is undefined.

**Java port implication:** the JWT principal's `userId()` should be bound directly as the SQL parameter that gets compared to `pp.teacher.user_id` inline in each query — mirror the Node structure exactly (no separate teacher-id-lookup query/service call), to preserve both behavior and the "0 rows if not a teacher" semantics.

## 5. Response Shapes & Status Codes

| # | Endpoint | 200 body | Error cases |
|---|---|---|---|
| 1 | `/cohorts` | `[{cohort_number, cohort_name}, ...]` (raw array) | 500 `{error:"Internal Server Error"}` |
| 2 | `/batches` | `[{batch_id, batch_name}, ...]` (raw array) | 500 `{error:"Internal Server Error"}` |
| 3 | `/timetable` | `[{timetable_id, day_of_week, start_time, end_time, classroom_id, classroom_name, class_link, subject_name, subject_code, teacher_name, day_order}, ...]` (raw array, includes the internal `day_order` sort helper column — client ignores it, groups by `day_of_week` client-side per `TimeTable.js:174-178`) | 500 `{error:"Internal Server Error"}` |
| 4 | `/students` | `[{...STUDENT_SELECT fields...}, ...]` (raw array) | 500 `{error:"Failed to fetch students."}` |
| 5 | `/students/:id/inactive-history` | `[{inactive_reason, inactive_date, created_by, updated_by}, ...]` (raw array, possibly empty) | 500 `{error:"Failed to fetch inactive history."}` |
| 6 | `/profile` | `{teacher_id, teacher_name, qualification, experience_yrs, doj, contact_no, username, subjects_taught, assigned_classrooms, photo_link}` (single object; `photo_link` is controller-injected, not from SQL) | 404 `{error:"Teacher profile not found"}` if no `pp.teacher` row for this user_id; 500 `{error:"Internal Server Error"}` |
| 7 | `/coordinators` | `[{user_id, full_name, user_email, contact_no, active_yn, shared_batches, photo_link}, ...]` (`photo_link` controller-injected per row) | 500 `{error:"Internal Server Error"}` |
| 8 | `/dashboard` | `{overview: {total_conducted, avg_attendance, total_batches}, subjectAnalysis: [{subject_name, classes_taken}], monthlyTrend: [{month_label, classes_taken}]}` | 500 `{error:"Internal Server Error"}` |
| 9 | `/reports/my-classes` | `{classes: [{session_id, date, cohort_name, batch_name, classroom_name, subject_name, attendance_marked}, ...]}` (wrapped in a `classes` key, unlike all other endpoints which return bare arrays) | 400 `{error:"fromDate and toDate are required"}` if either query param missing; 500 `{error:"Internal Server Error"}` |

**Auth failures (all 9 routes, from `auth` middleware, before any handler runs):** 401 `{error:"No token provided"}` (missing header); 401 `{error:"Token expired", code:"TOKEN_EXPIRED"}`; 401 `{error:"Invalid token", code:"TOKEN_INVALID"}`.

**Numeric-id serialization (node-pg defaults, no custom type parsers found in `config/db.js` for this module):**
- `integer` columns (`cohort_number` from `cohort_seq`... wait, `cohort_number` is `integer` — returned as JS **number**; `batch_id integer` → JS number; `classroom_id`, `timetable_id`, `session_id`, `subject_id`, `teacher_id` → all `integer` → JS **number**.
- `numeric`/`bigint`-typed columns → node-pg returns these as **strings** by default: `student_id numeric(14,0)` → string; `enr_id numeric(11,0)` → string; `applicant_id numeric(14,0)` → string; `user_id numeric(8,0)` (on `pp.teacher`, `pp."user"`, `pp.batch_coordinator_batches`) → string; `attendance_percent numeric(5,2)` (dashboard `avg_attendance`) → string; `experience_yrs integer` on `pp.teacher` → JS number (it's declared `integer`, not `numeric`).
- The client reads these largely as opaque display strings (e.g. `s.student_id` used only as a React key and in a URL path segment for inactive-history — string-vs-number doesn't matter there), and does `parseInt(...)` explicitly where it needs a number for chart math (`TeacherDashboard.js:32-33` — `parseInt(s.classes_taken)`, `parseInt(m.classes_taken)` — `classes_taken` from `COUNT(...)` is `bigint` in Postgres → node-pg returns it as a **string** too, hence the explicit `parseInt`). **Java/JdbcClient port must replicate string-vs-number typing per column** to avoid breaking client math/display (numeric/bigint columns → serialize as JSON string; integer columns → JSON number) — this is the same convention documented in prior phase ground-truths (phase4e, phase4a, etc.) for this codebase.
- `total_conducted` (dashboard query 1) is `COUNT(DISTINCT cs.session_id)` → Postgres `bigint` → string. `total_batches` (query 4) same → string. `classes_taken` (queries 2 & 3) same → string.

## 6. Transactions

**None.** All 9 endpoints are pure reads. The dashboard's 4 queries run concurrently via `Promise.all` on 4 separate pooled connections (no explicit `BEGIN`/`COMMIT`, no `pool.connect()`+client reuse) — there's no cross-query consistency guarantee (e.g. a session could be inserted between query 1 and query 3 finishing, though in practice this is a read-only self-service dashboard so the race window is inconsequential). Java port: plain `JdbcClient` queries, no `@Transactional` needed; the 4 dashboard queries can be run sequentially or in parallel (e.g. via `CompletableFuture`) without changing observable behavior, since Postgres MVCC gives each its own read snapshot either way and the Node version has no isolation guarantee to preserve.

## 7. Quirks & Complexity (file:line)

### 7.1 `/students/:id/inactive-history` has NO teacher-ownership check (IDOR)
`TeacherStudentController.js:106-121` / `TeacherStudentModel.js:156-174`. The handler takes `req.params.id` and queries `pp.inactive_students WHERE student_id=$1` directly — it never checks that the student belongs to a batch/classroom the calling teacher teaches. Contrast with `/students` (#4) and every other endpoint, all scoped via `pp.teacher.user_id`. Any authenticated user (teacher, coordinator, or otherwise, since there's no role check either — §0) can read any student's inactive-history log by iterating ids. **This is a pre-existing Node behavior to preserve byte-for-byte** (frozen wire contract) — flag it in the plan as a known gap, but do not silently "fix" it in the Java port without a product decision, since doing so would change response behavior (e.g. 403 instead of 200/[] for out-of-scope ids) and break wire compatibility for legitimately-scoped calls that happen to rely on the current permissive behavior. Recommend flagging as a follow-up security fix ticket, ported as-is for phase 5c.

### 7.2 `/dashboard` monthlyTrend is NOT "last 6 months" despite the code comment
`TeacherDashboardModel.js:29-40` comment says `-- 3. Get month-wise trend (Last 6 months)` but the query orders `ASC` then `LIMIT 6` — this returns the **earliest** 6 months with session data in the teacher's entire history, not the most recent 6. For a teacher active more than 6 months, the dashboard will show stale/early months forever and never show recent months. This is a bug in the frozen Node behavior; port verbatim (`ORDER BY DATE_TRUNC('month', cs.session_date) ASC LIMIT 6`), do not silently correct it — flag as a known-bug-to-preserve, same as `event_students`-style landmines in other phases, but this one is a **logic bug**, not a schema bug.

### 7.3 Fan-out duplication on `pp.inactive_students` LEFT JOIN in `/students`
Covered in §2 (query #4). Multiple inactive-history rows per student cause duplicate result rows from `getStudentsByTeacher`/`getStudentsByTeacherBatch`; client (`MyStudents.js:104`) de-dupes via a `Map` keyed by `student_id`, keeping the **last** row encountered in the array (JS `Map` semantics — later `.set()` calls with the same key overwrite). Any Java port that changes row ordering (even while returning the "same" logical rows) could pick a **different** `inactive_reason` to win the de-dupe, since the client dedup is order-dependent and the SQL has no `ORDER BY` on the inactive_students join tiebreaker. If wire-compatibility of `inactive_reason` display is important on this list view, the safest port is to preserve the exact query text (same JOIN, same implicit row order) and let the existing client dedup behavior carry over unchanged — do NOT add a `DISTINCT ON`/dedup in SQL, since that would pick the row by a *different* (likely more "correct" but behaviorally different) rule than "whatever Postgres happens to emit last."

### 7.4 Dead/unreachable code sharing this module's naming space: `getTeachersByCoordinator` references a NON-EXISTENT `pp.batch.coordinator_id`
`server/models/coordinator/teacherModel.js:8-24` (function `getTeachersByCoordinator`) contains `WHERE b.coordinator_id = $1` — **`pp.batch` has NO `coordinator_id` column** (confirmed against V1__baseline.sql:182-193 — only `batch_id, batch_name, cohort_number, created_at, updated_at, created_by, updated_by, medium, house_name`). This function is in the **coordinator** module's `models/coordinator/teacherModel.js`, not this teacher-portal module, and — critically — **is not called from `teacherStudentRoutes.js` or any of its controllers**; it belongs to a different router entirely. Grep confirms none of the 9 teacher-portal routes touch it. **Not reachable from this module — flagged here only because the brief asked to verify overlap; there is none.** If a future coordinator-module phase ports `teacherController.js`/`teacherModel.js` in `controllers/coordinator/` and `models/coordinator/`, that landmine belongs to that phase, not 5c.

### 7.5 Hardcoded photo-path convention (not DB-backed)
Both `/profile` (`TeacherProfileController.js:14`) and `/coordinators` (`TeacherCoordinatorController.js:10-13`) inject `photo_link: \`user-photos/${id}.jpg\`` as a **string template in the controller**, never read from any DB column (neither `pp.teacher` nor `pp."user"` has a `photo_link` column — confirmed in §3). This is a static file-path naming convention the Node/Express static file server presumably resolves under `server/uploads/user-photos/` or similar (not verified in this module's files — out of scope for this ground-truth, but the Java port must replicate the exact same string template, `"user-photos/" + id + ".jpg"`, verbatim, since the client hardcodes `${BACKEND}/${profile.photo_link}` / `${BACKEND}/user-photos/${coord.user_id}.jpg` against it).

### 7.6 `getBatchesController`'s optional-filter pattern duplicated identically across #2 and #3
Both `/batches` and `/timetable` implement "if optional filter param present, add a second bound `$2` and an `AND` clause; else omit it" as two structurally different SQL strings built via JS string concatenation/branching (not a single query with `COALESCE($2, x) IS NULL OR ...`-style optional-param SQL). Functionally equivalent to preserve in Java (e.g. via two `JdbcClient` template variants, or a single query using `(:batchId IS NULL OR cb.batch_id = :batchId)` if the Java team prefers — behaviorally identical output either way since these are pure filters with no side effects, so this is a safe place to consolidate in the port, unlike §7.1/§7.2/§7.3 which are behavior-preserving requirements).

### 7.7 `/dashboard`'s query 1 uses `LEFT JOIN` (teacher-with-zero-sessions still returns a row), but queries 2-4 use `JOIN` (silently return empty array in that case)
`TeacherDashboardModel.js:9-13` (query 1) vs `:20-24`, `:35-36`, `:46-48` (queries 2-4). A teacher with `pp.teacher` row but zero `pp.class_session` rows gets `overview: {total_conducted: "0", avg_attendance: 0, total_batches: "0"}` (COALESCE handles the AVG NULL case; COUNT DISTINCT on LEFT JOIN NULLs naturally yields 0) but `subjectAnalysis: []` and `monthlyTrend: []` (empty, not present-with-zeros) — this asymmetry is inherent to the query shapes and must be preserved (Java: same LEFT JOIN vs JOIN split per query).

### 7.8 No overlap with any coordinator-module report/attendance logic
Confirmed by full read of both this module and (for cross-reference) the coordinator module's report/dashboard queries described in `phase4e-coordinator-ground-truth.md` — no shared query text, no shared model function, no shared controller. The `/reports/my-classes` shape (session list + `attendance_marked` EXISTS flag) superficially resembles the coordinator's `getBatchClassDetails`/`getTeacherClassDetails` (also session lists w/ attendance flags) but is an independently-written, teacher-scoped query with its own SQL text — no code reuse, so no shared-helper extraction opportunity beyond the day-of-week `CASE` ordering trick already noted for the coordinator module (this module's `/timetable` reuses that exact same `CASE t.day_of_week WHEN 'SUNDAY' THEN 1 ...` pattern verbatim — worth using the same shared Java `ORDER BY` day-of-week helper/constant if/when the coordinator module's equivalent gets built, per phase4e §2's note).

## 8. Summary

**Endpoint count: 9.** All GET, all read-only, all behind `auth` (JWT-verify only, no role check). Clean source files — no dead/commented predecessor code anywhere in this module (router, controllers, or models), unlike the coordinator module.

**JWT → Teacher identity chain:** `req.user.user_id` (signed from `pp."user".user_id` at login/role-selection) is bound directly as `$1` in every scoped query's inline `JOIN pp.teacher t ON ... WHERE t.user_id = $1` (or equivalent alias) — no separate teacher-id-resolution step. Confirmed `pp.teacher.user_id` exists in the schema (V1__baseline.sql:1133). If the logged-in user has no `pp.teacher` row, scoped `JOIN`-based endpoints return empty results (or 404 for `/profile`); the one `LEFT JOIN`-based query (dashboard overview) returns zeroed stats instead.

**Schema-vs-code cross-check: PASS — no landmine found.** Every table (`pp.teacher`, `pp.teacher_subject`, `pp.classroom`, `pp.classroom_batch`, `pp.batch`, `pp.cohort`, `pp.student_master`, `pp.inactive_students`, `pp.institute`, `pp.subject`, `pp.timetable`, `pp.class_session`, `pp.student_attendance`, `pp.batch_coordinator_batches`, `pp."user"`) and every column referenced by this module's live SQL exists in `V1__baseline.sql`. No `event_students`-style schema/code mismatch in this module. (The one adjacent-but-unreachable landmine, `getTeachersByCoordinator`'s reference to a non-existent `pp.batch.coordinator_id`, lives in the **coordinator** module's dead-relative-to-this-router code and is not called from any of these 9 routes — noted for completeness in §7.4, not a blocker for 5c.)

**Top risks/landmines for the Java port (ranked):**

1. **§7.1 — `/students/:id/inactive-history` has no teacher-ownership check (IDOR).** Must be ported as-is (wire-compatible) even though it's a security gap; flag for a follow-up ticket rather than silently fixing in this phase.
2. **§7.3 — Duplicate-row fan-out on `/students` from unbounded `pp.inactive_students` LEFT JOIN**, papered over by client-side last-wins dedup. The Java port must preserve the exact same row order/duplication behavior (do not "fix" with `DISTINCT ON`) or the displayed `inactive_reason` for students with multiple history entries could silently change.
3. **§7.2 — Dashboard `monthlyTrend` is mislabeled**: it's the earliest 6 months of data, not the most recent 6, despite the code comment claiming otherwise. Port the exact `ORDER BY ... ASC LIMIT 6` verbatim; do not "fix" to `DESC ... LIMIT 6` + reverse, which is what the comment implies was intended — that would be a behavior change, not a bug fix, without product sign-off.
4. **§5 numeric/bigint-vs-integer JSON serialization** — `student_id`, `enr_id`, `applicant_id`, `user_id`, `attendance_percent`, and every `COUNT(...)` result (`total_conducted`, `total_batches`, `classes_taken`) are Postgres `numeric`/`bigint` and must serialize as JSON **strings** (matching node-pg's default), while `integer`-typed columns (`cohort_number`, `batch_id`, `classroom_id`, `timetable_id`, `session_id`, `subject_id`, `teacher_id`, `experience_yrs`) must serialize as JSON **numbers** — mismatches here will break the client's `parseInt(...)` calls (`TeacherDashboard.js:32-33`) or silently coerce comparisons.
5. **§7.5 hardcoded photo-path convention** (`user-photos/{id}.jpg`) is not DB-backed on either `pp.teacher` or `pp."user"` — must be reproduced as a literal string template in the Java controller, not queried.

**Recommended sub-task split for the plan:** given only 9 endpoints and no writes/transactions, this module does not need a multi-sub-plan split like the coordinator module (37 endpoints → 4 sub-plans). Recommend **a single phase-5c task** covering all 9 routes, grouped internally as: (a) filters — cohorts, batches, timetable (#1-3); (b) students — list + inactive-history (#4-5, flag the IDOR gap inline in code review); (c) profile + coordinators (#6-7, share the photo-path convention); (d) dashboard + reports (#8-9, share the `pp.class_session`/`pp.teacher` join pattern). All read-only `JdbcClient` queries, `@PreAuthorize("isAuthenticated()")` (no role restriction, matching the Node `auth`-only behavior — do NOT add a `hasRole('TEACHER')` check, since that would be a behavior change from the frozen Node contract), teacher scoping via `principal.userId()` bound inline in each query exactly as Node does it.
