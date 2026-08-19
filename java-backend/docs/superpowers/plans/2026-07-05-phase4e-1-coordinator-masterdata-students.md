# IMAS Spring Boot Migration — Plan 4e-1: Coordinator Master Data + Students + Institutes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the first 14 of the Node `coordinatorRoutes.js` module's 37 endpoints — institutes search, students CRUD-lite (list/update/mark-inactive/inactive-history), cohorts, batches, classrooms (list/create/lookup), teachers, platforms, subjects — to a new `com.rcf.imas.modules.coordinator` module, preserving exact SQL, response shapes, status codes and per-endpoint error envelopes, and closing the one real security gap in this slice: `updateStudentModel`'s unwhitelisted dynamic-column `SET` clause.

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `coordinator` with one controller (`web/CoordinatorController.java`) and two repositories (`persistence/CoordinatorReadRepository.java`, `persistence/CoordinatorWriteRepository.java`). No new Flyway migration — every table used by this slice (`pp.student_master`, `pp.inactive_students`, `pp.cohort`, `pp.batch`, `pp.batch_coordinator_batches`, `pp.classroom`, `pp.classroom_batch`, `pp.subject`, `pp.teacher`, `pp.teaching_platform`, `pp.institute`) already exists in `V1__baseline.sql`.

**Tech Stack (no additions):** Plain `JdbcClient`, already on the classpath. No new Maven dependency — this slice generates no files (no CSV/PDF/XLSX).

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Phases 0/1/2a/2b/2c/3a/3b/3c/3d/4a/4b/4c/4d are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`, `@AuthenticationPrincipal JwtService.FinalToken`), `SecurityConfig` (method security + `.anyRequest().authenticated()` at the filter-chain layer — a method-level `permitAll()` alone is NOT enough to make a route public, see Task 1), `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM.** `sm.batch_id = :batchId::integer`, `bcb.user_id = :userId::numeric`, `sm.student_id = :studentId::numeric`, `c.cohort_number = :cohortNumber::integer`, etc. Java JDBC binds an unqualified string param as `VARCHAR`; Postgres will not implicitly compare `VARCHAR = numeric/integer`.
> 3. **Numeric-column serialization — EXAMS/classroom-style `toBigInteger()`, not Plan 4a's `toPlainString()`.** Every numeric column touched by this slice (`student_id numeric(14,0)`, `applicant_id numeric(14,0)`, `enr_id numeric(11,0)`, `created_by`/`updated_by numeric(8,0)`, `user_id numeric`) is a whole-number id — no genuinely fractional numeric output anywhere in these 14 endpoints (attendance percentages belong to Plan 4e-2, out of scope here). `genericRow`'s `NUMERIC`/`DECIMAL` branch uses `bd.toBigInteger().toString()`. Plain `integer` columns (`batch_id`, `classroom_id`, `subject_id`, `teacher_id`, `platform_id`, `cohort_number`) serialize as **native JSON numbers** via the `else -> rs.getObject(i)` passthrough branch — do **not** force them to String.
> 4. **DATE → `"yyyy-MM-dd"`. TIMESTAMP → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).** No `TIME` columns appear in this slice's SQL. Everything else passes through `rs.getObject(i)`. Map keys are literal snake_case (the SQL column alias, verbatim, via `LinkedHashMap` so key order matches the `SELECT` list).
> 5. **snake_case JSON** global default. Request bodies read as `@RequestBody Map<String,Object>`; no bespoke request DTOs in this module.
> 6. **Auth:** class-level `@PreAuthorize("isAuthenticated()")` on `CoordinatorController` — Node's `authenticate` middleware gates every route in this router file except the bare `GET /` (see Task 1). This is **not** `hasRole('ADMIN')`, unlike the classroom/batch/exams modules — Node's `authenticate` accepts **any** valid JWT regardless of `role_name`, and no route in `coordinatorRoutes.js` layers a role check on top. Reproducing `hasRole('ADMIN')` here would be a **behavior regression** (locking out real coordinators, who by construction do not hold the ADMIN role) — match Node's actual gate, not the stricter pattern used by admin-only modules.
> 7. **Transactions:** `markStudentInactive` (used by both the direct `PUT /students/:id/inactive` endpoint and the inactive-branch of `PUT /students/:id`) is `@Transactional` in `CoordinatorWriteRepository` — two statements (`INSERT pp.inactive_students`, `UPDATE pp.student_master`). Node runs these as two loose sequential `pool.query` calls with **no** transaction at all (ground truth §7); wrapping them is a deliberate, documented improvement (a mid-failure in Node can leave an orphan history row with no matching master flip, or vice versa), not something any caller depends on. `createClassroom` and `updateStudent` (the non-inactive branch) are single-statement writes — no `@Transactional` needed.
> 8. **`updateStudentModel`'s dynamic `SET` clause is column-injection-shaped in Node** (`SET ${key} = $n` built from arbitrary request-body JSON keys, ground truth §8.10) — the Java port uses a **hard, closed whitelist** (`StudentUpdatableColumn` enum). Any request-body key **not** in the whitelist is silently ignored, never interpolated into SQL. See Task 4 for the final column list and the client-form evidence behind it.
> 9. **`createClassroom`'s `created_by`/`updated_by` are trusted from the request body**, not derived from the authenticated principal (ground truth §8.9) — Node's `classroomModel.createClassroom` does this and the plan preserves it verbatim per the "flag, don't silently fix" instruction for that quirk. Do **not** wire `principal.userId()` into this one write.
> 10. **`getTeachers` (route #12) returns `teacher_name` ONLY, no `teacher_id`** (`SELECT teacher_name FROM pp.teacher`, no `ORDER BY`) — a display-only dropdown, reproduced literally, not "fixed" to include an id.
> 11. **`active_yn` casing is table-specific, not a shared enum** (ground truth §8.14): `pp.student_master.active_yn` is `varchar(10)` valued `'ACTIVE'`/`'INACTIVE'`; `pp.classroom.active_yn` is `char(1)` valued `'Y'`/`'N'`. Both are passed through as plain strings (no Java enum), matching each column's own convention.
> 12. **Test isolation:** all `*IT` extend `PgIntegrationTest`, `@AutoConfigureMockMvc`. `@AfterEach` cleans children-before-parents. FK chain to respect for seeds: `pp."user"` → `pp.cohort` → `pp.batch` (FK `cohort_number` CASCADE) → `pp.batch_coordinator_batches` (junction, FKs to `"user"`/`batch`, no `ON DELETE`) → `pp.classroom` (FKs `subject_id`/`teacher_id`/`platform_id` SET NULL, `created_by`/`updated_by` to `"user"`) → `pp.classroom_batch` (junction, both FKs CASCADE) → `pp.student_master` (FK `batch_id` **no `ON DELETE`** i.e. RESTRICT; FK `applicant_id` to `pp.applicant_primary_info`, nullable — **leave `applicant_id` NULL in every seed** to avoid needing an `applicant_primary_info` row) → `pp.inactive_students` (FK `student_id`, no `ON DELETE`). Advance every sequence (`setval`) after an explicit-PK insert. Distinct numeric-prefix ranges per task (below) to avoid any cross-class collision in the shared embedded-Postgres JVM.
> 13. **`pp."user"`** is a quoted reserved word; unquoted `pp.user` (after the dot) is accepted by Postgres.

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase4e-coordinator-ground-truth.md` (§1 rows #1-14, §2, §3, §5, §7, §8.3, §8.9, §8.10, §8.13, §8.14). Node source (read to the bottom of each file — the live code sits below 2-3 stacked commented-out prior versions, ground truth §0):
- `server/routes/coordinatorRoutes.js` (live router: lines 381-567; mount `app.use("/api/coordinator", coordinatorRoutes)`).
- `server/controllers/coordinator/{studentController,cohortController,batchController,instituteController,subjectController,classroomController}.js`.
- `server/models/coordinator/{studentModel,cohortModel,batchModel,instituteModel,subjectModel,classroomModel}.js`.
- `client/src/pages/Coordinator/BatchManagement.js` (the coordinator student-edit form — source of the Task 4 whitelist, and of two corrections to the ground truth, see below).

### Two disagreements between the ground-truth doc and the live Node source (adjudicate before/while implementing)

1. **`markInactiveController`'s missing-reason 400 uses `{error:...}`, not `{message:...}`.** Ground truth §5 states this case is `{message:"..."}`. The actual live code (`server/controllers/coordinator/studentController.js:283`) is:
   ```js
   if (!inactive_reason || inactive_reason.trim() === "") {
     return res.status(400).json({ error: "Inactive reason is required" });
   }
   ```
   This plan follows the **live source** (`{error:"Inactive reason is required"}`) — Task 5 pins it with a test.
2. **`instituteController.searchInstitutes`'s 500 envelope is `{success:false, message:"..."}`, not the `{error:...}` shape the ground truth's §5 generic-pattern row implies for "most" catch blocks.** Live source (`server/controllers/coordinator/instituteController.js:19-24`):
   ```js
   } catch (error) {
     console.error("Error in searchInstitutes controller:", error);
     res.status(500).json({ success: false, message: "Failed to search institutes. Please try again." });
   }
   ```
   Task 1 reproduces this exact two-key shape, not a bare `{error:...}`.

Two more minor deltas worth recording (not contradictions, just detail the ground truth's summary table smooths over):
- `batchController.fetchBatches`'s 500 catch includes an extra `details: err.message` key (`server/controllers/coordinator/batchController.js:29-32`: `res.status(500).json({ error: "Failed to fetch batches", details: err.message })`) — reproduced via `ApiException.error(500,"Failed to fetch batches").with("details", e.getMessage())`.
- `getStudentsController`'s `classroomId` query param is destructured but **never used** by any live branch (`getActiveStudentsForAttendance`'s live 2-arg signature ignores it — ground truth §0/§8.3's "read to the bottom of the file" applies here too: an earlier, now-dead, 3-arg version of that function did use it). The Java controller accepts `classroomId` as a request param for wire-compatibility but never passes it to the repository — documented as dead-but-accepted in Deferred.

### Table facts used (from `V1__baseline.sql`, all pre-existing — see LOCKED CONVENTIONS #12 for the FK chain)

| Table | PK | Notable columns |
|---|---|---|
| `pp.student_master` | `student_id numeric(14,0)` | `active_yn varchar(10)` CHECK ACTIVE/INACTIVE; `gender char(1)` CHECK M/F/O; `batch_id integer` FK no-ON-DELETE; `applicant_id numeric(14,0)` FK nullable |
| `pp.inactive_students` | none (append-only log) | `student_id numeric(14,0)` FK no-ON-DELETE; `inactive_reason varchar(200)`, `inactive_date date` |
| `pp.cohort` | `cohort_number integer` (seq `pp.cohort_seq`) | `cohort_name varchar(100)` |
| `pp.batch` | `batch_id integer` (seq) | `cohort_number integer` FK CASCADE |
| `pp.batch_coordinator_batches` | composite `(user_id, batch_id)` | junction, no seq |
| `pp.classroom` | `classroom_id integer` (seq) | `active_yn char(1)` CHECK Y/N default 'Y'; FKs `subject_id`/`teacher_id`/`platform_id` SET NULL |
| `pp.classroom_batch` | composite `(classroom_id, batch_id)` | junction, both FKs CASCADE |
| `pp.subject` | `subject_id integer` (seq) | `subject_code varchar(5)`, `subject_name varchar(100)` |
| `pp.teacher` | `teacher_id integer` (seq) | `teacher_name varchar(150)` |
| `pp.teaching_platform` | `platform_id integer` (seq) | `platform_name varchar(100)` |
| `pp.institute` | `institute_id numeric(14,0)` (seq) | `dise_code varchar(15)`, `institute_name varchar(200)`, `institute_board varchar(20)`, `management_type varchar(50)` |

### Endpoint contract (14 routes, all `/api/coordinator`)

| # | Method + Path | Success | Errors |
|---|---|---|---|
| 1 | GET `/` (and `/`, no trailing-slash variant) | `200 "Coordinator Home"` text/plain — **no auth required** (Node has no `authenticate` on this one route) | — |
| 2 | GET `/institutes/search?q=` | `200 [{dise_code,institute_name,institute_board,management_type}]`, LIMIT 15; `200 []` if `q` missing/blank/<3 trimmed chars | `500 {success:false,message:"Failed to search institutes. Please try again."}` |
| 3 | GET `/students?cohortNumber&batchId&classroomId&isAttendance` | `200 [...]` (shape varies by branch, see Task 2) | `500 {error:"Failed to fetch students"}` |
| 4 | PUT `/students/{id}` | `200 {message:"Student updated successfully"}` OR `200 {message:"Student marked inactive successfully"}` (inactive branch) | `500 {error:"Failed to update student"}` |
| 5 | PUT `/students/{id}/inactive` | `200 {message:"Student marked inactive successfully"}` | `400 {error:"Inactive reason is required"}`; `500 {error:"Failed to mark student inactive"}` |
| 6 | GET `/students/{id}/inactive-history` | `200 [{inactive_reason,inactive_date,created_by,updated_by}]` ordered `inactive_date DESC` | `500 {error:"Failed to fetch inactive history"}` |
| 7 | GET `/cohorts` | `200 [{cohort_number,cohort_name}]` DISTINCT, scoped to coordinator's batches | `500 {error:"Failed to fetch cohorts"}` |
| 8 | GET `/batches?cohort_number=` | `200 [{batch_id,batch_name,cohort_number,cohort_name}]`, scoped to coordinator | `500 {error:"Failed to fetch batches",details:<message>}` |
| 9 | GET `/classrooms/{batchId}` | `200 [{classroom_id,classroom_name,class_link}]`, active (`active_yn='Y'`) only | `500 {error:"Failed to fetch classrooms"}` |
| 10 | GET `/classrooms` | `200 [{classroom_id,classroom_name,description,active_yn}]`, all statuses | `500 {error:"Failed to fetch classrooms"}` |
| 11 | POST `/classrooms` | `201 {classroom_id}` | `500 {error:"Failed to create classroom"}` |
| 12 | GET `/teachers` | `200 [{teacher_name}]` — **names only, no id** (quirk #10 above) | `500 {error:"Failed to fetch teachers"}` |
| 13 | GET `/platforms` | `200 [{platform_id,platform_name}]` ordered by name | `500 {error:"Failed to fetch platforms"}` |
| 14 | GET `/subjects` | `200 [*subject columns]` (`SELECT *`) ordered by name | `500 {error:"Internal server error"}` |

## Firm decisions

1. **Auth: `isAuthenticated()`, not `hasRole('ADMIN')`.** See LOCKED CONVENTIONS #6.
2. **genericRow passthrough, not typed DTOs.** `numeric(x,0)` ids (`student_id`, `enr_id`, `applicant_id`) → String; plain `integer` ids (`batch_id`, `classroom_id`, `subject_id`, `teacher_id`, `platform_id`, `cohort_number`) → native JSON number via `rs.getObject(i)`.
3. **`PUT /students/:id` uses a hard whitelist enum** (`StudentUpdatableColumn`) for the dynamic `SET` clause — see Task 4 for the finalized 22-column list and the client-form evidence.
4. **`markStudentInactive` is `@Transactional`** (both call sites) — a deliberate improvement over Node's non-atomic two-query sequence.
5. **Dead code, NOT ported:** `inactiveStudentModel.js` (`insertInactiveStudent`, unused duplicate); `studentController1.js` (calls two functions that don't exist in the live `studentModel.js` exports — would throw at runtime if ever invoked); `teacherModel.getTeachersByCoordinator` (references non-existent `pp.batch.coordinator_id`, would throw a column-does-not-exist error); the student-self-service exports on `studentController.js`/`studentModel.js` (`getStudentProfile`, `getMySchedule`, `getStudentSummary`, etc.) — **already ported** under `com.rcf.imas.modules.student` (`StudentPortalController`, Plan 4a), not re-ported here; they belong to a different Node router (`server/routes/studentRoutes.js`), not `coordinatorRoutes.js`, despite living in the same Node controller/model files.
6. **Coordinator scoping via `pp.batch_coordinator_batches`**, `user_id` = `principal.userId()` from the JWT, never a client-supplied param.
7. **`/institutes/search` min-3-chars guard reproduced exactly**: `q` missing, blank, or `<3` chars after `.trim()` → `200 []` (not an error, not a 400).
8. **`getStudentsController`'s cohort-only branch is implemented as a direct SQL `WHERE c.cohort_number = ...` clause** (a new `studentsByCoordinatorAndCohort` repository method), not Node's fetch-all-then-`Array.filter`-in-JS shape (ground truth §8.13: "simplicity and parity agree here, same output, better SQL").
9. **`createClassroom`'s `created_by`/`updated_by` stay body-sourced** (LOCKED CONVENTIONS #9) — not `principal.userId()`.

## Deferred / Flagged (for the reader of this plan, not implemented here)

- The `classroomId` query param on `GET /students` is accepted (wire compatibility) but never used — dead in live Node too (see "disagreements" section above).
- `GET /batches`'s Node-side `if (!req.user || !req.user.user_id) return 401 {error:"Unauthorized..."}` guard has no Java equivalent — Spring Security's filter chain rejects any unauthenticated request before the controller method runs (401, generic body), so this branch is structurally unreachable once `@PreAuthorize("isAuthenticated()")` is in place. Not implemented; noted so a reviewer doesn't go looking for a dead 401 branch.
- The `LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'` in `studentsByCohortAndBatch`/`studentsByCoordinator`/`studentsByCoordinatorAndCohort` has no de-dup logic — if a student has accumulated more than one `inactive_students` row (the table has no unique constraint, ground truth §3), the LEFT JOIN fans out and the same student appears more than once in the JSON array. This is a preserved Node bug (same root cause as ground truth §4.6's reporting double-count), not fixed here — flagged for whoever eventually owns `pp.inactive_students` history hygiene.
- `student_email_password` is **excluded** from the Task 4 whitelist (security-review override): the client round-trips it read-only/unchanged, so excluding it is wire-identical for the frozen client while preventing any authenticated caller from overwriting a student's email password via this endpoint. See Task 4.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/coordinator/
├── web/CoordinatorController.java                 (all 14 endpoints, isAuthenticated())
├── persistence/CoordinatorReadRepository.java      (defines the module's genericRow; Tasks 1-2)
└── persistence/CoordinatorWriteRepository.java     (Tasks 3-5)

imas-backend/src/test/java/com/rcf/imas/modules/coordinator/
├── CoordinatorMasterDataIT.java     (Task 1: home, institutes/search, teachers, platforms, subjects, classrooms×2)
├── CoordinatorScopedReadsIT.java    (Task 2: cohorts, batches, students×4 branches)
├── CoordinatorClassroomCreateIT.java (Task 3: POST /classrooms)
├── CoordinatorStudentUpdateIT.java  (Task 4: PUT /students/:id whitelist + inactive branch)
└── CoordinatorInactiveFlowIT.java   (Task 5: PUT /students/:id/inactive + GET inactive-history)

imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java   (Modify, Task 1: permitAll for GET /api/coordinator, /api/coordinator/)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → run full suite (regression) → commit. Serialize tasks (no parallel implementers — git index races).
- Seed-ID prefix per task (avoids cross-class collision in the shared embedded-Postgres JVM): Task 1 `965 1xx`; Task 2 `965 2xx`; Task 3 `965 3xx`; Task 4 `965 4xx`; Task 5 `965 5xx` (written below without the space, e.g. `965101`).
- Commit message trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

## Task 1: module skeleton, `genericRow`, public health route, and the 6 unscoped lookups

Establishes `CoordinatorReadRepository`'s `genericRow` (classroom/exams-style `toBigInteger()`) and the controller skeleton. Pins: the `GET /` route's public (no-JWT) access — which requires **both** a method-level `@PreAuthorize("permitAll()")` override **and** a `SecurityConfig` `permitAll()` matcher (method security alone does not bypass `.anyRequest().authenticated()` at the filter-chain layer); the `/institutes/search` min-3-chars guard and its non-standard `{success,message}` 500 envelope; `/teachers`' names-only shape; `/classrooms` (all) vs `/classrooms/{batchId}` (active-only) distinction.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorMasterDataIT.java`

- [ ] **Step 1: Write the failing integration test**

`imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorMasterDataIT.java`:
```java
package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorMasterDataIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965101,'coordUser965101','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(institute_id, dise_code, institute_name, institute_board, management_type) VALUES (965101,'DISE965101','Coordinator Test School 965101','STATE','GOVERNMENT')").update();
        jdbc.sql("INSERT INTO pp.institute(institute_id, dise_code, institute_name, institute_board, management_type) VALUES (965102,'DISE965102','Unrelated School Zzz','STATE','PRIVATE')").update();
        jdbc.sql("SELECT setval('pp.institute_id_seq', (SELECT MAX(institute_id)::bigint FROM pp.institute))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965101,'Coordinator Test Teacher 965101')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (965101,'Coordinator Test Platform 965101')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965101,'CT1','Coordinator Test Subject 965101')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965101,'Coordinator Cohort 965101')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965101,'Coordinator Test Batch 965101',965101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, description, active_yn, class_link) VALUES (965101,'Active Classroom 965101','desc-a','Y','https://x/active')").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, description, active_yn, class_link) VALUES (965102,'Inactive Classroom 965102','desc-b','N','https://x/inactive')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965101,965101)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965102,965101)").update();

        coordToken = jwt.issueFinalToken("965101", "coordUser965101", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965101").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 965101").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965101").update();
        jdbc.sql("DELETE FROM pp.institute WHERE institute_id IN (965101,965102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965101").update();
    }

    @Test
    void homeIsPublicNoTokenNeeded() throws Exception {
        mvc.perform(get("/api/coordinator/"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/plain"))
           .andExpect(content().string("Coordinator Home"));
    }

    @Test
    void otherRoutesRequireAuth() throws Exception {
        mvc.perform(get("/api/coordinator/subjects")).andExpect(status().isUnauthorized());
    }

    @Test
    void instituteSearchUnderThreeCharsReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search").param("q", "DI")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void instituteSearchMissingQReturnsEmptyArray() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void instituteSearchThreePlusCharsMatchesDiseOrName() throws Exception {
        mvc.perform(get("/api/coordinator/institutes/search").param("q", "965101")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].dise_code").value("DISE965101"))
           .andExpect(jsonPath("$[0].institute_name").value("Coordinator Test School 965101"));
    }

    @Test
    void teachersReturnsNameOnlyNoId() throws Exception {
        mvc.perform(get("/api/coordinator/teachers").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.teacher_name=='Coordinator Test Teacher 965101')]").exists())
           .andExpect(jsonPath("$[0].teacher_id").doesNotExist());
    }

    @Test
    void platformsReturnsIdAndName() throws Exception {
        mvc.perform(get("/api/coordinator/platforms").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.platform_id==965101)].platform_name").value("Coordinator Test Platform 965101"));
    }

    @Test
    void subjectsReturnsAllColumns() throws Exception {
        mvc.perform(get("/api/coordinator/subjects").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.subject_id==965101)].subject_code").value("CT1"))
           .andExpect(jsonPath("$[?(@.subject_id==965101)].subject_name").value("Coordinator Test Subject 965101"));
    }

    @Test
    void allClassroomsReturnsBothStatuses() throws Exception {
        mvc.perform(get("/api/coordinator/classrooms").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.classroom_id==965101)].active_yn").value("Y"))
           .andExpect(jsonPath("$[?(@.classroom_id==965102)].active_yn").value("N"));
    }

    @Test
    void classroomsByBatchReturnsActiveOnly() throws Exception {
        mvc.perform(get("/api/coordinator/classrooms/965101").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].classroom_id").value(965101))
           .andExpect(jsonPath("$[0].class_link").value("https://x/active"));
    }
}
```

- [ ] **Step 2: Run — confirm it FAILS (module doesn't exist yet)**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorMasterDataIT`

Expected: compile failure (no `com.rcf.imas.modules.coordinator` package yet).

- [ ] **Step 3: Implement `CoordinatorReadRepository`**

`imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReadRepository.java`:
```java
package com.rcf.imas.modules.coordinator.persistence;

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
public class CoordinatorReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcClient jdbc;

    public CoordinatorReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the coordinator module (LOCKED CONVENTIONS #3): EXAMS/classroom-style
     * bd.toBigInteger().toString() for NUMERIC/DECIMAL -- every numeric column in this 14-endpoint slice
     * (student_id, applicant_id, enr_id, created_by, updated_by) is a whole-number id, no fractional output.
     * integer columns (batch_id, classroom_id, subject_id, teacher_id, platform_id, cohort_number) pass
     * through natively via rs.getObject(i). Package-private static so CoordinatorWriteRepository reuses it.
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
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i); val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    /** searchInstitutesModel -- ILIKE on dise_code OR institute_name, LIMIT 15. Caller (controller) applies
     *  the min-3-chars guard and returns [] without calling this method at all. */
    public List<Map<String, Object>> instituteSearch(String term) {
        return jdbc.sql("""
                SELECT dise_code, institute_name, institute_board, management_type
                FROM pp.institute
                WHERE dise_code ILIKE :term OR institute_name ILIKE :term
                ORDER BY institute_name ASC
                LIMIT 15
                """).param("term", "%" + term + "%").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getTeachers -- names only, no id, no ORDER BY (ported literally, ground truth §8.8). */
    public List<Map<String, Object>> teachers() {
        return jdbc.sql("SELECT teacher_name FROM pp.teacher").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getPlatforms. */
    public List<Map<String, Object>> platforms() {
        return jdbc.sql("SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** subjectModel.getAllSubjects -- SELECT *. */
    public List<Map<String, Object>> subjects() {
        return jdbc.sql("SELECT * FROM pp.subject ORDER BY subject_name").query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getAllClassrooms -- all statuses. */
    public List<Map<String, Object>> allClassrooms() {
        return jdbc.sql("""
                SELECT classroom_id, classroom_name, description, active_yn
                FROM pp.classroom
                ORDER BY classroom_name
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** classroomModel.getClassroomsByBatch -- active_yn='Y' only. */
    public List<Map<String, Object>> classroomsByBatch(String batchId) {
        return jdbc.sql("""
                SELECT c.classroom_id, c.classroom_name, c.class_link
                FROM pp.classroom c
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE cb.batch_id = :batchId::integer AND c.active_yn = 'Y'
                ORDER BY c.classroom_name
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** cohortModel.getCohortsByUser -- DISTINCT, scoped via pp.batch_coordinator_batches. */
    public List<Map<String, Object>> cohortsByUser(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT c.cohort_number, c.cohort_name
                FROM pp.cohort c
                JOIN pp.batch b ON c.cohort_number = b.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE bcb.user_id = :userId::numeric
                ORDER BY c.cohort_number
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** batchModel.getBatchesByCohort -- scoped by cohort AND coordinator. */
    public List<Map<String, Object>> batchesByCohort(String cohortNumber, String coordinatorId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, b.cohort_number, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE b.cohort_number = :cohortNumber::integer AND bcb.user_id = :coordinatorId::numeric
                ORDER BY b.batch_id DESC
                """).param("cohortNumber", cohortNumber).param("coordinatorId", coordinatorId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** batchModel.getAllBatchesForCoordinator. */
    public List<Map<String, Object>> allBatchesForCoordinator(String userId) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name, b.cohort_number, c.cohort_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                WHERE bcb.user_id = :userId::numeric
                ORDER BY b.batch_id DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    private static final String STUDENT_SELECT = """
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
            """;

    /** studentModel.getStudentsByCohortAndBatch. NOTE: the LEFT JOIN inactive_students has no de-dup --
     *  a student with >1 inactive_students row (append-only, no unique constraint) fans out into duplicate
     *  rows here, matching Node's own behavior verbatim (see plan's Deferred section). */
    public List<Map<String, Object>> studentsByCohortAndBatch(String cohortNumber, String batchId) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE c.cohort_number = :cohortNumber::integer AND b.batch_id = :batchId::integer
                ORDER BY sm.student_name
                """).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getStudentsByCoordinator -- ALL of the coordinator's students, any status. */
    public List<Map<String, Object>> studentsByCoordinator(String userId) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE bcb.user_id = :userId::numeric
                ORDER BY sm.student_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** Firm Decision 8 (ground truth §8.13): direct SQL filter, not Node's fetch-all-then-JS-filter. */
    public List<Map<String, Object>> studentsByCoordinatorAndCohort(String userId, String cohortNumber) {
        return jdbc.sql("SELECT " + STUDENT_SELECT + """
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn = 'INACTIVE'
                WHERE bcb.user_id = :userId::numeric AND c.cohort_number = :cohortNumber::integer
                ORDER BY sm.student_name
                """).param("userId", userId).param("cohortNumber", cohortNumber)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getActiveStudentsForAttendance -- STRICTLY active_yn='ACTIVE', narrow column set. */
    public List<Map<String, Object>> activeStudentsForAttendance(String cohortNumber, String batchId) {
        return jdbc.sql("""
                SELECT sm.student_id, sm.enr_id, sm.student_name,
                       sm.contact_no1, sm.student_email, sm.batch_id, sm.active_yn
                FROM pp.student_master sm
                JOIN pp.batch b ON sm.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE c.cohort_number = :cohortNumber::integer
                  AND b.batch_id = :batchId::integer
                  AND sm.active_yn = 'ACTIVE'
                ORDER BY sm.student_name
                """).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** studentModel.getInactiveHistoryByStudentId. */
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

- [ ] **Step 4: Implement `CoordinatorController` (Task 1's 7 routes only)**

`imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`:
```java
package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")   // coordinatorRoutes.js: every route is `authenticate`-gated EXCEPT bare GET "/"
public class CoordinatorController {

    private final CoordinatorReadRepository reads;

    public CoordinatorController(CoordinatorReadRepository reads) { this.reads = reads; }

    /** coordinatorRoutes.js:489-491 -- the ONLY route with no `authenticate` middleware. */
    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    public String home() { return "Coordinator Home"; }

    @GetMapping("/institutes/search")
    public List<Map<String, Object>> instituteSearch(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 3) return List.of();
        try {
            return reads.instituteSearch(q.trim());
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to search institutes. Please try again.").with("success", false);
        }
    }

    @GetMapping("/teachers")
    public List<Map<String, Object>> teachers() {
        try {
            return reads.teachers();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch teachers");
        }
    }

    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        try {
            return reads.platforms();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch platforms");
        }
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        try {
            return reads.subjects();
        } catch (Exception e) {
            throw ApiException.error(500, "Internal server error");
        }
    }

    @GetMapping("/classrooms")
    public List<Map<String, Object>> allClassrooms() {
        try {
            return reads.allClassrooms();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch classrooms");
        }
    }

    @GetMapping("/classrooms/{batchId}")
    public List<Map<String, Object>> classroomsByBatch(@PathVariable String batchId) {
        try {
            return reads.classroomsByBatch(batchId);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch classrooms");
        }
    }
}
```

- [ ] **Step 5: Wire the public health route into `SecurityConfig`**

`imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java` — add one line to the `.authorizeHttpRequests(...)` chain (method-level `@PreAuthorize("permitAll()")` alone does not bypass `.anyRequest().authenticated()` at the filter-chain layer, per the `/api/student` precedent):
```java
                // Public coordinator health check -- CoordinatorController.home(), method-level
                // @PreAuthorize("permitAll()") override, per Plan 4e-1 (coordinatorRoutes.js's ONLY
                // non-authenticate route).
                .requestMatchers(HttpMethod.GET, "/api/coordinator", "/api/coordinator/").permitAll()
