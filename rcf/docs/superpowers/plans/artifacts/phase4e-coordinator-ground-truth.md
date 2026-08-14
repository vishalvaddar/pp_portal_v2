# COORDINATOR Module — Ground Truth (for Plan 4e)

Captured from a full read of the live Node source. Mount: `app.use("/api/coordinator", coordinatorRoutes)` (server/index.js). Router file: `server/routes/coordinatorRoutes.js`.

## 0. CRITICAL SCOPING CORRECTION

The task brief estimated **~102 endpoints**. The actual LIVE route count is **37** (36 real API routes + 1 trivial `GET /` "Coordinator Home" text route). `PROJECT-MAP.md` also under-counts it as "4" (stale guess, marked "controller = verify"). Do not plan for 102 — plan for 37.

**Why the file is huge (566 lines) despite only 37 live routes:** `coordinatorRoutes.js` contains **three entire prior versions of the router commented out** (lines 1–380, three successive `// ...` blocks — an old skeleton without institute-search/reports-extras, a middle version, then the one just before current), followed by the live CommonJS router at lines 381–567. Ignore lines 1–380 entirely. Several **controller files also carry dead/commented predecessor functions** above the live exports (`studentController.js`, `attendanceController.js`, `studentModel.js` all have 2-3 stacked historical copies before the live code) — always read to the bottom of the file for the live version, and note the file also exports functions **not wired into this router** (see §8 Quirks).

Also note: `server/controllers/coordinator/studentController1.js` and `server/models/coordinator/inactiveStudentModel.js` are **not imported anywhere in coordinatorRoutes.js** — dead files, not part of the live surface (see §8).

## 1. Endpoint Inventory (37 routes, all mounted at `/api/coordinator`)

All routes use `authenticate` (JWT-verifying middleware, `middleware/authMiddleware.js`) **except** the `/reports/*` group which uses a local `requireAuth` that only checks an `Authorization` header is *present* — it does **not** verify the JWT (see §8.1). No route enforces a COORDINATOR role check; `authenticate` accepts any valid JWT regardless of `role_name`.

| # | Method | Path | Handler (file) | Purpose | Group |
|---|--------|------|-----------------|---------|-------|
| 1 | GET | `/` | inline in routes file | Static "Coordinator Home" text | misc |
| 2 | GET | `/institutes/search` | instituteController.searchInstitutes | ILIKE search dise_code/name, `?q=` (min 3 chars), LIMIT 15 | institutes |
| 3 | GET | `/students` | studentController.getStudentsController | List students (filters: cohortNumber, batchId, classroomId, isAttendance) | students |
| 4 | PUT | `/students/:id` | studentController.updateStudentController | Dynamic-column update; routes to inactive-flow if `active_yn=INACTIVE`+reason | students |
| 5 | PUT | `/students/:id/inactive` | studentController.markInactiveController | Direct mark-inactive + history log | students |
| 6 | GET | `/students/:id/inactive-history` | studentController.getInactiveHistoryController | List inactive history rows | students |
| 7 | GET | `/cohorts` | cohortController.fetchCohorts | Cohorts for coordinator's assigned batches | cohorts/batches |
| 8 | GET | `/batches` | batchController.fetchBatches | Batches for coordinator (optionally filtered by `?cohort_number=`) | cohorts/batches |
| 9 | GET | `/classrooms/:batchId` | classroomController.fetchClassrooms | Classrooms for a batch (active only) | classrooms |
| 10 | GET | `/classrooms` | classroomController.getAllClassrooms | All classrooms (any status) | classrooms |
| 11 | POST | `/classrooms` | classroomController.createClassroom | Insert classroom | classrooms |
| 12 | GET | `/teachers` | classroomController.fetchTeachers | `SELECT teacher_name FROM pp.teacher` (names only, no id!) | classrooms |
| 13 | GET | `/platforms` | classroomController.fetchPlatforms | Teaching platforms dropdown | classrooms |
| 14 | GET | `/subjects` | subjectController.getSubjects | `SELECT *` all subjects | subjects |
| 15 | GET | `/attendance/session` | attendanceController.getOrFindSession | Find existing class_session by classroom+date+start_time | attendance |
| 16 | POST | `/attendance/csv/preview` | attendanceController.previewCSVAttendance | Multipart CSV (Zoom-style) → in-memory preview, NO db write | attendance |
| 17 | POST | `/attendance/csv/commit` | attendanceController.commitCSVAttendance | Transactional commit of previewData → session + attendance rows | attendance |
| 18 | POST | `/attendance/undo` | attendanceController.undoLastAttendanceCommit | Delete attendance+session rows for a session_id | attendance |
| 19 | GET | `/attendance/check-overlap` | attendanceController.checkOverlap | `OVERLAPS` time-range check for a classroom/date | attendance |
| 20 | POST | `/attendance/bulk` | attendanceController.submitBulkAttendance | **STUB** — returns static success message, does nothing | attendance |
| 21 | GET | `/attendance` | attendanceController.fetchAttendance | Manual-entry tab: students + their db_status for a session | attendance |
| 22 | GET | `/attendance/csv/reference` | attendanceController.downloadSampleCSV | `res.download()` a static file from `server/uploads/sample_attendance.csv` | attendance |
| 23 | GET | `/reports/attendance` | reportsController.getAttendanceReport | Full batch attendance matrix (subjects × students) | reports |
| 24 | GET | `/reports/absentees` | reportsController.getAbsenteesReport | Missed-class report per student vs scheduled timetable | reports |
| 25 | GET | `/reports/teacher-load` | reportsController.getTeacherLoad | Classes-taken counts per teacher/cohort/classroom/subject | reports |
| 26 | GET | `/reports/teacher-performance` | reportsController.getTeacherPerformance | Scheduled vs conducted per subject for one teacher | reports |
| 27 | GET | `/reports/coordinator-teachers` | teacherController.getCoordinatorTeachers | Teachers who taught this coordinator's batches (via class_session) | reports |
| 28 | GET | `/reports/batch-class-details` | reportsController.getBatchClassDetails | Session list for a batch w/ attendance_marked flag | reports |
| 29 | GET | `/reports/teacher-class-details` | reportsController.getTeacherClassDetails | Session list for a teacher (id or name filter) | reports |
| 30 | GET | `/timetable` | timetableController.getTimetable | Weekly timetable rows for `?batchId=` | timetable |
| 31 | GET | `/timetable/check-conflict` | timetableController.checkConflict | Overlap/room/teacher/link-batch conflict check | timetable |
| 32 | POST | `/timetable` | timetableController.createSlot | Transactional insert + sync classroom.class_link | timetable |
| 33 | PUT | `/timetable/:id` | timetableController.updateSlot | Transactional update + sync classroom.class_link | timetable |
| 34 | DELETE | `/timetable/:id` | timetableController.deleteSlot | Delete a timetable row | timetable |
| 35 | GET | `/attendance/batch-weekly-avg` | attendanceAnalyticsController.getBatchWeeklyAverage | Per-batch avg attendance for **last Mon–Sun week** (N+1 query loop) | dashboards |
| 36 | GET | `/reports/global-attendance` | reportsController.getGlobalAttendanceStats | Cohort "rainbow gauge" — current-month attendance %, all cohorts/batches | dashboards |
| 37 | GET | `/reports/teacher-subject-stats` | reportsController.getTeacherSubjectMonthlyStats | Per-subject/teacher current-month % for one batch | dashboards |

