# IMAS Spring Boot Migration — Plan 4b: Classrooms + Batches

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `classroomRoutes.js` / `batchRoutes.js` pair (26 endpoints: 8 classroom + 18 batch) to a new `com.rcf.imas.modules.classroom` module, preserving exact SQL, response shapes, status codes, and per-endpoint error envelopes — including every quirk documented in the ground truth (the dead `updateCohort`, the silently-dropped `batch_status`, the unguarded FK-violation 500 on `deleteBatch`, the active-cohort-scoped `GET /api/batches`, the two independent `getBatchesByCohort` implementations, and more).

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `classroom` with `web/` (2 controllers) and `persistence/` (4 repositories: 2 read, 2 write). No sort-whitelist enum needed (no dynamic `ORDER BY` anywhere in this module).

**Tech Stack (no additions):** Plain `JdbcClient`, already on the classpath. No new Maven dependency — this module does not generate files (no CSV/PDF/XLSX).

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Phases 0/1/2a/2b/2c/3a/3b/3c/3d/4a are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`, `@AuthenticationPrincipal JwtService.FinalToken`), `SecurityConfig` (method security), `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1–4a — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `WHERE ts.subject_id = :subjectId::integer`, `b.cohort_number = :cohortNumber::integer`, `sm.enr_id = :enrId::numeric`, etc. Java JDBC binds an unqualified string param as `VARCHAR`; Postgres will not implicitly compare `VARCHAR = numeric/integer`, so every numeric-column comparison needs an explicit cast on the bind variable — same failure-mode-on-bad-input logic as every prior plan (a non-numeric path value still throws a PG type-conversion error → whatever 500 envelope that endpoint's catch block maps to).
> 3. **Numeric-column serialization — EXAMS-STYLE, NOT Plan 4a's `toPlainString()` deviation.** This module's numeric columns (`batch.created_by`/`updated_by`, `classroom.created_by`/`updated_by`, `cohort.created_by`/`updated_by`, all `numeric(8,0)` scale 0) are all whole-number ids — there is **no genuinely fractional numeric output anywhere in classrooms/batches** (no `ROUND(x,2)`-style percentages like Plan 4a's `attendance_percent`). This module's single `genericRow` (defined once, in `ClassroomReadRepository`, reused by the other three repositories via same-package call) therefore uses **`bd.toBigInteger().toString()`** for the `NUMERIC`/`DECIMAL` branch — the same convention as `ExamsReadRepository`/`ExamsWriteRepository` — **not** `toPlainString()`. Do not blindly copy Plan 4a's `StudentPortalReadRepository.genericRow`; copy `ExamsReadRepository.genericRow` instead. `integer` columns (`batch_id`, `classroom_id`, `cohort_number`, `subject_id`, `teacher_id`, `platform_id`, `timetable_id`-equivalents — none of those last exist here, but the pattern is the same) still serialize as **native JSON numbers** via the `else → rs.getObject(i)` passthrough branch, never as strings.
> 4. **`ARRAY` columns** — this module has exactly one: `getClassrooms`'s `COALESCE(array_agg(cb.batch_id) FILTER (...), '{}') AS batch_ids`. `cb.batch_id` is `integer`, so each array element passes through as a native JSON number, matching node-pg's default array-of-integer → JS-number-array behavior. `genericRow` gets the same `ARRAY` case as `ExamsReadRepository` (convert each element: `BigDecimal` → `toBigInteger().toString()`, else passthrough) even though no `numeric` array ever appears in this module today — included for forward-consistency with the shared convention.
> 5. **DATE columns → `"yyyy-MM-dd"` string. TIME columns → `"HH:mm:ss"` string. TIMESTAMP → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).** Everything else passes through `rs.getObject(i)` natively. Map keys are literal snake_case (the SQL column alias, verbatim).
> 6. **snake_case JSON** global default. Request DTOs read as `@RequestBody Map<String,Object>` / `@RequestParam` / `@PathVariable` — no bespoke request POJOs anywhere in this module.
> 7. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`; `.with(key,value)` appends extra keys. **Two asymmetric error styles coexist in this module, both preserved exactly as observed in the ground truth:**
>    - **Classroom-side 500s** (all 8 `classroomController.js` handlers): the ground truth's response table shows a bare `500 {error}` for every one, with no literal text given anywhere — the strong implication (and the only pattern consistent with "no fixed text exists to quote") is that Node's catch blocks do `res.status(500).json({error: err.message})`, i.e. the *raw* driver/runtime error message, not a fixed string. Java therefore does `throw ApiException.error(500, e.getMessage())` in every classroom-side catch — this is a judgment call (flagged in Deferred section), not a verbatim-quoted Node string, because the ground truth genuinely does not give one.
>    - **Batch-side 500s** (`batchController.js`): several ARE given verbatim exact text (e.g. `getCoordinators`'s `{error:"Internal Server Error"}`, and every 400/404/409 across the batch endpoints has an exact quoted string in the ground truth's §4 table) — use those literal strings. For the *unspecified* batch-side 500s (several read endpoints just show `500` with no body detail), use the literal string `"Internal Server Error"` — this is the one text that IS given verbatim elsewhere in the same controller, making it the lowest-risk consistent assumption (also flagged in Deferred section, but low-risk).
>    - **Endpoint #10 (`GET /api/batches/students/:enr_id`) 404 uses `{message:"Student not found"}`** (`message` key) — **endpoint #18 (`PUT /api/batches/:batchId/students/:enr_id/status`) 404 for the exact same underlying "no such enr_id" condition uses `{error:"Student not found"}`** (`error` key, per ground truth §4's literal table row for that endpoint). Same English text, two different envelope keys, because they're two independent Node catch blocks. Do not accidentally unify these — pin both with distinct tests.
>    - `GET /:batchId` (endpoint #14) 404 is `{error:"Batch not found."}` **(trailing period)**; `DELETE /:batchId` (endpoint #16) 404 is `{error:"Batch not found"}` **(no trailing period)**. Also distinct, also pinned separately.
> 8. **Auth — ALL 26 endpoints get `@PreAuthorize("hasRole('ADMIN')")` at class level, on BOTH controllers.** (Firm Decision 1, detailed below — audit CRITICAL.) There is no `isAuthenticated()` bucket in this module, unlike Plan 4a's split. Do not copy Plan 4a's auth-split table pattern into a per-endpoint decision here — it's uniform.
> 9. **Controllers:** class package-private; every `@RequestMapping` handler method **`public`** (package-private methods silently skip `@PreAuthorize`).
> 10. **Transactions:** multi-statement writes (classroom CRUD + batch CRUD, all 6 of them) live in a single dedicated `ClassroomWriteRepository` `@Repository` bean, each method `@Transactional` (Firm Decision 5). Simple single-statement writes (`addBatchName`, `createCohort`, `addStudentsToBatch`, `removeStudentsFromBatch`, `updateStudentStatusInBatch`) live in `BatchWriteRepository`, **not** `@Transactional` (matches Node — none of these are wrapped in a transaction there either).
> 11. **Test isolation:** all `*IT` extend `PgIntegrationTest`, `@AutoConfigureMockMvc`. `@AfterEach`-clean children-before-parents. **This module has NO jurisdiction FK anywhere** — confirmed against the ground truth's §3 DDL block (`pp.batch`, `pp.classroom`, `pp.cohort`, `pp.classroom_batch`, `pp.batch_coordinator_batches`, `pp.teaching_platform` — none reference `pp.jurisdiction`). No jurisdiction seed rows needed in any task's `@BeforeEach`. FK chain to respect: `pp."user"` → `pp.cohort` → `pp.batch` (FK `cohort_number`, ON DELETE CASCADE) → `pp.classroom` (FK `subject_id`/`teacher_id`/`platform_id`, ON DELETE SET NULL) → `pp.classroom_batch` (junction, both FKs CASCADE) → `pp.student_master` (FK `batch_id`, **NO ON DELETE** — this is what makes `deleteBatch`'s FK-violation possible, Firm Decision 6's test needs a `student_master` row seeded). `pp.role`/`pp.user_role`/`pp.batch_coordinator_batches` needed only where a task's test exercises coordinators. Watch UNIQUE constraints: `pp.batch(cohort_number, batch_name)` (backs `addBatchName`'s `ON CONFLICT`), `pp.cohort(cohort_name)`. Advance sequences (`setval`) after every explicit-PK seed.
> 12. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.
> 13. **`COHORT_START_YEAR = 2021`** exists as a Java `static final int` **in two places** — `BatchReadRepository` (used to build `fetchAllBatches`'s literal-interpolated SQL text, matching Node's own hard-coded-in-the-query-string approach, NOT a bind param) and `BatchController` (used by `createCohort`'s `cohort_number = year - COHORT_START_YEAR` derivation) — intentionally duplicated, mirroring the ground truth's own finding that Node carries this constant twice (`batchController.js:3` and inlined in `batchModel.js`'s query string).

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase4b-classrooms-batches-ground-truth.md`. Node source: `server/routes/classroomRoutes.js`, `server/controllers/classroomController.js`, `server/models/classroomModel.js`, `server/routes/batchRoutes.js`, `server/controllers/batchController.js`, `server/models/batchModel.js`. Mounts: `app.use("/api/classrooms", classroomRoutes)`, `app.use("/api/batches", batchRoutes)` — **zero Node `authenticate` middleware on either mount** (this is the basis for Firm Decision 1).

### Table facts (from `live-schema.sql`, ground truth §3)

| Table | PK | Notable UNIQUE / FK | Notable columns |
|---|---|---|---|
| `pp.batch` | `batch_id integer` (seq `pp.batch_id_seq`) | UNIQUE `(cohort_number, batch_name)`; FK `cohort_number→cohort` CASCADE; FK `created_by`/`updated_by→"user"` | `medium varchar(20)` default `'KANNADA'`, `house_name varchar(100)` — **both unused by this module, never read/written**. No status/active column at all (backs quirk 2). |
| `pp.batch_coordinator_batches` | composite `(user_id, batch_id)` | FK `batch_id→batch` (no ON DELETE); FK `user_id→"user"` (no ON DELETE) | junction, no seq |
| `pp.classroom` | `classroom_id integer` (seq) | FK `subject_id`/`teacher_id`/`platform_id`→resp. tables SET NULL; FK `created_by`/`updated_by→"user"` | `classroom_name varchar(100) NOT NULL`; `active_yn character(1)` CHECK `Y/N` default `'Y'` (**different convention from `student_master.active_yn` varchar `ACTIVE/INACTIVE`**) |
| `pp.classroom_batch` | composite `(classroom_id, batch_id)` | FK `batch_id→batch` CASCADE; FK `classroom_id→classroom` CASCADE | junction |
| `pp.cohort` | `cohort_number integer` (seq `pp.cohort_seq`, overridden by app logic) | UNIQUE `cohort_name`; FK `created_by`/`updated_by→"user"` | `status varchar(20)` CHECK `ACTIVE/COMPLETED` — **never read/written by this module**; `current_grade integer` CHECK `9-12` — **also never read/written** |
| `pp.teaching_platform` | `platform_id integer` (seq) | — | `platform_name varchar(100) NOT NULL` |
| `pp.subject` | `subject_id integer` (seq) | — | `subject_code varchar(5) NOT NULL`, `subject_name varchar(100) NOT NULL` |
| `pp.teacher` | `teacher_id integer` (seq) | FK `user_id→"user"` (logical, used by the dropdown join) | `teacher_name varchar(150)` (own display name column — distinct from the login `user_name` the dropdown query joins to) |
| `pp.teacher_subject` | composite-ish `(teacher_id, subject_id, medium)` | — | `medium varchar(20) NOT NULL` default `'KANNADA'` |
| `pp.system_config` | `system_config_id integer` (seq) | — | `academic_year varchar(9) NOT NULL` CHECK format `^[0-9]{4}-[0-9]{2,4}$`; `is_active boolean` default `true` — drives `fetchAllBatches`'s scoping (Firm Decision 4) |
| `pp.student_master` (relevant cols only) | `student_id numeric(14,0)` | FK `batch_id→batch` **NO ON DELETE** (⇒ RESTRICT, backs Firm Decision 6) | `enr_id numeric(11,0)`, `active_yn varchar(10)` CHECK `ACTIVE/INACTIVE` |
| `pp.role` | `role_id numeric(4,0)` (seq) | — | `role_name varchar(100) NOT NULL` — `'BATCH COORDINATOR'` is a data value, not a schema constant |
| `pp."user"` | `user_id numeric(8,0)` (seq) | — | `user_name varchar(100) NOT NULL` |

`pp.system_config.academic_year` format constraint (`^[0-9]{4}-[0-9]{2,4}$`) matters for Task 2's active-cohort-scoping test: the derived year (`SUBSTRING(academic_year,1,4)::integer - 2021`) must be a plausible 4-digit year, which in turn means test `cohort_number` values for that specific test must be small (e.g. `500`/`501`, not the `9xxxxx` ranges used elsewhere in this plan) — see Task 2's seed notes.

### Endpoint contract (26 routes)

**Classrooms** (`/api/classrooms`, `classroomController.js`, Task 1):

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 1 | GET `/subjects` | `200 [{subject_id,subject_name,subject_code}]` | `500 {error:<raw message>}` |
| 2 | GET `/platforms` | `200 [{platform_id,platform_name}]` | `500 {error:<raw message>}` |
| 3 | GET `/teachers/{subjectId}` | `200 [{teacher_id,teacher_name}]` | `500 {error:<raw message>}` |
| 4 | GET `/batches/{cohortNumber}` | `200 [{batch_id,batch_name}]` (classroom-side) | `500 {error:<raw message>}` |
| 5 | GET `/` | `200 [{classroom_id,classroom_name,class_link,active_yn,description,created_at,subject_id,teacher_id,platform_id,subject_name,subject_code,teacher_name,platform_name,batch_ids:[...],cohort_number}]` | `500 {error:<raw message>}` |
| 6 | POST `/` | `201 {message:"Classroom created successfully",classroom_id}` | `500 {error:<raw message>}` |
| 7 | PUT `/{id}` | `200 {classroom_id,message:"Classroom updated"}` | `404 {message:"Classroom not found"}`; `500 {error:<raw message>}` |
| 8 | DELETE `/{id}` | `200 {message:"Classroom deleted successfully"}` | `404 {message:"Classroom not found"}`; `500 {error:<raw message>}` |

**Batches** (`/api/batches`, `batchController.js`, Tasks 2–5):

| # | Method + Path | Task | Success | Errors |
|---|---|---|---|---|
| 9 | GET `/coordinators` | 2 | `200 [{id,name}]` | `404 {error:"Coordinator role not found"}`; `500 {error:"Internal Server Error"}` |
| 10 | GET `/names` | 2 | `200 [{label,value}]` | `500 {error:"Internal Server Error"}` |
| 11 | POST `/names` | 3 | `201 {message:"Batch created successfully",batch:{...}}` OR `200 {message:"Batch name already exists for this cohort"}` | `400 {error:<assumed>}`; `500 {error:"Internal Server Error"}` |
| 12 | GET `/cohorts` | 2 | `200 [{cohort_number,cohort_name,start_date,description}]` | `500 {error:"Internal Server Error"}` |
| 13 | POST `/cohorts` | 3 | `201 {message:"Cohort created successfully",data:{...}}` | `400 {error:<assumed>}`; `409 {error:"Cohort name already exists"}`; `409 {error:"Cohort for year <Y> already exists."}`; `500` |
| 14 | GET `/cohorts/active` | 2 | `200 [{...cohort row}]` (`SELECT *`) | `500 {error:"Internal Server Error"}` |
| 15 | GET `/students/unassigned` | 2 | `200 [{student_id,enr_id,student_name,student_email,contact_no1}]` | `500 {error:"Internal Server Error"}` |
| 16 | POST `/{batchId}/add-students` | 5 | `200 {message:"Students successfully assigned to batch",count}` | `400 {error:<assumed>}`; `500 {error:"Internal Server Error"}` |
| 17 | POST `/students/remove` | 5 | `200 {message:"Students removed from batch successfully",count}` | `400 {error:"student_ids are required"}`; `500` |
| 18 | GET `/students/{enr_id}` | 2 | `200 {reg_number,...fullRow}` (`nmms_reg_number` appears twice) | `404 {message:"Student not found"}`; `500 {error:"Internal Server Error"}` |
| 19 | GET `/{cohort_number}/batches` | 2 | `200 [{...batch row}]` (`SELECT *`, batch-side) | `500 {error:"Internal Server Error"}` |
| 20 | GET `/` | 2 | `200 [{id,batch_name,cohort_number,cohort_name,coordinator_name,coordinator_id}]` — **active-cohort-scoped only** | `500 {error:"Internal Server Error"}` |
| 21 | POST `/` | 4 | `201 {...insertBatch row}` | `400 {error:"batch_name and cohort_number are required"}`; `409 {error:"Batch already exists for this cohort."}`; `500` |
| 22 | GET `/{batchId}` | 2 | `200 {batch_id,batch_name,cohort_name}` | `404 {error:"Batch not found."}`; `500` |
| 23 | PUT `/{batchId}` | 4 | `200 {...updateBatchDetails row}` | `400 {error:"Missing required fields"}`; `409 {error:"Duplicate batch name in cohort."}`; `404 {error:"Batch not found"}`; `500` |
| 24 | DELETE `/{batchId}` | 4 | `200 {message:"Batch deleted successfully",deleted:{...}}` | `404 {error:"Batch not found"}`; `500` (incl. unguarded FK-violation) |
| 25 | GET `/{batchId}/students` | 2 | `200 [{student_id,enr_id,student_name,student_email,contact_no1,active_yn,nmms_reg_number}]` | `500 {error:"Internal Server Error"}` |
| 26 | PUT `/{batchId}/students/{enr_id}/status` | 5 | `200 {message:"Student status updated successfully"}` | `400 {error:"student_id or enr_id is required"}`; `400 {error:"active_yn is required"}`; `404 {error:"Student not found"}`; `500 {error:"Internal Server Error",details:<err.message>}` |

## Firm decisions (baked in throughout — see rationale inline per task)

1. **All 26 endpoints get `@PreAuthorize("hasRole('ADMIN')")`.** Node applies zero `authenticate` middleware to either mount (ground truth's own Mounts line, quoted above). Per the lead's rule ("Node left open but clearly admin/coordinator management → ADMIN"), every endpoint here is classroom/batch/cohort/coordinator administration — mirrors `ExamsController`'s precedent (also class-level ADMIN for the same reason). **Audit CRITICAL**: unlike Plan 4a, there is no `isAuthenticated()` bucket to carry over — do not introduce one.
2. **`updateCohort` is DEAD — not ported.** `batchController.js:238-261` + `batchModel.js:194-209` (`checkCohortDuplicateForUpdate`/`updateCohortDetails`) are fully implemented but never wired into `batchRoutes.js` — no `PUT /cohorts/:id` route exists in Node. No Java `PUT /api/batches/cohorts/{id}` endpoint. See Deferred section.
3. **`batch_status` is silently accepted and dropped** on `PUT /api/batches/{id}`. The request body may contain it; it is read into nothing, persisted nowhere (no such column exists on `pp.batch`). Task 4 has a dedicated no-op test.
4. **`GET /api/batches` is scoped to the currently-active academic-year cohort**, not "all batches" — reproduces `fetchAllBatches`'s exact `EXISTS` subquery against `pp.system_config.is_active='true'` and the `COHORT_START_YEAR=2021` literal. Task 2 seeds two cohorts (one matching, one not) and asserts exclusion.
5. **Multi-step batch/classroom flows are genuinely `@Transactional`, in one dedicated `ClassroomWriteRepository` bean.** Covers classroom's `createClassroom`/`updateClassroom`/`deleteClassroom` (Task 1, Node used manual pool `BEGIN`/`COMMIT` which is not a real cross-statement transaction on a shared pool) and batch's `createBatch`/`updateBatch`/`deleteBatch` (Task 4, Node ran these as loose sequential autocommit queries with no transaction at all). Spring `@Transactional` makes both genuinely atomic — an intentional improvement, not a behavior change any caller depends on (matches `ExamsWriteRepository.deleteExam`'s precedent and rationale).
6. **`deleteBatch`'s FK violation is preserved as a raw 500** — no pre-check against `student_master`. Task 4 seeds a student pointing at the batch and asserts `500` (via `GlobalExceptionHandler`'s generic `{error:"Internal Server Error"}` fallback, since no `ApiException` catches it — confirmed against `GlobalExceptionHandler.java`, whose final `@ExceptionHandler(Exception.class)` returns exactly `ResponseEntity.status(500).body(Map.of("error","Internal Server Error"))`).
7. **The phantom frontend route `/api/batches/cohort/{cohortNumber}` is NOT replicated.** It was never a real Node route in `batchRoutes.js` — it 404s in the live app today (frontend bug). Do not confuse it with the *similarly-named but structurally different* route already shipped in Plan 4a: `GET /api/batches/cohort/{cohortNumber}` under `ApplicantSearchController` is `searchModel.getBatchesByCohort` (`searchModel.js`, `SELECT * FROM pp.batch WHERE cohort_number=$1 ORDER BY batch_id ASC` — different SQL, different route file entirely, already ported in Plan 4a). This plan's endpoint #19 (`GET /api/batches/{cohort_number}/batches`) is the real, different, batch-module-owned route — see the two-implementations note below.
8. **Numeric serialization re-verified against every column** (see convention #3 above): `batch_id`, `classroom_id`, `cohort_number`, `subject_id`, `teacher_id`, `platform_id` are all `integer` → native JSON numbers via `genericRow`'s passthrough branch; `created_by`/`updated_by` are `numeric(8,0)` → JSON strings via the `NUMERIC`/`DECIMAL` branch. **`|| fallback`-on-numeric-column hazard check (explicitly ruled out):** the ground truth's only merge-style JS logic in this module is `getStudentsInfoFromBatch`'s `{reg_number: row.nmms_reg_number, ...row}` (a straight duplication, no `||` fallback at all) and `createCohort`'s `cohort_number = new Date(start_date).getFullYear() - 2021` (arithmetic, not a fallback). No `x || y`-on-a-numeric-column pattern exists anywhere in `classroomModel.js`/`batchModel.js`/`classroomController.js`/`batchController.js` — confirmed by re-reading the ground truth's §2/§7 in full; **explicitly ruled out**, unlike Plan 4a's Quirk B (`pp_exam_score || "-"`) which had a real one.

## File-generating endpoints

None. All 26 endpoints return JSON only (confirmed by ground truth §5).

## Transactions (ground truth §6, Firm Decision 5)

Four Node model functions use manual `pool.connect()`+`BEGIN`/`COMMIT`/`ROLLBACK` (classroom's create/update/delete — none of them a *real* cross-connection transaction on a pooled client, since each `pool.query` inside call may or may not reuse the same connection depending on driver internals — Node's own manual transaction here is best-effort, not guaranteed). Batch's `createBatch`/`updateBatch`/`deleteBatch` controller flows are **not wrapped in any transaction at all** in Node — fully sequential autocommit statements. Per Firm Decision 5, Java makes **all six** of these genuinely atomic via Spring `@Transactional` in one `ClassroomWriteRepository` bean — self-invocation is avoided (all six live in the same dedicated `@Repository`, called externally from `ClassroomController`/`BatchController`, never from another method inside the same class), matching the working `ExamsWriteRepository.deleteExam`/`createExamOnly`/`assignStudents` precedent.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/classroom/
├── web/ClassroomController.java          (Task 1: /api/classrooms, 8 endpoints, ADMIN)
├── web/BatchController.java              (Tasks 2-5: /api/batches, 18 endpoints, ADMIN)
├── persistence/ClassroomReadRepository.java   (Task 1: defines the module's genericRow)
├── persistence/ClassroomWriteRepository.java  (Task 1 + Task 4: all @Transactional multi-step writes)
├── persistence/BatchReadRepository.java       (Task 2)
└── persistence/BatchWriteRepository.java      (Task 3 + Task 5: single-statement, non-@Transactional writes)

imas-backend/src/test/java/com/rcf/imas/modules/classroom/
├── ClassroomCrudIT.java              (Task 1: all 8 classroom endpoints)
├── BatchReadsIT.java                 (Task 2: 10 batch read endpoints incl. active-cohort scoping)
├── BatchSimpleWritesIT.java          (Task 3: addBatchName + createCohort)
├── BatchCrudIT.java                  (Task 4: createBatch/updateBatch/deleteBatch, incl. FK-violation + batch_status no-op)
└── BatchStudentAssignmentIT.java     (Task 5: add-students/remove/status, incl. both preserved quirks)

No SecurityConfig changes needed (unlike Plan 4a) — no endpoint in this module is public.
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → run full suite (regression) → commit. Serialize tasks (no parallel implementers — git index races).
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN")` for every test (no non-ADMIN success path exists in this module); one `"STUDENT"`-role token per test class is enough to pin the 403 case.
- Distinct seed-ID ranges per task file to avoid any cross-class collision in the shared embedded-Postgres JVM: Task 1 uses `910xxx`; Task 2 uses `920xxx` for most rows but **`500`/`501`/`502` for cohort_number specifically** (the active-cohort-scoping test needs small cohort numbers — see Task 2); Task 3 uses `930xxx` for most rows but `77`/`78`/`79` for cohort_number in the `createCohort` tests (same reason); Task 4 uses `940xxx`; Task 5 uses `950xxx`.

---

## Task 1: module skeleton + `ClassroomController` (all 8 classroom endpoints)

Establishes the module's single `genericRow` (EXAMS-style `toBigInteger()`, convention #3) and `ClassroomWriteRepository`'s classroom-side `@Transactional` methods. Pins the `batch_ids` omitted-vs-empty-array partial-update-vs-full-resync branch (ground truth §6 point 2) with a dedicated test.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/ClassroomReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/ClassroomWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/ClassroomController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/classroom/ClassroomCrudIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/classroom/ClassroomCrudIT.java`:
```java
package com.rcf.imas.modules.classroom;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ClassroomCrudIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;
    String studentToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910001,'crAdmin910','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (910001,'CR1','Classroom Subject 910')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (910002,'crTeacherLogin910','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (910001,910002,'Classroom Teacher 910')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id) VALUES (910001,910001)").update();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (910001,'Classroom Platform 910')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (910001,'Cohort CR910')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910001,'CR Batch A',910001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (910002,'CR Batch B',910001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("910001", "crAdmin910", "ADMIN");
        studentToken = jwt.issueFinalToken("910099", "crStudent910", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (SELECT classroom_id FROM pp.classroom WHERE classroom_name LIKE 'CR %')").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_name LIKE 'CR %'").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (910001,910002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 910001").update();
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 910001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 910001").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 910001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 910001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (910001,910002)").update();
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/classrooms/subjects")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminTokenIs403() throws Exception {
        mvc.perform(get("/api/classrooms/subjects").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void subjectsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/classrooms/subjects").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.subject_id==910001)].subject_name").value("Classroom Subject 910"));
    }

    @Test
    void platformsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/classrooms/platforms").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.platform_id==910001)].platform_name").value("Classroom Platform 910"));
    }

    @Test
    void teachersBySubjectJoinsThroughUserForLoginName() throws Exception {
        mvc.perform(get("/api/classrooms/teachers/910001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].teacher_id").value(910001))
           .andExpect(jsonPath("$[0].teacher_name").value("crTeacherLogin910")); // u.user_name, NOT t.teacher_name
    }

    @Test
    void batchesByCohortClassroomSideReturnsIdAndNameOnly() throws Exception {
        mvc.perform(get("/api/classrooms/batches/910001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
           .andExpect(jsonPath("$[0].batch_id").exists())
           .andExpect(jsonPath("$[0].batch_name").exists())
           .andExpect(jsonPath("$[0].cohort_number").doesNotExist()); // classroom-side shape: batch_id+batch_name ONLY
    }

    @Test
    void createUpdateGetListDeleteRoundTripWithBatchIdsResyncSemantics() throws Exception {
        // create with batch_ids = [910001, 910002]
        String createBody = """
            {"classroom_name":"CR Full Classroom","subject_id":910001,"teacher_id":910001,"platform_id":910001,
             "class_link":"https://x/y","active_yn":"Y","created_by":910001,"batch_ids":[910001,910002]}
            """;
        String createResp = mvc.perform(post("/api/classrooms").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("Classroom created successfully"))
            .andExpect(jsonPath("$.classroom_id").exists())
            .andReturn().getResponse().getContentAsString();
        int classroomId = com.jayway.jsonpath.JsonPath.read(createResp, "$.classroom_id");

        // list: batch_ids aggregated, both linked
        mvc.perform(get("/api/classrooms").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.classroom_id==" + classroomId + ")].batch_ids[0]").exists());

        // update WITHOUT batch_ids key at all -> links untouched (partial update branch)
        String partialUpdate = """
            {"classroom_name":"CR Full Classroom Renamed","subject_id":910001,"teacher_id":910001,
             "platform_id":910001,"class_link":"https://x/y2","active_yn":"Y","updated_by":910001}
            """;
        mvc.perform(put("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(partialUpdate))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Classroom updated"));

        Integer linkCountAfterPartial = jdbc.sql("SELECT COUNT(*) FROM pp.classroom_batch WHERE classroom_id = :id")
            .param("id", classroomId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(linkCountAfterPartial).isEqualTo(2); // untouched

        // update WITH batch_ids = [] -> full resync, unlinks everything
        String emptyResync = """
            {"classroom_name":"CR Full Classroom Renamed","subject_id":910001,"teacher_id":910001,
             "platform_id":910001,"class_link":"https://x/y2","active_yn":"Y","updated_by":910001,"batch_ids":[]}
            """;
        mvc.perform(put("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(emptyResync))
           .andExpect(status().isOk());

        Integer linkCountAfterResync = jdbc.sql("SELECT COUNT(*) FROM pp.classroom_batch WHERE classroom_id = :id")
            .param("id", classroomId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(linkCountAfterResync).isEqualTo(0); // fully unlinked

        // delete
        mvc.perform(delete("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Classroom deleted successfully"));

        // delete again -> 404
        mvc.perform(delete("/api/classrooms/" + classroomId).header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Classroom not found"));
    }

    @Test
    void updateUnknownIdIs404() throws Exception {
        String body = """
            {"classroom_name":"X","subject_id":910001,"teacher_id":910001,"platform_id":910001,
             "class_link":"x","active_yn":"Y","updated_by":910001}
            """;
        mvc.perform(put("/api/classrooms/999999999").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Classroom not found"));
    }

    @Test
    void createMissingRequiredColumnSurfacesRawDbErrorAs500() throws Exception {
        // classroom_name is NOT NULL at the DB level; Node never pre-validates it -- the raw PG error message
        // is what the classroom-side catch(err) => {error: err.message} pattern would surface (convention #7).
        String body = """
            {"subject_id":910001,"teacher_id":910001,"platform_id":910001,"active_yn":"Y","created_by":910001}
            """;
        mvc.perform(post("/api/classrooms").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ClassroomCrudIT` — Expected: FAIL (no module yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/classroom/persistence/ClassroomReadRepository.java`:
```java
package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClassroomReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ClassroomReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the whole classroom module (convention #3): EXAMS-style
     * bd.toBigInteger().toString() for NUMERIC/DECIMAL -- this module has NO genuinely fractional numeric
     * output anywhere (unlike Plan 4a's student module), so do NOT copy Plan 4a's toPlainString() deviation.
     * BIGINT -> String. DATE -> "yyyy-MM-dd". TIME -> "HH:mm:ss". TIMESTAMP -> ISO-Z. ARRAY (only
     * COALESCE(array_agg(cb.batch_id)...) in getClassrooms) -> List, elements passthrough (integer array,
     * never numeric here, but the same element-conversion rule as ExamsReadRepository is applied for
     * forward-consistency). Else passthrough via rs.getObject(i) (native JSON number for integer columns).
     * Package-private static so the other three repositories in this module call it directly (same package),
     * matching the ExamsWriteRepository-reuses-ExamsReadRepository.genericRow precedent.
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
                case java.sql.Types.ARRAY -> {
                    Array arr = rs.getArray(i);
                    val = arr == null ? null : arrayToList(arr);
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    private static List<Object> arrayToList(Array arr) throws SQLException {
        Object raw = arr.getArray();
        List<Object> out = new ArrayList<>();
        int len = java.lang.reflect.Array.getLength(raw);
        for (int i = 0; i < len; i++) {
            Object el = java.lang.reflect.Array.get(raw, i);
            if (el instanceof BigDecimal bd) { out.add(bd.toBigInteger().toString()); }
            else { out.add(el); }
        }
        return out;
    }

    public List<Map<String, Object>> subjects() {
        return jdbc.sql("SELECT subject_id, subject_name, subject_code FROM pp.subject ORDER BY subject_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> teachingPlatforms() {
        return jdbc.sql("SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> teachersBySubject(String subjectId) {
        return jdbc.sql("""
                SELECT
                    t.teacher_id,
                    u.user_name AS teacher_name
                 FROM pp.teacher t
                 JOIN pp.user u ON t.user_id = u.user_id
                 JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
                 WHERE ts.subject_id = :subjectId::integer
                 ORDER BY u.user_name
                """).param("subjectId", subjectId).query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getBatchesByCohort -- {batch_id,batch_name} ONLY. Distinct from BatchReadRepository's
     *  batch-side implementation (SELECT *) -- ground truth §7 quirk 5, do not unify. */
    public List<Map<String, Object>> batchesByCohortClassroomSide(String cohortNumber) {
        return jdbc.sql("SELECT batch_id, batch_name FROM pp.batch WHERE cohort_number = :cohortNumber::integer ORDER BY batch_name")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> classrooms() {
        return jdbc.sql("""
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
                """).query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/classroom/persistence/ClassroomWriteRepository.java` (Task 1 portion; Task 4 adds three more `@Transactional` methods to this same file):
```java
package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

@Repository
public class ClassroomWriteRepository {

    private final JdbcClient jdbc;

    public ClassroomWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** createClassroom() parity, made genuinely atomic (Firm Decision 5) -- Node's version runs manual
     *  pool BEGIN/COMMIT which is not a real cross-statement transaction on a pooled client. N+1 loop-insert
     *  for batch links preserved verbatim (ground truth §7 quirk 14 -- fine for the expected small lists). */
    @Transactional
    public Map<String, Object> createClassroom(String classroomName, String subjectId, String teacherId,
                                                 String platformId, String classLink, String activeYn,
                                                 String createdBy, String updatedBy, List<String> batchIds) {
        Map<String, Object> row = jdbc.sql("""
                INSERT INTO pp.classroom
                 (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
                 VALUES (:name, :subjectId::integer, :teacherId::integer, :platformId::integer, :classLink, :activeYn, :createdBy::numeric, :updatedBy::numeric)
                 RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("createdBy", createdBy).param("updatedBy", updatedBy)
                .query((rs, i) -> genericRow(rs)).single();

        Object classroomId = row.get("classroom_id");
        if (batchIds != null) {
            for (String batchId : batchIds) {
                jdbc.sql("INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES (:classroomId::integer, :batchId::integer)")
                        .param("classroomId", classroomId).param("batchId", batchId).update();
            }
        }
        return row;
    }

    /** updateClassroom() parity. batchIdsProvided distinguishes "key absent from the request body" (skip
     *  the resync entirely -- existing links untouched) from "key present, even as []" (full delete+reinsert
     *  resync) -- ground truth §6 point 2, a meaningful behavioral branch, NOT the same as batchIds==null. */
    @Transactional
    public Map<String, Object> updateClassroom(String classroomId, String classroomName, String subjectId,
                                                 String teacherId, String platformId, String classLink,
                                                 String activeYn, String updatedBy,
                                                 boolean batchIdsProvided, List<String> batchIds) {
        Map<String, Object> row = jdbc.sql("""
                UPDATE pp.classroom
                    SET classroom_name = :name, subject_id = :subjectId::integer, teacher_id = :teacherId::integer, platform_id = :platformId::integer,
                        class_link = :classLink, active_yn = :activeYn, updated_by = :updatedBy::numeric, updated_at = NOW()
                    WHERE classroom_id = :id::integer
                    RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("updatedBy", updatedBy).param("id", classroomId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return null;

        if (batchIdsProvided) {
            jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = :id::integer").param("id", classroomId).update();
            if (batchIds != null) {
                for (String batchId : batchIds) {
                    jdbc.sql("INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES (:classroomId::integer, :batchId::integer)")
                            .param("classroomId", classroomId).param("batchId", batchId).update();
                }
            }
        }
        return row;
    }

    /** deleteClassroom() parity. The manual classroom_batch DELETE is redundant given both junction FKs
     *  CASCADE (ground truth §3 note) but kept explicit/visible inside the transaction, matching Node. */
    @Transactional
    public boolean deleteClassroom(String classroomId) {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = :id::integer").param("id", classroomId).update();
        Map<String, Object> row = jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = :id::integer RETURNING classroom_id")
                .param("id", classroomId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        return row != null;
    }
}
```

`src/main/java/com/rcf/imas/modules/classroom/web/ClassroomController.java`:
```java
package com.rcf.imas.modules.classroom.web;

import com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository;
import com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/classrooms")
@PreAuthorize("hasRole('ADMIN')")   // ground truth: zero Node `authenticate` middleware on this mount -- Firm Decision 1
class ClassroomController {

    private final ClassroomReadRepository reads;
    private final ClassroomWriteRepository writes;

    ClassroomController(ClassroomReadRepository reads, ClassroomWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        try { return reads.subjects(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); } // convention #7: raw err.message
    }

    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        try { return reads.teachingPlatforms(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping("/teachers/{subjectId}")
    public List<Map<String, Object>> teachersBySubject(@PathVariable String subjectId) {
        try { return reads.teachersBySubject(subjectId); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping("/batches/{cohortNumber}")
    public List<Map<String, Object>> batchesByCohort(@PathVariable String cohortNumber) {
        try { return reads.batchesByCohortClassroomSide(cohortNumber); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> classrooms() {
        try { return reads.classrooms(); }
        catch (Exception e) { throw ApiException.error(500, e.getMessage()); }
    }

    @PostMapping({"", "/"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createClassroom(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            // NOTE (Deferred/assumption): Node's INSERT binds both created_by and updated_by params; the
            // ground truth doesn't disambiguate whether these come from one or two request fields. We read a
            // single "created_by" body field for both, the most common Node insert-time convention -- flagged.
            String createdBy = str(b.get("created_by"));
            Map<String, Object> row = writes.createClassroom(str(b.get("classroom_name")), str(b.get("subject_id")),
                    str(b.get("teacher_id")), str(b.get("platform_id")), str(b.get("class_link")),
                    str(b.get("active_yn")), createdBy, createdBy, asStringList(b.get("batch_ids")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Classroom created successfully");
            out.put("classroom_id", row.get("classroom_id"));
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateClassroom(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean batchIdsProvided = b.containsKey("batch_ids");
        try {
            Map<String, Object> row = writes.updateClassroom(id, str(b.get("classroom_name")), str(b.get("subject_id")),
                    str(b.get("teacher_id")), str(b.get("platform_id")), str(b.get("class_link")),
                    str(b.get("active_yn")), str(b.get("updated_by")), batchIdsProvided, asStringList(b.get("batch_ids")));
            if (row == null) throw ApiException.message(404, "Classroom not found");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("classroom_id", row.get("classroom_id"));
            out.put("message", "Classroom updated");
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteClassroom(@PathVariable String id) {
        try {
            boolean deleted = writes.deleteClassroom(id);
            if (!deleted) throw ApiException.message(404, "Classroom not found");
            return Map.of("message", "Classroom deleted successfully");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l)) return null;
        return l.stream().map(String::valueOf).toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ClassroomCrudIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/classroom imas-backend/src/test/java/com/rcf/imas/modules/classroom/ClassroomCrudIT.java
git commit -m "feat(classroom): module skeleton + classroom CRUD (subjects/platforms/teachers/batches dropdowns + create/update/delete)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `BatchController` reads (10 endpoints, incl. active-cohort-scoped `GET /`)

Endpoints #9-#10, #12, #14-#15, #18-#20, #22, #25 (using the plan-wide numbering from the endpoint contract table). Establishes `BatchReadRepository`. Pins Firm Decision 4 (`GET /` active-cohort scoping) and ground truth §7 quirk 5 (two independent `getBatchesByCohort` implementations).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/BatchReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchReadsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/classroom/BatchReadsIT.java`:
```java
package com.rcf.imas.modules.classroom;

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
class BatchReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920001,'brAdmin920','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Active-cohort scoping (Firm Decision 4): cohort_number must satisfy year-2021 where year is a
        // plausible 4-digit academic_year prefix (pp.system_config.academic_year format check) -- small
        // numbers only, NOT the 920xxx range used elsewhere in this file.
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, start_date) VALUES (500,'Cohort Active 500','2025-06-01')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, start_date) VALUES (501,'Cohort Other 501','2026-06-01')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920001,'Active Batch',500)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (920002,'Other Batch',501)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (920002,'brCoordinator920','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (920002,920001)").update();

        jdbc.sql("INSERT INTO pp.system_config(system_config_id, academic_year, phase, is_active) VALUES (920001,'2521-22','ADMISSION',true)").update();
        jdbc.sql("SELECT setval('pp.system_config_id_seq', (SELECT MAX(system_config_id)::bigint FROM pp.system_config))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (920001, 24920000001)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (920001)").update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (920001, 920001, 92000001, 'Batch Student 920', 'F', 920001, 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn)
            VALUES (920002, 920001, 92000002, 'Unassigned Student 920', 'M', 'ACTIVE')
            """).update(); // NOTE: shares applicant_id 920001 only to satisfy the FK cheaply -- fine for this read-only test
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("920001", "brAdmin920", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.role WHERE role_id = 9001").update();
        jdbc.sql("DELETE FROM pp.user_role WHERE role_id = 9001").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id = 920001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 920001").update();
        jdbc.sql("DELETE FROM pp.system_config WHERE system_config_id = 920001").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (920001,920002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (500,501)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (920001,920002)").update();
    }

    @Test
    void coordinatorsReturnsUsersInBatchCoordinatorRole() throws Exception {
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (9001,'BATCH COORDINATOR','Y')").update();
        jdbc.sql("SELECT setval('pp.role_id_seq', (SELECT MAX(role_id)::bigint FROM pp.role))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (920002,9001)").update();

        mvc.perform(get("/api/batches/coordinators").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("920002"))
           .andExpect(jsonPath("$[0].name").value("brCoordinator920"));
    }

    @Test
    void coordinatorsRoleMissingIs404() throws Exception {
        // role NOT seeded in this test -> role lookup fails
        mvc.perform(get("/api/batches/coordinators").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Coordinator role not found"));
    }

    @Test
    void batchNamesReturnsLabelValuePairs() throws Exception {
        mvc.perform(get("/api/batches/names").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.value=='Active Batch')].label").value("Active Batch"));
    }

    @Test
    void allCohortsReturnsProjectedColumnsOnly() throws Exception {
        mvc.perform(get("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==500)].cohort_name").value("Cohort Active 500"));
    }

    @Test
    void activeCohortsReturnsFullRowWhereEndDateIsNull() throws Exception {
        mvc.perform(get("/api/batches/cohorts/active").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==500)].description").exists()); // SELECT * shape
    }

    @Test
    void studentsUnassignedReturnsStudentsWithoutBatch() throws Exception {
        mvc.perform(get("/api/batches/students/unassigned").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.student_id=='920002')].student_name").value("Unassigned Student 920"));
    }

    @Test
    void studentInfoByEnrIdDuplicatesRegNumberUnderTwoKeys() throws Exception {
        mvc.perform(get("/api/batches/students/92000001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reg_number").value("24920000001"))
           .andExpect(jsonPath("$.nmms_reg_number").value("24920000001"));
    }

    @Test
    void studentInfoByEnrIdNotFoundUsesMessageKey() throws Exception {
        mvc.perform(get("/api/batches/students/99999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Student not found"));
    }

    @Test
    void batchesByCohortBatchSideReturnsSelectStarShape() throws Exception {
        // Distinct from classroom-side (batch_id+batch_name only) -- ground truth §7 quirk 5.
        mvc.perform(get("/api/batches/500/batches").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].created_at").exists())
           .andExpect(jsonPath("$[0].medium").exists());
    }

    @Test
    void allBatchesScopedToActiveCohortOnly() throws Exception {
        mvc.perform(get("/api/batches").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.id==920001)]").exists())
           .andExpect(jsonPath("$[?(@.id==920002)]").doesNotExist())
           .andExpect(jsonPath("$[?(@.id==920001)].coordinator_name").value("brCoordinator920"));
    }

    @Test
    void batchByIdReturnsProjectedShape() throws Exception {
        mvc.perform(get("/api/batches/920001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.batch_name").value("Active Batch"))
           .andExpect(jsonPath("$.cohort_name").value("Cohort Active 500"));
    }

    @Test
    void batchByIdNotFoundHasTrailingPeriod() throws Exception {
        mvc.perform(get("/api/batches/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found."));
    }

    @Test
    void studentsInBatchReturnsRows() throws Exception {
        mvc.perform(get("/api/batches/920001/students").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].student_name").value("Batch Student 920"))
           .andExpect(jsonPath("$[0].nmms_reg_number").value("24920000001"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchReadsIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/classroom/persistence/BatchReadRepository.java`:
```java
package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

@Repository
public class BatchReadRepository {

    /** Duplicated in BatchController too (convention #13) -- mirrors Node's own duplication of this literal
     *  across batchController.js:3 and the fetchAllBatches query string. */
    static final int COHORT_START_YEAR = 2021;

    private final JdbcClient jdbc;

    public BatchReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public static class CoordinatorRoleNotFoundException extends RuntimeException {}

    public List<Map<String, Object>> coordinators() {
        Long roleId = jdbc.sql("SELECT role_id FROM pp.role WHERE role_name = 'BATCH COORDINATOR'")
                .query(Long.class).optional().orElseThrow(CoordinatorRoleNotFoundException::new);
        return jdbc.sql("""
                SELECT u.user_id AS id, u.user_name AS name
                 FROM pp.user u
                 JOIN pp.user_role ur ON u.user_id = ur.user_id
                 WHERE ur.role_id = :roleId
                """).param("roleId", roleId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<String> batchNames() {
        return jdbc.sql("SELECT batch_name FROM pp.batch ORDER BY batch_name ASC").query(String.class).list();
    }

    public List<Map<String, Object>> allCohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name, start_date, description FROM pp.cohort ORDER BY cohort_number ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> activeCohorts() {
        return jdbc.sql("SELECT * FROM pp.cohort WHERE end_date IS NULL").query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> studentsNotInAnyBatch() {
        return jdbc.sql("""
                SELECT sm.student_id, sm.enr_id, sm.student_name, sm.student_email, sm.contact_no1
                 FROM pp.student_master sm
                 WHERE sm.batch_id IS NULL AND sm.active_yn = 'ACTIVE'
                 ORDER BY sm.student_name
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** fetchStudentInfoByEnrId -- reused verbatim by both endpoint #18 (display) and, in Task 5, by
     *  updateStudentStatusInBatch's enr_id->student_id resolution (ground truth §7 quirk 9). */
    public Optional<Map<String, Object>> studentInfoByEnrId(String enrId) {
        return jdbc.sql("""
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
                 WHERE sm.enr_id = :enrId::numeric
                """).param("enrId", enrId).query((rs, i) -> genericRow(rs)).optional();
    }

    /** batchModel.fetchBatchesByCohortNumber -- SELECT *, batch-module version. Distinct from
     *  ClassroomReadRepository.batchesByCohortClassroomSide (ground truth §7 quirk 5) -- no ORDER BY in Node,
     *  so none here either (verbatim). */
    public List<Map<String, Object>> batchesByCohortBatchSide(String cohortNumber) {
        return jdbc.sql("SELECT * FROM pp.batch WHERE cohort_number = :cohortNumber::integer")
                .param("cohortNumber", cohortNumber).query((rs, i) -> genericRow(rs)).list();
    }

    /** fetchAllBatches -- active-academic-year-cohort-scoped (Firm Decision 4). COHORT_START_YEAR is
     *  string-interpolated into the query text exactly like Node's own hard-coded literal (not a bind param --
     *  it is a server constant, never user input, matching the ground truth's own injection-risk note). */
    public List<Map<String, Object>> allBatches() {
        String sql = """
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
                  AND c.cohort_number = (CAST(SUBSTRING(sc.academic_year FROM 1 FOR 4) AS INTEGER) - %d)
                )
                ORDER BY b.batch_id DESC
                """.formatted(COHORT_START_YEAR);
        return jdbc.sql(sql).query((rs, i) -> genericRow(rs)).list();
    }

    public Optional<Map<String, Object>> batchById(String batchId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, c.cohort_name
                 FROM pp.batch b
                 LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                 WHERE b.batch_id = :batchId::integer
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).optional();
    }

    public List<Map<String, Object>> studentsInBatch(String batchId) {
        return jdbc.sql("""
                SELECT
                   sm.student_id, sm.enr_id, sm.student_name, sm.student_email,
                   sm.contact_no1, sm.active_yn, api.nmms_reg_number
                 FROM pp.student_master sm
                 JOIN pp.applicant_primary_info api ON sm.applicant_id = api.applicant_id
                 WHERE sm.batch_id = :batchId::integer
                 ORDER BY sm.student_name
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java` (10 handlers this task; Tasks 3-5 add the remaining 8 to this same file):
```java
package com.rcf.imas.modules.classroom.web;

import com.rcf.imas.modules.classroom.persistence.BatchReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batches")
@PreAuthorize("hasRole('ADMIN')")   // ground truth: zero Node `authenticate` middleware on this mount -- Firm Decision 1
class BatchController {

    /** Duplicated in BatchReadRepository too (convention #13). */
    static final int COHORT_START_YEAR = 2021;

    private final BatchReadRepository reads;

    BatchController(BatchReadRepository reads) {
        this.reads = reads;
    }

    @GetMapping("/coordinators")
    public List<Map<String, Object>> coordinators() {
        try {
            return reads.coordinators();
        } catch (BatchReadRepository.CoordinatorRoleNotFoundException e) {
            throw ApiException.error(404, "Coordinator role not found");
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/names")
    public List<Map<String, Object>> names() {
        try {
            return reads.batchNames().stream().map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", n);
                m.put("value", n);
                return m;
            }).toList();
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts() {
        try { return reads.allCohorts(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping("/cohorts/active")
    public List<Map<String, Object>> activeCohorts() {
        try { return reads.activeCohorts(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping("/students/unassigned")
    public List<Map<String, Object>> studentsUnassigned() {
        try { return reads.studentsNotInAnyBatch(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    /** endpoint #18: distinct 404 envelope KEY ("message") from Task 5's updateStudentStatusInBatch, which
     *  reuses this same repository lookup but reports 404 under "error" -- convention #7, do not unify. */
    @GetMapping("/students/{enr_id}")
    public Map<String, Object> studentInfo(@PathVariable("enr_id") String enrId) {
        try {
            Map<String, Object> row = reads.studentInfoByEnrId(enrId)
                    .orElseThrow(() -> ApiException.message(404, "Student not found"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reg_number", row.get("nmms_reg_number"));
            out.putAll(row);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/{cohort_number}/batches")
    public List<Map<String, Object>> batchesByCohort(@PathVariable("cohort_number") String cohortNumber) {
        try { return reads.batchesByCohortBatchSide(cohortNumber); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    @GetMapping({"", "/"})
    public List<Map<String, Object>> allBatches() {
        try { return reads.allBatches(); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    /** endpoint #22: "Batch not found." WITH trailing period -- distinct from #24 deleteBatch's "Batch not
     *  found" WITHOUT one (convention #7). */
    @GetMapping("/{batchId}")
    public Map<String, Object> batchById(@PathVariable String batchId) {
        try {
            return reads.batchById(batchId).orElseThrow(() -> ApiException.error(404, "Batch not found."));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @GetMapping("/{batchId}/students")
    public List<Map<String, Object>> studentsInBatch(@PathVariable String batchId) {
        try { return reads.studentsInBatch(batchId); }
        catch (Exception e) { throw ApiException.error(500, "Internal Server Error"); }
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static boolean isBlank(String s) { return s == null || s.isBlank(); }
    @SuppressWarnings("unchecked")
    static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchReadsIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/classroom imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchReadsIT.java
git commit -m "feat(classroom): batch reads incl. active-academic-year-scoped batch list (10 endpoints)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: batch/cohort simple single-statement writes (2 endpoints)

Endpoints #11 (`POST /names`) and #13 (`POST /cohorts`). Pins both `addBatchName` outcomes (201 insert vs 200 silent-conflict, ground truth §7 quirk 8) and `createCohort`'s server-derived `cohort_number` + both duplicate 409s (ground truth §7 quirk 6).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/BatchWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java` (add 2 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchSimpleWritesIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/classroom/BatchSimpleWritesIT.java`:
```java
package com.rcf.imas.modules.classroom;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BatchSimpleWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (930001,'bwAdmin930','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (930001,'Cohort BW930')").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (930001,'Existing Batch 930',930001)").update();

        // Reserved small cohort_number range for createCohort's server-derived year math (77/78/79, distinct
        // from every other test class's range -- same constraint as Task 2's 500/501).
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (77,'Existing Cohort Name 930')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (78,'Different Name 930')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("930001", "bwAdmin930", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch WHERE cohort_number = 930001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (930001,77,78,79)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 930001").update();
    }

    @Test
    void addBatchNameSucceedsWithNewNameUnderCohort() throws Exception {
        String body = """
            {"batch_name":"New Batch 930","cohort_number":930001,"created_by":930001}
            """;
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Batch created successfully"))
           .andExpect(jsonPath("$.batch.batch_name").value("New Batch 930"));
    }

    @Test
    void addBatchNameConflictIsSilent200NotAnError() throws Exception {
        String body = """
            {"batch_name":"Existing Batch 930","cohort_number":930001,"created_by":930001}
            """;
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Batch name already exists for this cohort"));

        Integer count = jdbc.sql("SELECT COUNT(*) FROM pp.batch WHERE cohort_number = 930001 AND batch_name = 'Existing Batch 930'")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1); // no duplicate row inserted
    }

    @Test
    void addBatchNameMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches/names").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void createCohortDerivesCohortNumberFromStartDateYear() throws Exception {
        String body = """
            {"cohort_name":"New Cohort 930","start_date":"2100-06-01","description":"desc"}
            """;
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Cohort created successfully"))
           .andExpect(jsonPath("$.data.cohort_number").value(79)) // 2100 - 2021
           .andExpect(jsonPath("$.data.cohort_name").value("New Cohort 930"));
    }

    @Test
    void createCohortDuplicateNameIs409() throws Exception {
        String body = """
            {"cohort_name":"Existing Cohort Name 930","start_date":"2200-06-01"}
            """;
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Cohort name already exists"));
    }

    @Test
    void createCohortDuplicateYearIs409() throws Exception {
        String body = """
            {"cohort_name":"Brand New Name 930","start_date":"2099-06-01"}
            """; // 2099 - 2021 = 78, already used by "Different Name 930"
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Cohort for year 2099 already exists."));
    }

    @Test
    void createCohortMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches/cohorts").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchSimpleWritesIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/classroom/persistence/BatchWriteRepository.java` (Task 3 portion; Task 5 adds 3 more methods to this same file):
```java
package com.rcf.imas.modules.classroom.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

import static com.rcf.imas.modules.classroom.persistence.ClassroomReadRepository.genericRow;

/** Simple single-autocommit-statement batch/cohort writes -- NOT @Transactional, matching Node (none of
 *  these are wrapped in a transaction there either). Multi-step transactional batch writes (createBatch/
 *  updateBatch/deleteBatch) live in ClassroomWriteRepository instead (Task 4, Firm Decision 5). */
@Repository
public class BatchWriteRepository {

    private final JdbcClient jdbc;

    public BatchWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** insertBatchName() parity: ON CONFLICT (cohort_number,batch_name) DO NOTHING RETURNING * -- a zero-row
     *  result means "already exists" and is NOT an error (ground truth §7 quirk 8) -- returns null, the
     *  controller maps that to a 200 (not 4xx/5xx). */
    public Map<String, Object> insertBatchName(String batchName, String cohortNumber, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.batch (batch_name, cohort_number, created_by, updated_by)
                 VALUES (:batchName, :cohortNumber::integer, :createdBy::numeric, :createdBy::numeric)
                 ON CONFLICT (cohort_number, batch_name) DO NOTHING
                 RETURNING *
                """).param("batchName", batchName).param("cohortNumber", cohortNumber).param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    public boolean cohortNameExists(String cohortName) {
        return jdbc.sql("SELECT 1 FROM pp.cohort WHERE cohort_name = :name").param("name", cohortName)
                .query(Integer.class).optional().isPresent();
    }

    public boolean cohortYearExists(int cohortNumber) {
        return jdbc.sql("SELECT 1 FROM pp.cohort WHERE cohort_number = :n").param("n", cohortNumber)
                .query(Integer.class).optional().isPresent();
    }

    public Map<String, Object> insertCohort(int cohortNumber, String cohortName, String startDate, String description) {
        return jdbc.sql("""
                INSERT INTO pp.cohort (cohort_number, cohort_name, start_date, description)
                 VALUES (:cohortNumber, :cohortName, :startDate::date, :description)
                 RETURNING *
                """).param("cohortNumber", cohortNumber).param("cohortName", cohortName)
                .param("startDate", startDate).param("description", description)
                .query((rs, i) -> genericRow(rs)).single();
    }
}
```

Add to `BatchController.java` (2 new handlers + `writes` field wiring):
```java
    // add field + constructor param:
    private final com.rcf.imas.modules.classroom.persistence.BatchWriteRepository writes;

    BatchController(BatchReadRepository reads, com.rcf.imas.modules.classroom.persistence.BatchWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @PostMapping("/names")
    public org.springframework.http.ResponseEntity<Map<String, Object>> addBatchName(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String batchName = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String createdBy = str(b.get("created_by"));
        // Exact Node validation-error text not given verbatim in the ground truth -- judgment call, flagged in Deferred.
        if (isBlank(batchName) || isBlank(cohortNumber) || isBlank(createdBy)) {
            throw ApiException.error(400, "batch_name, cohort_number and created_by are required");
        }
        try {
            Map<String, Object> row = writes.insertBatchName(batchName, cohortNumber, createdBy);
            if (row == null) {
                return org.springframework.http.ResponseEntity.ok(Map.of("message", "Batch name already exists for this cohort"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Batch created successfully");
            out.put("batch", row);
            return org.springframework.http.ResponseEntity.status(201).body(out);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PostMapping("/cohorts")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createCohort(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String cohortName = str(b.get("cohort_name"));
        String startDate = str(b.get("start_date"));
        String description = str(b.get("description"));
        // Exact Node validation-error text not given verbatim -- judgment call, flagged in Deferred.
        if (isBlank(cohortName) || isBlank(startDate)) {
            throw ApiException.error(400, "cohort_name and start_date are required");
        }
        int year;
        try {
            year = java.time.LocalDate.parse(startDate).getYear();
        } catch (Exception e) {
            throw ApiException.error(400, "Invalid start_date");
        }
        int cohortNumber = year - COHORT_START_YEAR;
        try {
            if (writes.cohortNameExists(cohortName)) throw ApiException.error(409, "Cohort name already exists");
            if (writes.cohortYearExists(cohortNumber)) throw ApiException.error(409, "Cohort for year " + year + " already exists.");
            Map<String, Object> row = writes.insertCohort(cohortNumber, cohortName, startDate, description);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Cohort created successfully");
            out.put("data", row);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchSimpleWritesIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/BatchWriteRepository.java imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchSimpleWritesIT.java
git commit -m "feat(classroom): addBatchName (silent-conflict 200) + createCohort (server-derived cohort_number, dual 409s)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: batch CRUD transactional (3 endpoints)

Endpoints #21 (`POST /`), #23 (`PUT /{batchId}`), #24 (`DELETE /{batchId}`). Adds three `@Transactional` methods to `ClassroomWriteRepository` (Firm Decision 5). Pins Firm Decision 3 (`batch_status` no-op) and Firm Decision 6 (unguarded FK-violation 500 on delete).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/ClassroomWriteRepository.java` (add 3 methods)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java` (add 3 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchCrudIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/classroom/BatchCrudIT.java`:
```java
package com.rcf.imas.modules.classroom;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BatchCrudIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940001,'bcAdmin940','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940002,'bcCoordA940','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (940003,'bcCoordB940','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (940001,'Cohort BC940')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940001,'Pre-existing Batch',940001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (940001, 24940000001)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("940001", "bcAdmin940", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE applicant_id = 940001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 940001").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (SELECT batch_id FROM pp.batch WHERE cohort_number = 940001)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE cohort_number = 940001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 940001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (940001,940002,940003)").update();
    }

    @Test
    void createBatchSucceedsAndAssignsCoordinator() throws Exception {
        String body = """
            {"batch_name":"New BC Batch","cohort_number":940001,"coordinator_id":940002}
            """;
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.batch_name").value("New BC Batch"))
           .andExpect(jsonPath("$.batch_id").exists());

        Integer coordCount = jdbc.sql("""
                SELECT COUNT(*) FROM pp.batch_coordinator_batches bcb
                JOIN pp.batch b ON b.batch_id = bcb.batch_id
                WHERE b.batch_name = 'New BC Batch' AND bcb.user_id = 940002
                """).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(coordCount).isEqualTo(1);
    }

    @Test
    void createBatchMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("batch_name and cohort_number are required"));
    }

    @Test
    void createBatchDuplicateIs409() throws Exception {
        String body = """
            {"batch_name":"Pre-existing Batch","cohort_number":940001}
            """;
        mvc.perform(post("/api/batches").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Batch already exists for this cohort."));
    }

    @Test
    void updateBatchResyncsCoordinatorAndSilentlyDropsBatchStatus() throws Exception {
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (940002, 940001)").update();

        String body = """
            {"batch_name":"Renamed Batch","cohort_number":940001,"coordinator_id":940003,"batch_status":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/940001").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.batch_name").value("Renamed Batch"));
        // batch_status has no column to persist to -- the 200 above with no error IS the pinning assertion
        // (Firm Decision 3): accepted, ignored, no failure.

        Integer oldCoordGone = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id=940001 AND user_id=940002")
                .query(Integer.class).single();
        Integer newCoordPresent = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id=940001 AND user_id=940003")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(oldCoordGone).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(newCoordPresent).isEqualTo(1);
    }

    @Test
    void updateBatchMissingFieldsIs400() throws Exception {
        mvc.perform(put("/api/batches/940001").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields"));
    }

    @Test
    void updateBatchUnknownIdIs404NoTrailingPeriod() throws Exception {
        String body = """
            {"batch_name":"X","cohort_number":940001}
            """;
        mvc.perform(put("/api/batches/999999999").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found"));
    }

    @Test
    void updateBatchDuplicateNameInCohortIs409() throws Exception {
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940002,'Other Batch',940001)").update();
        String body = """
            {"batch_name":"Pre-existing Batch","cohort_number":940001}
            """;
        mvc.perform(put("/api/batches/940002").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Duplicate batch name in cohort."));
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 940002").update();
    }

    @Test
    void deleteBatchSucceedsAndCleansCoordinators() throws Exception {
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (940003,'Deletable Batch',940001)").update();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (940002, 940003)").update();

        mvc.perform(delete("/api/batches/940003").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Batch deleted successfully"))
           .andExpect(jsonPath("$.deleted.batch_name").value("Deletable Batch"));

        Integer coordRows = jdbc.sql("SELECT COUNT(*) FROM pp.batch_coordinator_batches WHERE batch_id = 940003")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(coordRows).isEqualTo(0);
    }

    @Test
    void deleteBatchUnknownIdIs404() throws Exception {
        mvc.perform(delete("/api/batches/999999999").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Batch not found"));
    }

    @Test
    void deleteBatchWithAssignedStudentSurfacesRawFkViolationAs500() throws Exception {
        // Firm Decision 6: no pre-check -- the FK violation itself propagates uncaught to
        // GlobalExceptionHandler's generic fallback.
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (940001, 940001, 94000001, 'FK Guard Student', 'F', 940001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        mvc.perform(delete("/api/batches/940001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"));

        // batch row must still exist -- the transaction rolled back (Firm Decision 5 improvement)
        Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.batch WHERE batch_id = 940001").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillThere).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchCrudIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ClassroomWriteRepository.java` (append to the class, after `deleteClassroom`):
```java
    public record BatchWriteResult(Status status, Map<String, Object> row) {
        public enum Status { OK, CONFLICT, NOT_FOUND }
        static BatchWriteResult ok(Map<String, Object> row) { return new BatchWriteResult(Status.OK, row); }
        static BatchWriteResult conflict() { return new BatchWriteResult(Status.CONFLICT, null); }
        static BatchWriteResult notFound() { return new BatchWriteResult(Status.NOT_FOUND, null); }
    }

    /** createBatch() parity, made genuinely atomic (Firm Decision 5) -- Node ran the insert then the optional
     *  coordinator-assignment as two loose sequential autocommit queries with no transaction at all. */
    @Transactional
    public BatchWriteResult createBatch(String batchName, String cohortNumber, String coordinatorId) {
        boolean exists = jdbc.sql("SELECT 1 FROM pp.batch WHERE batch_name = :name AND cohort_number = :cohort::integer")
                .param("name", batchName).param("cohort", cohortNumber).query(Integer.class).optional().isPresent();
        if (exists) return BatchWriteResult.conflict();

        Map<String, Object> row = jdbc.sql("INSERT INTO pp.batch (batch_name, cohort_number) VALUES (:name, :cohort::integer) RETURNING *")
                .param("name", batchName).param("cohort", cohortNumber).query((rs, i) -> genericRow(rs)).single();

        if (coordinatorId != null && !coordinatorId.isBlank()) {
            Object batchId = row.get("batch_id");
            jdbc.sql("INSERT INTO pp.batch_coordinator_batches (user_id, batch_id) VALUES (:coordinatorId::numeric, :batchId::integer) ON CONFLICT DO NOTHING")
                    .param("coordinatorId", coordinatorId).param("batchId", batchId).update();
        }
        return BatchWriteResult.ok(row);
    }

    /** updateBatch() parity. batch_status is deliberately never read from the caller (Firm Decision 3) --
     *  there is no parameter for it here at all, matching updateBatchDetails' exact 2-column SET list. */
    @Transactional
    public BatchWriteResult updateBatch(String batchId, String batchName, String cohortNumber, String coordinatorId) {
        boolean dup = jdbc.sql("SELECT 1 FROM pp.batch WHERE batch_name = :name AND cohort_number = :cohort::integer AND batch_id != :id::integer")
                .param("name", batchName).param("cohort", cohortNumber).param("id", batchId).query(Integer.class).optional().isPresent();
        if (dup) return BatchWriteResult.conflict();

        Map<String, Object> row = jdbc.sql("UPDATE pp.batch SET batch_name = :name, cohort_number = :cohort::integer WHERE batch_id = :id::integer RETURNING *")
                .param("name", batchName).param("cohort", cohortNumber).param("id", batchId)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return BatchWriteResult.notFound();

        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = :id::integer").param("id", batchId).update();
        if (coordinatorId != null && !coordinatorId.isBlank()) {
            jdbc.sql("INSERT INTO pp.batch_coordinator_batches (user_id, batch_id) VALUES (:coordinatorId::numeric, :id::integer) ON CONFLICT DO NOTHING")
                    .param("coordinatorId", coordinatorId).param("id", batchId).update();
        }
        return BatchWriteResult.ok(row);
    }

    /** deleteBatch() parity. NO pre-check against pp.student_master (Firm Decision 6) -- if any student row
     *  still points at this batch, the DELETE FROM pp.batch statement below throws a raw FK-violation that
     *  propagates OUT of this @Transactional method uncaught, rolling back the coordinator-delete too (an
     *  improvement over Node's non-atomic two-query sequence -- Firm Decision 5), and is caught only by
     *  GlobalExceptionHandler's generic Exception handler -> 500 {error:"Internal Server Error"}. Do NOT add
     *  a try/catch here or in the controller beyond the not-found check -- that would silently change this
     *  preserved-500 behavior into something Node never did. */
    @Transactional
    public BatchWriteResult deleteBatch(String batchId) {
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = :id::integer").param("id", batchId).update();
        Map<String, Object> row = jdbc.sql("DELETE FROM pp.batch WHERE batch_id = :id::integer RETURNING *")
                .param("id", batchId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (row == null) return BatchWriteResult.notFound();
        return BatchWriteResult.ok(row);
    }
```

Add to `BatchController.java` (3 new handlers + `classroomWrites` field wiring):
```java
    // add field + constructor param:
    private final com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository classroomWrites;

    BatchController(BatchReadRepository reads, com.rcf.imas.modules.classroom.persistence.BatchWriteRepository writes,
                     com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository classroomWrites) {
        this.reads = reads;
        this.writes = writes;
        this.classroomWrites = classroomWrites;
    }

    @PostMapping({"", "/"})
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createBatch(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String coordinatorId = str(b.get("coordinator_id"));
        if (isBlank(name) || isBlank(cohortNumber)) {
            throw ApiException.error(400, "batch_name and cohort_number are required");
        }
        try {
            var result = classroomWrites.createBatch(name, cohortNumber, coordinatorId);
            if (result.status() == com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository.BatchWriteResult.Status.CONFLICT) {
                throw ApiException.error(409, "Batch already exists for this cohort.");
            }
            return result.row();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PutMapping("/{batchId}")
    public Map<String, Object> updateBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("batch_name"));
        String cohortNumber = str(b.get("cohort_number"));
        String coordinatorId = str(b.get("coordinator_id"));
        // batch_status is read from the body into NOTHING -- Firm Decision 3, ground truth §7 quirk 2.
        // No column exists to persist it to; it is simply never looked at again below.
        if (isBlank(name) || isBlank(cohortNumber)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            var result = classroomWrites.updateBatch(batchId, name, cohortNumber, coordinatorId);
            var Status = com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository.BatchWriteResult.Status.class;
            if (result.status() == Status.getEnumConstants()[1]) throw ApiException.error(409, "Duplicate batch name in cohort.");
            if (result.status() == Status.getEnumConstants()[2]) throw ApiException.error(404, "Batch not found");
            return result.row();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @DeleteMapping("/{batchId}")
    public Map<String, Object> deleteBatch(@PathVariable String batchId) {
        // Deliberately no broad try/catch here (Firm Decision 6) -- an FK-violation exception from
        // classroomWrites.deleteBatch() must propagate to GlobalExceptionHandler's generic 500, not be
        // rewrapped by an ApiException in this handler.
        var result = classroomWrites.deleteBatch(batchId);
        if (result.status() == com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository.BatchWriteResult.Status.NOT_FOUND) {
            throw ApiException.error(404, "Batch not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Batch deleted successfully");
        out.put("deleted", result.row());
        return out;
    }
```

**Note for the implementer:** the `Status.getEnumConstants()[1]/[2]` indexing above in `updateBatch` is deliberately awkward — replace it with a clean direct enum reference when writing the real file, e.g.:
```java
import com.rcf.imas.modules.classroom.persistence.ClassroomWriteRepository.BatchWriteResult;
// ...
if (result.status() == BatchWriteResult.Status.CONFLICT) throw ApiException.error(409, "Duplicate batch name in cohort.");
if (result.status() == BatchWriteResult.Status.NOT_FOUND) throw ApiException.error(404, "Batch not found");
```
(Shown as an inline reflective workaround above only to keep the diff self-contained without repeating the whole file; use the clean import form in the actual commit.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchCrudIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/classroom imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchCrudIT.java
git commit -m "feat(classroom): transactional batch CRUD (create/update/delete), preserve batch_status no-op + unguarded FK 500

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: student-batch assignment operations (3 endpoints)

Endpoints #16 (`POST /{batchId}/add-students`), #17 (`POST /students/remove`), #26 (`PUT /{batchId}/students/{enr_id}/status`). Pins ground truth §7 quirk 9 (`batchId` accepted but never used to scope the status update; dead `student_id` param branch) and quirk 10 (`removeStudentsFromBatch` ignores `batch_id`), plus quirk 11 (the one handler that leaks `err.message` under `details`).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/persistence/BatchWriteRepository.java` (add 3 methods)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/classroom/web/BatchController.java` (add 3 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchStudentAssignmentIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/classroom/BatchStudentAssignmentIT.java`:
```java
package com.rcf.imas.modules.classroom;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BatchStudentAssignmentIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (950001,'saAdmin950','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (950001,'Cohort SA950')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (950001,'SA Batch',950001)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (950002,'Other SA Batch',950001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (950001, 24950000001)").update();
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_reg_number) VALUES (950002, 24950000002)").update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (950001)").update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id) VALUES (950002)").update();

        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, active_yn)
            VALUES (950001, 950001, 95000001, 'Assignable Student', 'F', 'ACTIVE')
            """).update();
        jdbc.sql("""
            INSERT INTO pp.student_master(student_id, applicant_id, enr_id, student_name, gender, batch_id, active_yn)
            VALUES (950002, 950002, 95000002, 'Status Student', 'M', 950001, 'ACTIVE')
            """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("950001", "saAdmin950", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.applicant_secondary_info WHERE applicant_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (950001,950002)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 950001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 950001").update();
    }

    @Test
    void addStudentsToBatchBulkAssignsAndReturnsCount() throws Exception {
        String body = """
            {"student_ids":[950001]}
            """;
        mvc.perform(post("/api/batches/950001/add-students").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Students successfully assigned to batch"))
           .andExpect(jsonPath("$.count").value(1));

        Integer newBatch = jdbc.sql("SELECT batch_id FROM pp.student_master WHERE student_id = 950001").query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(newBatch).isEqualTo(950001);
    }

    @Test
    void addStudentsMissingIdsIs400() throws Exception {
        mvc.perform(post("/api/batches/950001/add-students").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void removeStudentsIgnoresBatchIdInBodyAndNullsRegardless() throws Exception {
        // batch_id in the body is a MISMATCHED / wrong value on purpose -- ground truth §7 quirk 10:
        // the server never scopes removal by it, only by student_ids.
        String body = """
            {"batch_id":950002,"student_ids":[950002]}
            """;
        mvc.perform(post("/api/batches/students/remove").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Students removed from batch successfully"))
           .andExpect(jsonPath("$.count").value(1));

        Object batchId = jdbc.sql("SELECT batch_id FROM pp.student_master WHERE student_id = 950002").query(Integer.class).optional().orElse(null);
        org.assertj.core.api.Assertions.assertThat(batchId).isNull();
    }

    @Test
    void removeStudentsMissingIdsIs400() throws Exception {
        mvc.perform(post("/api/batches/students/remove").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("student_ids are required"));
    }

    @Test
    void updateStudentStatusIgnoresBatchIdInUrlAndResolvesViaEnrId() throws Exception {
        // batchId in the URL path is a WRONG/unrelated id on purpose -- ground truth §7 quirk 9: it is never
        // used to scope the update, only enr_id matters.
        String body = """
            {"active_yn":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/999999999/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student status updated successfully"));

        String newStatus = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 950002").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(newStatus.trim()).isEqualTo("INACTIVE");
    }

    @Test
    void updateStudentStatusMissingActiveYnIs400() throws Exception {
        mvc.perform(put("/api/batches/950001/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("active_yn is required"));
    }

    @Test
    void updateStudentStatusUnknownEnrIdIs404WithErrorKey() throws Exception {
        String body = """
            {"active_yn":"INACTIVE"}
            """;
        mvc.perform(put("/api/batches/950001/students/99999999/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Student not found")); // "error" key -- distinct from endpoint #18's "message" key
    }

    @Test
    void updateStudentStatusInvalidValueLeaksErrMessageUnderDetails() throws Exception {
        // active_yn CHECK constraint only allows ACTIVE/INACTIVE -- an invalid value throws a raw PG
        // check-violation, caught and surfaced with err.message under "details" (ground truth §7 quirk 11,
        // the ONE handler in this module that does this).
        String body = """
            {"active_yn":"Y"}
            """; // classroom's convention leaking into a student_master column -- exactly the quirk scenario
        mvc.perform(put("/api/batches/950001/students/95000002/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value("Internal Server Error"))
           .andExpect(jsonPath("$.details").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchStudentAssignmentIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `BatchWriteRepository.java` (append to the class):
```java
    public int addStudentsToBatch(String batchId, java.util.List<String> studentIds) {
        return jdbc.sql("UPDATE pp.student_master SET batch_id = :batchId::int WHERE student_id = ANY(:studentIds::bigint[])")
                .param("batchId", batchId).param("studentIds", studentIds.toArray(new String[0])).update();
    }

    /** removeStudentBatchId() parity -- deliberately takes NO batch_id parameter at all (ground truth §7
     *  quirk 10): removal is scoped only by student_ids, never by whatever batch_id the caller sent. */
    public int removeStudentsFromBatch(java.util.List<String> studentIds) {
        return jdbc.sql("UPDATE pp.student_master SET batch_id = NULL WHERE student_id = ANY(:studentIds::bigint[])")
                .param("studentIds", studentIds.toArray(new String[0])).update();
    }

    public Map<String, Object> updateStudentStatus(String newStatus, Object studentId) {
        return jdbc.sql("UPDATE pp.student_master SET active_yn = :status WHERE student_id = :id::numeric RETURNING *")
                .param("status", newStatus).param("id", studentId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }
```

Add to `BatchController.java` (3 new handlers):
```java
    @PostMapping("/{batchId}/add-students")
    public Map<String, Object> addStudentsToBatch(@PathVariable String batchId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> ids = asStringList(b.get("student_ids"));
        // Exact Node validation-error text not given verbatim -- judgment call, flagged in Deferred.
        if (isBlank(batchId) || ids.isEmpty()) throw ApiException.error(400, "batchId and student_ids are required");
        try {
            int count = writes.addStudentsToBatch(batchId, ids);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Students successfully assigned to batch");
            out.put("count", count);
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    @PostMapping("/students/remove")
    public Map<String, Object> removeStudents(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        // batch_id, if present, is read and silently ignored (ground truth §7 quirk 10) -- removal is NOT
        // scoped by batch, matching Node's removeStudentBatchId/removeStudentsFromBatch exactly.
        List<String> ids = asStringList(b.get("student_ids"));
        if (ids.isEmpty()) throw ApiException.error(400, "student_ids are required");
        try {
            int count = writes.removeStudentsFromBatch(ids);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Students removed from batch successfully");
            out.put("count", count);
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** NOTE: batchId is accepted per the route shape but NEVER used to scope the update (ground truth §7
     *  quirk 9) -- preserved verbatim; a student belonging to a different batch than :batchId in the URL
     *  still updates successfully. Reuses reads.studentInfoByEnrId (the same repository method backing
     *  endpoint #18) to resolve enr_id -> student_id, matching Node's own reuse of fetchStudentInfoByEnrId
     *  here (the "dead student_id param" branch, ground truth §7 quirk 9, is never reachable -- Spring's
     *  @PathVariable model has no such param at all, so there is nothing to even omit). */
    @PutMapping("/{batchId}/students/{enr_id}/status")
    public Map<String, Object> updateStudentStatusInBatch(@PathVariable String batchId,
                                                            @PathVariable("enr_id") String enrId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String activeYn = str(b.get("active_yn"));
        if (isBlank(enrId)) throw ApiException.error(400, "student_id or enr_id is required");
        if (isBlank(activeYn)) throw ApiException.error(400, "active_yn is required");
        try {
            Map<String, Object> info = reads.studentInfoByEnrId(enrId)
                    .orElseThrow(() -> ApiException.error(404, "Student not found")); // "error" key -- distinct from endpoint #18
            Object studentId = info.get("student_id");
            Map<String, Object> updated = writes.updateStudentStatus(activeYn, studentId);
            if (updated == null) throw ApiException.error(404, "Student not found");
            return Map.of("message", "Student status updated successfully");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // The ONE handler in this module that echoes err.message under "details" (ground truth §7 quirk 11).
            throw ApiException.error(500, "Internal Server Error").with("details", e.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BatchStudentAssignmentIT` — Expected: PASS.

- [ ] **Step 5: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS (all prior tests + this module's 5 IT classes green).

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/classroom imas-backend/src/test/java/com/rcf/imas/modules/classroom/BatchStudentAssignmentIT.java
git commit -m "feat(classroom): student-batch assignment ops (add/remove/status), preserve batchId/batch_id ignored quirks + err.message leak

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 5 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/classroom` package against this plan + the ground truth, checking:
- **Auth uniformity:** both `ClassroomController` and `BatchController` are `@PreAuthorize("hasRole('ADMIN')")` class-level, no method-level overrides anywhere (unlike Plan 4a's `permitAll()` health-check exception — this module has none). Cross-check all 26 endpoints against the endpoint contract table — no endpoint accidentally left auth-unguarded.
- **`genericRow`'s convention:** confirm the single definition (in `ClassroomReadRepository`) uses `toBigInteger().toString()` (EXAMS-style), NOT `toPlainString()` (Plan 4a's student-module deviation) — and that all three other repositories call it via the same-package static reference, not a second copy-pasted definition.
- **`batch_ids` omitted-vs-empty-array branch:** confirm `ClassroomWriteRepository.updateClassroom`'s `batchIdsProvided` boolean is driven by `Map.containsKey("batch_ids")` in the controller, not by `batchIds != null` — those are NOT equivalent (a JSON body with `"batch_ids": null` would set `containsKey=true` but `batchIds=null`, a genuinely different case Node's `Array.isArray(batch_ids)` check would treat as "not an array" → skip resync; verify the Java mirrors this: `batchIdsProvided && batchIds == null` should behave like the omitted case, not like `[]`). Fix if this edge slipped through.
- **Two independent `getBatchesByCohort` implementations:** confirm `ClassroomReadRepository.batchesByCohortClassroomSide` (`{batch_id,batch_name}`) and `BatchReadRepository.batchesByCohortBatchSide` (`SELECT *`) remain two separate methods on two separate repositories — no accidental unification.
- **Active-cohort scoping (`GET /api/batches`):** confirm the `COHORT_START_YEAR` literal is interpolated into the SQL text (not bound as a param) in exactly one place (`BatchReadRepository.allBatches`), and that the duplicated `static final int` in `BatchController` is genuinely used only by `createCohort`'s arithmetic, matching Node's own two-copies-of-the-same-constant quirk.
- **`batch_status` no-op:** confirm `ClassroomWriteRepository.updateBatch` has no parameter for it at all (not merely "received but ignored" — genuinely absent from the method signature), matching Firm Decision 3.
- **`deleteBatch`'s FK-violation path:** confirm there is NO try/catch anywhere between `ClassroomWriteRepository.deleteBatch` and `GlobalExceptionHandler` that would intercept a `DataIntegrityViolationException` and rewrap it — the whole point of Firm Decision 6 is that it must reach the generic fallback unmodified.
- **Error-envelope key/text asymmetries:** spot-check the full matrix — endpoint #10 vs #26's differing 404 key (`message` vs `error`) for the identical English text; endpoint #22 vs #24's differing trailing-period text for "Batch not found[.]"; the classroom-side `{error: e.getMessage()}` pattern vs the batch-side fixed-string pattern.
- **No cross-repository `@Transactional` self-invocation:** confirm all six `@Transactional` methods live in `ClassroomWriteRepository` and are always invoked from `ClassroomController`/`BatchController` (external beans), never from another method inside `ClassroomWriteRepository` itself (which Spring's default proxy would silently fail to intercept).
- **Numeric serialization spot-check:** `batch_id`/`classroom_id`/`cohort_number`/`subject_id`/`teacher_id`/`platform_id` render as JSON numbers in test assertions (not quoted strings); `created_by`/`updated_by` render as JSON strings.

Update `imas-migration-status` memory: Phase 4b complete, new test count, ready for Phase 4c.

## Deferred / parity decisions carried into this plan

- **Dead `updateCohort` NOT ported** (`batchController.js:238-261` + `batchModel.js:194-209`, `checkCohortDuplicateForUpdate`/`updateCohortDetails`) — never wired into `batchRoutes.js`, no `PUT /api/batches/cohorts/{id}` route exists in Node today. If a future phase needs cohort editing, the ground truth's model-layer SQL is already transcribed and ready to port as a genuinely-new endpoint (not a parity port).
- **`batch_status` is a preserved no-op** (Firm Decision 3) — the request field is accepted and silently discarded, matching the UI's "House status changed to X successfully!" toast being permanently false in Node too (ground truth §7 quirk 2). Not fixed to add a real column; a product decision, out of scope for a parity port.
- **`deleteBatch`'s FK violation surfaces as a raw, opaque `500 {error:"Internal Server Error"}`** — no friendlier pre-check added (Firm Decision 6), matching Node exactly. A future UX improvement could add a pre-check 400 ("N students are still assigned to this batch") but that would be a behavior change, not a port.
- **The phantom frontend route `/api/batches/cohort/{cohortNumber}` is not replicated** (it 404s in the live Node app too — a pre-existing frontend bug, ground truth §1). Not to be confused with the *different, already-shipped* `GET /api/batches/cohort/{cohortNumber}` under Plan 4a's `ApplicantSearchController` (`searchModel.getBatchesByCohort`, `searchModel.js`, `SELECT * FROM pp.batch WHERE cohort_number=$1 ORDER BY batch_id ASC`) — a same-looking path from a completely different Node source file. This plan's own real, batch-module-owned route is `GET /api/batches/{cohort_number}/batches`.
- **Two independently-existing `getBatchesByCohort` implementations are NOT unified** — `ClassroomReadRepository.batchesByCohortClassroomSide` (`{batch_id,batch_name}` only, mounted at `GET /api/classrooms/batches/{cohortNumber}`) and `BatchReadRepository.batchesByCohortBatchSide` (`SELECT *`, mounted at `GET /api/batches/{cohort_number}/batches`) legitimately serve different frontend screens (ground truth §7 quirk 5).
- **`cohort_number` is always server-derived** (`start_date.getFullYear() - COHORT_START_YEAR`), never client-supplied, even though `pp.cohort.cohort_number`'s sequence default (`pp.cohort_seq`) is effectively dead code from this module's perspective — the app always overrides it with an explicit value (ground truth §7 quirk 6).
- **Three unreconciled notions of "active" are preserved, not conflated** (ground truth §7 quirk 7): `getActiveCohorts` filters `end_date IS NULL`; `fetchAllBatches`'s scoping (Firm Decision 4) uses `pp.system_config.is_active`; `pp.cohort.status` (`ACTIVE`/`COMPLETED` CHECK constraint) is never read or written anywhere in this module — a third, entirely unused "active" signal sits on the table, left alone.
- **Unused table columns left unmapped in business logic:** `pp.batch.medium` (default `'KANNADA'`) and `pp.batch.house_name`; `pp.cohort.status` and `pp.cohort.current_grade`. All four exist on the tables and will appear in any `SELECT *` response (e.g. `activeCohorts()`, `batchesByCohortBatchSide()`, the create/update-batch `RETURNING *` payloads) but no endpoint in this module explicitly reads or writes them as business fields.
- **Classroom-side 500 bodies use `e.getMessage()` (the raw driver/runtime error), not a fixed string** — a judgment call (convention #7), since the ground truth's §4 table shows a bare `500 {error}` for every one of the 8 classroom endpoints with no literal text ever quoted, the only interpretation consistent with "no fixed text exists to quote."
- **Several batch-side 400s use an assumed message text** (not given verbatim in the ground truth): `addBatchName`'s 400 (missing `batch_name`/`cohort_number`/`created_by`), `createCohort`'s 400 (missing `cohort_name`/`start_date`), and `addStudentsToBatch`'s 400 (missing `batchId`/`student_ids`). All three are flagged inline in their controller code with a comment; if the real Node source text is ever recovered (e.g. from a fuller re-read of `batchController.js`), these three strings are the ones to double-check and correct.
- **`createClassroom`'s dual `created_by`/`updated_by` INSERT params are assumed to both come from a single `created_by` request field** — the ground truth's SQL shows two separate bind params (`$7`, `$8`) but does not disambiguate whether Node's controller reads one or two distinct body fields. Flagged inline; low risk (both values are typically identical on initial insert in every other module ported so far).
- **ADMIN enforcement on all 26 endpoints is NEW** vs Node's fully-open routes (audit CRITICAL, per Firm Decision 1) — add to the fetch audit alongside Plan 4a's Firm Decision 1 and Plan 3d's Results-module note.
