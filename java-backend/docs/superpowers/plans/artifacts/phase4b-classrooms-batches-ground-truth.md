# CLASSROOMS + BATCHES Modules — Ground Truth (for Plan 4b)

Captured from a full read of the Node source. Mounts: `app.use("/api/classrooms", classroomRoutes)` (server/index.js:302), `app.use("/api/batches", batchRoutes)` (server/index.js:305). No auth middleware is applied to either mount (matches the rest of the app — `app.use("/api/auth", authRoutes)` is the only auth-specific mount; nothing global sits in front of these two routers). Files (all live code, nothing commented out):
- `server/routes/classroomRoutes.js` (16 lines, 8 routes)
- `server/controllers/classroomController.js` (83 lines, 8 handlers)
- `server/models/classroomModel.js` (207 lines, 8 exports)
- `server/routes/batchRoutes.js` (44 lines, 18 routes)
- `server/controllers/batchController.js` (418 lines, 18 wired handlers + 1 **dead** handler `updateCohort`)
- `server/models/batchModel.js` (349 lines, 21 exports — 2 of them, `checkCohortDuplicateForUpdate`/`updateCohortDetails`, are only reachable from the dead `updateCohort` handler)

## 1. Endpoint Inventory

### classroomRoutes.js (8 routes, all under `/api/classrooms`)