```
placed alongside the existing `/api/student`, `/api/student/` line.

- [ ] **Step 6: Run — confirm PASS**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorMasterDataIT`

- [ ] **Step 7: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReadRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorMasterDataIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): module skeleton -- public health route, institutes search, teachers/platforms/subjects, classrooms (all + by-batch)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: coordinator-scoped reads (`/cohorts`, `/batches`, `/students`)

Pins the `pp.batch_coordinator_batches` scoping (a student/cohort/batch belonging to a batch the coordinator is NOT assigned to must never appear), and all 4 branches of `GET /students` (attendance-only-active, cohort+batch, cohort-only, coordinator-wide).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorScopedReadsIT.java`

- [ ] **Step 1: Write the failing integration test**

`imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorScopedReadsIT.java`:
```java
package com.rcf.imas.modules.coordinator;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorScopedReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965201,'coordUser965201','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Two cohorts: 965201 (coordinator IS assigned, via batch 965201), 965202 (coordinator is NOT assigned).
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965201,'Scoped Cohort 965201')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965202,'Unassigned Cohort 965202')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965201,'Assigned Batch 965201',965201)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965202,'Unassigned Batch 965202',965202)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965201,965201)").update();

        // Students: 965211 ACTIVE in assigned batch, 965212 INACTIVE in assigned batch, 965213 ACTIVE in
        // the UNASSIGNED batch (must never appear in any coordinator-scoped result).
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965211,'Active Assigned Student 965211',965201,'F','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965212,'Inactive Assigned Student 965212',965201,'M','INACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965213,'Unassigned Batch Student 965213',965202,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965201", "coordUser965201", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965211,965212,965213)").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (965201,965202)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965201").update();
    }

    @Test
    void cohortsReturnsOnlyAssignedCohort() throws Exception {
        mvc.perform(get("/api/coordinator/cohorts").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].cohort_number").value(965201));
    }

    @Test
    void batchesNoFilterReturnsOnlyAssignedBatch() throws Exception {
        mvc.perform(get("/api/coordinator/batches").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(965201));
    }

    @Test
    void batchesFilteredByCohortNumber() throws Exception {
        mvc.perform(get("/api/coordinator/batches").param("cohort_number", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(965201));
    }

    @Test
    void studentsCohortAndBatchReturnsBothStatuses() throws Exception {
        mvc.perform(get("/api/coordinator/students")
                .param("cohortNumber", "965201").param("batchId", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[?(@.student_id=='965211')]").exists())
           .andExpect(jsonPath("$[?(@.student_id=='965212')]").exists());
    }

    @Test
    void studentsNoFiltersReturnsCoordinatorScopedOnly() throws Exception {
        mvc.perform(get("/api/coordinator/students").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[?(@.student_id=='965213')]").doesNotExist());
    }

    @Test
    void studentsCohortOnlyFiltersByCohort() throws Exception {
        mvc.perform(get("/api/coordinator/students").param("cohortNumber", "965201")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void studentsIsAttendanceModeReturnsStrictlyActiveOnlyWithNarrowShape() throws Exception {
        mvc.perform(get("/api/coordinator/students")
                .param("cohortNumber", "965201").param("batchId", "965201").param("isAttendance", "true")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].student_id").value("965211"))
           .andExpect(jsonPath("$[0].father_name").doesNotExist()); // narrow column set, unlike the other branches
    }
}
```

- [ ] **Step 2: Run — confirm FAIL** (`/cohorts`, `/batches`, `/students` routes don't exist yet — 404)

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorScopedReadsIT`

- [ ] **Step 3: Add the 3 routes to `CoordinatorController`**

Add to `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`, plus the `@AuthenticationPrincipal`/`JwtService` imports:
```java
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
```
```java
    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.cohortsByUser(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch cohorts");
        }
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(name = "cohort_number", required = false) String cohortNumber,
                                               @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            if (cohortNumber != null) {
                return reads.batchesByCohort(cohortNumber, principal.userId());
            }
            return reads.allBatchesForCoordinator(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch batches").with("details", e.getMessage());
        }
    }

    /** getStudentsController -- `classroomId` is accepted for wire compatibility but never used (dead in
     *  live Node too, see plan's "disagreements" section). */
    @GetMapping("/students")
    public List<Map<String, Object>> students(@RequestParam(required = false) String cohortNumber,
                                                @RequestParam(required = false) String batchId,
                                                @RequestParam(required = false) String classroomId,
                                                @RequestParam(required = false) String isAttendance,
                                                @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            if ("true".equals(isAttendance) && cohortNumber != null && batchId != null) {
                return reads.activeStudentsForAttendance(cohortNumber, batchId);
            }
            if (cohortNumber != null && batchId != null) {
                return reads.studentsByCohortAndBatch(cohortNumber, batchId);
            }
            if (cohortNumber != null) {
                return reads.studentsByCoordinatorAndCohort(principal.userId(), cohortNumber);
            }
            return reads.studentsByCoordinator(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch students");
        }
    }
```

- [ ] **Step 4: Run — confirm PASS**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorScopedReadsIT`

- [ ] **Step 5: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorScopedReadsIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): scoped reads -- /cohorts, /batches, /students (4 filter branches) via batch_coordinator_batches

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `POST /classrooms` (create)

Pins the exact response shape (`{classroom_id}` only, `RETURNING classroom_id`, `201`) and the body-sourced `created_by`/`updated_by` quirk (Firm Decision 9 — not `principal.userId()`).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorClassroomCreateIT.java`

- [ ] **Step 1: Write the failing integration test**

`imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorClassroomCreateIT.java`:
```java
package com.rcf.imas.modules.coordinator;

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
class CoordinatorClassroomCreateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965301,'coordUser965301','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965301,'CC1','Create Classroom Subject 965301')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965301,'Create Classroom Teacher 965301')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teaching_platform(platform_id, platform_name) VALUES (965301,'Create Classroom Platform 965301')").update();
        jdbc.sql("SELECT setval('pp.platform_id_seq', (SELECT MAX(platform_id)::bigint FROM pp.teaching_platform))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965301", "coordUser965301", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (SELECT classroom_id FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301')").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301'").update();
        jdbc.sql("DELETE FROM pp.teaching_platform WHERE platform_id = 965301").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965301").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965301").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965301").update();
    }

    @Test
    void createClassroomReturnsClassroomIdOnlyAnd201() throws Exception {
        String body = """
            {"classroom_name":"Created Classroom 965301","subject_id":965301,"teacher_id":965301,
             "platform_id":965301,"class_link":"https://x/created","active_yn":"Y",
             "created_by":965301,"updated_by":965301}
            """;
        String resp = mvc.perform(post("/api/coordinator/classrooms")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.classroom_id").exists())
           .andExpect(jsonPath("$.classroom_name").doesNotExist()) // RETURNING classroom_id ONLY
           .andReturn().getResponse().getContentAsString();

        Integer newId = jdbc.sql("SELECT classroom_id FROM pp.classroom WHERE classroom_name = 'Created Classroom 965301'")
                .query(Integer.class).single();
        String activeYn = jdbc.sql("SELECT active_yn FROM pp.classroom WHERE classroom_id = :id")
                .param("id", newId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Y", activeYn);
        org.junit.jupiter.api.Assertions.assertTrue(resp.contains(String.valueOf(newId)));
    }
}
```

- [ ] **Step 2: Run — confirm FAIL**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorClassroomCreateIT`

- [ ] **Step 3: Implement `CoordinatorWriteRepository.createClassroom` + controller route**

`imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java`:
```java
package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

@Repository
public class CoordinatorWriteRepository {

    private final JdbcClient jdbc;

    public CoordinatorWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** classroomModel.createClassroom parity. created_by/updated_by are trusted from the request body,
     *  NOT derived from the authenticated principal (Firm Decision 9 / LOCKED CONVENTIONS #9) -- a deliberate,
     *  documented, non-"fixed" quirk. Single statement, no @Transactional needed. */
    public Map<String, Object> createClassroom(String classroomName, String subjectId, String teacherId,
                                                 String platformId, String classLink, String activeYn,
                                                 String createdBy, String updatedBy) {
        return jdbc.sql("""
                INSERT INTO pp.classroom
                 (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
                VALUES (:name, :subjectId::integer, :teacherId::integer, :platformId::integer,
                        :classLink, :activeYn, :createdBy::numeric, :updatedBy::numeric)
                RETURNING classroom_id
                """)
                .param("name", classroomName).param("subjectId", subjectId).param("teacherId", teacherId)
                .param("platformId", platformId).param("classLink", classLink).param("activeYn", activeYn)
                .param("createdBy", createdBy).param("updatedBy", updatedBy)
                .query((rs, i) -> genericRow(rs)).single();
    }
}
```

Add to `CoordinatorController` (constructor now takes both repositories):
```java
    private final CoordinatorWriteRepository writes;

    public CoordinatorController(CoordinatorReadRepository reads, CoordinatorWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }
```
```java
    @PostMapping("/classrooms")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createClassroom(@RequestBody Map<String, Object> body) {
        try {
            return writes.createClassroom(
                    (String) body.get("classroom_name"),
                    body.get("subject_id") == null ? null : String.valueOf(body.get("subject_id")),
                    body.get("teacher_id") == null ? null : String.valueOf(body.get("teacher_id")),
                    body.get("platform_id") == null ? null : String.valueOf(body.get("platform_id")),
                    (String) body.get("class_link"),
                    (String) body.get("active_yn"),
                    body.get("created_by") == null ? null : String.valueOf(body.get("created_by")),
                    body.get("updated_by") == null ? null : String.valueOf(body.get("updated_by")));
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to create classroom");
        }
    }
```

- [ ] **Step 4: Run — confirm PASS**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorClassroomCreateIT`

- [ ] **Step 5: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorClassroomCreateIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): POST /classrooms -- create with body-sourced created_by/updated_by (Node parity)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `PUT /students/:id` — whitelisted dynamic update + inactive branch (security-critical)

`updateStudentModel` builds `SET ${key}=$n` directly from arbitrary request-body JSON keys in Node (ground truth §8.10) — no whitelist. This task closes that gap with a hard, closed `StudentUpdatableColumn` enum, and reproduces the inactive-flow branch exactly.

### Final whitelist (21 columns)

`student_name, father_name, father_occupation, mother_name, mother_occupation, gender, student_email, parent_email, contact_no1, contact_no2, home_address, current_institute_dise_code, previous_institute_dise_code, sim_name, teacher_name, teacher_mobile_number, recharge_status, sponsor, photo_link, batch_id, active_yn`

**`student_email_password` is DELIBERATELY EXCLUDED** (a security-review override on top of the client evidence below). Evidence from `client/src/pages/Coordinator/BatchManagement.js`:
- Line 52: `{ id: "student_email_password", label: "Email Password" }` is a real field in the edit form, but rendered **read-only** (`isLocked` list, line 609).
- Line 428: `setEditForm({...s})` clones the ENTIRE fetched student row (including `student_email_password`) into edit-form state; lines 311-316 (`saveEdit`) do NOT strip it, so it round-trips in every `PUT /students/:id` body with its **unchanged** value.

Because the value never changes for the frozen client, excluding this column from the whitelist leaves the resulting DB row **byte-identical** to what Node would write — no wire-observable difference. But including it would let any `isAuthenticated()` caller overwrite an arbitrary student's email-account password (this endpoint has no coordinator-role restriction). That is exactly the "unintended-column overwrite" §8.10 warns about, so it is excluded. A dedicated test (`studentEmailPasswordIsIgnored`) proves a posted `student_email_password` does NOT change the stored value.

Never allowed regardless of what the client sends: `student_id`, `applicant_id`, `enr_id`, `created_by`, `updated_by`, `user_id` — the client also round-trips `student_id`/`applicant_id`/`enr_id` (same `{...s}`-clone mechanism, same "not stripped" reasoning) with their existing unchanged values, but a hard whitelist must reject them outright regardless of value, per the ground truth's explicit instruction (§8.10).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorStudentUpdateIT.java`

- [ ] **Step 1: Write the failing integration test**

`imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorStudentUpdateIT.java`:
```java
package com.rcf.imas.modules.coordinator;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorStudentUpdateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965401,'coordUser965401','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965401,'Update Cohort 965401')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965401,'Update Batch 965401',965401)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn, father_name, contact_no1, student_email_password)
                VALUES (965411,'Update Target Student 965411',965401,'F','ACTIVE','Original Father','9990001111','orig-secret-pw')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("965401", "coordUser965401", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 965411").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 965411").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965401").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965401").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965401").update();
    }

    @Test
    void whitelistedFieldsUpdateSuccessfully() throws Exception {
        String body = """
            {"father_name":"Updated Father","contact_no1":"9998887777"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        String contact = jdbc.sql("SELECT contact_no1 FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Updated Father", fatherName);
        org.junit.jupiter.api.Assertions.assertEquals("9998887777", contact);
    }

    @Test
    void nonWhitelistedStudentIdKeyIsIgnored() throws Exception {
        String body = """
            {"student_id":"999999","father_name":"Attempted PK Overwrite Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        // student_id UNCHANGED (row still addressable at 965411 -- if the PK had been overwritten this
        // lookup would return no rows).
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Attempted PK Overwrite Father", fatherName);
        Integer bogusRowCount = jdbc.sql("SELECT COUNT(*) FROM pp.student_master WHERE student_id = 999999")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, bogusRowCount);
    }

    @Test
    void injectionStyleKeyIsIgnoredNotInterpolated() throws Exception {
        String body = """
            {"created_by; DROP TABLE pp.student_master; --":"x","father_name":"Post Injection Attempt Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        // Table still exists and the legitimate field DID update -- proves the bogus key was silently
        // dropped, never interpolated into SQL.
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Post Injection Attempt Father", fatherName);
    }

    @Test
    void studentEmailPasswordIsIgnored() throws Exception {
        // student_email_password is deliberately NOT in the whitelist (see Task 4). A caller trying to
        // change it must have the attempt silently dropped -- the stored value stays 'orig-secret-pw'.
        String body = """
            {"student_email_password":"hacked-pw","father_name":"Pw Attempt Father"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student updated successfully"));

        String pw = jdbc.sql("SELECT student_email_password FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("orig-secret-pw", pw); // UNCHANGED -- not in whitelist
        String fatherName = jdbc.sql("SELECT father_name FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Pw Attempt Father", fatherName); // whitelisted field DID update
    }

    @Test
    void activeYnInactiveWithReasonRoutesToInactiveBranch() throws Exception {
        String body = """
            {"active_yn":"INACTIVE","inactive_reason":"Moved to another program"}
            """;
        mvc.perform(put("/api/coordinator/students/965411")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student marked inactive successfully"));

        String activeYn = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE", activeYn);

        String reason = jdbc.sql("SELECT inactive_reason FROM pp.inactive_students WHERE student_id = 965411")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Moved to another program", reason);
    }
}
```

- [ ] **Step 2: Run — confirm FAIL**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorStudentUpdateIT`

- [ ] **Step 3: Implement the whitelist enum + write-repo methods**

Add to `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java`:
```java
package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

@Repository
public class CoordinatorWriteRepository {

    private final JdbcClient jdbc;

    public CoordinatorWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ... createClassroom from Task 3 stays here unchanged ...

    /**
     * Hard, closed whitelist for updateStudentModel's dynamic SET clause (LOCKED CONVENTIONS #8, ground
     * truth §8.10). Any request-body key NOT listed here is silently ignored -- never interpolated into SQL.
     * batch_id is the only column needing an explicit ::integer cast (everything else is varchar/char/text,
     * for which Postgres accepts an implicit text bind). Final 21-column list. NOTE: student_email_password
     * is DELIBERATELY EXCLUDED though the client round-trips it -- the form renders it read-only, so the
     * value never changes, and excluding it (a) leaves the DB identical for the frozen client and (b) stops
     * any authenticated caller from overwriting a student's email-account password via this endpoint.
     */
    private enum StudentUpdatableColumn {
        STUDENT_NAME("student_name"), FATHER_NAME("father_name"), FATHER_OCCUPATION("father_occupation"),
        MOTHER_NAME("mother_name"), MOTHER_OCCUPATION("mother_occupation"), GENDER("gender"),
        STUDENT_EMAIL("student_email"),
        PARENT_EMAIL("parent_email"), CONTACT_NO1("contact_no1"), CONTACT_NO2("contact_no2"),
        HOME_ADDRESS("home_address"), CURRENT_INSTITUTE_DISE_CODE("current_institute_dise_code"),
        PREVIOUS_INSTITUTE_DISE_CODE("previous_institute_dise_code"), SIM_NAME("sim_name"),
        TEACHER_NAME("teacher_name"), TEACHER_MOBILE_NUMBER("teacher_mobile_number"),
        RECHARGE_STATUS("recharge_status"), SPONSOR("sponsor"), PHOTO_LINK("photo_link"),
        BATCH_ID("batch_id", "::integer"), ACTIVE_YN("active_yn");

        final String column;
        final String castSuffix;
        StudentUpdatableColumn(String column) { this(column, ""); }
        StudentUpdatableColumn(String column, String castSuffix) { this.column = column; this.castSuffix = castSuffix; }
    }

    /** updateStudentModel parity, whitelist-filtered. inactive_reason is never a real column (excluded by
     *  construction, not by a delete-from-payload step like Node's). active_yn is NOT uppercased here --
     *  the controller normalizes it before calling this method (mirrors Node's payload.active_yn =
     *  payload.active_yn.toUpperCase() happening in the model, but Java keeps that string-massaging in the
     *  controller alongside the inactive-branch decision, since both need the same uppercased value).
     *  No-op (does nothing, no exception) if the payload contains zero whitelisted keys -- Node parity. */
    public void updateStudent(String studentId, Map<String, Object> payload) {
        List<StudentUpdatableColumn> present = new ArrayList<>();
        for (StudentUpdatableColumn col : StudentUpdatableColumn.values()) {
            if (payload.containsKey(col.column)) present.add(col);
        }
        if (present.isEmpty()) return;

        List<String> setFragments = new ArrayList<>();
        for (StudentUpdatableColumn col : present) {
            setFragments.add(col.column + " = :" + col.column + col.castSuffix);
        }
        String sql = "UPDATE pp.student_master SET " + String.join(", ", setFragments)
                + ", updated_at = CURRENT_TIMESTAMP WHERE student_id = :studentId::numeric";

        StatementSpec spec = jdbc.sql(sql).param("studentId", studentId);
        for (StudentUpdatableColumn col : present) {
            Object v = payload.get(col.column);
            spec = spec.param(col.column, v == null ? null : String.valueOf(v));
        }
        spec.update();
    }

    /** markStudentInactiveModel parity, made genuinely atomic (LOCKED CONVENTIONS #7 / Firm Decision 4).
     *  Used by BOTH PUT /students/:id's inactive branch and the direct PUT /students/:id/inactive route. */
    @Transactional
    public void markStudentInactive(String studentId, String reason, String userId) {
        jdbc.sql("""
                INSERT INTO pp.inactive_students (student_id, inactive_reason, inactive_date, created_by, updated_by)
                VALUES (:studentId::numeric, :reason, CURRENT_DATE, :userId::numeric, :userId::numeric)
                """).param("studentId", studentId).param("reason", reason).param("userId", userId).update();

        jdbc.sql("""
                UPDATE pp.student_master SET active_yn = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE student_id = :studentId::numeric
                """).param("studentId", studentId).update();
    }
}
```

- [ ] **Step 4: Add the controller route**

Add to `CoordinatorController`:
```java
    /** updateStudentController parity. Inactive-branch condition matches Node's exact truthiness check:
     *  active_yn present, case-insensitively "INACTIVE", AND inactive_reason present and non-blank. */
    @PutMapping("/students/{id}")
    public Map<String, Object> updateStudent(@PathVariable String id, @RequestBody Map<String, Object> payload,
                                               @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            Object activeYn = payload.get("active_yn");
            Object inactiveReason = payload.get("inactive_reason");
            boolean inactiveBranch = activeYn != null && String.valueOf(activeYn).equalsIgnoreCase("INACTIVE")
                    && inactiveReason != null && !String.valueOf(inactiveReason).isBlank();

            if (inactiveBranch) {
                writes.markStudentInactive(id, String.valueOf(inactiveReason), principal.userId());
                return Map.of("message", "Student marked inactive successfully");
            }

            Map<String, Object> normalized = new java.util.HashMap<>(payload);
            if (activeYn != null) normalized.put("active_yn", String.valueOf(activeYn).toUpperCase());
            writes.updateStudent(id, normalized);
            return Map.of("message", "Student updated successfully");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to update student");
        }
    }
```

- [ ] **Step 5: Run — confirm PASS**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorStudentUpdateIT`

- [ ] **Step 6: Run full suite (regression check on `SecurityConfig`/shared modules)**

`mvn -f C:/work/rcf/imas-backend/pom.xml test`

- [ ] **Step 7: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorWriteRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorStudentUpdateIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): PUT /students/:id -- whitelisted dynamic update (closes Node's unwhitelisted column-injection risk) + inactive-branch routing

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `PUT /students/:id/inactive` (direct mark-inactive) + `GET /students/:id/inactive-history`

Pins the 400 missing-reason envelope (`{error:...}`, per the live-source correction above, not the ground truth's `{message:...}`) and the `inactive_date DESC` history ordering.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorInactiveFlowIT.java`

- [ ] **Step 1: Write the failing integration test**

`imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorInactiveFlowIT.java`:
```java
package com.rcf.imas.modules.coordinator;

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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorInactiveFlowIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965501,'coordUser965501','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965501,'Inactive Flow Cohort 965501')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965501,'Inactive Flow Batch 965501',965501)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965511,'Inactive Flow Student 965511',965501,'M','ACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        // A student with pre-existing history rows, for the GET .../inactive-history ordering test.
        jdbc.sql("""
                INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                VALUES (965512,'History Student 965512',965501,'F','INACTIVE')
                """).update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965512,'Earlier reason', DATE '2025-01-01')").update();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965512,'Later reason', DATE '2025-06-01')").update();

        coordToken = jwt.issueFinalToken("965501", "coordUser965501", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id IN (965511,965512)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965511,965512)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965501").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965501").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965501").update();
    }

    @Test
    void missingReasonIs400WithErrorKey() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Inactive reason is required"))
           .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void blankReasonIs400() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inactive_reason\":\"   \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Inactive reason is required"));
    }

    @Test
    void validReasonMarksInactiveAndLogsHistory() throws Exception {
        mvc.perform(put("/api/coordinator/students/965511/inactive")
                .header("Authorization", "Bearer " + coordToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inactive_reason\":\"Family relocation\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Student marked inactive successfully"));

        String activeYn = jdbc.sql("SELECT active_yn FROM pp.student_master WHERE student_id = 965511")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE", activeYn);

        String reason = jdbc.sql("SELECT inactive_reason FROM pp.inactive_students WHERE student_id = 965511")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("Family relocation", reason);
    }

    @Test
    void inactiveHistoryReturnsOrderedByDateDescending() throws Exception {
        mvc.perform(get("/api/coordinator/students/965512/inactive-history")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].inactive_reason").value("Later reason"))
           .andExpect(jsonPath("$[1].inactive_reason").value("Earlier reason"));
    }
}
```

- [ ] **Step 2: Run — confirm FAIL**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorInactiveFlowIT`

- [ ] **Step 3: Add the 2 controller routes**

Add to `CoordinatorController`:
```java
    /** markInactiveController parity. LIVE-SOURCE CORRECTION vs. the ground truth doc: this 400 uses
     *  {error:...}, NOT {message:...} (server/controllers/coordinator/studentController.js:283) -- see
     *  plan's "disagreements" section. */
    @PutMapping("/students/{id}/inactive")
    public Map<String, Object> markInactive(@PathVariable String id, @RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal JwtService.FinalToken principal) {
        Object reason = body.get("inactive_reason");
        if (reason == null || String.valueOf(reason).isBlank()) {
            throw ApiException.error(400, "Inactive reason is required");
        }
        try {
            writes.markStudentInactive(id, String.valueOf(reason), principal.userId());
            return Map.of("message", "Student marked inactive successfully");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to mark student inactive");
        }
    }

    @GetMapping("/students/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history");
        }
    }
```

- [ ] **Step 4: Run — confirm PASS**

`mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorInactiveFlowIT`

- [ ] **Step 5: Run full suite**

`mvn -f C:/work/rcf/imas-backend/pom.xml test`

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorInactiveFlowIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): PUT /students/:id/inactive + GET /students/:id/inactive-history -- completes Plan 4e-1's 14 endpoints

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review checklist (confirmed before handing off)

- All 14 endpoints (#1-14) map to a task: #1/#2/#12/#13/#14/#10/#9 → Task 1; #7/#8/#3 → Task 2; #11 → Task 3; #4 → Task 4; #5/#6 → Task 5.
- `updateStudentModel`'s whitelist is a closed enum (`StudentUpdatableColumn`); Task 4 has 3 dedicated tests proving non-whitelisted keys (including an injection-shaped key) are ignored and the target row's PK/identity is provably unchanged.
- Every SQL statement is copied verbatim (column list, join order, filter conditions) from the live Node model functions read in this session — no placeholder SQL.
- No placeholders anywhere in code steps; every test has concrete seed `INSERT`s and concrete `jsonPath` assertions.
- All 9 Firm Decisions + both live-source corrections (missing-reason envelope key, institute-search 500 shape) are reflected in the relevant task.
- Method/type names consistent across tasks (`CoordinatorReadRepository`, `CoordinatorWriteRepository`, `CoordinatorController`, `genericRow`, `StudentUpdatableColumn`).
