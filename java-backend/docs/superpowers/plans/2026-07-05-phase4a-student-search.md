# IMAS Spring Boot Migration — Plan 4a: Student Portal + Student Search + Applicant Search

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `studentRoutes.js` / `studentSearchRoutes.js` / `searchRoutes.js` trio (15 endpoints, all read-only) to a new `com.rcf.imas.modules.student` module, preserving exact SQL, response shapes, status codes, and per-endpoint error envelopes — while **splitting authorization by source route file**: the student-mobile-app-facing `studentRoutes` endpoints get `@PreAuthorize("isAuthenticated()")` (any valid JWT, matches Node's `authenticate` middleware — the frozen mobile app must keep working), and the admin-only `studentSearchRoutes`/`searchRoutes` endpoints (which Node left completely open) get the NEW hardening `@PreAuthorize("hasRole('ADMIN')")`.

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `student` with `web/` (3 controllers, split by auth level and by source Node controller) and `persistence/` (3 repositories + 1 closed sort-whitelist enum). All 15 endpoints are single-statement or two-statement autocommit reads — **no `@Transactional` anywhere**, no write repository, no file generation.

**Tech Stack (no additions):** Plain `JdbcClient`, already on the classpath.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Phases 0/1/2a/2b/2c/3a/3b/3c/3d are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`, `@AuthenticationPrincipal JwtService.FinalToken`), `SecurityConfig` (method security, `permitAll()` precedent for `/api/exams/hallticket/**`), `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1–3d — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `WHERE sm.user_id = :userId::numeric`, `api.district = :educationDistrict::numeric`, `pp.batch WHERE cohort_number = :cohortNumber::integer`, etc. Java JDBC binds an unqualified string param as `VARCHAR`; Postgres will not implicitly compare `VARCHAR = numeric`, so every numeric-column comparison needs an explicit `::numeric`/`::integer` cast on the bind variable (this is a Java-side necessity even where Node's `pg` driver got away with implicit inference — the *failure mode* on bad input is preserved either way: a non-numeric path/query value still throws a PG type-conversion error → whatever 500 envelope that endpoint's catch block maps to, matching Node's uncaught-coercion-error behavior).
> 3. **Numeric-column serialization — MODULE-SPECIFIC DEVIATION, read carefully.** Other modules' shared `genericRow` truncates every `NUMERIC`/`DECIMAL` column via `bd.toBigInteger().toString()` because their numeric columns are all whole-number ids/scores (scale 0). **This module has genuinely fractional numeric output** (`attendance_percent`, `percent` — both `ROUND(x::numeric, 2)`) that Node's `pg` driver returns as a decimal string (e.g. `"66.67"`), matching node-pg's default numeric-to-string serialization. Truncating those via `toBigInteger()` would silently corrupt the response (`"66.67"` → `"66"`). This module's three `genericRow` copies therefore use **`bd.toPlainString()`** instead of `bd.toBigInteger().toString()` for the `NUMERIC`/`DECIMAL` branch. `toPlainString()` is a strict superset — for scale-0 numerics (ids, `nmms_year`, `gmat_score numeric(2,0)`, etc.) it produces the byte-identical output as `toBigInteger().toString()` (e.g. `"820001"`), so this is a safe, backward-compatible generalization, not a behavior change for the id-like columns. `COUNT(...)`/`COUNT(...) FILTER(...)` results are Postgres `BIGINT` (`total_classes`, `attended_classes`) → the existing `BIGINT → String` branch (`rs.getLong` + `wasNull` check) is unaffected and reused as-is. `integer`/`int` columns (`batch_id`, `cohort_number`, `timetable_id`, …) still serialize as native JSON numbers via the `else → rs.getObject(i)` passthrough branch — untouched.
> 4. **DATE columns → `"yyyy-MM-dd"` string. TIME columns → `"HH:mm:ss"` string. TIMESTAMP → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).** Everything else passes through `rs.getObject(i)` natively. Map keys are literal snake_case (the SQL column alias, verbatim).
> 5. **snake_case JSON** global default. Request DTOs read as individual `@RequestParam`/`@PathVariable`/`@RequestBody Map<String,Object>` — no bespoke request POJOs needed anywhere in this module (all bodies are query strings or absent).
> 6. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`; `.with(key,value)` appends extra keys (e.g. `.with("success", false)`, `.with("details", e.getMessage())`). **This module has the most heterogeneous error envelopes of any module ported so far — near-every endpoint has a unique body.** Each handler wraps its repository call(s) in a local `try { … } catch (ApiException e) { throw e; } catch (Exception e) { throw ApiException.<key>(status, "<exact Node message>"); }` so the endpoint-specific message survives instead of falling through to the module-wide `GlobalExceptionHandler`'s generic `{error:"Internal Server Error"}` fallback. One endpoint (`GET /api/student/:student_id`'s 500) returns a body with **neither** `error` nor `message` key at all (`{success:false}`) — `ApiException` cannot produce a keyless body, so that one handler returns a `ResponseEntity` directly instead of throwing (see Task 3).
> 7. **Auth split (FIRM DECISION — audit CRITICAL, read before writing any handler):**
>    | Source Node file | Java controller | Class-level `@PreAuthorize` | Why |
>    |---|---|---|---|
>    | `studentRoutes.js` (endpoints #1–#10) | `StudentPortalController` | `isAuthenticated()` | Node's own `authenticate` middleware gated every route here except the health check — this is the student-mobile-app's own data (`req.user.user_id`-scoped), not admin data. Blanket-ADMIN would break the frozen mobile app. |
>    | `studentRoutes.js` endpoint **#1 only** (`GET /api/student/`) | `StudentPortalController.health()` | method-level `@PreAuthorize("permitAll()")` override + `SecurityConfig` matcher | The **only** route in `studentRoutes.js` with no `authenticate` middleware (plain-text health check, no PII). Same override pattern as the existing `/api/exams/hallticket/**` precedent in `SecurityConfig`. |
>    | `studentSearchRoutes.js` (endpoints #11–#12) | `StudentSearchController` | `hasRole('ADMIN')` | Node left these fully open (no `authenticate` at all) — admin screens, NEW hardening. |
>    | `searchRoutes.js` (endpoints #13–#15) | `ApplicantSearchController` | `hasRole('ADMIN')` | Same as above — Node left these open too. |
> 8. **Controllers:** class package-private; every handler method **`public`** (package-private methods silently skip `@PreAuthorize`).
> 9. **Transactions:** none needed — every query in this module is a single autocommit `SELECT` (confirmed zero `INSERT`/`UPDATE`/`DELETE`/`BEGIN` across all three Node model files). No write repository, no `@Transactional`.
> 10. **Test isolation:** all `*IT` extend `PgIntegrationTest`. `@AfterEach`-clean children-before-parents. **This module's FK chain is the deepest yet** — verify against the DDL facts table (§ below) before writing any seed:
>     `pp.student_attendance` → `pp.class_session` → `pp.classroom_batch` → `pp.classroom` → (`pp.subject`, `pp.teacher`, `pp.teaching_platform`) and `pp.timetable` → `pp.student_master`(unique `applicant_id`, FK `batch_id`, FK `user_id`) → `pp.applicant_primary_info` / `pp.batch`(FK `cohort_number` — logical, not enforced) → `pp.cohort` / `pp."user"`. `pp.inactive_students` and `pp.exam_results` have **no primary key** — plain unconditional `DELETE ... WHERE` cleanup, no `ON CONFLICT` upsert possible for them (use `INSERT` once per test, never re-run without cleanup). Seed `jurisdiction_type` before `jurisdiction` where jurisdiction rows are needed (Tasks 3–4 only; Tasks 1–2 need no jurisdiction rows at all). Advance sequences (`setval`) after every explicit-PK seed.
> 11. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.
> 12. **Route-collision note (informational, not a Java concern):** Node relies on `studentRoutes` (mounted first, no bare `/:id`) falling through to `studentSearchRoutes`'s `/student/:student_id` for numeric-looking path segments. Spring's `@RequestMapping` per-controller model has no such fragility — `StudentPortalController` and `StudentSearchController` each own disjoint, explicit path templates, so this hazard does not carry over. No action needed beyond registering the exact paths from the ground truth.

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase4a-student-search-ground-truth.md`. Node source re-read directly for this plan (ground truth's SQL/route text verified verbatim against):
- `server/routes/studentRoutes.js` (line 36–96 live block; lines 1–31 commented-out, ignored)
- `server/controllers/coordinator/studentController.js` (line 188–470 live block; commented-out draft above, ignored)
- `server/models/coordinator/studentModel.js` (line 420–956 live block — SQL text only, re-verified against ground truth §2a)
- `server/routes/studentSearchRoutes.js`, `server/controllers/studentSearchController.js`, `server/models/studentSearchModel.js` (all fully live, no dead code)
- `server/routes/searchRoutes.js`, `server/controllers/searchController.js`, `server/models/searchModel.js` (all fully live, no dead code)
- Mounts (`server/index.js`): `app.use("/api/student", studentRoutes)` (line 287) → `app.use("/api", studentSearchRoutes)` (line 293) → `app.use("/api", searchRoutes)` (line 320).

### Table facts (from `live-schema.sql`)

| Table | PK | Notable UNIQUE / FK | Notable columns for this module |
|---|---|---|---|
| `pp.student_master` | `student_id numeric(14,0)` | UNIQUE `applicant_id`, UNIQUE `enr_id numeric(11,0)`; FK `applicant_id→applicant_primary_info`, `batch_id→batch`, `user_id→"user"` | `student_email_password varchar(100)` — **excluded from endpoint #12's response** (firm decision). `gender character(1)` CHECK `M/F/O`. `active_yn varchar(10)` CHECK `ACTIVE/INACTIVE` default `ACTIVE`. |
| `pp.batch` | `batch_id integer` (seq `pp.batch_id_seq`) | `cohort_number integer` — no enforced FK, logical link only | `medium varchar(20)` CHECK 4 values, default `KANNADA`. |
| `pp.cohort` | `cohort_number integer` (seq `pp.cohort_seq`) | — | `status`/`current_grade` CHECK-constrained, both nullable. |
| `pp.institute` | `institute_id numeric(14,0)` (seq) | `dise_code varchar(15)` (not DB-UNIQUE per live DDL, but used as a natural join key) | — |
| `pp.timetable` | `timetable_id integer` (seq) | FK `classroom_id→classroom` | `start_time`/`end_time time NOT NULL`; `day_of_week` CHECK 7 values. |
| `pp.classroom` | `classroom_id integer` (seq) | FK `subject_id`/`teacher_id`/`platform_id` → resp. tables (SET NULL) | `classroom_name varchar(100) NOT NULL`. |
| `pp.classroom_batch` | composite `(classroom_id, batch_id)` | both NOT NULL, junction, no seq | — |
| `pp.subject` | `subject_id integer` (seq) | `subject_code`/`subject_name NOT NULL` | — |
| `pp.teacher` | `teacher_id integer` (seq) | — | all columns nullable except PK |
| `pp.teaching_platform` | `platform_id integer` (seq) | UNIQUE `platform_name` | `platform_name NOT NULL` |
| `pp.class_session` | `session_id integer` (seq `pp.class_session_seq`) | FK `classroom_id→classroom NOT NULL` | `session_date`/`start_time`/`end_time NOT NULL` |
| `pp.student_attendance` | `attendance_id integer` (seq) | FK `session_id→class_session`, `student_id→student_master` | `status varchar(20) NOT NULL` CHECK `PRESENT/ABSENT/LATE JOINED/LEAVE` |
| `pp.inactive_students` | **none (no PK)** | FK `student_id→student_master` | plain unconditional insert/delete only |
| `pp.exam_results` | **none (no PK)** | FK `applicant_id→applicant_primary_info` | `pp_exam_score numeric(3,0)` |
| `pp.applicant_primary_info` | `applicant_id numeric(14,0)` (seq) | UNIQUE `nmms_reg_number numeric(11,0) NOT NULL` | `app_state/district/nmms_block numeric(12,0)`; `gmat_score/sat_score numeric(2,0)`; `nmms_year numeric(4,0)` |
| `pp.applicant_secondary_info` | `applicant_id` (PK+FK, 1:1) | FK `applicant_id→applicant_primary_info` CASCADE | `spl_health_cond`/`spl_family_cond character(1)` default `'N'` |
| `pp.jurisdiction` | `juris_code numeric(12,0)` | self-FK `parent_juris` (logical, unenforced) | `juris_name varchar(100)`, `juris_type varchar(100)` |
| `pp."user"` | `user_id numeric(8,0)` (seq) | UNIQUE-ish `user_name NOT NULL` (no DB constraint but app-unique) | — |

### Endpoint contract (15 routes)

| # | Method + Path | Auth | Success | Errors |
|---|---|---|---|---|
| 1 | GET `/api/student/` (also `/api/student`) | public | `200` plain text `"Student API Working"` | — |
| 2 | GET `/api/student/profile` | isAuthenticated | `200` full profile object | `404 {message:"Student profile not found"}`; `500 {error:"Server error"}` |
| 3 | GET `/api/student/timetable` | isAuthenticated | `200 [...]` | `404 {message:"Student profile not found."}` (note trailing period, differs from #2); `400 {message:"No batch assigned."}`; `500 {message:"Internal Server Error"}` (note: `message` key, not `error`, unlike every sibling below) |
| 4 | GET `/api/student/performance` | isAuthenticated | `200 [...]` — **alias of #6**, same handler | `500 {error:"Failed to fetch subject performance"}` |
| 5 | GET `/api/student/summary` | isAuthenticated | `200 {total_classes,attended_classes,attendance_percent,exam_score}` (or `{exam_score:"-"}` only — see quirk) | `500 {error:"Failed to fetch summary"}` |
| 6 | GET `/api/student/subjects` | isAuthenticated | `200 [...]` | `500 {error:"Failed to fetch subject performance"}` |
| 7 | GET `/api/student/monthly` | isAuthenticated | `200 [{month,percent}]` | `500 {error:"Failed to fetch monthly data"}` |
| 8 | GET `/api/student/weekly` | isAuthenticated | `200 [{week_start,percent}]` | `500 {error:"Failed to fetch weekly data"}` |
| 9 | GET `/api/student/custom?fromDate=&toDate=` | isAuthenticated | `200 [...]` | `400 {error:"Date range required"}`; `500 {error:"Failed to fetch custom data"}` |
| 10 | GET `/api/student/{id}/inactive-history` | isAuthenticated | `200 [...]` (empty array if none, not 404) | `500 {error:"Failed to fetch inactive history"}` |
| 11 | GET `/api/search-students` | **ADMIN (new)** | `200 {success:true,data:[...],pagination:{total,limit,offset,page,totalPages,hasMore}}` | `500 {success:false,error:"Internal Server Error"}` |
| 12 | GET `/api/student/{student_id}` | **ADMIN (new)** | `200 {success:true,data:{...student_master row, password column EXCLUDED...}}` | `404 {success:false,message:"Student not found"}`; `500 {success:false}` (bare — no `error`/`message` key at all) |
| 13 | GET `/api/search` | **ADMIN (new)** | `200 {data:[...],pagination:{total,limit,offset,totalPages,currentPage,nextOffset,prevOffset},sort:{sortBy,sortOrder}}` | `404 {message:"No applications found matching the criteria."}` (**only** when `offset===0` and zero rows); `500 {error:"Internal Server Error",details:...}` |
| 14 | GET `/api/cohorts` | **ADMIN (new)** | `200 {data:[...]}` | `500 {error:"Internal Server Error",details:...}` |
| 15 | GET `/api/batches/cohort/{cohortNumber}` | **ADMIN (new)** | `200 {data:[...]}` | `500 {error:"Internal Server Error",details:...}` |

**Duplicate route alias:** #4 and #6 map to the exact same Java handler (`@GetMapping({"/performance","/subjects"})`) — one method, two paths, matching Node's shared `getStudentSubjectPerformance` controller function used by both routes.

## Exact SQL (verbatim, from the three Node model files)

### `studentModel.js` (endpoints #2–#10)

```sql
-- getStudentProfileByUserId(user_id) -- #2, reused internally by #3
SELECT
  sm.student_id, sm.applicant_id, sm.enr_id, sm.student_name, sm.gender,
  sm.father_name, sm.father_occupation, sm.mother_name, sm.mother_occupation,
  sm.student_email, sm.student_email_password, sm.parent_email,
  sm.contact_no1, sm.contact_no2, sm.home_address,
  sm.current_institute_dise_code, sm.previous_institute_dise_code,
  ci.institute_name AS current_institute, pi.institute_name AS previous_institute,
  sm.sim_name, sm.teacher_name, sm.teacher_mobile_number,
  sm.active_yn, sm.recharge_status, sm.sponsor, sm.photo_link,
  sm.batch_id, b.batch_name, c.cohort_number, c.cohort_name,
  ins.inactive_reason, sm.created_at, sm.updated_at
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
Note: the `batch`/`cohort` joins are **INNER** — a student with `batch_id IS NULL` gets no profile row at all (404, not a partial object). Note that this query returns `sm.student_email_password` — **this is the student's OWN profile (isAuthenticated, own data), NOT the admin `SELECT *` of endpoint #12 — do NOT redact it here.** Only endpoint #12 redacts.

```sql
-- getStudentTimetableModel(batchId) -- #3
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

```sql
-- getStudentSummaryModel(user_id) -- #5, TWO queries, no transaction
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
WHERE sm.user_id = $1;

SELECT er.pp_exam_score
FROM pp.exam_results er
JOIN pp.student_master sm ON sm.applicant_id = er.applicant_id
WHERE sm.user_id = $1;
```
JS merge: `{ ...rows[0], exam_score: examRes.rows[0]?.pp_exam_score || "-" }`.
**Quirk A (must preserve verbatim):** if the attendance query returns **zero rows** (no `student_master` row for `user_id`, e.g. because `sm.user_id` matches nothing — the INNER JOINs downstream make it impossible for a matched row to yield zero aggregate rows, so this only happens when there's no matching `student_master` row at all), `rows[0]` is `undefined`, `{...undefined}` spreads to `{}`, and the response degrades to **exactly `{exam_score:"-"}`** — no `total_classes`/`attended_classes`/`attendance_percent` keys at all (not even `null`).
**Quirk B (newly identified during this plan's Node re-read, NOT in the earlier ground truth doc — flag for the reviewer):** JS `x || "-"` treats `0` as falsy. If `pp_exam_score` is a genuine `0` (not null, not missing), the response still shows `exam_score:"-"`, discarding the real zero score. This must be pinned with a test using `pp_exam_score = 0` on a student who **does** have attendance data, to prove the attendance keys are present while `exam_score` still degrades to `"-"` (distinguishing this from Quirk A, which hides ALL keys).

```sql
-- getStudentSubjectPerformanceModel(user_id) -- #4/#6 alias
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
Note: `JOIN pp.subject` (INNER) — a classroom with `subject_id IS NULL` is silently dropped, unlike the timetable query's `LEFT JOIN subject`.

```sql
-- getStudentMonthlyAttendanceModel(user_id) -- #7
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

```sql
-- getStudentWeeklyAttendanceModel(user_id) -- #8, identical shape keyed by week
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
Neither monthly nor weekly guards with `NULLIF(...,0)` (unlike summary/subjects/custom) — safe in practice only because `GROUP BY` on a date-derived column guarantees `COUNT(cs.session_id) >= 1` per group. Preserve as-is (do not add the guard).

```sql
-- getStudentCustomAttendanceModel(user_id, fromDate, toDate) -- #9
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
Node passes `fromDate`/`toDate` raw into `BETWEEN` with only a truthiness check (`!fromDate || !toDate`), no format validation. Java binds `:fromDate::date`/`:toDate::date` (house convention: cast the param) — an invalid date string still throws a PG error, caught by this endpoint's `catch` → `500 {error:"Failed to fetch custom data"}`, matching Node's identical failure mode (raw PG error bubbles into the same generic catch).

```sql
-- getInactiveHistoryByStudentId(student_id) -- #10
SELECT inactive_reason, inactive_date, created_by, updated_by
FROM pp.inactive_students
WHERE student_id = $1
ORDER BY inactive_date DESC;
```
`student_id` is `req.params.id`, unc­ast in Node (implicit PG coercion; non-numeric → uncaught 500). Java casts `:id::numeric` explicitly — same failure mode preserved (PG throws either way on non-numeric input; Java's `catch` maps it to this endpoint's `{error:"Failed to fetch inactive history"}`).

### `studentSearchModel.js` (endpoints #11–#12)

```sql
-- searchStudents(filters) -- #11, dynamic WHERE, fully parameterized
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
  [AND sm.batch_id = $n]                        -- if batch_id
  [AND c.cohort_number = $n]                     -- if cohort_number
  [AND sm.student_name ILIKE $n]  -- '%name%'    -- if name
  [AND CAST(sm.enr_id AS TEXT) ILIKE $n]         -- if enr_id, '%enr_id%'
  [AND UPPER(sm.gender) = $n]     -- uppercased  -- if gender
  [AND api.app_state = $n]                       -- if state_id
  [AND api.district = $n]                        -- if district_id
  [AND api.nmms_block = $n]                      -- if block_id
  [AND COALESCE(asi.spl_health_cond, 'N') = $n]  -- if spl_health_cond
  [AND COALESCE(asi.spl_family_cond, 'N') = $n]  -- if spl_family_cond
ORDER BY sm.student_name ASC
LIMIT $n OFFSET $n;

-- count query: same joins minus the two jurisdiction LEFT JOINs, same WHERE, no limit/offset
SELECT COUNT(*) AS total
FROM pp.student_master sm
JOIN pp.batch b ON sm.batch_id = b.batch_id
JOIN pp.cohort c ON b.cohort_number = c.cohort_number
JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
WHERE 1=1 [same AND clauses];
```
Coercions (verified from live Node source `studentSearchModel.js`): `batch_id`/`cohort_number`/`state_id`/`district_id`/`block_id` → `Number()`; `limit` clamped `Math.min(Math.max(Number(limit)||50, 1), 100)`; `offset` clamped `Math.max(Number(offset)||0, 0)`; `gender` uppercased; `name`/`enr_id` trimmed. `JOIN pp.applicant_primary_info` (INNER) — a `student_master` row with no matching applicant is silently excluded from all search results, even with zero filters.

Response envelope (verified from live `studentSearchController.js`):
```js
res.json({
  success: true, data: result.rows,
  pagination: {
    total: result.total, limit: result.limit, offset: result.offset,
    page: Math.floor(result.offset / result.limit) + 1,
    totalPages: Math.ceil(result.total / result.limit),
    hasMore: result.offset + result.limit < result.total,
  },
});
```

```sql
-- getStudentById(student_id) -- #12
SELECT * FROM pp.student_master WHERE student_id = $1
```
`SELECT *` includes `student_email_password` verbatim. **Firm decision: EXCLUDE it** — Java selects an explicit column list (every `student_master` column except `student_email_password`).

### `searchModel.js` (endpoints #13–#15)

```sql
-- searchStudents(filters, pagination, sorting) -- #13
-- shared base:
FROM pp.applicant_primary_info a
LEFT JOIN pp.institute i ON a.current_institute_dise_code = i.dise_code
LEFT JOIN pp.jurisdiction js ON a.app_state = js.juris_code
LEFT JOIN pp.jurisdiction jd ON a.district = jd.juris_code
LEFT JOIN pp.jurisdiction jb ON a.nmms_block = jb.juris_code
WHERE 1=1
  -- if nmms_reg_number present: ONLY this filter, all others ignored:
  [AND a.nmms_reg_number = $n]
  -- else, all of:
  [AND a.student_name ILIKE $n]      -- '%student_name%'
  [AND a.nmms_year = $n]             -- parseInt(nmms_year)
  [AND UPPER(a.medium) = $n]
  [AND a.app_state = $n]
  [AND a.district = $n]
  [AND a.nmms_block = $n]
  [AND a.current_institute_dise_code = $n]

-- count:
SELECT COUNT(*) <base+where>
-- IF totalCount === 0: return {rows:[], totalCount:0} WITHOUT running the data query at all (short-circuit).

-- data (only reached if totalCount > 0):
SELECT a.*, i.institute_name,
  js.juris_name AS state_name, jd.juris_name AS district_name, jb.juris_name AS block_name
<base+where>
ORDER BY a.${sortBy} ${sortOrder}
LIMIT $n OFFSET $n
```
**`sortBy`/`sortOrder` are string-interpolated into `ORDER BY`, not parameterized.** Verified from live `searchController.js:29-50`: `sortBy` is checked against `sortableFields = [applicant_id, student_name, nmms_year, nmms_reg_number, medium, district, nmms_block, app_state, current_institute_dise_code, spl_health_cond, spl_family_cond]`, defaulting to `applicant_id`; `sortOrder` is coerced to exactly `"ASC"` or `"DESC"` (`sort_order?.toUpperCase() === "DESC" ? "DESC" : "ASC"`). **Firm decision: port this whitelist as a closed Java `enum` (`ApplicantSortField`) at the query-builder boundary — never string-concatenate the raw request value.**
**BUG PRESERVED VERBATIM:** `spl_health_cond`/`spl_family_cond` are in the sort whitelist but those columns live on `pp.applicant_secondary_info`, not `pp.applicant_primary_info` (aliased `a` — the only table this query orders by). `ORDER BY a.spl_health_cond` throws `column a.spl_health_cond does not exist` at the database — an uncaught PG error, caught by the endpoint's generic `catch` → `500 {error:"Internal Server Error", details:"..."}`. **Not** reachable when `totalCount === 0` (data query is skipped entirely in that case) — needs seeded matching rows to pin.
Pagination math (verified from live `searchController.js`): `pageLimit = parseInt(limit,10) || 10` — **note `|| 10`, not `Math.max`: an explicit `limit=0` also falls back to `10`** (JS falsy-zero, same class of quirk as Quirk B above); `pageOffset = parseInt(offset,10) || 0`; **no clamping/max bound at all** (unlike #11's `Math.min(...,100)`). `totalPages = Math.ceil(totalCount/pageLimit)`; `currentPage = Math.floor(pageOffset/pageLimit)+1`; `nextOffset = pageOffset+pageLimit<totalCount ? pageOffset+pageLimit : null`; `prevOffset = pageOffset-pageLimit>=0 ? pageOffset-pageLimit : null`. 404 only when `!rows.length && pageOffset===0`.

```sql
-- getAllCohorts() -- #14
SELECT * FROM pp.cohort ORDER BY cohort_number ASC
```
```sql
-- getBatchesByCohort(cohortNumber) -- #15
SELECT * FROM pp.batch WHERE cohort_number = $1 ORDER BY batch_id ASC
```
`cohortNumber` = `req.params.cohortNumber`, passed as a **raw string, no `Number()` cast** — relies on implicit PG coercion. Non-numeric path segment → uncaught PG error → `500 {error:"Internal Server Error", details:...}`. Java casts `:cohortNumber::integer` explicitly (house convention) — same failure mode preserved.

## File-generating endpoints

**None.** No `multer`/csv/xlsx/pdf/`res.attachment`/`res.download` anywhere in these three route/controller/model files — confirmed by direct read of all three live Node sources. All 15 endpoints return JSON except #1 (plain text).

## Transactions

**None.** Every query across all three model files is a single `pool.query(...)` — plain autocommit reads. `getStudentSummaryModel` runs two queries but with **no transaction** wrapping them (confirmed — sequential `await pool.query` calls, no `pool.connect()`/`BEGIN`).

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/student/
├── web/StudentPortalController.java         (Tasks 1+2: isAuthenticated, #1-#10; #1 method-level permitAll())
├── web/StudentSearchController.java         (Task 3: ADMIN, #11-#12)
├── web/ApplicantSearchController.java       (Task 4: ADMIN, #13-#15)
├── persistence/StudentPortalReadRepository.java
├── persistence/StudentSearchReadRepository.java
├── persistence/ApplicantSearchReadRepository.java
└── persistence/ApplicantSortField.java      (Task 4: closed sort-column whitelist enum)

imas-backend/src/test/java/com/rcf/imas/modules/student/
├── StudentPortalCoreIT.java                 (Task 1: health/profile/timetable/inactive-history)
├── StudentAttendanceAnalyticsIT.java        (Task 2: performance/subjects/summary/monthly/weekly/custom)
├── StudentAdminSearchIT.java                (Task 3: search-students + student-by-id password redaction)
└── ApplicantSearchIT.java                   (Task 4: /search sort-whitelist + cohorts + batches)

Modify: imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java
        (add GET /api/student, /api/student/ to the existing permitAll matcher list)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks (no parallel implementers — git index races).
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN"|"STUDENT")`.
- `@AuthenticationPrincipal JwtService.FinalToken principal` → `principal.userId()` (String) is this module's `req.user.user_id` equivalent everywhere in `StudentPortalController`.

---

## Task 1: module skeleton + `StudentPortalController` core reads (health, profile, timetable, inactive-history)

Endpoints #1, #2, #3, #10. Establishes the module's `genericRow` mapper (with the `toPlainString()` deviation documented in convention #3) and the `isAuthenticated()`/`permitAll()` auth split.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/persistence/StudentPortalReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/web/StudentPortalController.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/student/StudentPortalCoreIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/student/StudentPortalCoreIT.java`:
```java
package com.rcf.imas.modules.student;

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
class StudentPortalCoreIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String studentToken;      // user_id 900001, has batch + timetable + inactive-history row
    String noBatchStudentToken; // user_id 900002, student_master row exists but batch_id IS NULL

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (900001,'sp1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (900002,'sp2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (900001,'Cohort SP1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (900001,'Batch SP1',900001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('SP100000000001','SP Institute')").update();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (900001, 24090000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (900002, 24090000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, user_id,
                current_institute_dise_code, active_yn)
            VALUES (900001, 900001, 24000001, 'Portal Student', 'F', 900001, 900001, 'SP100000000001', 'ACTIVE')
            """).update();
        // 900002: no batch_id -- pins the "No batch assigned." 400 for /timetable.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, user_id, active_yn)
            VALUES (900002, 900002, 24000002, 'No Batch Student', 'M', 900002, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (900001,'SP1','SP Subject')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (900001,'SP Teacher')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (900001,'SP Platform 900001')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id, platform_id)
            VALUES (900001,'SP Classroom',900001,900001,900001)
            """).update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (900001,900001)").update();
        jdbc.sql("""
            INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
            VALUES (900001,900001,'MONDAY','09:00:00','10:00:00')
            """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (900001,'Relocated','2025-01-10')").update();

        studentToken = jwt.issueFinalToken("900001", "sp1", "STUDENT");
        noBatchStudentToken = jwt.issueFinalToken("900002", "sp2", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 900001").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 900001 AND batch_id = 900001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 900001").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 900001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 900001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 900001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (900001,900002)").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'SP100000000001'").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 900001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 900001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (900001,900002)").update();
    }

    @Test
    void healthCheckIsPublicPlainText() throws Exception {
        mvc.perform(get("/api/student/"))
           .andExpect(status().isOk())
           .andExpect(content().string("Student API Working"));
    }

    @Test
    void profileNoTokenIs401() throws Exception {
        mvc.perform(get("/api/student/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void profileReturnsOwnRecordWhenAuthenticated() throws Exception {
        mvc.perform(get("/api/student/profile").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.student_name").value("Portal Student"))
           .andExpect(jsonPath("$.batch_name").value("Batch SP1"))
           .andExpect(jsonPath("$.cohort_name").value("Cohort SP1"))
           .andExpect(jsonPath("$.student_id").value("900001"));
    }

    @Test
    void profileNotFoundIs404() throws Exception {
        String unknownToken = jwt.issueFinalToken("999999", "nobody", "STUDENT");
        mvc.perform(get("/api/student/profile").header("Authorization", "Bearer " + unknownToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student profile not found"));
    }

    @Test
    void timetableReturnsOrderedRows() throws Exception {
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"))
           .andExpect(jsonPath("$[0].subject_name").value("SP Subject"));
    }

    @Test
    void timetableNoBatchAssignedIs400() throws Exception {
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + noBatchStudentToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("No batch assigned."));
    }

    @Test
    void timetableProfileNotFoundHasTrailingPeriod() throws Exception {
        String unknownToken = jwt.issueFinalToken("999999", "nobody", "STUDENT");
        mvc.perform(get("/api/student/timetable").header("Authorization", "Bearer " + unknownToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student profile not found."));
    }

    @Test
    void inactiveHistoryReturnsRows() throws Exception {
        mvc.perform(get("/api/student/900001/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].inactive_reason").value("Relocated"));
    }

    @Test
    void inactiveHistoryEmptyIsEmptyArrayNot404() throws Exception {
        mvc.perform(get("/api/student/999999/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    @Test
    void inactiveHistoryNonNumericIdIs500() throws Exception {
        mvc.perform(get("/api/student/abc/inactive-history").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch inactive history"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentPortalCoreIT` — Expected: FAIL (no module yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/student/persistence/StudentPortalReadRepository.java`:
```java
package com.rcf.imas.modules.student.persistence;

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
import java.util.Optional;

@Repository
public class StudentPortalReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public StudentPortalReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * node-pg parity, WITH ONE MODULE-SPECIFIC DEVIATION: NUMERIC/DECIMAL -> bd.toPlainString() (NOT
     * toBigInteger()). This module has genuinely fractional numeric output (attendance_percent, percent --
     * both ROUND(x::numeric,2)) that must not be truncated to an integer string. toPlainString() is a safe
     * superset for scale-0 numerics too (ids, nmms_year, etc. -- identical output to toBigInteger()).
     * BIGINT -> String (COUNT() results). DATE -> "yyyy-MM-dd". TIME -> "HH:mm:ss". TIMESTAMP -> ISO-Z.
     * Else passthrough via rs.getObject(i) (native JSON number for integer/int columns). Map keys are the
     * column label verbatim (snake_case).
     */
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
                    val = bd == null ? null : bd.toPlainString();
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

    private static final String PROFILE_SELECT = """
            SELECT
              sm.student_id, sm.applicant_id, sm.enr_id, sm.student_name, sm.gender,
              sm.father_name, sm.father_occupation, sm.mother_name, sm.mother_occupation,
              sm.student_email, sm.student_email_password, sm.parent_email,
              sm.contact_no1, sm.contact_no2, sm.home_address,
              sm.current_institute_dise_code, sm.previous_institute_dise_code,
              ci.institute_name AS current_institute, pi.institute_name AS previous_institute,
              sm.sim_name, sm.teacher_name, sm.teacher_mobile_number,
              sm.active_yn, sm.recharge_status, sm.sponsor, sm.photo_link,
              sm.batch_id, b.batch_name, c.cohort_number, c.cohort_name,
              ins.inactive_reason, sm.created_at, sm.updated_at
            FROM pp.student_master sm
            JOIN pp.batch b ON sm.batch_id = b.batch_id
            JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
            LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
            LEFT JOIN pp.inactive_students ins
              ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
            WHERE sm.user_id = :userId::numeric
            LIMIT 1
            """;

    /** Own-profile lookup, reused internally by /timetable (batch_id null -> "No batch assigned."). */
    public Optional<Map<String, Object>> profileByUserId(String userId) {
        return jdbc.sql(PROFILE_SELECT).param("userId", userId).query((rs, i) -> genericRow(rs)).optional();
    }

    public List<Map<String, Object>> timetableByBatchId(Object batchId) {
        return jdbc.sql("""
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
                WHERE cb.batch_id = :batchId
                ORDER BY
                    CASE tt.day_of_week
                        WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END,
                    tt.start_time ASC
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> inactiveHistory(String studentId) {
        return jdbc.sql("""
                SELECT inactive_reason, inactive_date, created_by, updated_by
                FROM pp.inactive_students
                WHERE student_id = :studentId::numeric
                ORDER BY inactive_date DESC
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/student/web/StudentPortalController.java` (4 handlers this task; Task 2 adds 5 more):
```java
package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.StudentPortalReadRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/student")
@PreAuthorize("isAuthenticated()")   // studentRoutes.js: every route except "/" is `authenticate`-gated (student mobile app token)
class StudentPortalController {

    private final StudentPortalReadRepository reads;

    StudentPortalController(StudentPortalReadRepository reads) { this.reads = reads; }

    /** studentRoutes.js:25-27 -- the ONLY route in this file with no `authenticate` middleware. */
    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    public String health() { return "Student API Working"; }

    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.profileByUserId(principal.userId())
                    .orElseThrow(() -> ApiException.message(404, "Student profile not found"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Server error");
        }
    }

    @GetMapping("/timetable")
    public List<Map<String, Object>> timetable(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            Map<String, Object> profile = reads.profileByUserId(principal.userId())
                    .orElseThrow(() -> ApiException.message(404, "Student profile not found."));
            Object batchId = profile.get("batch_id");
            if (batchId == null) {
                throw ApiException.message(400, "No batch assigned.");
            }
            return reads.timetableByBatchId(batchId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Internal Server Error");
        }
    }

    @GetMapping("/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history");
        }
    }
}
```

Add to `SecurityConfig.filterChain(...)` (in `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java`), right after the existing hall-ticket `permitAll` matcher:
```java
                // Public hall-ticket PDF download -- ExamsController.hallTicket() (GET /api/exams/hallticket/{no}),
                // method-level @PreAuthorize("permitAll()") override, per Plan 3a Firm Decision 8.
                .requestMatchers(HttpMethod.GET, "/api/exams/hallticket/**").permitAll()
                // Public student-portal health check -- StudentPortalController.health(), method-level
                // @PreAuthorize("permitAll()") override, per Plan 4a (studentRoutes.js's ONLY non-authenticate route).
                .requestMatchers(HttpMethod.GET, "/api/student", "/api/student/").permitAll()
                .requestMatchers("/actuator/health").permitAll()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentPortalCoreIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/student imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java imas-backend/src/test/java/com/rcf/imas/modules/student
git commit -m "feat(student): module skeleton + portal core reads (health/profile/timetable/inactive-history)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: attendance analytics (`/performance`+`/subjects` alias, `/summary`, `/monthly`, `/weekly`, `/custom`)

Endpoints #4, #5, #6, #7, #8, #9. All `isAuthenticated()`, same controller class. Pins Quirk A (`{exam_score:"-"}`-only degenerate summary) and Quirk B (JS falsy-zero `pp_exam_score`).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/student/persistence/StudentPortalReadRepository.java` (add 5 query methods)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/student/web/StudentPortalController.java` (add 5 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/student/StudentAttendanceAnalyticsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/student/StudentAttendanceAnalyticsIT.java`:
```java
package com.rcf.imas.modules.student;

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
class StudentAttendanceAnalyticsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    // 910001: has attendance data (1 present, 1 absent) across 2 sessions in different weeks, same month;
    //         exam_results.pp_exam_score = 0 -- pins Quirk B (falsy-zero exam_score despite real attendance data).
    // 910002: student_master row exists but batch_id IS NULL -- attendance queries INNER-JOIN through batch_id,
    //         so this yields ZERO rows from the aggregate query -- pins Quirk A ({exam_score:"-"} ONLY).
    String withAttendanceToken;
    String noBatchToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910001,'aa1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910002,'aa2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (910001,'Cohort AA1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910001,'Batch AA1',910001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (910001, 24091000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (910002, 24091000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, user_id, active_yn)
            VALUES (910001, 910001, 24010001, 'Attendance Student', 'F', 910001, 910001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, user_id, active_yn)
            VALUES (910002, 910002, 24010002, 'No Batch Student2', 'M', 910002, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (910001,'AA1','AA Subject')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id) VALUES (910001,'AA Classroom',910001)
            """).update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (910001,910001)").update();

        // Session 1: 2025-06-02 (week starting Monday 2025-06-02), status PRESENT.
        jdbc.sql("""
            INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
            VALUES (910001, 910001, '2025-06-02', '09:00:00', '10:00:00')
            """).update();
        // Session 2: 2025-06-16 (different week, same month), status ABSENT (no attendance row -- but we insert
        // an explicit ABSENT row to distinguish "attended but marked absent" from "no attendance row at all").
        jdbc.sql("""
            INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
            VALUES (910002, 910001, '2025-06-16', '09:00:00', '10:00:00')
            """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (910001, 910001, 'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (910002, 910001, 'ABSENT')").update();

        // pp_exam_score = 0 for student 910001 -- pins Quirk B (JS `0 || "-"` => "-").
        jdbc.sql("INSERT INTO pp.exam_results(applicant_id, pp_exam_score) VALUES (910001, 0)").update();

        withAttendanceToken = jwt.issueFinalToken("910001", "aa1", "STUDENT");
        noBatchToken = jwt.issueFinalToken("910002", "aa2", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.exam_results WHERE applicant_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 910001 AND batch_id = 910001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 910001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 910001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 910001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 910001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (910001,910002)").update();
    }

    @Test
    void subjectPerformanceAggregatesBothSessions() throws Exception {
        mvc.perform(get("/api/student/subjects").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].subject_name").value("AA Subject"))
           .andExpect(jsonPath("$[0].total_classes").value("2"))
           .andExpect(jsonPath("$[0].attended_classes").value("1"))
           .andExpect(jsonPath("$[0].attendance_percent").value("50.00"));
    }

    @Test
    void performanceAliasReturnsIdenticalShapeToSubjects() throws Exception {
        mvc.perform(get("/api/student/performance").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].subject_name").value("AA Subject"))
           .andExpect(jsonPath("$[0].attendance_percent").value("50.00"));
    }

    @Test
    void summaryIncludesAttendanceAndFalsyZeroExamScoreBecomesDash() throws Exception {
        mvc.perform(get("/api/student/summary").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total_classes").value("2"))
           .andExpect(jsonPath("$.attended_classes").value("1"))
           .andExpect(jsonPath("$.attendance_percent").value("50.00"))
           .andExpect(jsonPath("$.exam_score").value("-"));   // Quirk B: pp_exam_score=0 is JS-falsy
    }

    @Test
    void summaryDegradesToExamScoreOnlyWhenNoBatchAssigned() throws Exception {
        mvc.perform(get("/api/student/summary").header("Authorization", "Bearer " + noBatchToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.exam_score").value("-"))
           .andExpect(jsonPath("$.total_classes").doesNotExist())   // Quirk A: keys absent, not null
           .andExpect(jsonPath("$.attended_classes").doesNotExist())
           .andExpect(jsonPath("$.attendance_percent").doesNotExist());
    }

    @Test
    void monthlyGroupsBothSessionsIntoOneMonth() throws Exception {
        mvc.perform(get("/api/student/monthly").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].month").value("2025-06"))
           .andExpect(jsonPath("$[0].percent").value("50.00"));
    }

    @Test
    void weeklySplitsSessionsIntoTwoWeeks() throws Exception {
        mvc.perform(get("/api/student/weekly").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].week_start").value("2025-06-02"))
           .andExpect(jsonPath("$[0].percent").value("100.00"))
           .andExpect(jsonPath("$[1].week_start").value("2025-06-16"))
           .andExpect(jsonPath("$[1].percent").value("0.00"));
    }

    @Test
    void customRangeFiltersToOneSession() throws Exception {
        mvc.perform(get("/api/student/custom?fromDate=2025-06-01&toDate=2025-06-10")
                .header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].total_classes").value("1"))
           .andExpect(jsonPath("$[0].attended_classes").value("1"))
           .andExpect(jsonPath("$[0].attendance_percent").value("100.00"));
    }

    @Test
    void customRangeMissingDatesIs400() throws Exception {
        mvc.perform(get("/api/student/custom").header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Date range required"));
    }

    @Test
    void customRangeMalformedDateIs500WithCustomMessage() throws Exception {
        mvc.perform(get("/api/student/custom?fromDate=not-a-date&toDate=2025-06-10")
                .header("Authorization", "Bearer " + withAttendanceToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Failed to fetch custom data"));
    }

    @Test
    void analyticsEndpointsRequireAuth() throws Exception {
        mvc.perform(get("/api/student/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/monthly")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/weekly")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentAttendanceAnalyticsIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `StudentPortalReadRepository` (add `import java.math.BigDecimal;` and `import java.util.LinkedHashMap;` if not already present):
```java
    private static final String ATTENDANCE_FILTER =
            "sa.status IN ('PRESENT','LATE JOINED','LEAVE')";

    private static final String ATTENDANCE_AGG_SQL = """
            SELECT
              COUNT(cs.session_id) AS total_classes,
              COUNT(sa.session_id) FILTER (WHERE %s) AS attended_classes,
              ROUND(
                COUNT(sa.session_id) FILTER (WHERE %s)::numeric
                / NULLIF(COUNT(cs.session_id),0) * 100
              ,2) AS attendance_percent
            FROM pp.student_master sm
            JOIN pp.classroom_batch cb ON cb.batch_id = sm.batch_id
            JOIN pp.classroom c ON c.classroom_id = cb.classroom_id
            JOIN pp.class_session cs ON cs.classroom_id = c.classroom_id
            LEFT JOIN pp.student_attendance sa
              ON sa.session_id = cs.session_id AND sa.student_id = sm.student_id
            WHERE sm.user_id = :userId::numeric
            """.formatted(ATTENDANCE_FILTER, ATTENDANCE_FILTER);

    private static final String EXAM_SCORE_SQL = """
            SELECT er.pp_exam_score
            FROM pp.exam_results er
            JOIN pp.student_master sm ON sm.applicant_id = er.applicant_id
            WHERE sm.user_id = :userId::numeric
            """;

    /**
     * Two round-trips, no transaction (Node parity). Quirk A: if the attendance aggregate returns zero rows
     * (no matching student_master.user_id), result degrades to exactly {exam_score:"-"} -- no other keys, not
     * even null. Quirk B: JS `pp_exam_score || "-"` treats a genuine 0 as falsy -- a real zero score still
     * shows "-", even when attendance keys ARE present (distinguishing it from Quirk A).
     */
    public Map<String, Object> summary(String userId) {
        Map<String, Object> agg = jdbc.sql(ATTENDANCE_AGG_SQL).param("userId", userId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        BigDecimal examScoreBd = jdbc.sql(EXAM_SCORE_SQL).param("userId", userId)
                .query((rs, i) -> rs.getBigDecimal(1)).optional().orElse(null);
        String examScore = (examScoreBd == null || examScoreBd.signum() == 0) ? "-" : examScoreBd.toPlainString();

        Map<String, Object> result = new LinkedHashMap<>();
        if (agg != null) result.putAll(agg);
        result.put("exam_score", examScore);
        return result;
    }

    public List<Map<String, Object>> subjectPerformance(String userId) {
        return jdbc.sql("""
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
                WHERE sm.user_id = :userId::numeric
                GROUP BY subj.subject_name
                ORDER BY subj.subject_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** No NULLIF guard, unlike summary/subjects/custom -- preserved (see plan's SQL section for why it's safe). */
    public List<Map<String, Object>> monthlyAttendance(String userId) {
        return jdbc.sql("""
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
                WHERE sm.user_id = :userId::numeric
                GROUP BY month
                ORDER BY month
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> weeklyAttendance(String userId) {
        return jdbc.sql("""
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
                WHERE sm.user_id = :userId::numeric
                GROUP BY week_start
                ORDER BY week_start
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> customAttendance(String userId, String fromDate, String toDate) {
        return jdbc.sql("""
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
                WHERE sm.user_id = :userId::numeric
                  AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                GROUP BY subj.subject_name
                ORDER BY subj.subject_name
                """).param("userId", userId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
```

Add to `StudentPortalController`:
```java
    /** #4 (/performance) and #6 (/subjects) are the SAME Node handler function -- one Java method, two paths. */
    @GetMapping({"/performance", "/subjects"})
    public List<Map<String, Object>> subjectPerformance(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.subjectPerformance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch subject performance");
        }
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.summary(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch summary");
        }
    }

    @GetMapping("/monthly")
    public List<Map<String, Object>> monthly(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.monthlyAttendance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch monthly data");
        }
    }

    @GetMapping("/weekly")
    public List<Map<String, Object>> weekly(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.weeklyAttendance(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch weekly data");
        }
    }

    @GetMapping("/custom")
    public List<Map<String, Object>> custom(@AuthenticationPrincipal JwtService.FinalToken principal,
                                             @RequestParam(required = false) String fromDate,
                                             @RequestParam(required = false) String toDate) {
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw ApiException.error(400, "Date range required");
        }
        try {
            return reads.customAttendance(principal.userId(), fromDate, toDate);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch custom data");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentAttendanceAnalyticsIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/student imas-backend/src/test/java/com/rcf/imas/modules/student/StudentAttendanceAnalyticsIT.java
git commit -m "feat(student): attendance analytics (performance/subjects alias, summary, monthly, weekly, custom)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: admin student search (`/api/search-students`) + student-by-id (`/api/student/{student_id}`, password redacted)

Endpoints #11, #12. New `StudentSearchController`, class-level `hasRole('ADMIN')` — NEW hardening (Node left these fully open).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/persistence/StudentSearchReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/web/StudentSearchController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/student/StudentAdminSearchIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/student/StudentAdminSearchIT.java`:
```java
package com.rcf.imas.modules.student;

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
class StudentAdminSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String studentTok;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920001,'as1seed','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920002,'as2seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("920001", "as1", "ADMIN");
        studentTok = jwt.issueFinalToken("920002", "as2", "STUDENT");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (920001,'KARNATAKA','STATE')").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (920002,'BELAGAVI','EDUCATION DISTRICT',920001)").update();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (920001,'Cohort AS1')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920001,'Batch AS1',920001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, app_state, district)
            VALUES (920001, 24092000001, 920001, 920002)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, app_state, district)
            VALUES (920002, 24092000002, 920001, 920002)
            """).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, spl_health_cond) VALUES (920001, 'Y')").update();
        // 920002 has NO applicant_secondary_info row -- pins the COALESCE(...,'N') default.

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, student_email_password)
            VALUES (920001, 920001, 24030001, 'Asha Search', 'F', 920001, 'SECRET-PW-1')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id)
            VALUES (920002, 920002, 24030002, 'Basha Search', 'M', 920001)
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 920001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 920001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (920001,920002)").update();
    }

    @Test
    void searchNoFiltersDefaultsToLimit50() throws Exception {
        mvc.perform(get("/api/search-students").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.pagination.limit").value(50))
           .andExpect(jsonPath("$.pagination.page").value(1))
           .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    void searchByNameIlike() throws Exception {
        mvc.perform(get("/api/search-students?name=Asha").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].student_name").value("Asha Search"));
    }

    @Test
    void searchByGenderUppercasesInput() throws Exception {
        mvc.perform(get("/api/search-students?gender=f").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].gender").value("F"));
    }

    @Test
    void searchSplHealthCondDefaultsToNViaCoalesce() throws Exception {
        mvc.perform(get("/api/search-students?spl_health_cond=N").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].student_name").value("Basha Search"))
           .andExpect(jsonPath("$.data[0].spl_health_cond").value("N"));
    }

    @Test
    void searchLimitClampedToMax100OffsetClampedToZero() throws Exception {
        mvc.perform(get("/api/search-students?limit=500&offset=-5").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.pagination.limit").value(100))
           .andExpect(jsonPath("$.pagination.offset").value(0));
    }

    @Test
    void searchStudentsIsAdminOnly() throws Exception {
        mvc.perform(get("/api/search-students").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/search-students")).andExpect(status().isUnauthorized());
    }

    @Test
    void studentByIdRedactsPasswordColumn() throws Exception {
        mvc.perform(get("/api/student/920001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.student_name").value("Asha Search"))
           .andExpect(jsonPath("$.data.student_email_password").doesNotExist());
    }

    @Test
    void studentByIdNotFoundIs404() throws Exception {
        mvc.perform(get("/api/student/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Student not found"));
    }

    @Test
    void studentByIdNonNumericIsBareSuccessFalse500() throws Exception {
        mvc.perform(get("/api/student/abc").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.error").doesNotExist())
           .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void studentByIdIsAdminOnly() throws Exception {
        mvc.perform(get("/api/student/920001").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentAdminSearchIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/student/persistence/StudentSearchReadRepository.java`:
```java
package com.rcf.imas.modules.student.persistence;

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
import java.util.Optional;

@Repository
public class StudentSearchReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public StudentSearchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as StudentPortalReadRepository (NUMERIC/DECIMAL -> toPlainString(), see Plan 4a
     *  convention #3) -- duplicated per this module's established per-repository house style. */
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
                    val = bd == null ? null : bd.toPlainString();
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

    private static final String DATA_SELECT = """
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
            """;

    private static final String COUNT_SELECT = """
            SELECT COUNT(*) AS total
            FROM pp.student_master sm
            JOIN pp.batch b ON sm.batch_id = b.batch_id
            JOIN pp.cohort c ON b.cohort_number = c.cohort_number
            JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
            LEFT JOIN pp.applicant_secondary_info asi ON sm.applicant_id = asi.applicant_id
            WHERE 1=1
            """;

    public record SearchResult(List<Map<String, Object>> rows, long total, int limit, int offset) {}

    /**
     * studentSearchModel.js:searchStudents parity. Dynamic WHERE via StringBuilder + named params (never
     * positional ?, matching the Results module's precedent). Every filter param is JS-truthy-checked in Node
     * (present() below mirrors `if (x)` / `if (x?.trim())`); limit clamped [1,100] default 50, offset clamped
     * >=0 default 0.
     */
    public SearchResult search(String batchId, String cohortNumber, String name, String enrId, String gender,
                                String stateId, String districtId, String blockId,
                                String splHealthCond, String splFamilyCond, Integer limitReq, Integer offsetReq) {
        int limit = Math.min(Math.max(limitReq == null ? 50 : limitReq, 1), 100);
        int offset = Math.max(offsetReq == null ? 0 : offsetReq, 0);

        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (present(batchId))       { where.append(" AND sm.batch_id = :batchId::numeric"); params.put("batchId", batchId); }
        if (present(cohortNumber))  { where.append(" AND c.cohort_number = :cohortNumber::numeric"); params.put("cohortNumber", cohortNumber); }
        if (present(name))          { where.append(" AND sm.student_name ILIKE :name"); params.put("name", "%" + name.trim() + "%"); }
        if (present(enrId))         { where.append(" AND CAST(sm.enr_id AS TEXT) ILIKE :enrId"); params.put("enrId", "%" + enrId.trim() + "%"); }
        if (present(gender))        { where.append(" AND UPPER(sm.gender) = :gender"); params.put("gender", gender.trim().toUpperCase()); }
        if (present(stateId))       { where.append(" AND api.app_state = :stateId::numeric"); params.put("stateId", stateId); }
        if (present(districtId))    { where.append(" AND api.district = :districtId::numeric"); params.put("districtId", districtId); }
        if (present(blockId))       { where.append(" AND api.nmms_block = :blockId::numeric"); params.put("blockId", blockId); }
        if (present(splHealthCond)) { where.append(" AND COALESCE(asi.spl_health_cond, 'N') = :splHealthCond"); params.put("splHealthCond", splHealthCond); }
        if (present(splFamilyCond)) { where.append(" AND COALESCE(asi.spl_family_cond, 'N') = :splFamilyCond"); params.put("splFamilyCond", splFamilyCond); }

        var dataQuery = jdbc.sql(DATA_SELECT + where + " ORDER BY sm.student_name ASC LIMIT :limit OFFSET :offset");
        var countQuery = jdbc.sql(COUNT_SELECT + where);
        for (var e : params.entrySet()) {
            dataQuery = dataQuery.param(e.getKey(), e.getValue());
            countQuery = countQuery.param(e.getKey(), e.getValue());
        }
        dataQuery = dataQuery.param("limit", limit).param("offset", offset);

        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        long total = countQuery.query(Long.class).single();
        return new SearchResult(rows, total, limit, offset);
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    /**
     * Explicit column list -- EXCLUDES student_email_password (firm decision: never return credentials, even
     * to ADMIN). Node's raw `SELECT *` is intentionally NOT reproduced verbatim here.
     */
    public Optional<Map<String, Object>> byId(String studentId) {
        return jdbc.sql("""
                SELECT
                  student_id, applicant_id, enr_id, student_name, father_name, father_occupation,
                  mother_name, mother_occupation, gender, batch_id, sim_name, student_email, parent_email,
                  photo_link, home_address, contact_no1, contact_no2,
                  current_institute_dise_code, previous_institute_dise_code,
                  active_yn, recharge_status, sponsor, teacher_name, teacher_mobile_number,
                  created_at, updated_at, created_by, updated_by, user_id
                FROM pp.student_master
                WHERE student_id = :studentId::numeric
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).optional();
    }
}
```

`src/main/java/com/rcf/imas/modules/student/web/StudentSearchController.java`:
```java
package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.StudentSearchReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")   // studentSearchRoutes.js: zero `authenticate` middleware in Node -- NEW hardening
class StudentSearchController {

    private final StudentSearchReadRepository reads;

    StudentSearchController(StudentSearchReadRepository reads) { this.reads = reads; }

    @GetMapping("/search-students")
    public Map<String, Object> searchStudents(
            @RequestParam(required = false) String batch_id,
            @RequestParam(required = false) String cohort_number,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String enr_id,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String state_id,
            @RequestParam(required = false) String district_id,
            @RequestParam(required = false) String block_id,
            @RequestParam(required = false) String spl_health_cond,
            @RequestParam(required = false) String spl_family_cond,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        try {
            StudentSearchReadRepository.SearchResult result = reads.search(batch_id, cohort_number, name, enr_id,
                    gender, state_id, district_id, block_id, spl_health_cond, spl_family_cond, limit, offset);

            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("total", result.total());
            pagination.put("limit", result.limit());
            pagination.put("offset", result.offset());
            pagination.put("page", result.offset() / result.limit() + 1);
            pagination.put("totalPages", (long) Math.ceil((double) result.total() / result.limit()));
            pagination.put("hasMore", result.offset() + result.limit() < result.total());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", result.rows());
            body.put("pagination", pagination);
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("success", false);
        }
    }

    /**
     * 500 body is LITERALLY {success:false} with NEITHER "error" NOR "message" key (studentSearchController.js:42
     * `res.status(500).json({ success: false })`). ApiException always carries exactly one of those two keys, so
     * this one exceptional case bypasses it and returns a ResponseEntity directly.
     */
    @GetMapping("/student/{studentId}")
    public Object byId(@PathVariable String studentId) {
        Map<String, Object> row;
        try {
            row = reads.byId(studentId).orElse(null);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false));
        }
        if (row == null) {
            throw ApiException.message(404, "Student not found").with("success", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", row);
        return body;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=StudentAdminSearchIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/student imas-backend/src/test/java/com/rcf/imas/modules/student/StudentAdminSearchIT.java
git commit -m "feat(student): admin search-students + student-by-id (password column redacted), ADMIN-only

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: applicant/NMMS search (`/api/search`, sort whitelist) + cohorts + batches

Endpoints #13, #14, #15. New `ApplicantSearchController`, `hasRole('ADMIN')`. Introduces the closed `ApplicantSortField` enum and pins the `spl_health_cond`/`spl_family_cond` sort-crash bug.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/persistence/ApplicantSortField.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/persistence/ApplicantSearchReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/student/web/ApplicantSearchController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/student/ApplicantSearchIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/student/ApplicantSearchIT.java`:
```java
package com.rcf.imas.modules.student;

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
class ApplicantSearchIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String studentTok;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (930001,'as1seed','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        admin = jwt.issueFinalToken("930001", "as1", "ADMIN");
        studentTok = jwt.issueFinalToken("930002", "as2", "STUDENT");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (930001,'KARNATAKA','STATE')").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, nmms_year, student_name, medium, app_state)
            VALUES (930001, 24093000001, 2025, 'Applicant Alpha', 'KANNADA', 930001)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number, nmms_year, student_name, medium, app_state)
            VALUES (930002, 24093000002, 2025, 'Applicant Beta', 'ENGLISH', 930001)
            """).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930001,'Cohort ASR1')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930002,'Cohort ASR2')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (930001,'Batch ASR1',930001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 930001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (930001,930002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (930001,930002)").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 930001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 930001").update();
    }

    @Test
    void searchByNmmsRegNumberIgnoresAllOtherFilters() throws Exception {
        // student_name filter deliberately mismatches applicant 930001's own name -- Node ignores it entirely
        // once nmms_reg_number is present.
        mvc.perform(get("/api/search?nmms_reg_number=24093000001&student_name=Applicant Beta")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].student_name").value("Applicant Alpha"));
    }

    @Test
    void searchDefaultLimitIsTenNotFifty() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.pagination.limit").value(10))
           .andExpect(jsonPath("$.sort.sortBy").value("applicant_id"))
           .andExpect(jsonPath("$.sort.sortOrder").value("ASC"));
    }

    @Test
    void searchSortByStudentNameDescending() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=student_name&sort_order=desc")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sort.sortBy").value("student_name"))
           .andExpect(jsonPath("$.sort.sortOrder").value("DESC"))
           .andExpect(jsonPath("$.data[0].student_name").value("Applicant Beta"))
           .andExpect(jsonPath("$.data[1].student_name").value("Applicant Alpha"));
    }

    @Test
    void searchInvalidSortByFallsBackToApplicantId() throws Exception {
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=not_a_real_column")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sort.sortBy").value("applicant_id"));
    }

    @Test
    void searchSortBySplHealthCondCrashesWithDetails500() throws Exception {
        // BUG PRESERVED: spl_health_cond is in Node's sort whitelist but the column lives on
        // applicant_secondary_info, not applicant_primary_info (aliased `a`) -- ORDER BY a.spl_health_cond
        // throws at the database. Only reachable when totalCount > 0 (data query is skipped when total=0).
        mvc.perform(get("/api/search?nmms_year=2025&sort_by=spl_health_cond")
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void searchNoMatchAtOffsetZeroIs404() throws Exception {
        mvc.perform(get("/api/search?nmms_year=1900").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No applications found matching the criteria."));
    }

    @Test
    void searchNoMatchAtNonzeroOffsetIs200EmptyData() throws Exception {
        mvc.perform(get("/api/search?nmms_year=1900&offset=50").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void cohortsReturnsAllOrderedByCohortNumberAscending() throws Exception {
        mvc.perform(get("/api/cohorts").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].cohort_number").value("930001"))
           .andExpect(jsonPath("$.data[1].cohort_number").value("930002"));
    }

    @Test
    void batchesByCohortReturnsOrderedByBatchId() throws Exception {
        mvc.perform(get("/api/batches/cohort/930001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].batch_name").value("Batch ASR1"));
    }

    @Test
    void batchesByCohortNonNumericIs500WithDetails() throws Exception {
        mvc.perform(get("/api/batches/cohort/abc").header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }

    @Test
    void applicantSearchEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/search").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cohorts").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/batches/cohort/930001").header("Authorization", "Bearer " + studentTok)).andExpect(status().isForbidden());
        mvc.perform(get("/api/search")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantSearchIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/student/persistence/ApplicantSortField.java`:
```java
package com.rcf.imas.modules.student.persistence;

/**
 * Closed whitelist mirroring searchController.js:29-41's `sortableFields` array. Node validates `sort_by`
 * against this list BEFORE the string reaches `ORDER BY a.${sortBy}` in searchModel.js -- the model itself has
 * no guard, only the controller does. This enum enforces the same whitelist at the query-builder boundary so a
 * future caller can never invoke ApplicantSearchReadRepository with an unvalidated column name.
 *
 * BUG PRESERVED VERBATIM: SPL_HEALTH_COND / SPL_FAMILY_COND are in Node's whitelist but map to columns that
 * live on pp.applicant_secondary_info, NOT pp.applicant_primary_info (aliased `a` in the query, the only table
 * this endpoint orders by). Sorting by either one throws "column a.spl_health_cond does not exist" at the
 * database -- an uncaught PG error, mapped by the endpoint's catch block to the SAME 500 envelope Node produces
 * (`{error:"Internal Server Error", details:"..."}`). Do NOT fix -- see ApplicantSearchIT's pinning test.
 */
public enum ApplicantSortField {
    APPLICANT_ID("applicant_id"),
    STUDENT_NAME("student_name"),
    NMMS_YEAR("nmms_year"),
    NMMS_REG_NUMBER("nmms_reg_number"),
    MEDIUM("medium"),
    DISTRICT("district"),
    NMMS_BLOCK("nmms_block"),
    APP_STATE("app_state"),
    CURRENT_INSTITUTE_DISE_CODE("current_institute_dise_code"),
    SPL_HEALTH_COND("spl_health_cond"),
    SPL_FAMILY_COND("spl_family_cond");

    public final String column;

    ApplicantSortField(String column) { this.column = column; }

    public static ApplicantSortField fromRequestOrDefault(String requested) {
        for (ApplicantSortField f : values()) {
            if (f.column.equals(requested)) return f;
        }
        return APPLICANT_ID;
    }
}
```

`src/main/java/com/rcf/imas/modules/student/persistence/ApplicantSearchReadRepository.java`:
```java
package com.rcf.imas.modules.student.persistence;

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
public class ApplicantSearchReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ApplicantSearchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same genericRow convention as the other two repositories in this module (see Plan 4a convention #3). */
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
                    val = bd == null ? null : bd.toPlainString();
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

    private static final String BASE_FROM = """
            FROM pp.applicant_primary_info a
            LEFT JOIN pp.institute i ON a.current_institute_dise_code = i.dise_code
            LEFT JOIN pp.jurisdiction js ON a.app_state = js.juris_code
            LEFT JOIN pp.jurisdiction jd ON a.district = jd.juris_code
            LEFT JOIN pp.jurisdiction jb ON a.nmms_block = jb.juris_code
            WHERE 1=1
            """;

    private static final String COUNT_SELECT = "SELECT COUNT(*) " + BASE_FROM;

    private static final String DATA_SELECT = """
            SELECT a.*, i.institute_name,
              js.juris_name AS state_name, jd.juris_name AS district_name, jb.juris_name AS block_name
            """ + BASE_FROM;

    public record SearchResult(List<Map<String, Object>> rows, long totalCount) {}

    /** nmms_reg_number branch: ignores every other filter, matching searchModel.js's if/else split verbatim. */
    public SearchResult searchByRegNumber(String regNumber, ApplicantSortField sortField, String sortOrder,
                                           int limit, int offset) {
        String where = " AND a.nmms_reg_number = :regNumber::numeric";
        long total = jdbc.sql(COUNT_SELECT + where).param("regNumber", regNumber).query(Long.class).single();
        if (total == 0) return new SearchResult(List.of(), 0);   // Node skips the data query entirely when total=0

        List<Map<String, Object>> rows = jdbc.sql(DATA_SELECT + where + orderByLimitOffset(sortField, sortOrder))
                .param("regNumber", regNumber).param("limit", limit).param("offset", offset)
                .query((rs, i) -> genericRow(rs)).list();
        return new SearchResult(rows, total);
    }

    public SearchResult search(String studentName, String nmmsYear, String medium, String appState, String district,
                                String nmmsBlock, String diseCode, ApplicantSortField sortField, String sortOrder,
                                int limit, int offset) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (present(studentName)) { where.append(" AND a.student_name ILIKE :studentName"); params.put("studentName", "%" + studentName + "%"); }
        if (present(nmmsYear))    { where.append(" AND a.nmms_year = :nmmsYear::numeric"); params.put("nmmsYear", nmmsYear); }
        if (present(medium))      { where.append(" AND UPPER(a.medium) = :medium"); params.put("medium", medium.trim().toUpperCase()); }
        if (present(appState))    { where.append(" AND a.app_state = :appState::numeric"); params.put("appState", appState.trim()); }
        if (present(district))    { where.append(" AND a.district = :district::numeric"); params.put("district", district.trim()); }
        if (present(nmmsBlock))   { where.append(" AND a.nmms_block = :nmmsBlock::numeric"); params.put("nmmsBlock", nmmsBlock.trim()); }
        if (present(diseCode))    { where.append(" AND a.current_institute_dise_code = :diseCode"); params.put("diseCode", diseCode.trim()); }

        var countQuery = jdbc.sql(COUNT_SELECT + where);
        for (var e : params.entrySet()) countQuery = countQuery.param(e.getKey(), e.getValue());
        long total = countQuery.query(Long.class).single();
        if (total == 0) return new SearchResult(List.of(), 0);   // Node skips the data query entirely when total=0

        var dataQuery = jdbc.sql(DATA_SELECT + where + orderByLimitOffset(sortField, sortOrder));
        for (var e : params.entrySet()) dataQuery = dataQuery.param(e.getKey(), e.getValue());
        dataQuery = dataQuery.param("limit", limit).param("offset", offset);
        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        return new SearchResult(rows, total);
    }

    /** sortField.column comes ONLY from the closed enum (never request-concatenated); sortOrder is
     *  pre-validated by the controller to exactly "ASC"/"DESC" -- safe to concatenate both. */
    private static String orderByLimitOffset(ApplicantSortField sortField, String sortOrder) {
        return " ORDER BY a." + sortField.column + " " + sortOrder + " LIMIT :limit OFFSET :offset";
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    public List<Map<String, Object>> allCohorts() {
        return jdbc.sql("SELECT * FROM pp.cohort ORDER BY cohort_number ASC").query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> batchesByCohort(String cohortNumber) {
        return jdbc.sql("SELECT * FROM pp.batch WHERE cohort_number = :cohortNumber::integer ORDER BY batch_id ASC")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/student/web/ApplicantSearchController.java`:
```java
package com.rcf.imas.modules.student.web;

import com.rcf.imas.modules.student.persistence.ApplicantSearchReadRepository;
import com.rcf.imas.modules.student.persistence.ApplicantSortField;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")   // searchRoutes.js: zero `authenticate` middleware in Node -- NEW hardening
class ApplicantSearchController {

    private final ApplicantSearchReadRepository reads;

    ApplicantSearchController(ApplicantSearchReadRepository reads) { this.reads = reads; }

    @GetMapping("/search")
    public Object search(
            @RequestParam(required = false) String nmms_year,
            @RequestParam(required = false) String nmms_reg_number,
            @RequestParam(required = false) String student_name,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String nmms_block,
            @RequestParam(required = false) String app_state,
            @RequestParam(required = false) String current_institute_dise_code,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String sort_by,
            @RequestParam(required = false) String sort_order) {

        // Node: `parseInt(limit,10) || 10` -- an explicit limit=0 ALSO falls back to 10 (JS falsy-zero).
        int pageLimit = (limit == null || limit == 0) ? 10 : limit;
        int pageOffset = (offset == null || offset == 0) ? 0 : offset;
        ApplicantSortField sortField = ApplicantSortField.fromRequestOrDefault(sort_by);
        String sortOrderSafe = (sort_order != null && sort_order.equalsIgnoreCase("DESC")) ? "DESC" : "ASC";

        String regTrim = nmms_reg_number == null ? null : nmms_reg_number.trim();
        String nameTrim = student_name == null ? null : student_name.trim();

        try {
            ApplicantSearchReadRepository.SearchResult result = (regTrim != null && !regTrim.isBlank())
                    ? reads.searchByRegNumber(regTrim, sortField, sortOrderSafe, pageLimit, pageOffset)
                    : reads.search(nameTrim, nmms_year, medium, app_state, district, nmms_block,
                            current_institute_dise_code, sortField, sortOrderSafe, pageLimit, pageOffset);

            if (result.rows().isEmpty() && pageOffset == 0) {
                throw ApiException.message(404, "No applications found matching the criteria.");
            }

            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("total", result.totalCount());
            pagination.put("limit", pageLimit);
            pagination.put("offset", pageOffset);
            pagination.put("totalPages", (long) Math.ceil((double) result.totalCount() / pageLimit));
            pagination.put("currentPage", pageOffset / pageLimit + 1);
            pagination.put("nextOffset", pageOffset + pageLimit < result.totalCount() ? pageOffset + pageLimit : null);
            pagination.put("prevOffset", pageOffset - pageLimit >= 0 ? pageOffset - pageLimit : null);

            Map<String, Object> sort = new LinkedHashMap<>();
            sort.put("sortBy", sortField.column);
            sort.put("sortOrder", sortOrderSafe);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", result.rows());
            body.put("pagination", pagination);
            body.put("sort", sort);
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        try {
            return Map.of("data", reads.allCohorts());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }

    @GetMapping("/batches/cohort/{cohortNumber}")
    public Map<String, Object> batchesByCohort(@PathVariable String cohortNumber) {
        try {
            return Map.of("data", reads.batchesByCohort(cohortNumber));
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantSearchIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + the 4 new student-module test classes green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/student imas-backend/src/test/java/com/rcf/imas/modules/student/ApplicantSearchIT.java
git commit -m "feat(student): applicant/NMMS search (sort-column enum whitelist) + cohorts + batches-by-cohort

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 4 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/student` package against this plan + the spec, checking:
- **Auth split correctness:** `StudentPortalController` is `isAuthenticated()` class-level with `health()` the ONLY `permitAll()` override (and the matching `SecurityConfig` matcher exists); `StudentSearchController` and `ApplicantSearchController` are `hasRole('ADMIN')` class-level. Cross-check each of the 15 endpoints against the auth-split table in this plan's convention #7 — no endpoint drifted to the wrong controller.
- **`genericRow`'s `toPlainString()` deviation:** confirm all three repositories use `toPlainString()` (not `toBigInteger().toString()`) for `NUMERIC`/`DECIMAL`, and that `attendance_percent`/`percent` round-trip with their full 2-decimal scale (e.g. `"50.00"`, not `"50"`).
- **Quirk A vs Quirk B in `/summary`:** confirm both are pinned by DISTINCT tests — Quirk A (`{exam_score:"-"}` only, no other keys, when the attendance aggregate returns 0 rows) vs Quirk B (`exam_score:"-"` despite attendance keys being present, because `pp_exam_score=0` is JS-falsy).
- **Password redaction:** `StudentSearchReadRepository.byId()` uses an explicit column list, confirm `student_email_password` is not in it and the pinning test (`studentByIdRedactsPasswordColumn`) asserts `doesNotExist()`, not just an empty/null value. Also confirm `StudentPortalReadRepository.PROFILE_SELECT` (the *own*-profile query) still legitimately includes `student_email_password` — that omission would itself be a parity bug (a student must still see their own credentials).
- **Sort-column whitelist:** `ApplicantSortField` is a closed enum; `orderByLimitOffset` never concatenates a raw request string; the `spl_health_cond`/`spl_family_cond` crash-bug is preserved (not "fixed") and pinned with a 500+details test that specifically requires `totalCount > 0` (the bug is unreachable when total=0, since Node skips the data query).
- **Two incompatible pagination DTOs:** endpoint #11 (`success/page/hasMore`) vs endpoint #13 (`currentPage/nextOffset/prevOffset`) remain two independently-built `Map`s, no shared `PageResponse<T>` introduced.
- **Per-endpoint error envelopes:** spot-check the full matrix in the endpoint contract table — especially `/timetable`'s 500 using `message` (not `error`, unlike every sibling), and `GET /api/student/{student_id}`'s 500 body being literally `{success:false}` via `ResponseEntity`, not `ApiException`.
- **No transactions/writes:** confirm zero `@Transactional`, zero write repository — this module is 100% reads.
- **FK seeding order:** spot-check each IT's `@BeforeEach`/`cleanup()` against this plan's convention #10 FK chain; confirm `inactive_students`/`exam_results` (no PK) are cleaned with unconditional `DELETE`, never `ON CONFLICT`.

Update `imas-migration-status` memory: Phase 4a complete, new test count, ready for Phase 4b (classrooms/batches).

## Deferred / parity decisions carried into this plan

- **`GET /api/student/:student_id` (endpoint #12) is dead in the current frontend** (no live `client/src` caller found per the ground truth) but is ported anyway for API parity per the firm decision — some other unaudited caller (Postman, a future admin screen) could depend on it, and dropping a live route silently is riskier than keeping it ADMIN-gated and password-redacted.
- **Duplicate route alias (`/performance` === `/subjects`, endpoints #4/#6)** implemented as one Java method mapped to two paths (`@GetMapping({"/performance","/subjects"})`), matching Node's shared controller function — not modeled as two independent features.
- **Dead coordinator exports NOT ported.** `studentController.js`/`studentModel.js` also export `getStudentsController`, `updateStudentController`, `markInactiveController`, `getStudentsByCohortAndBatch`, `getActiveStudentsForAttendance` — none of these are wired into `studentRoutes.js` (they belong to `coordinatorRoutes.js`/`teacherStudentRoutes.js`, out of scope for Phase 4a per the ground truth; tracked separately for Phase 4e coordinator).
- **Quirk A (`{exam_score:"-"}`-only degenerate summary) and Quirk B (JS falsy-zero `pp_exam_score`) both preserved verbatim**, pinned by two distinct tests in `StudentAttendanceAnalyticsIT` so a future refactor can't collapse them into one code path by accident.
- **No `NULLIF(COUNT(cs.session_id),0)` guard in `/monthly` and `/weekly`**, unlike `/summary`/`/subjects`/`/custom` — preserved as an inconsistency (safe only because `GROUP BY` on a date-derived column guarantees ≥1 row per group; not deliberately "fixed" to match the other three).
- **`/custom`'s date params are cast (`::date`) but not format-validated** before the query runs — a malformed date string still throws a PG error, caught by the same `catch` as every other unexpected failure on that endpoint, producing the identical `500 {error:"Failed to fetch custom data"}` Node would produce (not upgraded to a `400`, since no firm decision authorized that behavior change).
- **`/api/search`'s pagination has no clamp/max bound** on `limit`/`offset` (unlike `/api/search-students`'s `Math.min(...,100)`) — preserved exactly; also preserves the `parseInt(x,10) || default` JS falsy-zero quirk for an explicit `limit=0`/`offset=0` query param.
- **`spl_health_cond`/`spl_family_cond` are dead *filter* params on `/api/search`** — present in the sort whitelist and accepted as query params by Node's controller, but `searchModel.searchStudents` never reads them as WHERE filters (only as sort columns, where they crash — see above). Not implemented as filters here either, matching Node's incomplete/abandoned feature exactly.
- **ADMIN enforcement on endpoints #11–#15 is NEW** vs Node's fully-open routes (audit CRITICAL) — add to the fetch audit alongside Phase 3d's Results-module note.
- **`isAuthenticated()` on endpoints #1(exception)–#10 preserves Node's existing `authenticate` gate** — not a new restriction, just the Java-native equivalent; the student mobile app's existing tokens continue to work unchanged.