| # | Method | Path | Line | Handler | Purpose |
|---|--------|------|------|---------|---------|
| 1 | GET | `/subjects` | 6 | getSubjects | Dropdown: all subjects |
| 2 | GET | `/platforms` | 7 | getTeachingPlatforms | Dropdown: teaching platforms |
| 3 | GET | `/teachers/:subjectId` | 8 | getTeachersBySubject | Dropdown: teachers for a subject |
| 4 | GET | `/batches/:cohortNumber` | 9 | getBatchesByCohort | Dropdown: batches in a cohort (classroom-side, separate impl from batchModel's) |
| 5 | GET | `/` | 12 | getClassrooms | List all classrooms w/ aggregated batch_ids |
| 6 | POST | `/` | 13 | createClassroom | Create classroom (+ optional batch links) |
| 7 | PUT | `/:id` | 14 | updateClassroom | Update classroom (+ re-sync batch links) |
| 8 | DELETE | `/:id` | 15 | deleteClassroom | Delete classroom (+ unlink batches) |

No route-ordering hazards here: `/subjects`, `/platforms`, `/teachers/:subjectId`, `/batches/:cohortNumber` are all registered before `/` and `/:id`, and none share a literal-vs-param collision (`/:id` is a bare single segment, the others are two-segment or literal).

### batchRoutes.js (18 routes, all under `/api/batches`)

| # | Method | Path | Line | Handler | Purpose |
|---|--------|------|------|---------|---------|
| 1 | GET | `/coordinators` | 6 | getCoordinators | Users holding role `BATCH COORDINATOR` |
| 2 | GET | `/names` | 9 | getBatchNames | Distinct batch names as `{label,value}` |
| 3 | POST | `/names` | 10 | addBatchName | Insert a batch name row (needs cohort_number + created_by) |
| 4 | GET | `/cohorts` | 13 | getAllCohorts | All cohorts |
| 5 | POST | `/cohorts` | 14 | createCohort | Create cohort; `cohort_number` is **derived**, not client-supplied |
| 6 | GET | `/cohorts/active` | 15 | getActiveCohorts | Cohorts where `end_date IS NULL` |
| 7 | GET | `/students/unassigned` | 18 | getStudentsNotInAnyBatch | Students with `batch_id IS NULL AND active_yn='ACTIVE'` |
| 8 | POST | `/:batchId/add-students` | 20 | addStudentsToBatch | Bulk-assign `student_ids[]` to a batch |
| 9 | POST | `/students/remove` | 23 | removeStudentsFromBatch | Bulk-unassign `student_ids[]` (batch_id → NULL) |
| 10 | GET | `/students/:enr_id` | 26 | getStudentsInfoFromBatch | Full applicant profile by enrollment id |
| 11 | GET | `/:cohort_number/batches` | 29 | getBatchesByCohort | Batches in a cohort (batch-module impl, `SELECT *`) |
| 12 | GET | `/` | 32 | getAllBatches | All batches **filtered to the active cohort only** (see quirks) |
| 13 | POST | `/` | 33 | createBatch | Create batch (+ optional coordinator assignment) |
| 14 | GET | `/:batchId` | 36 | getBatchById | Single batch (id + name + cohort_name only) |
| 15 | PUT | `/:batchId` | 37 | updateBatch | Update batch name/cohort/coordinator |
| 16 | DELETE | `/:batchId` | 38 | deleteBatch | Delete batch (coordinators first, then batch row) |
| 17 | GET | `/:batchId/students` | 41 | getStudentsInBatch | Students in a batch |
| 18 | PUT | `/:batchId/students/:enr_id/status` | 42 | updateStudentStatusInBatch | Toggle a student's `active_yn` |

**Route-ordering:** safe. All literal-prefix routes (`/coordinators`, `/names`, `/cohorts`, `/cohorts/active`, `/students/unassigned`, `/students/remove`, `/students/:enr_id`) are registered *before* the catch-all single-segment `/:batchId` (line 36), so Express resolves them correctly. `/:batchId/add-students` (POST, line 20) is registered before `/:batchId` (GET/PUT/DELETE, lines 36-38) but that's irrelevant since methods differ and Express still matches by full path pattern regardless of declaration order for POST vs GET. No two routes with the same HTTP verb and colliding shape exist.

**Dead endpoint / dead code:** `batchController.updateCohort` (controller lines 238-261) and its two model functions `checkCohortDuplicateForUpdate` / `updateCohortDetails` (batchModel.js:194-209) are fully implemented but **never wired into `batchRoutes.js`** — there is no `PUT /cohorts/:id` route. Cohorts can be created but never edited from the API today. Decision needed: port as a real endpoint (Java has the model layer ready) or drop it.

**Frontend-vs-backend mismatch:** `client/src/pages/Admin/Students.js:80` calls `GET ${API_BASE}/api/batches/cohort/${cohortNumber}` — this path does not exist (the real route is `GET /api/batches/:cohort_number/batches`). This call 404s in the live app; it's a pre-existing frontend bug, not something to replicate server-side, but flag it since "ground truth" parity work should not manufacture a matching dead route.

## 2. Exact SQL (verbatim)

### classroomModel.js

```sql
-- getTeachersBySubject($1=subjectId)
SELECT 
    t.teacher_id, 
    u.user_name AS teacher_name
 FROM pp.teacher t
 JOIN pp.user u ON t.user_id = u.user_id
 JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
 WHERE ts.subject_id = $1
 ORDER BY u.user_name

-- createClassroom: INSERT (transaction, see §6)
INSERT INTO pp.classroom
 (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
 RETURNING classroom_id

-- per batch_id in batch_ids[] (loop, N+1 inserts)
INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES ($1, $2)

-- getClassrooms
SELECT 
    c.classroom_id, c.classroom_name, c.class_link, c.active_yn, c.description, c.created_at,
    c.subject_id, c.teacher_id, c.platform_id,
    s.subject_name, s.subject_code,
    u.user_name AS teacher_name, 
    p.platform_name,
    COALESCE(array_agg(cb.batch_id) FILTER (WHERE cb.batch_id IS NOT NULL), '{}') AS batch_ids,
    MAX(b.cohort_number) AS cohort_number
 FROM pp.classroom c
 LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
 LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
 LEFT JOIN pp.user u ON t.user_id = u.user_id
 LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
 LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
 LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
 GROUP BY 
    c.classroom_id, s.subject_name, s.subject_code, 
    u.user_name, p.platform_name
 ORDER BY c.created_at DESC

-- getSubjects
SELECT subject_id, subject_name, subject_code FROM pp.subject ORDER BY subject_name

-- getTeachingPlatforms
SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name

-- updateClassroom: UPDATE (transaction, see §6)
UPDATE pp.classroom
    SET classroom_name = $1, subject_id = $2, teacher_id = $3, platform_id = $4,
        class_link = $5, active_yn = $6, updated_by = $7, updated_at = NOW()
    WHERE classroom_id = $8
    RETURNING classroom_id
-- then (only if batch_ids is an array, even empty []):
DELETE FROM pp.classroom_batch WHERE classroom_id = $1
-- per batch_id in batch_ids[] (loop)
INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES ($1, $2)

-- deleteClassroom: (transaction, see §6)
DELETE FROM pp.classroom_batch WHERE classroom_id = $1
DELETE FROM pp.classroom WHERE classroom_id = $1 RETURNING classroom_id

-- getBatchesByCohort($1=cohort_number)  [classroom-module version]
SELECT batch_id, batch_name FROM pp.batch WHERE cohort_number = $1 ORDER BY batch_name
```

### batchModel.js

```sql
-- fetchCoordinator (no params)
SELECT role_id FROM pp.role WHERE role_name = 'BATCH COORDINATOR'

-- fetchCoordinatorsByRole($1=roleId)
SELECT u.user_id AS id, u.user_name AS name
 FROM pp.user u
 JOIN pp.user_role ur ON u.user_id = ur.user_id
 WHERE ur.role_id = $1

-- checkBatchExists($1=batch_name, $2=cohort_number)
SELECT 1 FROM pp.batch WHERE batch_name = $1 AND cohort_number = $2

-- insertBatch($1=batch_name, $2=cohort_number)
INSERT INTO pp.batch (batch_name, cohort_number) VALUES ($1, $2) RETURNING *

-- assignCoordinatorToBatch($1=coordinator_id/user_id, $2=batchId)
INSERT INTO pp.batch_coordinator_batches (user_id, batch_id)
 VALUES ($1, $2) ON CONFLICT DO NOTHING

-- fetchAllBatches (no bind params — COHORT_START_YEAR is interpolated as a JS constant, not user input)
SELECT 
  b.batch_id AS id, b.batch_name, b.cohort_number, c.cohort_name, 
  u.user_name AS coordinator_name, u.user_id AS coordinator_id 
FROM pp.batch b 
LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number 
LEFT JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id 
LEFT JOIN pp.user u ON bcb.user_id = u.user_id 
WHERE EXISTS (
  SELECT 1 FROM pp.system_config sc 
  WHERE sc.is_active = 'true'
  AND c.cohort_number = (CAST(SUBSTRING(sc.academic_year FROM 1 FOR 4) AS INTEGER) - 2021)
)
ORDER BY b.batch_id DESC

-- fetchBatchById($1=batchId)
SELECT b.batch_id, b.batch_name, c.cohort_name
 FROM pp.batch b
 LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
 WHERE b.batch_id = $1

-- checkDuplicateBatchForUpdate($1=batch_name, $2=cohort_number, $3=batchId)
SELECT 1 FROM pp.batch WHERE batch_name = $1 AND cohort_number = $2 AND batch_id != $3

-- updateBatchDetails($1=batch_name, $2=cohort_number, $3=batchId)
UPDATE pp.batch SET batch_name = $1, cohort_number = $2 WHERE batch_id = $3 RETURNING *
-- NOTE: coordinator_id and batch_status are NOT part of this UPDATE (see §7 quirks)

-- deleteBatchCoordinators($1=batchId)
DELETE FROM pp.batch_coordinator_batches WHERE batch_id = $1

-- deleteBatchById($1=batchId)
DELETE FROM pp.batch WHERE batch_id = $1 RETURNING *

-- fetchBatchNames (no params)
SELECT batch_name FROM pp.batch ORDER BY batch_name ASC

-- insertBatchName($1=batch_name, $2=cohort_number, $3=created_by)
INSERT INTO pp.batch (batch_name, cohort_number, created_by, updated_by)
 VALUES ($1, $2, $3, $3)
 ON CONFLICT (cohort_number, batch_name) DO NOTHING
 RETURNING *

-- fetchAllCohorts (no params)
SELECT cohort_number, cohort_name, start_date, description 
FROM pp.cohort ORDER BY cohort_number ASC

-- checkCohortNameExists($1=cohort_name)
SELECT 1 FROM pp.cohort WHERE cohort_name = $1

-- checkCohortYearExists($1=cohort_number)
SELECT 1 FROM pp.cohort WHERE cohort_number = $1

-- insertCohort($1=cohort_number, $2=cohort_name, $3=start_date, $4=description)
INSERT INTO pp.cohort (cohort_number, cohort_name, start_date, description)
 VALUES ($1, $2, $3, $4) RETURNING *

-- [DEAD — no route] checkCohortDuplicateForUpdate($1=cohort_name, $2=id)
SELECT 1 FROM pp.cohort WHERE cohort_name = $1 AND cohort_number != $2

-- [DEAD — no route] updateCohortDetails($1=cohort_name, $2=start_date, $3=description, $4=id)
UPDATE pp.cohort SET cohort_name = $1, start_date = $2, description = $3
 WHERE cohort_number = $4 RETURNING *

-- fetchStudentsInBatch($1=batchId)
SELECT 
   sm.student_id, sm.enr_id, sm.student_name, sm.student_email, 
   sm.contact_no1, sm.active_yn, api.nmms_reg_number
 FROM pp.student_master sm
 JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
 WHERE sm.batch_id = $1
 ORDER BY sm.student_name

-- fetchActiveCohorts (no params)
SELECT * FROM pp.cohort WHERE end_date IS NULL

-- fetchBatchesByCohortNumber($1=cohort_number)
SELECT * FROM pp.batch WHERE cohort_number = $1

-- fetchStudentInfoByEnrId($1=enr_id)
SELECT 
   sm.student_id, sm.enr_id,
   api.nmms_reg_number, api.nmms_year, api.student_name, api.father_name, api.mother_name,
   api.gender, api.aadhaar, api.dob, api.medium, api.home_address, api.family_income_total,
   api.contact_no1, api.contact_no2, api.current_institute_dise_code, api.previous_institute_dise_code,
   asi.village, asi.father_occupation, asi.mother_occupation, asi.father_education, asi.mother_education,
   asi.household_size, asi.own_house, asi.smart_phone_home, asi.internet_facility_home,
   asi.career_goals, asi.subjects_of_interest, asi.transportation_mode, asi.distance_to_school,
   asi.num_two_wheelers, asi.num_four_wheelers, asi.irrigation_land, asi.neighbor_name,
   asi.neighbor_phone, asi.favorite_teacher_name, asi.favorite_teacher_phone
 FROM pp.student_master sm
 JOIN pp.applicant_primary_info api USING (applicant_id)
 JOIN pp.applicant_secondary_info asi USING (applicant_id)
 WHERE sm.enr_id = $1

-- fetchStudentsNotInAnyBatch (no params)
SELECT sm.student_id, sm.enr_id, sm.student_name, sm.student_email, sm.contact_no1
 FROM pp.student_master sm
 WHERE sm.batch_id IS NULL AND sm.active_yn = 'ACTIVE'
 ORDER BY sm.student_name

-- updateStudentBatchId($1=batchId::int cast in JS, $2=student_ids::bigint[] cast in JS)
UPDATE pp.student_master SET batch_id = $1 WHERE student_id = ANY($2::bigint[])

-- removeStudentBatchId($1=student_ids::bigint[] cast in JS)
UPDATE pp.student_master SET batch_id = NULL WHERE student_id = ANY($1::bigint[])

-- updateStudentStatus($1=newStatus, $2=student_id)
UPDATE pp.student_master SET active_yn = $1 WHERE student_id = $2 RETURNING *
```

All queries are fully parameterized (`$1..$n`) except `fetchAllBatches`'s `COHORT_START_YEAR` constant (`2021`), which is a hard-coded JS `const`, not request input — not an injection risk, but a literal to carry into Java (`static final int COHORT_START_YEAR = 2021` — also duplicated in `batchController.js:3` as its own separate constant, so it exists **twice** in Node, once per file).

## 3. Table DDL (from live-schema.sql)

```sql
-- pp.batch
CREATE TABLE pp.batch (
    batch_id integer DEFAULT nextval('pp.batch_id_seq'::regclass) NOT NULL,
    batch_name character varying(100),
    cohort_number integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    medium character varying(20) DEFAULT 'KANNADA'::character varying,
    house_name character varying(100),
    CONSTRAINT batch_medium_check CHECK (medium IN ('ENGLISH','KANNADA','HINDI','MARATHI'))
);
-- PK: batch_pkey (batch_id)
-- UNIQUE: batch_cohort_number_batch_name_key (cohort_number, batch_name)  -- backs insertBatchName's ON CONFLICT
-- FK: batch_cohort_number_fkey (cohort_number) REFERENCES pp.cohort(cohort_number) ON DELETE CASCADE
-- FK: batch_created_by_fkey (created_by) REFERENCES pp."user"(user_id)
-- FK: batch_updated_by_fkey (updated_by) REFERENCES pp."user"(user_id)
-- NOTE: no "status"/"active" column exists on this table (see §7 quirk on batch_status)
-- NOTE: "medium" and "house_name" columns exist but are never read/written by classroomController/batchController

-- pp.batch_coordinator_batches
CREATE TABLE pp.batch_coordinator_batches (
    user_id numeric(8,0) NOT NULL,
    batch_id integer NOT NULL
);
-- PK: batch_coordinator_batches_pkey (user_id, batch_id)
-- FK: batch_coordinator_batches_batch_id_fkey (batch_id) REFERENCES pp.batch(batch_id)   -- no ON DELETE
-- FK: batch_coordinator_batches_user_id_fkey (user_id) REFERENCES pp."user"(user_id)     -- no ON DELETE

-- pp.classroom
CREATE TABLE pp.classroom (
    classroom_id integer DEFAULT nextval('pp.classroom_id_seq'::regclass) NOT NULL,
    classroom_name character varying(100) NOT NULL,
    subject_id integer,
    teacher_id integer,
    platform_id integer,
    description character varying(200),
    active_yn character(1) DEFAULT 'Y'::bpchar,
    class_link character varying(150),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT classroom_active_yn_check CHECK (active_yn IN ('Y','N'))
);
-- PK: classroom_pkey (classroom_id)
-- FK: classroom_created_by_fkey / classroom_updated_by_fkey (created_by/updated_by) REFERENCES pp."user"(user_id)
-- FK: classroom_platform_id_fkey (platform_id) REFERENCES pp.teaching_platform(platform_id) ON DELETE SET NULL
-- FK: classroom_subject_id_fkey (subject_id) REFERENCES pp.subject(subject_id) ON DELETE SET NULL
-- FK: classroom_teacher_id_fkey (teacher_id) REFERENCES pp.teacher(teacher_id) ON DELETE SET NULL
-- NOTE: active_yn is char(1) 'Y'/'N' here, DIFFERENT convention from student_master.active_yn (varchar 'ACTIVE'/'INACTIVE')

-- pp.classroom_batch (junction table)
CREATE TABLE pp.classroom_batch (
    classroom_id integer NOT NULL,
    batch_id integer NOT NULL
);
-- PK: classroom_batch_pkey (classroom_id, batch_id)
-- FK: classroom_batch_batch_id_fkey (batch_id) REFERENCES pp.batch(batch_id) ON DELETE CASCADE
-- FK: classroom_batch_classroom_id_fkey (classroom_id) REFERENCES pp.classroom(classroom_id) ON DELETE CASCADE
-- Because both FKs cascade, deleting a batch OR a classroom auto-cleans this junction table at the DB level;
-- classroomModel's manual DELETE FROM pp.classroom_batch before deleting a classroom is redundant-but-harmless
-- (keeps behavior visible/explicit inside the app transaction rather than relying on DB cascade timing).

-- pp.cohort
CREATE TABLE pp.cohort (
    cohort_number integer DEFAULT nextval('pp.cohort_seq'::regclass) NOT NULL,
    cohort_name character varying(100),
    start_date date,
    end_date date,
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    status character varying(20),
    current_grade integer,
    CONSTRAINT cohort_current_grade_check CHECK (current_grade IN (9,10,11,12)),
    CONSTRAINT cohort_status_check CHECK (status IN ('ACTIVE','COMPLETED'))
);
-- PK: cohort_pkey (cohort_number)  -- sequence: pp.cohort_seq (default-populated; app also computes cohort_number = year - 2021 explicitly on insert, overriding the sequence default)
-- UNIQUE: cohort_cohort_name_key (cohort_name)
-- FK: cohort_created_by_fkey / cohort_updated_by_fkey (created_by/updated_by) REFERENCES pp."user"(user_id)
-- NOTE: "status" and "current_grade" columns exist but createCohort/updateCohort never set them (always NULL from this module)
-- NOTE: getActiveCohorts filters by end_date IS NULL, NOT by the "status" column — two different, unreconciled notions of "active"

-- pp.teaching_platform
CREATE TABLE pp.teaching_platform (
    platform_id integer DEFAULT nextval('pp.platform_id_seq'::regclass) NOT NULL,
    platform_name character varying(100) NOT NULL
);
-- PK not shown in grep output but platform_id is the natural PK (serial-style, referenced by classroom_platform_id_fkey)

-- pp.student_master (relevant columns only — full table is owned by student module)
CREATE TABLE pp.student_master (
    student_id numeric(14,0) DEFAULT nextval('pp.student_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    enr_id numeric(11,0),
    batch_id integer,
    active_yn character varying(10) DEFAULT 'ACTIVE'::character varying,
    ...
    CONSTRAINT student_master_active_yn_check CHECK (active_yn IN ('ACTIVE','INACTIVE'))
);
-- FK: student_master_batch_id_fkey (batch_id) REFERENCES pp.batch(batch_id)  -- NO ON DELETE clause => RESTRICT
-- This means deleteBatch will hit a raw FK-violation Postgres error (caught generically as 500) if any
-- student_master row still points at that batch_id. Node does NOT pre-check/guard for this (see §7).
```

Sequences used: `pp.batch_id_seq` (batch.batch_id), `pp.classroom_id_seq` (classroom.classroom_id), `pp.cohort_seq` (cohort.cohort_number — overridden by app logic), `pp.platform_id_seq` (teaching_platform.platform_id), `pp.student_id_seq` (student_master.student_id, not touched by this module but relevant to FK).

## 4. Response Shapes & Status Codes

### Classrooms

| Endpoint | 200/201 body | Error bodies |
|---|---|---|
| GET `/subjects` | `[{subject_id, subject_name, subject_code}, ...]` | `500 {error}` |
| GET `/platforms` | `[{platform_id, platform_name}, ...]` | `500 {error}` |
| GET `/teachers/:subjectId` | `[{teacher_id, teacher_name}, ...]` | `500 {error}` |
| GET `/batches/:cohortNumber` | `[{batch_id, batch_name}, ...]` | `500 {error}` |
| GET `/` | `[{classroom_id, classroom_name, class_link, active_yn, description, created_at, subject_id, teacher_id, platform_id, subject_name, subject_code, teacher_name, platform_name, batch_ids:[...], cohort_number}, ...]` | `500 {error}` |
| POST `/` | `201 {message:"Classroom created successfully", classroom_id}` | `500 {error}` |
| PUT `/:id` | `200 {message:"Classroom updated", classroom_id}` (spreads `updated` row) | `404 {message:"Classroom not found"}` if no row matched; `500 {error}` |
| DELETE `/:id` | `200 {message:"Classroom deleted successfully"}` | `404 {message:"Classroom not found"}`; `500 {error}` |

### Batches

| Endpoint | 200/201 body | Error bodies |
|---|---|---|
| GET `/coordinators` | `[{id, name}, ...]` | `404 {error:"Coordinator role not found"}` if role missing; `500 {error:"Internal Server Error"}` |
| GET `/names` | `[{label, value}, ...]` (both = batch_name) | `500` |
| POST `/names` | `201 {message:"Batch created successfully", batch:{...row}}`; **or `200 {message:"Batch name already exists for this cohort"}`** when the `ON CONFLICT DO NOTHING` returns zero rows | `400 {error}` (missing batch_name/cohort_number/created_by); `500` |
| GET `/cohorts` | `[{cohort_number, cohort_name, start_date, description}, ...]` | `500` |
| POST `/cohorts` | `201 {message:"Cohort created successfully", data:{...row}}` | `400` (missing fields / invalid start_date); `409 {error:"Cohort name already exists"}`; `409 {error:"Cohort for year <Y> already exists."}`; `500` |
| GET `/cohorts/active` | `[{...cohort row}, ...]` (`SELECT *`) | `500` |
| GET `/students/unassigned` | `[{student_id, enr_id, student_name, student_email, contact_no1}, ...]` | `500` |
| POST `/:batchId/add-students` | `200 {message:"Students successfully assigned to batch", count}` | `400 {error}` (missing batchId / student_ids); `500` |
| POST `/students/remove` | `200 {message:"Students removed from batch successfully", count}` | `400 {error:"student_ids are required"}`; `500` |
| GET `/students/:enr_id` | `200 {reg_number, ...fullRow}` (reg_number is `nmms_reg_number` duplicated under an alias key, then the row itself spread — so `nmms_reg_number` appears twice under two keys) | `404 {message:"Student not found"}`; `500` |
| GET `/:cohort_number/batches` | `[{...batch row}, ...]` (`SELECT *` from pp.batch) | `500` |
| GET `/` | `[{id, batch_name, cohort_number, cohort_name, coordinator_name, coordinator_id}, ...]` — **only batches in the currently-active academic-year cohort** | `500` |
| POST `/` | `201 {...insertBatch row}` (batch_id, batch_name, cohort_number, timestamps, etc.) | `400 {error:"batch_name and cohort_number are required"}`; `409 {error:"Batch already exists for this cohort."}`; `500` |
| GET `/:batchId` | `200 {batch_id, batch_name, cohort_name}` | `404 {error:"Batch not found."}`; `500` |
| PUT `/:batchId` | `200 {...updateBatchDetails row}` | `400 {error:"Missing required fields"}`; `409 {error:"Duplicate batch name in cohort."}`; `404 {error:"Batch not found"}`; `500` |
| DELETE `/:batchId` | `200 {message:"Batch deleted successfully", deleted:{...row}}` | `404 {error:"Batch not found"}`; `500` (incl. unguarded FK-violation from student_master, see §7) |
| GET `/:batchId/students` | `[{student_id, enr_id, student_name, student_email, contact_no1, active_yn, nmms_reg_number}, ...]` | `500` |
| PUT `/:batchId/students/:enr_id/status` | `200 {message:"Student status updated successfully"}` | `400 {error:"student_id or enr_id is required"}`; `400 {error:"active_yn is required"}`; `404 {error:"Student not found"}` (either from enr_id lookup or from zero-row update); `500 {error, details}` (this one handler uniquely echoes `err.message` in the body) |

Every handler in both controllers uses `try/catch` around raw model calls; there is no shared error-formatting middleware — each catch block hand-writes its own status/body, and the two batch "not found" checks (`getBatchById`, `deleteBatch`) key off `rows.length===0` vs `rowCount===0` respectively (both correct for their query shape, but inconsistent style to note for Java).

## 5. File-Generating Endpoints

None. Neither module streams a file, CSV, PDF, or ZIP. All 26 endpoints return JSON only.

## 6. Transactions

Four model functions use `pool.connect()` + manual `BEGIN`/`COMMIT`/`ROLLBACK` (client always released in `finally`):

1. **`classroomModel.createClassroom`** (lines 31-62): INSERT classroom → RETURNING classroom_id → loop-INSERT into `classroom_batch` for each `batch_ids[]` entry (only if array non-empty) → COMMIT. N+1 inserts for batch links (no bulk/multi-row INSERT).
2. **`classroomModel.updateClassroom`** (lines 116-156): UPDATE classroom → if `batch_ids` is an Array (even `[]`) → `DELETE FROM classroom_batch WHERE classroom_id=$1` then loop-INSERT new links → COMMIT. **If `batch_ids` is omitted entirely** (not an array, e.g. `undefined`), the delete/re-insert step is skipped and existing links are left untouched — this is a meaningful behavioral branch to preserve (partial update vs full resync).
3. **`classroomModel.deleteClassroom`** (lines 164-188): `DELETE FROM classroom_batch WHERE classroom_id=$1` → `DELETE FROM classroom WHERE classroom_id=$1 RETURNING classroom_id` → COMMIT.
4. None of the batch-module functions use an explicit transaction — every `batchModel.*` export is a single `pool.query(...)` call. Multi-step batch controller flows (`createBatch` → `assignCoordinatorToBatch`; `updateBatch` → `deleteBatchCoordinators` → `assignCoordinatorToBatch`; `deleteBatch` → `deleteBatchCoordinators` → `deleteBatchById`) are **NOT wrapped in a transaction** — each step is a separate autocommit query issued sequentially from the controller. If step 2 fails after step 1 succeeded (e.g. coordinator insert fails after batch insert committed), the batch row is left orphaned/partially configured. Decide whether to tighten this in Java (recommended: wrap these controller-level multi-query sequences in a single transaction) or preserve the loose Node behavior for exact parity.

## 7. Quirks / Complexity (file:line)

1. **Dead endpoint — `updateCohort`.** `batchController.js:238-261` (`exports.updateCohort`) and its two model functions `batchModel.js:194-199` (`checkCohortDuplicateForUpdate`) / `batchModel.js:201-209` (`updateCohortDetails`) are fully written but never mounted in `batchRoutes.js`. There is no way to edit a cohort via the current API. **Decision needed**: port it as `PUT /cohorts/:id` (Java can do this cleanly since the model layer already exists) or intentionally omit it.

2. **`batch_status` is a UI-only fiction.** `client/src/pages/Admin/Batches.js:233,280` sends `batch_status` in the PUT `/api/batches/:id` body (both from the edit form and from a dedicated "toggle status" confirm flow at line 280). `batchController.updateBatch` (batchController.js:90-119) destructures only `{batch_name, cohort_number, coordinator_id}` from `req.body` — `batch_status` is silently dropped. `pp.batch` has **no status/active column at all** (see §3 DDL). The "House status changed to X successfully!" toast the UI shows is **always false** — nothing persists. Flag for product decision: either add a real column + wire it, or drop the dead UI affordance. Java should not invent persistence for a field that was never real in Node — replicate the no-op unless told to fix it.

3. **`deleteBatch` has no FK guard.** `batchController.deleteBatch` (batchController.js:124-140) does not check whether any `pp.student_master` rows still reference the batch before deleting. `student_master_batch_id_fkey` (live-schema.sql:4208-4209) has no `ON DELETE` clause (defaults to `NO ACTION`/RESTRICT), so deleting a batch with assigned students throws a raw Postgres FK-violation exception, caught generically and returned as `500 {error:"Internal Server Error"}` with no explanation to the user. Decide whether Java should add a friendlier pre-check (recommended) or replicate the opaque 500.

4. **`fetchAllBatches` hard-scopes to "the active cohort" via `pp.system_config`.** `batchModel.js:55-76` (`GET /api/batches`, the main batch list) only returns batches whose `cohort_number` equals `CAST(SUBSTRING(sc.academic_year,1,4) AS INTEGER) - 2021`, for the row in `pp.system_config` where `is_active='true'`. This is a significant business rule easy to miss: **the "list all batches" endpoint does not list all batches** — it's implicitly scoped to whatever academic year is currently flagged active in `system_config`. `2021` is `COHORT_START_YEAR`, duplicated as a separate literal in both `batchController.js:3` and inlined directly in this query string (not passed as a bind param) — two independently-maintained copies of the same magic number.

5. **Two different `getBatchesByCohort` implementations coexist.** `classroomModel.getBatchesByCohort` (classroomModel.js:190-196, mounted at `GET /api/classrooms/batches/:cohortNumber`) returns only `{batch_id, batch_name}`. `batchModel.fetchBatchesByCohortNumber` (batchModel.js:241-246, mounted at `GET /api/batches/:cohort_number/batches`) returns `SELECT *` (all batch columns). Both are legitimately used by different frontend screens for different purposes — port both, don't try to unify them.

6. **`cohort_number` is derived server-side, never client-supplied.** `createCohort` (batchController.js:202-233) computes `cohort_number = new Date(start_date).getFullYear() - 2021` and rejects (`409`) if that year's cohort already exists — even though the `pp.cohort_number` column itself has a sequence default (`pp.cohort_seq`). The sequence default is effectively dead code from the app's perspective since the app always supplies an explicit value.

7. **Two unreconciled notions of "active cohort".** `getActiveCohorts` (`GET /cohorts/active`) filters by `end_date IS NULL`. `fetchAllBatches`'s active-scoping (quirk #4) uses `pp.system_config.is_active`. The `pp.cohort.status` column (`'ACTIVE'|'COMPLETED'` check constraint) is never read or written anywhere in this module — a third, entirely unused "active" signal sits on the table. Do not conflate these three when porting.

8. **`insertBatchName`'s silent-success-on-conflict.** `addBatchName` (batchController.js:160-184) treats `ON CONFLICT (cohort_number, batch_name) DO NOTHING` returning zero rows as a **200 success** ("Batch name already exists for this cohort"), not an error — differs from `createBatch`'s equivalent duplicate case, which does an explicit pre-check and returns **409**. Two different duplicate-handling philosophies for conceptually the same "add a batch name" operation (one is pessimistic-check, the other is optimistic-insert-then-check-rowcount). Preserve both distinctly — they back different UI flows (`POST /names` is the inline "add new batch name" autocomplete-creator in `Batches.js:469`; `POST /` is the full create-batch form).

9. **`updateStudentStatusInBatch` route/param mismatch.** Route is `PUT /:batchId/students/:enr_id/status` (batchRoutes.js:42) — only `batchId` and `enr_id` are route params. But the handler (batchController.js:387-417) destructures `const { student_id, enr_id } = req.params;` — `student_id` is never a route param, so `sid` is always initially `undefined` and the code always falls into the `if (!sid && enr_id)` branch, doing an extra `fetchStudentInfoByEnrId` lookup to resolve `enr_id → student_id` before the actual status UPDATE. Functionally correct (frontend always sends `enrId`, `ViewBatchStudents.js:277`) but the `student_id` param branch is unreachable dead code — a leftover from an earlier route shape. Also note: `batchId` (route param) is accepted but **never used** in this handler at all — the status update has no batch-scoping, so it would happily update a student's status even if `enr_id` belongs to a different batch than `:batchId` in the URL.

10. **`removeStudentsFromBatch` ignores `batch_id`.** Frontend (`ViewBatchStudents.js:295-297`) sends `{batch_id: batchId, student_ids}` to `POST /students/remove`, but `batchController.removeStudentsFromBatch` (batchController.js:368-385) and `BatchModel.removeStudentBatchId` (batchModel.js:329-338) only read `student_ids` — `batch_id` in the request body is silently ignored. The UPDATE sets `batch_id = NULL` for the given student ids regardless of which batch they're currently in. Not exploitable (student ids come from a batch-scoped UI list) but means the endpoint provides no server-side guarantee that removal is scoped to the batch the UI thinks it's removing from.

11. **`active_yn` convention mismatch across tables in the same module.** `pp.classroom.active_yn` is `character(1)` constrained to `'Y'/'N'`. `pp.student_master.active_yn` (used by `updateStudentStatusInBatch`, `fetchStudentsInBatch`, `fetchStudentsNotInAnyBatch`) is `varchar(10)` constrained to `'ACTIVE'/'INACTIVE'`. `updateStudentStatus` (batchModel.js:341-348) passes whatever `active_yn` string the client sends straight through to the UPDATE with no server-side validation against the allowed enum — an invalid value (e.g. `'Y'` sent by mistake, given the classroom convention exists elsewhere in the same module) throws a raw Postgres check-constraint violation, caught and surfaced as `500 {error:"Internal Server Error", details: err.message}` (this is the one handler that leaks `err.message`).

12. **Unused table columns.** `pp.batch.medium` (default `'KANNADA'`, check-constrained to 4 languages) and `pp.batch.house_name` exist on the table but neither `classroomController`/`classroomModel` nor `batchController`/`batchModel` ever reads or writes them. `pp.cohort.status` and `pp.cohort.current_grade` are likewise always NULL from this module's perspective (quirk #7 covers `status`; same applies to `current_grade`). If Java DTOs are generated from the DB schema, these columns will appear but should map to nothing in the ported business logic unless a newer/undocumented code path elsewhere in the app uses them (worth a repo-wide grep before assuming true dead weight — out of scope for this doc, which only covers the two named route files).

13. **No dynamic-SQL/injection risk in this module.** Every query in both models is fully parameterized (`$1..$n`); the one string-interpolated value (`COHORT_START_YEAR` in `fetchAllBatches`) is a hard-coded numeric JS constant, never derived from request input. Unlike the NMMS-merge module's dynamic table-name pattern, there is nothing here requiring an enum/whitelist translation — straightforward to port as prepared statements / `JdbcClient` param binding throughout.

14. **N+1 insert pattern for classroom↔batch links.** Both `createClassroom` and `updateClassroom` (classroomModel.js) loop over `batch_ids[]` issuing one `INSERT INTO classroom_batch` per id inside the already-open transaction, rather than a single multi-row `INSERT ... VALUES ($1,$2),($1,$3),...` or `unnest()`-based bulk insert. Fine to replicate as-is for parity (transaction bounds correctness either way); worth a bulk-insert upgrade note if performance ever matters (batch_ids lists are expected to be small — a handful of batches per classroom).