**Route-order note:** `/students/:id` (PUT) and `/students/:id/inactive` (PUT) and `/students/:id/inactive-history` (GET) do not collide (different methods/suffixes) — no Express routing-order bug here, unlike some other modules.

## 2. Resource Grouping + Recommended Plan Split

37 endpoints cluster into 6 groups. Recommended split into **4 sub-plans** (~9-10 endpoints each), grouping the small lookup-only controllers with a related bigger one to avoid a plan with only 3-4 endpoints:

| Sub-plan | Groups | Endpoints | Count | Rationale |
|---|---|---|---|---|
| **4e-1** | Master data + students + institutes | #1-14 (institutes, students×4, cohorts, batches, classrooms×5, teachers-lookup, platforms, subjects) | 14 | All simple CRUD/lookup, shared `pp.student_master`/`pp.batch`/`pp.cohort`/`pp.classroom` tables. Foundation the other groups depend on (need batch/classroom ids to test attendance/timetable). |
| **4e-2** | Attendance (session-based + CSV) | #15-22 | 8 | Hardest group: file upload, in-memory fuzzy CSV matching, transactional commit, **broken ON CONFLICT clauses** (§7.1) needing a real fix decision before porting. |
| **4e-3** | Reports (non-dashboard) | #23-29 | 7 | Heaviest raw SQL (CTEs, FILTER, ARRAY_AGG). All read-only, no schema risk, but must reproduce grouping/shaping logic in Java exactly. |
| **4e-4** | Timetable + dashboard analytics | #30-37 (timetable×5, weekly-avg, global-attendance, subject-stats) | 8 | Timetable has its own 2 transactional writes; dashboards are read-only aggregation queries good to test together (share `current_month` CTE pattern). |

**Cross-group shared tables:** `pp.student_master`, `pp.batch`, `pp.cohort`, `pp.classroom`, `pp.classroom_batch`, `pp.batch_coordinator_batches`, `pp.class_session`, `pp.student_attendance`, `pp.teacher`, `pp.subject`, `pp.timetable` — essentially the whole academics schema is shared across all 4 sub-plans; build 4e-1's read helpers first since 4e-2/3/4 all join against `student_master`/`batch`/`cohort`.

**Cross-group shared helper:** the day-of-week `CASE` ordering trick (`SUNDAY→1 ... SATURDAY→7`) appears in `studentModel.getStudentTimetableModel`, `timetableModel.getTimetableByBatch` — worth a shared Java `ORDER BY` helper/constant.

## 3. Table DDL Facts (from live-schema.sql)

### pp.student_master (PK: student_id)
```
student_id numeric(14,0) DEFAULT nextval('pp.student_id_seq') NOT NULL   -- PK
applicant_id numeric(14,0)
enr_id numeric(11,0)
student_name varchar(100)
father_name varchar(100)
father_occupation varchar(100)
mother_name varchar(100)
mother_occupation varchar(100)
gender char(1)                       -- CHECK IN ('M','F','O')
batch_id integer                     -- FK -> pp.batch(batch_id)
sim_name varchar(10)
student_email varchar(150)
student_email_password varchar(100)
parent_email varchar(150)
photo_link text
home_address varchar(200)
contact_no1 varchar(12)
contact_no2 varchar(12)
current_institute_dise_code varchar(15)   -- FK -> pp.institute(dise_code) ON DELETE SET NULL
previous_institute_dise_code varchar(15)  -- FK -> pp.institute(dise_code) ON DELETE SET NULL
active_yn varchar(10) DEFAULT 'ACTIVE'    -- CHECK IN ('ACTIVE','INACTIVE')
recharge_status varchar(20)          -- CHECK IN ('GRANTED','NOT GRANTED')
sponsor varchar(100)
teacher_name varchar(100)
teacher_mobile_number varchar(12)
created_at / updated_at timestamp
created_by / updated_by numeric(8,0)
user_id numeric
```

### pp.inactive_students (NO PK, NO FK on inactive_reason table itself besides student_id FK)
```
student_id numeric(14,0)             -- FK -> pp.student_master(student_id), no ON DELETE clause (RESTRICT default)
inactive_reason varchar(200)
inactive_date date
created_by / updated_by numeric(8,0)
```
No unique constraint — a student can accumulate multiple inactive-history rows (append-only log), matches `markStudentInactiveModel`'s INSERT-only behavior.

### pp.cohort (PK: cohort_number, from cohort_seq)
```
cohort_number integer DEFAULT nextval('pp.cohort_seq') NOT NULL  -- PK
cohort_name varchar(100)
start_date / end_date date
description text
status varchar(20)                   -- CHECK IN ('ACTIVE','COMPLETED')
current_grade integer                -- CHECK IN (9,10,11,12)
created_at/updated_at, created_by/updated_by
```

### pp.batch (PK: batch_id, from batch_id_seq)
```
batch_id integer DEFAULT nextval('pp.batch_id_seq') NOT NULL  -- PK
batch_name varchar(100)
cohort_number integer                -- FK -> pp.cohort(cohort_number) ON DELETE CASCADE
medium varchar(20) DEFAULT 'KANNADA' -- CHECK IN ('ENGLISH','KANNADA','HINDI','MARATHI')
house_name varchar(100)
created_at/updated_at, created_by/updated_by
```

### pp.batch_coordinator_batches (PK: user_id, batch_id — composite, junction table)
```
user_id numeric(8,0) NOT NULL         -- PK part
batch_id integer NOT NULL             -- PK part, FK -> pp.batch(batch_id)
```
This is THE table that scopes "coordinator's batches/students/cohorts" everywhere (`getStudentsByCoordinator`, `getBatchesByCohort`, `getAllBatchesForCoordinator`, `getCohortsByUser`, `getBatchWeeklyAverage`, `getCoordinatorTeachers`). No `user_id` FK to `pp."user"` shown in this excerpt (check separately if needed) but treat `user_id` as the authenticated coordinator's id from JWT.

### pp.classroom (PK: classroom_id, from classroom_id_seq)
```
classroom_id integer DEFAULT nextval('pp.classroom_id_seq') NOT NULL  -- PK
classroom_name varchar(100) NOT NULL
subject_id integer                    -- FK -> pp.subject(subject_id) ON DELETE SET NULL
teacher_id integer                    -- FK -> pp.teacher(teacher_id) ON DELETE SET NULL (this is the "default" teacher; sessions can override — see §7.3)
platform_id integer                   -- FK -> pp.teaching_platform(platform_id) ON DELETE SET NULL
description varchar(200)
active_yn char(1) DEFAULT 'Y'         -- CHECK IN ('Y','N')
class_link varchar(150)               -- synced by timetable create/update (see §7.4)
created_at/updated_at, created_by/updated_by
```

### pp.classroom_batch (PK: classroom_id, batch_id — composite junction)
```
classroom_id integer NOT NULL  -- FK -> pp.classroom(classroom_id) ON DELETE CASCADE
batch_id integer NOT NULL      -- FK -> pp.batch(batch_id) ON DELETE CASCADE
```

### pp.subject (PK: subject_id, from subject_id_seq)
```
subject_id integer DEFAULT nextval('pp.subject_id_seq') NOT NULL  -- PK
subject_code varchar(5) NOT NULL
subject_name varchar(100) NOT NULL
created_at/updated_at, created_by/updated_by
```

### pp.teacher (PK: teacher_id, from teacher_id_seq)
```
teacher_id integer DEFAULT nextval('pp.teacher_id_seq') NOT NULL  -- PK
user_id numeric(8,0)
teacher_name varchar(150)
qualification varchar(150)
experience_yrs integer               -- CHECK >= 0
doj date
contact_no varchar(12)
created_at/updated_at, created_by/updated_by
```

### pp.teacher_subject (PK: teacher_id, subject_id, medium — composite)
```
teacher_id integer NOT NULL           -- FK -> pp.teacher(teacher_id) ON DELETE CASCADE
subject_id integer NOT NULL           -- FK -> pp.subject(subject_id) ON DELETE CASCADE
medium varchar(20) DEFAULT 'KANNADA' NOT NULL  -- CHECK IN ('ENGLISH','KANNADA','HINDI','MARATHI')
```
Note: `teacherModel.getAllTeachers`/`getTeacherById` join on this table, but the **coordinator route's live handler `getCoordinatorTeachers` does NOT use this model** — it queries `pp.class_session` + `pp.teacher` directly (see §8.2).

### pp.teaching_platform (PK: platform_id)
```
platform_id integer DEFAULT nextval('pp.platform_id_seq') NOT NULL  -- PK
platform_name varchar(100) NOT NULL
```

### pp.class_session (PK: session_id, from class_session_seq)
```
session_id integer DEFAULT nextval('pp.class_session_seq') NOT NULL  -- PK
classroom_id integer NOT NULL         -- FK -> pp.classroom(classroom_id)
session_date date NOT NULL
start_time time NOT NULL
end_time time NOT NULL
timetable_id integer                  -- FK -> pp.timetable(timetable_id) ON DELETE SET NULL
duration_minutes integer
teacher_id integer                    -- FK -> pp.teacher(teacher_id) ON DELETE SET NULL (SESSION-LEVEL teacher, can differ from classroom.teacher_id)
created_at/updated_at, created_by/updated_by
```
**NO unique constraint on (classroom_id, session_date, start_time, end_time)** — see §7.1 CRITICAL.

### pp.student_attendance (PK: attendance_id, from attendance_id_seq)
```
attendance_id integer DEFAULT nextval('pp.attendance_id_seq') NOT NULL  -- PK
session_id integer                    -- FK -> pp.class_session(session_id) ON DELETE CASCADE
student_id numeric(14,0)              -- FK -> pp.student_master(student_id) ON DELETE CASCADE
status varchar(20) NOT NULL           -- CHECK IN ('PRESENT','ABSENT','LATE JOINED','LEAVE')
time_joined / time_exited time
attendance_percent numeric(5,2)
duration_minutes integer
remarks varchar(200)
created_at/updated_at, created_by/updated_by
```
**NO unique constraint on (session_id, student_id)** — see §7.1 CRITICAL.

### pp.timetable (PK: timetable_id, from timetable_id_seq)
```
timetable_id integer DEFAULT nextval('pp.timetable_id_seq') NOT NULL  -- PK
classroom_id integer                  -- FK -> pp.classroom(classroom_id), no ON DELETE clause
day_of_week varchar(10)               -- CHECK IN ('SUNDAY'..'SATURDAY')
start_time / end_time time NOT NULL
created_at/updated_at, created_by/updated_by
```

### pp.institute (PK: institute_id; used read-only here via dise_code)
Relevant columns only: `dise_code varchar(15)`, `institute_name varchar(200)`, `institute_board varchar(20)`, `management_type varchar(50)`. FK target for `student_master.current/previous_institute_dise_code`.

## 4. Exact SQL for the Hardest / Most Complex Flows

### 4.1 CSV attendance preview (`previewCSVAttendance`, controllers/coordinator/attendanceController.js:474-614)
In-memory only (Papa Parse over `fs.createReadStream(req.file.path)`, `skipEmptyLines:true`). Diskstorage multer (`multer.diskStorage`, dest `server/uploads/`, filename `${Date.now()}-${originalname.replace(/\s+/g,"_")}`, `.csv` only via fileFilter). **File IS written to disk** then `fs.unlinkSync(req.file.path)` at the end of the handler (not cleaned up on early-return error paths — leak on `results.length < 2` 400 and on thrown exceptions before the unlink line).

Row-shape assumptions (Zoom-style export): row 0 = header, row 1 col D (`results[1][3]`) = total meeting duration string e.g. `"1 hr 25 min"`, data rows start at index 2, columns: A=name(0), D=duration(3), E=time_joined(4), F=time_exited(5).

```js
const parseDurationToMinutes = (raw) => {
    if (!raw || String(raw).toLowerCase() === "null") return 0;
    const s = String(raw).toLowerCase();
    let totalMinutes = 0;
    const hrMatch = s.match(/(\d+)\s*hr/);
    const minMatch = s.match(/(\d+)\s*min/);
    const secMatch = s.match(/(\d+)\s*sec/);
    if (hrMatch) totalMinutes += parseInt(hrMatch[1], 10) * 60;
    if (minMatch) totalMinutes += parseInt(minMatch[1], 10);
    if (secMatch) totalMinutes += (parseInt(secMatch[1], 10) / 60);
    return Math.round(totalMinutes);
};
```
Build `csvMap` keyed by `rawName.toLowerCase()`, keeping the row with the LARGEST duration per key (handles duplicate join/leave events for the same name). Then for every ACTIVE db student, **substring match**: `for (let [csvKey, data] of csvMap) if (csvKey.includes(dbNameClean)) { match }` — first match wins (Map iteration order = insertion order), **not exact match**, DB name must be a substring of a CSV name. `pct = duration/totalCSVDurationMins*100`; `status = pct>=75 ? PRESENT : pct>=40 ? "LATE JOINED" : "ABSENT"`. Students with `active_yn !== 'ACTIVE'` are diverted to a separate `inactiveStudents[]` array (still checked against CSV, but excluded from `previewData`, and don't affect `matchedCSVKeys` unless matched). No match at all in CSV → `previewData` row with `duration_minutes:0, status:"ABSENT"`. CSV rows never matched by any db student → `unmatchedStudents[]`. Final `previewData` is `localeCompare`-sorted by name. Response: `{previewData, unmatchedStudents, inactiveStudents}`, `200`.

### 4.2 CSV attendance commit (`commitCSVAttendance`, attendanceController.js:617-685) — TRANSACTIONAL
```js
const client = await pool.connect();
try {
  const { previewData, session_date, classroom_id, start_time, end_time } = req.body;
  await client.query("BEGIN");
  const session_id = await getOrCreateSession(classroom_id, session_date, normalizeTimeToDB(start_time), normalizeTimeToDB(end_time));
  for (const r of previewData) {
    if (!r.student_id) continue;
    const startMins = timeToMinutes(start_time), endMins = timeToMinutes(end_time);
    const totalSessionMins = endMins > startMins ? (endMins - startMins) : 0;
    const attPct = totalSessionMins > 0 ? (r.duration_minutes / totalSessionMins) * 100 : 0;
    await client.query(
      `INSERT INTO pp.student_attendance
          (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       ON CONFLICT (session_id, student_id)
       DO UPDATE SET status=EXCLUDED.status, duration_minutes=EXCLUDED.duration_minutes,
          time_joined=EXCLUDED.time_joined, time_exited=EXCLUDED.time_exited,
          attendance_percent=EXCLUDED.attendance_percent, updated_at=NOW()`,
      [session_id, r.student_id, r.status, normalizeTimeToDB(r.time_joined), normalizeTimeToDB(r.time_exited), r.duration_minutes || 0, Math.min(100, parseFloat(attPct.toFixed(2)))]
    );
  }
  await client.query("COMMIT");
  res.status(200).json({ session_id });
} catch (err) { await client.query("ROLLBACK"); res.status(500).json({ message: err.message }); }
finally { client.release(); }
```
`getOrCreateSession` (models/coordinator/attendanceModel.js:50-80) itself does a SELECT to look up `teacher_id` from the classroom, then:
```sql
INSERT INTO pp.class_session (classroom_id, session_date, start_time, end_time, teacher_id)
VALUES ($1, $2, $3, $4, $5)
ON CONFLICT (classroom_id, session_date, start_time, end_time)
DO UPDATE SET teacher_id = EXCLUDED.teacher_id, updated_at = CURRENT_TIMESTAMP
RETURNING session_id;
```
**Both `ON CONFLICT` clauses reference column sets with NO matching unique constraint in the live schema — see §7.1, this is currently a live 500-error bug.**

### 4.3 Time normalization helpers (must port verbatim — reused across attendance)
```js
const normalizeTimeToDB = (raw) => {
    if (!raw || String(raw).trim() === "" || String(raw).toLowerCase() === "null") return "00:00:00";
    let s = String(raw).replace(/ | /g, " ").trim();   // strip narrow-no-break-space / nbsp (common in Excel/Zoom exports)
    if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(s)) return s.length === 5 ? s + ":00" : s;
    const ampmMatch = s.match(/(\d+):(\d+)\s*(AM|PM)/i);
    if (ampmMatch) {
        let hrs = parseInt(ampmMatch[1], 10), mins = parseInt(ampmMatch[2], 10);
        const ampm = ampmMatch[3].toUpperCase();
        if (ampm === "PM" && hrs < 12) hrs += 12;
        if (ampm === "AM" && hrs === 12) hrs = 0;
        return `${String(hrs).padStart(2,'0')}:${String(mins).padStart(2,'0')}:00`;
    }
    return "00:00:00";
};
const timeToMinutes = (raw) => {
    const timeStr = normalizeTimeToDB(raw);
    if (!timeStr || timeStr === "00:00:00") return 0;
    const [h, m] = timeStr.split(":").map(Number);
    return h * 60 + m;
};
```

### 4.4 Timetable conflict check (models/coordinator/timetableModel.js:37-77)
```sql
SELECT t.timetable_id, t.start_time, t.end_time, c.classroom_name, s.subject_name, te.teacher_name
FROM pp.timetable t
JOIN pp.classroom c ON t.classroom_id = c.classroom_id
LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
LEFT JOIN pp.teacher te ON c.teacher_id = te.teacher_id
WHERE
    t.day_of_week = $1
    AND ($2 < t.end_time AND $3 > t.start_time)          -- classic interval-overlap test
    AND (
          ( $4::int IS NOT NULL AND t.classroom_id = $4 )
       OR ( $5::int IS NOT NULL AND c.teacher_id = $5 )
       OR EXISTS (
            SELECT 1 FROM pp.classroom_batch cb1
            JOIN pp.classroom_batch cb2 ON cb1.batch_id = cb2.batch_id
            WHERE cb1.classroom_id = t.classroom_id AND cb2.classroom_id = $4
          )
    )
    AND ($6::int IS NULL OR t.timetable_id <> $6)
```
Params: `[day, start_time, end_time, classroom_id||null, teacher_id||null, exclude_id||null]`. The controller (`checkConflict`, `createSlot`, `updateSlot`) always passes `teacher_id: null` from create/update paths (only the standalone GET `/timetable/check-conflict` can pass a real teacher_id from query) — so classroom-vs-classroom and cross-batch-sharing (the `EXISTS` clause: catches when two different classroom rows are actually shared by the same batch) are the operative checks for create/update.

### 4.5 Timetable createSlot / updateSlot — TRANSACTIONAL, syncs classroom.class_link
```js
// createSlot
await client.query('BEGIN');
const res = await client.query(
  `INSERT INTO pp.timetable (classroom_id, day_of_week, start_time, end_time, created_by, updated_by)
   VALUES ($1, $2, $3, $4, 1, 1) RETURNING *`,   // <-- created_by/updated_by HARD-CODED to 1, not req.user.user_id
  [classroom_id, day, start_time, end_time]
);
await client.query(`UPDATE pp.classroom SET class_link = $2 WHERE classroom_id = $1`, [classroom_id, class_link || null]);
await client.query('COMMIT');
```
`updateSlotAndLink` is the same pattern but `UPDATE pp.timetable SET classroom_id=$2, day_of_week=$3, start_time=$4, end_time=$5, updated_at=NOW() WHERE timetable_id=$1 RETURNING *` then the same classroom.class_link sync. **Hard-coded `created_by=1, updated_by=1`** on createSlot is a quirk to decide on (Node bug — never uses `req.user.user_id`); updateSlotAndLink doesn't set updated_by at all.

### 4.6 Reports — attendance matrix (reportsController.js:13-151) `getAttendanceReport`
Three sequential queries against `ReportsModel.query` (thin `pool.query` wrapper):
1. batch_name/cohort_name lookup.
2. **conducted** (per subject_code × teacher_name count of distinct sessions in range) via `WITH batch_classrooms AS (SELECT classroom_id FROM pp.classroom_batch WHERE batch_id=$1) ...`.
3. **student attendance** — the interesting one, handles students who went inactive mid-range:
```sql
WITH batch_classrooms AS (SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = $1),
batch_students AS (
    SELECT sm.student_id, sm.student_name, ins.inactive_date
    FROM pp.student_master sm
    LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id
    WHERE sm.batch_id = $1 AND (ins.student_id IS NULL OR ins.inactive_date > $2::date)
),
sessions AS (
    SELECT cs.session_id, cs.teacher_id, subj.subject_code, cs.session_date
    FROM pp.class_session cs
    JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
    JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
    JOIN pp.subject subj ON subj.subject_id = c.subject_id
    WHERE cs.session_date BETWEEN $2::date AND $3::date
),
student_sessions AS (
    SELECT bs.student_id, bs.student_name, bs.inactive_date, s.session_id, s.subject_code, s.teacher_id, s.session_date
    FROM sessions s
    JOIN batch_students bs ON (bs.inactive_date IS NULL OR s.session_date < bs.inactive_date)
)
SELECT ss.student_id, ss.student_name, ss.subject_code, t.teacher_name,
       COUNT(DISTINCT ss.session_id) FILTER (WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended
FROM student_sessions ss
LEFT JOIN pp.student_attendance sa ON sa.session_id = ss.session_id AND sa.student_id = ss.student_id
LEFT JOIN pp.teacher t ON t.teacher_id = ss.teacher_id
GROUP BY ss.student_id, ss.student_name, ss.subject_code, t.teacher_name
ORDER BY ss.student_name;
```
Note: `LEFT JOIN pp.inactive_students ins` with NO `AND ins.inactive_reason IS NOT NULL`-style filter — since `inactive_students` is append-only (no unique constraint, §3), a student marked inactive twice produces duplicate rows in `batch_students` via the LEFT JOIN, which would fan out `student_sessions`/attendance counts. In Java, dedupe (e.g. take MAX(inactive_date) per student) or this becomes a silent double-count bug carried forward from Node.

Response shaping in JS (not SQL): builds `conductedStructured{subject_code:[{teacher_name,conducted}]}` and `studentMap{student_id:{id,name,subjects:{subject_code:{teacher_name:attended_count}}}}`, final `students = Object.values(studentMap)`. Response: `{reportId:"ATT-<batchId>-<from>-<to>", cohort_name, batch_name, subjects, students}`.

### 4.7 Reports — absentees (reportsController.js:153-219) `getAbsenteesReport`
Uses `generate_series` to expand the date range and matches against `pp.timetable.day_of_week` by day-name string comparison — fragile but exact-reproduce target:
```sql
WITH dates AS (SELECT generate_series($2::date, $3::date, interval '1 day')::date AS dt),
batch_classrooms AS (SELECT cb.classroom_id FROM pp.classroom_batch cb WHERE cb.batch_id = $1),
scheduled AS (
    SELECT c.classroom_id, s.subject_code, d.dt
    FROM pp.classroom c
    JOIN batch_classrooms bc ON bc.classroom_id = c.classroom_id
    JOIN pp.timetable t ON t.classroom_id = c.classroom_id
    JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))   -- to_char 'DAY' is padded/locale-dependent; trim(upper()) both sides is the defensive fix already applied
    JOIN pp.subject s ON s.subject_id = c.subject_id
),
attended AS (
    SELECT sa.student_id, c.subject_id, cs.session_date AS date, sa.status
    FROM pp.student_attendance sa
    JOIN pp.class_session cs ON cs.session_id = sa.session_id
    JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
    WHERE cs.session_date BETWEEN $2::date AND $3::date
),
compare AS (
    SELECT bs.student_id, bs.student_name, sch.subject_code,
           COUNT(*) AS scheduled_count,
           COUNT(att.*) FILTER (WHERE att.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_count,
           ARRAY_AGG(CASE WHEN att.status = 'ABSENT' THEN att.date END) FILTER (WHERE att.status = 'ABSENT') AS absent_dates
    FROM (SELECT sm.student_id, sm.student_name FROM pp.student_master sm WHERE sm.batch_id = $1) bs
    JOIN scheduled sch ON TRUE                     -- deliberate CROSS JOIN via ON TRUE
    LEFT JOIN attended att
      ON att.student_id = bs.student_id
      AND att.subject_id = (SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)
      AND att.date = sch.dt
    GROUP BY bs.student_id, bs.student_name, sch.subject_code
)
SELECT student_id, student_name, subject_code AS subject, scheduled_count, attended_count,
       (scheduled_count - attended_count) AS missed_count, COALESCE(absent_dates, '{}') AS missed_dates
FROM compare
WHERE (scheduled_count - attended_count) > 0
ORDER BY missed_count DESC;
```
Note: `scheduled_count` counts *any* row from `scheduled` matched to the student (irrespective of a class-session existing that day) — i.e. it counts **timetabled slots**, not actual `class_session` rows; a timetabled class the teacher never actually ran (no `class_session` row created) still counts as "scheduled" and would inflate `missed_count`. Also the subquery `(SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)` assumes `subject_code` is effectively unique (no DB-level UNIQUE constraint on it — see §3, only `subject_id` is the real PK) — non-unique `subject_code` would silently pick an arbitrary subject. Response grouped in JS into `{reportId, students:[{id,name,missedClasses:[{subject,count,dates}],totalMissed}]}`.

### 4.8 Dashboards — global rainbow gauges (reportsController.js:372-413) `getGlobalAttendanceStats`
Uses correlated scalar subqueries per batch (not the most efficient shape, but must reproduce output exactly):
```sql
WITH current_month AS (
    SELECT date_trunc('month', CURRENT_DATE) as start_dt, (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
),
metrics AS (
    SELECT b.batch_id, b.batch_name, b.cohort_number,
        (SELECT COUNT(*) FROM pp.student_master WHERE batch_id = b.batch_id AND active_yn = 'ACTIVE') as s_count,
        (SELECT COUNT(DISTINCT cs.session_id) FROM pp.classroom_batch cb
         JOIN pp.class_session cs ON cs.classroom_id = cb.classroom_id CROSS JOIN current_month cm
         WHERE cb.batch_id = b.batch_id AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as sess_count,
        (SELECT COUNT(sa.attendance_id) FROM pp.student_attendance sa
         JOIN pp.class_session cs ON sa.session_id = cs.session_id
         JOIN pp.student_master sm ON sm.student_id = sa.student_id CROSS JOIN current_month cm
         WHERE sm.batch_id = b.batch_id AND sm.active_yn = 'ACTIVE' AND sa.status IN ('PRESENT','LEAVE')
         AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as p_count
    FROM pp.batch b
)
SELECT c.cohort_name, c.cohort_number,
    ROUND(AVG(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END)::numeric, 2) as cohort_avg,
    jsonb_agg(jsonb_build_object('batch_name', m.batch_name,
        'avg', ROUND(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END::numeric, 2),
        'classes_held', m.sess_count) ORDER BY m.batch_name) as batches
FROM pp.cohort c
JOIN metrics m ON m.cohort_number = c.cohort_number
GROUP BY c.cohort_name, c.cohort_number ORDER BY c.cohort_number;
```
Note: `sa.status IN ('PRESENT','LEAVE')` here — **excludes 'LATE JOINED'** from the "present" numerator, unlike every other report in this module which uses `('PRESENT','LATE JOINED','LEAVE')`. Verify with the user/business owner whether this is intentional before porting; flagged in §7.

### 4.9 `getBatchWeeklyAverage` (attendanceAnalyticsController.js) — N+1 loop, no aggregation SQL
Fetches all of the coordinator's batches, then loops calling `getWeeklyBatchAverage(batch_id, fromDate, toDate)` once per batch (N+1 query pattern — fine to keep for a handful of batches, flag if coordinators can have many). Week window is **hard-computed in JS as "last complete Mon–Sun"** relative to server "now" (JS `Date`, not SQL) — reproduce with `LocalDate`/`ChronoUnit` in Java, be careful of day-of-week numbering (`getDay()`: Sunday=0):
```js
const today = new Date();
const day = today.getDay();
const lastSunday = new Date(today); lastSunday.setDate(today.getDate() - day);
const lastMonday = new Date(lastSunday); lastMonday.setDate(lastSunday.getDate() - 6);
```
Per-batch SQL (`models/coordinator/attendanceModel.js:318-337`):
```sql
SELECT AVG(CASE WHEN sa.status = 'PRESENT' THEN 100 WHEN sa.status = 'LATE JOINED' THEN 50 ELSE 0 END) AS avg_attendance
FROM pp.student_attendance sa
JOIN pp.student_master sm ON sa.student_id = sm.student_id
JOIN pp.class_session cs ON sa.session_id = cs.session_id
WHERE sm.batch_id = $1 AND cs.session_date BETWEEN $2 AND $3;
```
Note the **weighting scheme differs from every other report**: PRESENT=100%, LATE JOINED=50% (partial credit), everything else (ABSENT, LEAVE, no row at all — since it's an unweighted `AVG` over only rows that exist, absent students who never got a `student_attendance` row aren't counted at all, only rows that were explicitly marked) =0. This is genuinely a different formula from the FILTER-based "attended = PRESENT+LATE JOINED+LEAVE" used elsewhere — port verbatim, do not "fix" to match the other reports' definition without a product decision.

## 5. Response Shapes & Status Codes

| Pattern | Where |
|---|---|
| `res.json(rows)` — bare array, 200 | most GET lookups: students, cohorts, batches, classrooms, teachers, platforms, subjects, timetable, inactive-history |
| `{error: "..."}`, 500 | most catch blocks (studentController, classroomController, subjectController, cohortController, batchController, instituteController, timetableController) |
| `{message: "..."}`, 500 | attendanceController catch blocks (uses `message` key, NOT `error`) — **inconsistent with the rest of the module**, port each endpoint's exact key |
| `{message: "..."}`, 400 | markInactiveController missing-reason (400), timetableController missing-required-fields (400) |
| `{error: "..."}`, 400 | classroomController/timetableController missing required param checks (mixed with the above — verify per-handler) |
| `{success:true, data:{...}}` | timetable createSlot/updateSlot success |
| `{success:true}` | timetable deleteSlot |
| `{overlap:true/false, conflicts:[...]}` | timetable checkConflict / createSlot-and-updateSlot's inline pre-check (400 when overlap detected on create/update, 200 when the dedicated GET is just probing) |
| `{success:true, count:N, classes:[...]}` | getBatchClassDetails, getTeacherClassDetails |
| `{teacherClassCounts:[...]}` | getTeacherLoad |
| `{reportId, ...}` | getAttendanceReport, getAbsenteesReport, getTeacherPerformance (each has a distinct reportId format string, reproduce exactly) |
| bare array `[...]`, 200 | getGlobalAttendanceStats, getTeacherSubjectMonthlyStats, getCoordinatorTeachers |
| `{session_id}` | commitCSVAttendance success; getOrFindSession returns `{session_id:null}` fallback if not found (still 200, not 404) |
| `{previewData, unmatchedStudents, inactiveStudents}` | previewCSVAttendance |
| `{message:"No file uploaded"}`, 400 | previewCSVAttendance missing file |
| `{message:"CSV missing data rows."}`, 400 | previewCSVAttendance <2 rows |
| `{message:"Undo Successful"}` | undoLastAttendanceCommit |
| `{message:"Student marked inactive successfully"}` | markInactiveController / updateStudentController's inactive branch |
| `{message:"Student updated successfully"}` | updateStudentController normal branch |
| numeric ids | all ids in Node are raw JS numbers from `pg` driver auto-coercion of `integer`/`numeric` columns under a certain precision — Java port must follow the project convention (numeric PK → String, except where noted) per RESUME-migration.md rule 3; `student_id`/`enr_id` are `numeric(14,0)`/`numeric(11,0)` → serialize as String. `classroom_id`,`batch_id`,`subject_id`,`teacher_id`,`timetable_id`,`session_id`,`attendance_id`,`platform_id` are plain `integer` — Node returns these as JS numbers (pg driver parses int4 as number); **decide Java convention up front** — likely also String per project convention #3 (numeric ids as strings) even though the DB type is `integer` not `numeric`, since the project rule is about identifier semantics not the underlying SQL type — confirm with existing Phase 1-3 precedent before implementing. |

## 6. File-Generating Endpoints

Only ONE genuinely file-related endpoint in this module, and it's a static download, not a generated file:
- **`GET /attendance/csv/reference`** (`downloadSampleCSV`) — `res.download(path.join(__dirname, "../../uploads/sample_attendance.csv"))`. This is a **static file shipped in the repo/deploy**, not generated per-request. For Java: bundle the same CSV as a classpath resource (`src/main/resources/static/...` or similar) and serve via `ResponseEntity<Resource>` / `StreamingResponseBody`, no POI/OpenPDF needed.
- **`POST /attendance/csv/preview`** reads an uploaded CSV (multer diskStorage → `fs.createReadStream` + `papaparse`) but produces JSON, not a file — for Java, accept `MultipartFile`, parse with Apache Commons CSV (project's existing CSV convention from Phase 2a) instead of writing to disk if avoidable; if disk write is kept for parity, ensure cleanup runs on ALL paths (Node's `fs.unlinkSync` only runs on the success path — a leak to consciously NOT reproduce).

No XLSX/PDF generation in the coordinator module — POI/OpenPDF are not needed for phase 4e.

## 7. Transactions

Handlers using `pool.connect()` + explicit `BEGIN/COMMIT/ROLLBACK`:
1. `attendanceController.commitCSVAttendance` (§4.2) — one session upsert + N attendance upserts.
2. `attendanceModel.getOrCreateSession` — **NOT itself wrapped in BEGIN/COMMIT** (single statement, autocommit; only wrapped because its caller `commitCSVAttendance` already opened a transaction and passes... actually re-check: `getOrCreateSession` uses the **global `pool`**, not the caller's `client`, so it runs in ITS OWN autocommit transaction, separate from `commitCSVAttendance`'s `client` transaction — see §7.2, a real bug to flag, not just a style note).
3. `timetableModel.createSlot` — insert timetable row + update classroom.class_link, own `client`.
4. `timetableModel.updateSlotAndLink` — update timetable row + update classroom.class_link, own `client`.

All other writes (`updateStudentModel`, `markStudentInactiveModel`, `createClassroom`, `undoLastAttendanceCommit`, `deleteSlot`) are single/sequential autocommit statements — `markStudentInactiveModel`'s two `pool.query` calls (INSERT history, then UPDATE master) are NOT transactional; a mid-failure leaves an orphan inactive-history row without the master flip (or vice versa) — worth wrapping in Java even though Node doesn't.

## 8. Quirks & Complexity Warnings (file:line)

### 8.1 CRITICAL: `ON CONFLICT` clauses reference non-existent unique constraints
- `models/coordinator/attendanceModel.js:64` — `ON CONFLICT (classroom_id, session_date, start_time, end_time)` on `pp.class_session`.
- `attendanceController.js:655` (live commitCSVAttendance) and `attendanceModel.js:101,155` (`createAttendance`/`createBulkAttendance`, unused by this router but same pattern) — `ON CONFLICT (session_id, student_id)` on `pp.student_attendance`.

**Live schema (`live-schema.sql`) has NO unique index or constraint matching either conflict target** (verified: `grep "PRIMARY KEY|CREATE UNIQUE INDEX"` across the dump finds only the `attendance_id`/`session_id` single-column PKs). In real Postgres this makes both `INSERT ... ON CONFLICT (...)` statements throw `42P10 no unique or exclusion constraint matching the ON CONFLICT specification` at runtime — i.e., **`/attendance/csv/commit` is very likely a live 500 error in production today**, unless the actual deployed DB has migrations beyond this dump that added the missing unique indexes (check before assuming it's simply broken — but the ground-truth artifact you're building from does not show them). **Decision needed before porting:** either (a) confirm the production DB actually has these unique constraints (dump may be stale) and add them to the Flyway baseline, or (b) implement idempotent upsert logic in Java as `SELECT...then INSERT/UPDATE` instead of `ON CONFLICT`, matching whatever the true current behavior is. Do not silently "fix" by adding a constraint without confirming with the user — this changes semantics if duplicate rows already exist in production.

### 8.2 `getOrCreateSession` uses a different DB connection than its transactional caller
`attendanceController.commitCSVAttendance` opens `client = await pool.connect()` and calls `client.query("BEGIN")`, but then calls `getOrCreateSession(...)` (attendanceModel.js:50) which internally uses the **module-level `pool`**, not the passed-in `client` — so the session INSERT/UPDATE commits immediately and independently, outside the surrounding transaction. If the subsequent attendance-row loop fails and `ROLLBACK` runs on `client`, the already-created/updated session row is NOT rolled back — partial-commit bug. Java port should thread a single connection/transaction through both operations (this is exactly the "dedicated @Repository bean, no self-invocation" pattern already established in RESUME-migration.md convention #8).

### 8.3 Dead / unwired code (do not port; confirm not needed elsewhere first)
- `server/controllers/coordinator/studentController1.js` — not required by any route file found; calls `getAllStudents`/`getStudentsByCohort` from `studentModel`, but **`studentModel.js`'s live export list does not include either function** (they only existed in the earlier commented-out iterations of the model file) — this file would throw `TypeError: getAllStudents is not a function` if ever invoked. Confirmed dead.
- `server/models/coordinator/inactiveStudentModel.js` (`insertInactiveStudent`) — not imported by `studentController.js` (which does its own inline INSERT via `markStudentInactiveModel` in `studentModel.js` instead). Confirmed dead, duplicate logic.
- `studentController.js` exports `getStudentProfile`, `getMySchedule`, `getStudentSummary`, `getStudentSubjectPerformance`, `getStudentMonthlyAttendance`, `getStudentWeeklyAttendance`, `getStudentCustomAttendance` (lines 315-459) and the backing model functions in `studentModel.js` (`getStudentProfileByUserId`, `getStudentTimetableModel`, `getStudentSummaryModel`, etc., lines 676-935) — **none of these are wired into `coordinatorRoutes.js`**. They look like they belong to a STUDENT-facing self-service module (probably mounted elsewhere, e.g. a `/api/student` router) — grep `server/routes/` for another file requiring `studentController` before assuming they're truly dead; out of scope for the *coordinator* router regardless, but do not silently drop them from the codebase inventory — flag to the planner in case a student-portal phase needs them.
- `teacherModel.getTeachersByCoordinator` (models/coordinator/teacherModel.js:10-28) references `b.coordinator_id` — **`pp.batch` has no `coordinator_id` column** (coordinator scoping is via the `pp.batch_coordinator_batches` junction table everywhere else in this module). This function would throw a Postgres column-does-not-exist error if called. It is never called — `teacherController.getCoordinatorTeachers` (the live route #27) uses its own inline raw SQL instead, correctly joining through `batch_coordinator_batches`. Confirmed dead/broken, do not port.
- `attendanceController.js` and `studentController.js` and `studentModel.js` each contain 2-3 stacked, fully-commented prior versions of themselves above the live code (documented in §0) — purely noise for the Java port, but useful as a diff history if a "why did this change" question comes up.

### 8.4 `submitBulkAttendance` is a stub
`attendanceController.js:727-729` — `POST /attendance/bulk` always returns `{message:"Bulk submission logic active"}`, 200, and does **nothing** (no DB write, ignores the request body entirely). Confirm with the user whether Java should implement real bulk-JSON-attendance-submit behavior or preserve the no-op stub for parity. Given "simplicity > comprehensive" and pure API parity as the stated goal, default recommendation: **port as a no-op stub** unless the frontend actually depends on it doing something (check `client/src/pages/Coordinator/AttendanceTracker.js` usage before deciding).

### 8.5 Auth inconsistency — `/reports/*` uses a non-verifying middleware
`reportsController.js:5-9`:
```js
const requireAuth = (req, res, next) => {
    const auth = req.headers.authorization;
    if (!auth) return res.status(401).json({ error: "Missing authorization" });
    next();
};
```
This is used (not `authenticate`) for ALL 7 `/reports/*` routes (#23-29) instead of the JWT-verifying `authenticate` middleware used everywhere else in the module. It only checks that *some* `Authorization` header string is present — **it never calls `jwt.verify`, so any garbage bearer token, or even `Authorization: x`, passes**, and `req.user` is never populated (none of the reports handlers read `req.user` anyway, so this doesn't crash, but it means reports endpoints have **no real authentication** in the live app). Per RESUME-migration.md convention #7 ("enforce it... this is intended new enforcement"), the Java port should use the same enforced `authenticate`-equivalent (`@PreAuthorize` / JWT filter) for these 7 endpoints, closing this gap — flag as a deliberate, not accidental, hardening decision to record in the plan.

### 8.6 `getGlobalAttendanceStats` uses a different "present" status set than every other report
See §4.8 — `sa.status IN ('PRESENT','LEAVE')` (excludes `'LATE JOINED'`) vs. `('PRESENT','LATE JOINED','LEAVE')` used in `getAttendanceReport`, `getBatchWeeklyAverage`'s sibling reports, and the student-performance models. Port verbatim per-endpoint; do not unify without a product decision, this could be intentional (rainbow gauge treats "late joined" as not-fully-present) or an oversight — call it out in the plan review.

### 8.7 `getBatchWeeklyAverage` uses yet another weighting formula
See §4.9 — PRESENT=100, LATE JOINED=50 (partial credit), everything else (including no attendance row at all, since it's an unweighted AVG over existing rows only) = excluded/0. A third distinct "attendance percentage" definition in the same module (alongside the FILTER-based one and the rainbow-gauge one). Table these three definitions for the user to confirm before Java implementation, since a reviewer could easily "fix" them all to be consistent, silently changing dashboard numbers.

### 8.8 `getTeachers` (classrooms group, route #12) returns names only, no id
`classroomModel.js:61-67` — `SELECT teacher_name FROM pp.teacher` (no `teacher_id`). Whatever frontend consumes this dropdown cannot actually submit a `teacher_id` from it — likely a UI-only display list, not used for selection-by-id. Port literally (don't "fix" to include the id) unless the frontend usage proves otherwise; grep `client/src` for `/api/coordinator/teachers` consumer before deciding.

### 8.9 `createClassroom`/`updateSlot` etc. — `created_by`/`updated_by` handling is inconsistent module-wide
- `classroomModel.createClassroom` takes `created_by`/`updated_by` from the request body (`req.body`) — trusts the client to send the right user id, no `req.user.user_id` usage.
- `timetableModel.createSlot` hard-codes `1, 1` (see §4.5) — ignores both body and `req.user`.
- `attendanceModel` upserts don't set `created_by`/`updated_by` at all on the attendance rows.
Java port should standardize on `req.user`/`principal`-derived `created_by` per RESUME-migration.md's general auth-enforcement direction, but this is a genuine behavior change from Node — flag explicitly in the plan rather than silently "fixing."

### 8.10 Dynamic-column risk in `updateStudentModel`
`models/coordinator/studentModel.js:549-576` — builds `SET ${fields}` from `Object.keys(payload).map((key,i) => \`${key} = $${i+1}\`)`, i.e. **column names come directly from arbitrary request-body JSON keys**, string-interpolated into SQL (values are parameterized, but the column names are not). No whitelist/enum of allowed columns. This is the classic dynamic-column injection risk the task brief asked to flag: an attacker sending `{"student_id = student_id; DROP TABLE pp.student_master; --": "x"}` as a key... (Postgres identifiers with spaces/semicolons need quoting to actually execute as separate statements via `pg`'s single-statement `query()`, which limits blast radius somewhat since `pg` doesn't allow multi-statement injection through a parameterized single query call in most drivers, but it absolutely allows **column-name spoofing to overwrite unintended columns** the caller shouldn't be able to touch, e.g. `student_id`, `applicant_id`, `created_by`, `active_yn` bypassing the intended inactive-workflow branch). **For Java: use a hard whitelist enum of allowed updatable columns** (`student_name, father_name, father_occupation, mother_name, mother_occupation, gender, student_email, parent_email, contact_no1, contact_no2, home_address, current_institute_dise_code, previous_institute_dise_code, sim_name, teacher_name, teacher_mobile_number, recharge_status, sponsor, photo_link, batch_id, active_yn` roughly — cross-check against the frontend edit form fields in `client/src/pages/Coordinator/*` before finalizing the whitelist) and reject/ignore any other key, never interpolate arbitrary request keys into SQL.

### 8.11 `checkConflict` query param aliasing
`timetableController.checkConflict` accepts BOTH `camelCase` (`classroomId`) and `snake_case` (`classroom_id`) query params via `||` fallback chains for every field. Port both accepted forms if the frontend genuinely sends either (check `client/src/pages/Coordinator/TimeTableManagement.js`), otherwise simplify to one canonical form in Java and update the frontend contract note in the plan (acceptable simplification per "simplicity > comprehensive" priority, but must be a deliberate call-out, not silent).

### 8.12 `getTeacherClassDetails` dynamic filter column (low risk, but same family as 8.10)
`reportsController.js:579-582` — `const filterColumn = isNumeric ? "t.teacher_id" : "t.teacher_name"; ... WHERE ${filterColumn} = $1`. The column NAME is chosen dynamically but from a **fixed 2-way internal branch** (not from user input directly) — safe as written (no injection, since the string literal source is hard-coded, not the request value), but still worth a Java `enum`/switch rather than string interpolation for clarity and to make the pattern visually distinct from the genuinely risky §8.10 case.

### 8.13 `getStudentsController` "cohortNumber only" branch fetches-then-filters in app code
`studentController.js:227-231` — when only `cohortNumber` is given (no `batchId`), it calls `getStudentsByCoordinator(user_id)` (fetches ALL of the coordinator's students across all batches/cohorts) then filters in JS by `String(s.cohort_number) === String(cohortNumber)`. Inefficient but functionally fine to reproduce as a WHERE clause in the Java SQL directly (simpler and correct) rather than literally porting the fetch-all-then-filter shape — this is a case where "simplicity" and "parity" agree (same output, better SQL).

### 8.14 `active_yn` value casing / column type mismatch across tables
`pp.student_master.active_yn` is `varchar(10)` with values `'ACTIVE'/'INACTIVE'` (full words), while `pp.classroom.active_yn` is `char(1)` with values `'Y'/'N'`. Two different "is this thing active" conventions in the same schema/module — don't assume a shared Java enum; model them as separate types (`StudentActiveStatus{ACTIVE,INACTIVE}` vs. plain boolean/char for classroom).

## Summary for planner

- 37 live endpoints (not ~102), 4 sub-plans of ~7-14 each (4e-1 masterdata/students, 4e-2 attendance, 4e-3 reports, 4e-4 timetable/dashboards).
- Hardest flows: CSV attendance preview (substring fuzzy match) + commit (broken ON CONFLICT + split-transaction bug), the 3-tier CTE attendance/absentees reports, the rainbow-gauge dashboard queries (3 different "attendance %" formulas across the module).
- No file-generation (POI/OpenPDF) needed — only a static CSV download to move to a classpath resource.
- Biggest risk items to resolve before/during implementation: §8.1 (ON CONFLICT with no matching unique constraint — likely a live prod bug, needs a decision), §8.2 (non-atomic transaction bug in commitCSVAttendance), §8.5 (reports routes have no real auth today), §8.10 (dynamic-column update needs a whitelist).
