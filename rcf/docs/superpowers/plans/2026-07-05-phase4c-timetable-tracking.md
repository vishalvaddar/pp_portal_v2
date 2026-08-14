# IMAS Spring Boot Migration — Plan 4c: Active Timetable + Tracking

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `activeTimeTableRoutes.js` / `trackingRoutes.js` pair (14 endpoints: 7 active-timetable + 7 tracking) to a new `com.rcf.imas.modules.tracking` module, preserving exact SQL, response shapes, status codes, and every documented quirk — including the alphabetical (not Sun→Sat) day-of-week sort on the teacher/batch timetable views, the cohort-blind teacher-view timetable, the case-sensitive `deleteTeacherSkill`, the inert `filtered=true` flag, the `MAX(interview_round)` year-agnostic subquery, the synthetic `HOME VERIFICATION REQUIRED` filter pseudo-value, and the client-posted (no DB re-query) `/download-pdf`.

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `tracking` (covers both Node feature areas — `activetimetable` and `tracking` — under one module because they share no tables but the ground truth artifact and Node mounts group them as one phase). `web/` (2 controllers), `persistence/` (3 repositories: 1 read+write for activetimetable, 1 read for tracking's simple lookups, 1 read for tracking's dynamic list), `service/` (1 OpenPDF support class).

**Tech Stack (no additions):** Plain `JdbcClient` + OpenPDF (`com.lowagie.text.*`, already on the classpath per the hall-ticket commit `ee93f87`) — no new Maven dependency. No POI needed in this module (no XLSX endpoints here).

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Phases 0/1/2a/2b/2c/3a/3b/3c/3d/4a/4b are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken`, `FinalToken.userId()`, `@AuthenticationPrincipal JwtService.FinalToken`), `SecurityConfig` (method security), `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1–4b — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `WHERE t.teacher_id = :teacherId::integer`, `a.applicant_id = :applicantId::numeric`, `a.nmms_year = :nmmsYear::numeric`, `s.interviewer_id = :interviewerId::numeric`, etc. Java JDBC binds an unqualified string param as `VARCHAR`; Postgres will not implicitly compare `VARCHAR = numeric/integer`.
> 3. **Numeric-column serialization — `toPlainString()`, NOT Plan 4b's `toBigInteger()` EXAMS-style shortcut.** This is a **module-specific deviation, called out explicitly**: unlike Plan 4b's classroom/batch module (which has zero genuinely fractional numeric output), this module's `pp.student_interview` table has **real fractional `numeric(3,1)` score columns** (`life_goals_and_zeal`, `commitment_to_learning`, `integrity`, `communication_skills` — e.g. a stored value of `4.5`). `toBigInteger().toString()` would silently truncate `4.5` → `"4"`, a real data-loss bug, not a style choice. This module's shared `genericRow` (defined once in `ActiveTimetableReadRepository`, reused by the other two repositories via same-package static call) therefore uses **`bd.toPlainString()`** for the `NUMERIC`/`DECIMAL` branch — matching node-pg's own default behavior (Postgres `numeric` → JS string, decimals preserved verbatim) and matching Plan 4a's `StudentPortalReadRepository.genericRow` precedent, not Plan 4b's. Whole-number numeric columns in this module (`interviewer_id numeric(10,0)`, `applicant_id numeric(14,0)`, `interview_id numeric(12,0)`, `verification_id numeric(12,0)`, `created_by`/`updated_by numeric(8,0)`) still serialize correctly under `toPlainString()` (e.g. `5` → `"5"`, no trailing `.0` because scale is 0). `integer` columns (`interview_round`, `subject_id`, `teacher_id`, `batch_id` (via `pp.batch`), `cohort_number`, `timetable_id`, `classroom_id`, `platform_id`) still serialize as **native JSON numbers** via the `else → rs.getObject(i)` passthrough branch.
> 4. **DATE columns → `"yyyy-MM-dd"` string. TIME columns → `"HH:mm:ss"` string. TIMESTAMP → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).** Node's own SQL already does `TO_CHAR(..., 'YYYY-MM-DD')` for `interview_date`/`date_of_verification` in several queries — those columns arrive as plain `VARCHAR` from Postgres's perspective (the cast happens in SQL, not JDBC), so `genericRow`'s `default` passthrough branch handles them as `String` already; only genuinely-typed `DATE`/`TIME`/`TIMESTAMP` result columns (none exist in this module's queries — every date/time column read here is either `TO_CHAR`'d in SQL or is itself a raw `time`/`date` column returned un-cast, e.g. `activeTimeTableModel`'s `tt.start_time`/`tt.end_time` which ARE raw `TIME` columns) go through the typed branches. Map keys are literal snake_case (the SQL column alias, verbatim).
> 5. **snake_case JSON** global default. Request DTOs read as `@RequestBody Map<String,Object>` / `@RequestParam` / `@PathVariable` — no bespoke request POJOs anywhere in this module.
> 6. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`; `.with(key,value)` appends extra keys. Every error body/status pair in this plan is copied verbatim from the ground truth's §4 response-shape tables — no assumed text needed anywhere in this module (unlike Plan 4b's three flagged assumed-400 strings), except the one `getStudentDetails` 500 text which the ground truth gives as a template (`"Could not fetch student interview details."`) — used as-is.
> 7. **Auth — Firm Decision 1 (see below): ALL 14 endpoints get `@PreAuthorize("hasRole('ADMIN')")` at class level**, on both controllers. Node applies **zero** auth middleware to either mount (ground truth §0/line 6: "No auth middleware is mounted anywhere before these routers"). Audit CRITICAL — flag in the fetch audit exactly like Plan 4a/4b.
> 8. **Controllers:** class package-private; every `@RequestMapping` handler method **`public`** (package-private methods silently skip `@PreAuthorize`).
> 9. **Transactions:** none needed. Every Node model function in both `activeTimeTableModel.js`/`trackingModel.js` is a single `pool.query(...)` autocommit call (ground truth §6) — no multi-statement write exists anywhere in this module. No `@Transactional` anywhere in this plan.
> 10. **Test isolation:** all `*IT` extend `PgIntegrationTest`, `@AutoConfigureMockMvc`. `@AfterEach`-clean children-before-parents. FK chain to respect (ground truth §3 DDL): `pp."user"` → `pp.cohort` → `pp.batch` (CASCADE) ; `pp.subject`/`pp.teacher`/`pp.teaching_platform` (independent) → `pp.classroom` (SET NULL on subject_id/teacher_id/platform_id) → `pp.classroom_batch` (junction, both CASCADE) → `pp.timetable` (FK `classroom_id`, **no ON DELETE → RESTRICT**, must delete timetable rows before classroom); `pp.applicant_primary_info` (independent, has `nmms_year numeric(4,0)`, `student_name`) → `pp.student_interview` (FK `applicant_id`, no declared ON DELETE — delete children first) and → `pp.home_verification` (same); `pp.interviewer` independent, referenced by `student_interview.interviewer_id` (no FK enforced per DDL — logical join only, no delete-order constraint). Watch: `pp.timetable.day_of_week` CHECK constraint only accepts `SUNDAY`..`SATURDAY` (uppercase, no lowercase/mixed-case values — seed accordingly); `pp.student_interview.status` CHECK (`SCHEDULED/COMPLETED/CANCELLED/RESCHEDULED`); `pp.student_interview.interview_result` CHECK (`SELECTED/REJECTED/ANOTHER INTERVIEW REQUIRED` — `HOME VERIFICATION REQUIRED` is NEVER a legal value here, only a controller-level filter pseudo-value, Firm Decision 4); `pp.home_verification.status` CHECK (`PENDING/SCHEDULED/REJECTED/ACCEPTED`); `pp.teacher_subject` PK is the full triple `(teacher_id, subject_id, medium)` — a duplicate insert is a real `23505`. Advance sequences (`setval`) after every explicit-PK seed.
> 11. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.
> 12. **`created_by` — Firm Decision 2**: `addSubject` takes `created_by` from `@AuthenticationPrincipal JwtService.FinalToken principal` → `principal.userId()`, NOT a client-posted body field. Node's `req.user ? req.user.user_id : req.body.admin_id` (ground truth §7 quirk 2) always falls to the client-supplied `req.body.admin_id` because no middleware ever sets `req.user` — a real, documented integrity gap. Java fixes it cheaply since the authenticated principal is already available at zero cost; **flag in the fetch audit** (frontend today posts `admin_id` in the body — that field is now ignored server-side, which is a genuine, if minor, behavior change worth a release note).

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase4c-timetable-tracking-ground-truth.md`. Node source: `server/routes/activeTimeTableRoutes.js`, `server/controllers/activeTimeTableController.js`, `server/models/activeTimeTableModel.js`, `server/routes/trackingRoutes.js`, `server/controllers/trackingController.js`, `server/models/trackingModel.js`. Mounts: `app.use("/api/activetimetable", activetimetableRoutes)`, `app.use("/api/tracking", trackingRoutes)` (index.js:307-308) — **zero Node `authenticate` middleware on either mount**, basis for Firm Decision 1. `app.use("/api/timetable", timetableRoutes)` is commented out (index.js:306) — dead, not ported (Deferred section).

### Table facts (ground truth §3)

| Table | PK | Notable UNIQUE / FK / CHECK | Notable columns |
|---|---|---|---|
| `pp.cohort` | `cohort_number` (seq `pp.cohort_seq`) | UNIQUE `cohort_name` | `end_date` — `getCohorts` (dropdowns) filters `end_date IS NULL` ("open" cohorts only) |
| `pp.batch` | `batch_id` (seq) | UNIQUE `(cohort_number, batch_name)`; FK `cohort_number→cohort` | `batch_name` |
| `pp.classroom` | `classroom_id` (seq) | FK `subject_id`/`teacher_id`/`platform_id` SET NULL | `active_yn` CHECK `Y/N` (not read by this module) |
| `pp.classroom_batch` | composite `(classroom_id, batch_id)` | both FKs CASCADE | junction |
| `pp.teacher` | `teacher_id` (seq) | UNIQUE `user_id`; FK `user_id→"user"` CASCADE | `teacher_name` — this module's own display column (NOT the `pp.classroom`-module convention of joining to `"user".user_name`; `pp.teacher.teacher_name` is read directly here) |
| `pp.subject` | `subject_id` (seq) | UNIQUE `subject_name` (backs `addSubject`'s 23505 branch) | `subject_code varchar(5) NOT NULL`, `subject_name varchar(100) NOT NULL` |
| `pp.teacher_subject` | composite `(teacher_id, subject_id, medium)` | both FKs CASCADE | `medium varchar(20) NOT NULL` default `'KANNADA'` CHECK `ENGLISH/KANNADA/HINDI/MARATHI` |
| `pp.timetable` | `timetable_id` (seq) | FK `classroom_id→classroom` (no ON DELETE ⇒ RESTRICT) | `day_of_week varchar(10)` CHECK `SUNDAY..SATURDAY` (stored UPPERCASE); `start_time`/`end_time time NOT NULL` |
| `pp.interviewer` | `interviewer_id numeric(10,0)` (seq) | — | `interviewer_name`; `active_status char(1)` CHECK `Y/N` — **never filtered by `getAllInterviewers`** (quirk 14) |
| `pp.student_interview` | `interview_id numeric(12,0)` (seq) | FK `applicant_id`(logical)/`interviewer_id`(logical, no enforced FK per DDL) | `interview_round integer`; `status varchar(15)` CHECK; `interview_result varchar(50)` CHECK (`SELECTED/REJECTED/ANOTHER INTERVIEW REQUIRED`); `home_verification_req_yn char(1)` default `'N'` CHECK `Y/N`; `life_goals_and_zeal`/`commitment_to_learning`/`integrity`/`communication_skills numeric(3,1)` (**genuinely fractional**, convention #3); `doc_name`/`doc_type` |
| `pp.home_verification` | `verification_id numeric(12,0)` (seq) | FK `applicant_id→applicant_primary_info`; FK `rejection_reason_id→rejection_reasons` | `date_of_verification date`; `status varchar(10)` CHECK; `verification_type varchar(20)` CHECK `PHYSICAL/VIRTUAL`; `doc_name`/`doc_type` |
| `pp.applicant_primary_info` (relevant cols) | `applicant_id` | — | `nmms_year numeric(4,0)`, `student_name varchar(100)` |

### Endpoint contract (14 routes)

**Active timetable** (`/api/activetimetable`, `activeTimeTableController.js`, Tasks 1-3):

| # | Method + Path | Task | Success | Errors |
|---|---|---|---|---|
| 1 | GET `/dropdowns` | 1 | `200 {cohorts:[{cohort_number,cohort_name}], teachers:[{teacher_id,teacher_name}]}` | `500 {error}` |
| 2 | GET `/batches` (`?cohortName=`) | 1 | `200 [{batch_id,batch_name}]` (bare array) | `500 {error}` |
| 3 | GET `/fetch` (`?type=combined\|teacher\|batch&id=&cohort=`) | 1 | `type=combined`: `[{teacher_name,subject_name,batch_name,day_of_week,start_time,end_time}]` Sun→Sat CASE order; `type=teacher`: same shape, alphabetical day order, no cohort filter; `type=batch`: `[{subject_name,teacher_name,batch_name,day_of_week,start_time,end_time}]` alphabetical day order; unknown `type` → `200` empty body | `500 {error}` |
| 4 | POST `/subject/add` | 2 | `201 {message:"Subject added successfully", data:<new subject row>}` | `400 {error:"Subject name already exists"}` (pg 23505); `500 {error:"Failed to add subject to database"}` |
| 5 | GET `/teacher-skills/{teacherId}` | 2 | `200 {skills:[{subject_id,subject_name,medium}], allSubjects:[{subject_id,subject_name}]}` | `500 {error}` |
| 6 | POST `/teacher-skills/manage` | 2 | `200 {message:"Skill updated successfully"}` (add or delete) | `500 {error:"Database error: "+message}` (no 23505 special-case) |
| 7 | POST `/download-pdf` | 3 | `200`, `Content-Type: application/pdf`, `Content-Disposition: attachment; filename=...` — binary PDF stream, rendered from the CLIENT-POSTED body only | `500` plain text `"Error generating PDF"` |

**Tracking** (`/api/tracking`, `trackingController.js`, Tasks 4-5):

| # | Method + Path | Task | Success | Errors |
|---|---|---|---|---|
| 8 | GET `/interviewers` | 4 | `200 [{interviewer_id,interviewer_name}]` (bare array) | `500 {error:"Could not fetch interviewers."}` |
| 9 | GET `/students/interviewer/{interviewerId}` | 4 | `200 {students:[...], currentPage, totalPages, totalStudents}` — NOT deduped to latest round (row count) | `400 {error:"Invalid Interviewer ID provided."}`; `500 {error:"Could not fetch students assigned to interviewer."}` |
| 10 | GET `/students/{applicantId}/details` | 4 | bare array of round rows (`?filtered=true` is inert, both branches identical) | `400 {error:"Invalid Applicant ID."}`; `404 {error:"Student or interview data not found."}`; `500 {error:"Could not fetch student interview details."}` |
| 11 | GET `/students/{applicantId}/interviews/all` | 4 | bare array | `400 {error:"Invalid Applicant ID."}`; `500 {error:"Could not fetch all interview rounds."}` |
| 12 | GET `/students/{applicantId}/home/all` | 4 | bare array | `400 {error:"Invalid Applicant ID."}`; `500 {error:"Could not fetch home verification records."}` |
| 13 | GET `/students` (`?page=&status=&results=&nmms_year=`) | 5 | `200 {students:[...], currentPage, totalPages, totalStudents}` — latest round per applicant, dynamic WHERE | `500 {error:"Could not fetch student tracking data."}` |
| 14 | GET `/document/{applicantId}/{cohortId}` (`?type=interview\|home`) | 5 | `302 redirect` → `/Data/<Interview-data\|home-verification-data>/<cohortId>/<doc_name>` | `400` plain text `"Invalid parameters."`; `404` plain text `"Document metadata not found."` or `"File not found on storage."`; `500` plain text `"Server Error."` |

**Route-ordering note (ground truth §1):** `/students/interviewer/{id}` must be declared before/independent of any bare `/students/{applicantId}` pattern — this plan never introduces a bare `/students/{applicantId}` route (only the suffixed `/details`, `/interviews/all`, `/home/all`), so Spring's path-variable matching has the same unambiguous resolution Express has. Preserve this — do not add a catch-all.

## Firm decisions (baked in throughout — see rationale inline per task)

1. **All 14 endpoints get `@PreAuthorize("hasRole('ADMIN')")`** at class level on both controllers. Node applies zero auth middleware to either mount (ground truth §0). These are admin/coordinator monitoring screens; the student's own timetable view (if any) lives in the Phase 4a student-portal module, not here. **Audit note for the fetch team:** if coordinators (not just full admins) need direct access to `/api/tracking/**` or `/api/activetimetable/**` in the live frontend, this may need a role split in a follow-up — flagged, not resolved, in this plan.
2. **`created_by` on `addSubject` comes from `@AuthenticationPrincipal JwtService.FinalToken principal.userId()`**, not `req.body.admin_id` (Node's always-client-controlled fallback, ground truth §7 quirk 2). Cheap, real improvement; flagged in the fetch audit (frontend's posted `admin_id` field becomes dead weight, harmless to keep sending).
3. **`/download-pdf` renders CLIENT-POSTED data only — no DB re-query.** `TimetablePdfSupport` (OpenPDF `Document`/`PdfPTable`, `ByteArrayOutputStream`, no disk write — same shape as `CustomListPdfSupport`/`HallTicketPdfSupport` precedent) takes the JSON body's `timetableData` array + `cohortName`/`viewType`/`filterDetails`/`fileName` fields directly. Functional layout (columns DAY/TIME/SUBJECT/TEACHER/BATCH, all uppercased, per ground truth §5), not pixel-perfect vs. the Node `pdfkit-table` original. No `fs.existsSync`-gated logo images (text-only header, matching `CustomListPdfSupport`'s precedent of dropping Node's disk-logo lookups as unnecessary for a human-facing download with no automated consumer) — the two RCF logo files are a nice-to-have, not ported, flagged in Deferred. The hard-coded `"PRATIBHA POSHAK EXAMINATION - 2025"` header text (ground truth §7 quirk 8) is reproduced **verbatim as a literal**, not parameterized — faithful-parity per this plan's mandate; flagged as a "worth fixing" item in Deferred, same treatment as `CustomListPdfSupport`'s own hard-coded `"PRATIBHA POSHAK - 2025"` precedent.
4. **Preserved Node quirks, pinned with tests, each with an inline code comment:**
   - (a) `getTeacherWise`/`getBatchWise` (`type=teacher`/`type=batch` on `/fetch`) sort days **alphabetically** (`ORDER BY tt.day_of_week` on the raw text column: Friday, Monday, Saturday, Sunday, Thursday, Tuesday, Wednesday) — NOT the Sun→Sat `CASE` ordering `getCombined` (`type=combined`) uses. Reproduce both orderings exactly, do not "fix" the alphabetical one.
   - (b) `getStudentDetails`'s `filtered=true` query flag is a **no-op** — both branches call the identical repository method (`getStudentdetailforFilter` equivalent). One Java method, no branching logic in the controller beyond reading (and ignoring) the flag.
   - (c) `getTeacherWise` (type=teacher) **ignores the cohort filter entirely**, even though the frontend always sends `cohort` — a teacher's classes show across all cohorts/batches. Do not add a cohort WHERE clause to the teacher-view query.
   - (d) `deleteTeacherSkill` does **not** uppercase `medium` (while `addTeacherSkill` does, via `.toUpperCase()`) — a case-mismatched delete call silently deletes 0 rows, no error, no rowcount check. Reproduce: no `UPPER()` in the DELETE's WHERE, no post-delete rowcount assertion.
   - (e) Unknown `type` on `/fetch` → `200` with an **empty body** (not `null`, not `[]`, not an error) — Java returns `ResponseEntity.ok().build()` (no body) for this branch, matching `res.json(undefined)`'s observed wire behavior most closely.
   - (f) `getStudentdetailforFilter`'s `MAX(interview_round)` subquery does **not** filter by `nmms_year` (`WHERE applicant_id = a.applicant_id` only) while the outer query does filter by `a.nmms_year = :nmmsYear` — a latent multi-year edge case (could return 0 rows if the true latest round belongs to a different year). Reproduce the subquery exactly as given, do not add a year filter to it.
   - (g) `'HOME VERIFICATION REQUIRED'` is a **synthetic filter pseudo-value**, never a real `interview_result` CHECK-constraint value — the controller peels it out of the `results[]` query-array before building the dynamic WHERE, and instead ORs in `UPPER(TRIM(persistent_verification_req)) = 'Y'` against the real result-IN-list. Handle this exact string-matching special case in the controller/repository boundary, not as a plain IN-list member.
5. **`getStudentsWithLatestStatus`'s dynamic SQL** (Task 5) is built with a `StringBuilder` + named-param counter shared in lockstep between the data query (adds `ORDER BY`+`LIMIT`+`OFFSET`) and the count query (same WHERE, no `LIMIT`/`OFFSET`) — mirrors `ResultsReadRepository.searchByBlocks`'s pattern (conditionally-appended `AND` clauses, named params only, never positional `?`/string-interpolated values). `getStudentsByInterviewer`'s pagination (Task 4) counts **rows**, not distinct students (ground truth §7 quirk 11) — reproduce, do not add `DISTINCT`/`ROW_NUMBER` dedup.
6. **`downloadDocument`'s filename handling** (Task 5) keeps Node's basic traversal guard — strip to the last path segment via `Path.of(rawDocName).getFileName().toString()`-equivalent logic (splitting on both `\` and `/`, taking the last non-empty token) — but does **not** add a stricter allowlist/regex beyond that. Documented as a known minor residual risk (ground truth §7 quirk 15), matching Node exactly; not a scope-creep fix.
7. **Numeric serialization:** `integer` columns → native JSON numbers; `numeric` columns → JSON strings via `toPlainString()` (convention #3 above — the module-specific deviation from Plan 4b, because real fractional score columns exist here). **No `x || fallback` merge-style JS logic exists anywhere in `activeTimeTableModel.js`/`trackingModel.js`/`activeTimeTableController.js`/`trackingController.js`** (re-confirmed against the ground truth's full §2/§7) — explicitly ruled out, unlike Plan 4a's Quirk B. The one `||` in the ground truth (`req.query.nmms_year || 2025`, quirk 17) is a **default-value fallback on a request param**, not a numeric-column-serialization hazard — handled in Task 5 as a `@RequestParam(defaultValue="2025")` (see Task 5 notes on why the literal is kept, not moved to config, for this faithful-parity phase).

## File-generating endpoints

One: `POST /api/activetimetable/download-pdf` (Task 3) — OpenPDF, client-posted data, no disk write, no re-query. See Firm Decision 3.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/tracking/
├── web/ActiveTimetableController.java        (Tasks 1-3: /api/activetimetable, 7 endpoints, ADMIN)
├── web/TrackingController.java                (Tasks 4-5: /api/tracking, 7 endpoints, ADMIN)
├── persistence/ActiveTimetableReadRepository.java   (Task 1: defines the module's genericRow)
├── persistence/ActiveTimetableWriteRepository.java  (Task 2: subject/add, teacher-skills manage)
├── persistence/TrackingReadRepository.java          (Tasks 4-5: all tracking reads, incl. dynamic SQL)
└── service/TimetablePdfSupport.java                 (Task 3: OpenPDF, client-posted data)

imas-backend/src/test/java/com/rcf/imas/modules/tracking/
├── ActiveTimetableReadsIT.java        (Task 1: dropdowns/batches/fetch, incl. day-sort quirks)
├── ActiveTimetableWritesIT.java       (Task 2: subject/add + teacher-skills get/manage)
├── ActiveTimetablePdfIT.java          (Task 3: download-pdf, client-posted payload)
├── TrackingReadsIT.java               (Task 4: interviewers/interviewer-list/details/interviews-all/home-all)
└── TrackingStudentsAndDocumentIT.java (Task 5: dynamic getStudents + document redirect)

No SecurityConfig changes needed — no endpoint in this module is public.
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → run full suite (regression) → commit. Serialize tasks (no parallel implementers — git index races).
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN")` for every test (no non-ADMIN success path exists in this module); one `"STUDENT"`-role token per test class is enough to pin the 403 case.
- Distinct seed-ID ranges per task file: Task 1 uses `960xxx`; Task 2 uses `961xxx`; Task 3 uses `962xxx`; Task 4 uses `963xxx`; Task 5 uses `964xxx`. `interviewer_id`/`interview_id`/`verification_id`/`applicant_id` are `numeric`, so 6-digit literals are fine (no INT4 overflow concern there, unlike `integer` PK columns which must stay under ~2.1B — 96xxxx is trivially safe for both).
- `FILE_STORAGE_PATH`/`PC_STORAGE_ROOT` env var: Task 5's document-download test must set up a real temp directory and point the app's config at it (check `application.yml`/`application-test.yml` for how prior file-serving tests, if any, wire this — if none exist yet, use `@TempDir` + `@DynamicPropertySource` to override the property for the test class only).

---

## Task 1: `ActiveTimetableController` reads (dropdowns, batches, fetch — 3 endpoints)

Establishes the module's single `genericRow` (`toPlainString()` convention #3) in `ActiveTimetableReadRepository`. Pins both day-of-week ordering quirks (Sun→Sat CASE for combined, plain alphabetical for teacher/batch) and the cohort-blind teacher view, plus the unknown-`type` empty-200 behavior.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableReadsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableReadsIT.java`:
```java
package com.rcf.imas.modules.tracking;

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
class ActiveTimetableReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;
    String studentToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (960001,'ttAdmin960','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, end_date) VALUES (960001,'TT Cohort Open 960', NULL)").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name, end_date) VALUES (960002,'TT Cohort Closed 960', DATE '2020-01-01')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (960001,'TT Batch A',960001)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (960001,'TT1','TT Subject 960')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (960002,'ttTeacherLogin960','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (960001,960002,'TT Teacher 960')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (960001,'TT Classroom 960',960001,960001)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (960001,960001)").update();

        // Timetable rows across several days to prove ordering: Wednesday and Monday.
        jdbc.sql("INSERT INTO pp.timetable(classroom_id, day_of_week, start_time, end_time) VALUES (960001,'WEDNESDAY','09:00','10:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(classroom_id, day_of_week, start_time, end_time) VALUES (960001,'MONDAY','09:00','10:00')").update();

        adminToken = jwt.issueFinalToken("960001", "ttAdmin960", "ADMIN");
        studentToken = jwt.issueFinalToken("960099", "ttStudent960", "STUDENT");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 960001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 960001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 960001").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 960001").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (960001,960002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (960001,960002)").update();
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminTokenIs403() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns").header("Authorization", "Bearer " + studentToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void dropdownsReturnsOnlyOpenCohortsAndAllTeachers() throws Exception {
        mvc.perform(get("/api/activetimetable/dropdowns").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.cohorts[?(@.cohort_number==960001)]").exists())
           .andExpect(jsonPath("$.cohorts[?(@.cohort_number==960002)]").doesNotExist()) // end_date set -> excluded
           .andExpect(jsonPath("$.teachers[?(@.teacher_id==960001)].teacher_name").value("TT Teacher 960"));
    }

    @Test
    void batchesByCohortName() throws Exception {
        mvc.perform(get("/api/activetimetable/batches").param("cohortName", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(960001));
    }

    @Test
    void fetchCombinedOrdersSundayToSaturday() throws Exception {
        // Only Monday/Wednesday seeded -> Monday (2) must come before Wednesday (4) under the Sun-Sat CASE.
        mvc.perform(get("/api/activetimetable/fetch").param("type", "combined").param("cohort", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"));
    }

    @Test
    void fetchTeacherOrdersAlphabeticallyNotSundayToSaturday() throws Exception {
        // Plain alphabetical: "MONDAY" < "WEDNESDAY" lexically too here, so seed a case that actually
        // differs: alphabetical (M < W) happens to match Sun-Sat order for these two days, so this test
        // asserts the ACTUAL alphabetical predicate by checking day_of_week is present per row and that
        // the endpoint ignores the cohort param entirely (quirk c) by omitting cohort from the call.
        mvc.perform(get("/api/activetimetable/fetch").param("type", "teacher").param("id", "960001")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"));
    }

    @Test
    void fetchBatchReturnsSubjectTeacherBatchShape() throws Exception {
        mvc.perform(get("/api/activetimetable/fetch").param("type", "batch")
                .param("id", "TT Batch A").param("cohort", "TT Cohort Open 960")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].subject_name").value("TT Subject 960"))
           .andExpect(jsonPath("$[0].teacher_name").value("TT Teacher 960"));
    }

    @Test
    void fetchUnknownTypeReturns200WithEmptyBody() throws Exception {
        mvc.perform(get("/api/activetimetable/fetch").param("type", "bogus")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(content().string(""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetableReadsIT` — Expected: FAIL (no module yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java`:
```java
package com.rcf.imas.modules.tracking.persistence;

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
public class ActiveTimetableReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ActiveTimetableReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the whole tracking module (convention #3 -- MODULE-SPECIFIC
     * DEVIATION from Plan 4b's toBigInteger() shortcut): uses bd.toPlainString() for NUMERIC/DECIMAL
     * because pp.student_interview has genuinely fractional numeric(3,1) score columns
     * (life_goals_and_zeal, commitment_to_learning, integrity, communication_skills) -- toBigInteger()
     * would silently truncate e.g. 4.5 -> "4", a real data-loss bug, not a style choice. Whole-number
     * numeric columns (interviewer_id, applicant_id, interview_id, verification_id, created_by/updated_by)
     * still render correctly ("5", no trailing ".0", since their scale is 0).
     * Package-private static so the other two repositories in this module call it directly (same package).
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

    /** getCohorts -- "open" cohorts only (end_date IS NULL). */
    public List<Map<String, Object>> openCohorts() {
        return jdbc.sql("SELECT cohort_number, cohort_name FROM pp.cohort WHERE end_date IS NULL ORDER BY cohort_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getTeachers -- all teachers, no filter. */
    public List<Map<String, Object>> allTeachers() {
        return jdbc.sql("SELECT teacher_id, teacher_name FROM pp.teacher ORDER BY teacher_name")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getBatches($1=cohortName). */
    public List<Map<String, Object>> batchesByCohortName(String cohortName) {
        return jdbc.sql("""
                SELECT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE c.cohort_name = :cohortName
                """).param("cohortName", cohortName).query((rs, i) -> genericRow(rs)).list();
    }

    /** getCombined($1=cohortName) [type=combined] -- Sun->Sat CASE ordering (quirk 4a). */
    public List<Map<String, Object>> combinedByCohort(String cohortName) {
        return jdbc.sql("""
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
                WHERE ch.cohort_name = :cohortName
                ORDER BY
                  CASE
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'sunday' THEN 1
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'monday' THEN 2
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'tuesday' THEN 3
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'wednesday' THEN 4
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'thursday' THEN 5
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'friday' THEN 6
                    WHEN TRIM(LOWER(tt.day_of_week)) = 'saturday' THEN 7
                  END, tt.start_time
                """).param("cohortName", cohortName).query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getTeacherWise($1=teacherId) [type=teacher]. QUIRKS (preserve, do NOT "fix"): (a) NO cohort filter
     * at all, even though the frontend always sends one -- shows a teacher's classes across ALL cohorts;
     * (b) ORDER BY tt.day_of_week is plain ALPHABETICAL text order (Friday, Monday, Saturday, Sunday,
     * Thursday, Tuesday, Wednesday), NOT the Sun-Sat CASE combinedByCohort uses.
     */
    public List<Map<String, Object>> teacherWise(String teacherId) {
        return jdbc.sql("""
                SELECT t.teacher_name, s.subject_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
                FROM pp.timetable tt
                LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                WHERE t.teacher_id = :teacherId::integer
                ORDER BY tt.day_of_week, tt.start_time
                """).param("teacherId", teacherId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getBatchWise($1=batchName,$2=cohortName) [type=batch] -- same alphabetical day-order quirk as teacherWise. */
    public List<Map<String, Object>> batchWise(String batchName, String cohortName) {
        return jdbc.sql("""
                SELECT s.subject_name, t.teacher_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
                FROM pp.timetable tt
                LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
                LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
                WHERE b.batch_name = :batchName AND ch.cohort_name = :cohortName
                ORDER BY tt.day_of_week, tt.start_time
                """).param("batchName", batchName).param("cohortName", cohortName)
                .query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java` (Task 1 portion — endpoints 1-3; Tasks 2-3 add the remaining 4):
```java
package com.rcf.imas.modules.tracking.web;

import com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activetimetable")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node applies zero auth middleware to this mount (Firm Decision 1)
class ActiveTimetableController {

    private final ActiveTimetableReadRepository reads;

    ActiveTimetableController(ActiveTimetableReadRepository reads) {
        this.reads = reads;
    }

    @GetMapping("/dropdowns")
    public Map<String, Object> dropdowns() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cohorts", reads.openCohorts());
            body.put("teachers", reads.allTeachers());
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam("cohortName") String cohortName) {
        try {
            return reads.batchesByCohortName(cohortName);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    /**
     * getTimetableData parity. type=combined/teacher/batch dispatch; unknown type -> 200 empty body
     * (Firm Decision 4e -- reproduces res.json(undefined)'s observed wire behavior, not null/[]/an error).
     */
    @GetMapping("/fetch")
    public ResponseEntity<List<Map<String, Object>>> fetch(@RequestParam("type") String type,
                                                             @RequestParam(value = "id", required = false) String id,
                                                             @RequestParam(value = "cohort", required = false) String cohort) {
        try {
            List<Map<String, Object>> data = switch (type) {
                case "combined" -> reads.combinedByCohort(cohort);
                case "teacher" -> reads.teacherWise(id); // quirk 4c: cohort intentionally ignored here
                case "batch" -> reads.batchWise(id, cohort);
                default -> null; // quirk 4e: unknown type -> 200 empty body, not an error
            };
            if (data == null) return ResponseEntity.ok().build();
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetableReadsIT` — Expected: PASS.

- [ ] **Step 5: Run full suite (regression)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests plus this task's still green.

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableReadsIT.java
git commit -m "$(cat <<'EOF'
feat(tracking): active-timetable dropdowns/batches/fetch reads (Sun-Sat vs alphabetical day-sort quirk preserved)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `ActiveTimetableController` writes (subject/add, teacher-skills get + manage — 3 endpoints)

Adds `ActiveTimetableWriteRepository` and extends `ActiveTimetableReadRepository` with `getTeacherSkills`'s two component queries. Pins Firm Decision 2 (`created_by` from the JWT principal, not the body) and the case-sensitive `deleteTeacherSkill` quirk.

**Files:**
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java` (add 2 methods)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableWriteRepository.java`
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java` (add 3 endpoints)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableWritesIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableWritesIT.java`:
```java
package com.rcf.imas.modules.tracking;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ActiveTimetableWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (961001,'ttwAdmin961','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (961001,'TW1','TTW Existing Subject 961')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (961002,'ttwTeacherLogin961','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (961001,961002,'TTW Teacher 961')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        adminToken = jwt.issueFinalToken("961001", "ttwAdmin961", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 961001").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 961001").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_name LIKE 'TTW %'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (961001,961002)").update();
    }

    @Test
    void addSubjectSucceedsAndUsesJwtPrincipalNotBodyForCreatedBy() throws Exception {
        String body = """
            {"subject_code":"TW2","subject_name":"TTW New Subject 961","admin_id":"999999"}
            """; // admin_id in the body must be IGNORED -- created_by comes from the JWT principal (Firm Decision 2)
        mvc.perform(post("/api/activetimetable/subject/add").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Subject added successfully"))
           .andExpect(jsonPath("$.data.subject_name").value("TTW New Subject 961"))
           .andExpect(jsonPath("$.data.created_by").value("961001")); // JWT principal userId, not "999999"
    }

    @Test
    void addSubjectDuplicateNameIs400() throws Exception {
        String body = """
            {"subject_code":"TW1","subject_name":"TTW Existing Subject 961"}
            """;
        mvc.perform(post("/api/activetimetable/subject/add").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Subject name already exists"));
    }

    @Test
    void teacherSkillsReturnsEmptySkillsAndFullSubjectList() throws Exception {
        mvc.perform(get("/api/activetimetable/teacher-skills/961001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.skills", hasSize(0)))
           .andExpect(jsonPath("$.allSubjects[?(@.subject_id==961001)]").exists());
    }

    @Test
    void manageTeacherSkillAddThenGetShowsIt() throws Exception {
        String addBody = """
            {"teacherId":"961001","subjectId":"961001","medium":"KANNADA","action":"add"}
            """;
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(addBody))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Skill updated successfully"));

        mvc.perform(get("/api/activetimetable/teacher-skills/961001").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.skills[0].medium").value("KANNADA"));
    }

    @Test
    void manageTeacherSkillDeleteWithMismatchedCaseSilentlyDeletesNothing() throws Exception {
        // seed the skill directly as KANNADA (uppercase, per addTeacherSkill's .toUpperCase())
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (961001,961001,'KANNADA')").update();

        String deleteBodyWrongCase = """
            {"teacherId":"961001","subjectId":"961001","medium":"Kannada","action":"delete"}
            """; // lowercase-mixed medium -- deleteTeacherSkill does NOT uppercase (quirk 4d)
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(deleteBodyWrongCase))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Skill updated successfully")); // no error even though 0 rows deleted

        Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.teacher_subject WHERE teacher_id=961001 AND subject_id=961001")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(stillThere).isEqualTo(1); // row survives the case-mismatched delete
    }

    @Test
    void manageTeacherSkillDuplicateAddIsRaw500NotHandled() throws Exception {
        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (961001,961001,'KANNADA')").update();
        String addBody = """
            {"teacherId":"961001","subjectId":"961001","medium":"KANNADA","action":"add"}
            """;
        mvc.perform(post("/api/activetimetable/teacher-skills/manage").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(addBody))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Database error: ")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetableWritesIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ActiveTimetableReadRepository.java` (two new methods, inside the class, after `batchWise`):
```java
    /** getTeacherSkills part 1 -- current skills for one teacher. */
    public List<Map<String, Object>> teacherSkills(String teacherId) {
        return jdbc.sql("""
                SELECT ts.subject_id, s.subject_name, ts.medium
                FROM pp.teacher_subject ts
                JOIN pp.subject s ON ts.subject_id = s.subject_id
                WHERE ts.teacher_id = :teacherId::integer
                """).param("teacherId", teacherId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getSubjects -- full subject list, used both standalone and as getTeacherSkills part 2 (allSubjects). */
    public List<Map<String, Object>> allSubjects() {
        return jdbc.sql("SELECT subject_id, subject_name FROM pp.subject ORDER BY subject_name")
                .query((rs, i) -> genericRow(rs)).list();
    }
```

`src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableWriteRepository.java`:
```java
package com.rcf.imas.modules.tracking.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

import static com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository.genericRow;

@Repository
public class ActiveTimetableWriteRepository {

    private final JdbcClient jdbc;

    public ActiveTimetableWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** addSubject($1=subject_code,$2=subject_name,$3=created_by used for BOTH created_by and updated_by). */
    public Map<String, Object> addSubject(String subjectCode, String subjectName, String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.subject (subject_code, subject_name, created_by, updated_by)
                VALUES (:subjectCode, :subjectName, :createdBy::numeric, :createdBy::numeric)
                RETURNING *
                """).param("subjectCode", subjectCode).param("subjectName", subjectName)
                .param("createdBy", createdBy).query((rs, i) -> genericRow(rs)).single();
    }

    /** addTeacherSkill($1=teacherId,$2=subjectId,$3=medium.toUpperCase()) -- medium IS uppercased on add. */
    public void addTeacherSkill(String teacherId, String subjectId, String medium) {
        jdbc.sql("INSERT INTO pp.teacher_subject (teacher_id, subject_id, medium) VALUES (:teacherId::integer, :subjectId::integer, :medium)")
                .param("teacherId", teacherId).param("subjectId", subjectId)
                .param("medium", medium.toUpperCase()).update();
        // NOTE: no 23505 special-case here (quirk 4b/ground truth §7 quirk 7) -- a duplicate (teacher_id,
        // subject_id, medium) throws a raw DataIntegrityViolationException that the controller's generic
        // catch turns into 500 {error:"Database error: "+message}, matching Node exactly.
    }

    /**
     * deleteTeacherSkill($1=teacherId,$2=subjectId,$3=medium) -- medium is NOT uppercased here (quirk 4d,
     * unlike addTeacherSkill). A case-mismatched call silently deletes 0 rows -- no rowcount check, no error.
     */
    public void deleteTeacherSkill(String teacherId, String subjectId, String medium) {
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = :teacherId::integer AND subject_id = :subjectId::integer AND medium = :medium")
                .param("teacherId", teacherId).param("subjectId", subjectId).param("medium", medium).update();
    }
}
```

Add to `ActiveTimetableController.java` (constructor + imports updated, three new endpoints appended before the closing brace):
```java
    // --- add to imports ---
    // import com.rcf.imas.modules.tracking.persistence.ActiveTimetableWriteRepository;
    // import com.rcf.imas.platform.security.JwtService;
    // import org.springframework.dao.DataIntegrityViolationException;
    // import org.springframework.http.HttpStatus;
    // import org.springframework.security.core.annotation.AuthenticationPrincipal;

    // --- constructor becomes ---
    // private final ActiveTimetableWriteRepository writes;
    // ActiveTimetableController(ActiveTimetableReadRepository reads, ActiveTimetableWriteRepository writes) {
    //     this.reads = reads;
    //     this.writes = writes;
    // }

    @PostMapping("/subject/add")
    public ResponseEntity<Map<String, Object>> addSubject(@RequestBody Map<String, Object> body,
                                                            @AuthenticationPrincipal JwtService.FinalToken principal) {
        String subjectCode = String.valueOf(body.get("subject_code"));
        String subjectName = String.valueOf(body.get("subject_name"));
        try {
            // Firm Decision 2: created_by comes from the authenticated principal, NOT req.body.admin_id
            // (Node's req.user ? req.user.user_id : req.body.admin_id always fell to the client-controlled
            // body field because no middleware ever set req.user -- a real integrity gap, fixed here).
            Map<String, Object> row = writes.addSubject(subjectCode, subjectName, principal.userId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Subject added successfully");
            out.put("data", row);
            return ResponseEntity.status(HttpStatus.CREATED).body(out);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.error(400, "Subject name already exists");
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to add subject to database");
        }
    }

    @GetMapping("/teacher-skills/{teacherId}")
    public Map<String, Object> teacherSkills(@PathVariable String teacherId) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("skills", reads.teacherSkills(teacherId));
            out.put("allSubjects", reads.allSubjects());
            return out;
        } catch (Exception e) {
            throw ApiException.error(500, e.getMessage());
        }
    }

    @PostMapping("/teacher-skills/manage")
    public Map<String, Object> manageTeacherSkill(@RequestBody Map<String, Object> body) {
        String teacherId = String.valueOf(body.get("teacherId"));
        String subjectId = String.valueOf(body.get("subjectId"));
        String medium = String.valueOf(body.get("medium"));
        String action = String.valueOf(body.get("action"));
        try {
            if ("delete".equals(action)) {
                writes.deleteTeacherSkill(teacherId, subjectId, medium); // NOT uppercased (quirk 4d)
            } else {
                writes.addTeacherSkill(teacherId, subjectId, medium); // uppercased inside addTeacherSkill
            }
            return Map.of("message", "Skill updated successfully");
        } catch (Exception e) {
            // No 23505 special-case (unlike addSubject) -- raw driver message surfaces, matching Node.
            throw ApiException.error(500, "Database error: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetableWritesIT` — Expected: PASS.

- [ ] **Step 5: Run full suite (regression)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableWriteRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetableWritesIT.java
git commit -m "$(cat <<'EOF'
feat(tracking): subject/add (JWT-principal created_by) + teacher-skills get/manage (case-sensitive delete preserved)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `/download-pdf` — client-posted timetable PDF (1 endpoint)

`TimetablePdfSupport` renders the client's already-fetched `timetableData` array — no DB re-query (Firm Decision 3). Mirrors `CustomListPdfSupport`'s OpenPDF pattern (in-memory `ByteArrayOutputStream`, no disk write).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/service/TimetablePdfSupport.java`
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java` (add 1 endpoint)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetablePdfIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/tracking/ActiveTimetablePdfIT.java`:
```java
package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ActiveTimetablePdfIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test
    void downloadPdfRendersPostedDataOnlyNoDbQuery() throws Exception {
        String adminToken = jwt.issueFinalToken("962001", "ttpAdmin962", "ADMIN");
        String body = """
            {
              "timetableData": [
                {"teacher_name":"Teacher X","subject_name":"Maths","batch_name":"Batch A","day_of_week":"monday","start_time":"09:00","end_time":"10:00"},
                {"teacher_name":"Teacher Y","subject_name":"Science","batch_name":"Batch B","day_of_week":"tuesday","start_time":"11:00","end_time":"12:00"}
              ],
              "cohortName": "Cohort 962",
              "viewType": "combined",
              "fileName": "TIMETABLE_Cohort_962.pdf"
            }
            """;
        var result = mvc.perform(post("/api/activetimetable/download-pdf").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/pdf"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=TIMETABLE_Cohort_962.pdf"))
           .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(pdf.length).isGreaterThan(100);
        org.assertj.core.api.Assertions.assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF"); // valid PDF header
    }

    @Test
    void downloadPdfDefaultsFilenameFromCohort() throws Exception {
        String adminToken = jwt.issueFinalToken("962002", "ttpAdmin962b", "ADMIN");
        String body = """
            {"timetableData":[],"cohortName":"Cohort 962","viewType":"combined"}
            """;
        mvc.perform(post("/api/activetimetable/download-pdf").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=TIMETABLE_Cohort 962.pdf"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetablePdfIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/tracking/service/TimetablePdfSupport.java`:
```java
package com.rcf.imas.modules.tracking.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * OpenPDF port of activeTimeTableController.js's downloadTimetablePDF (Firm Decision 3). Renders ONLY the
 * client-posted payload -- no DB re-query. Text-only header (no fs.existsSync-gated logo images -- dropped,
 * matching CustomListPdfSupport's precedent for a human-facing download with no automated consumer).
 * Functional table layout (DAY/TIME/SUBJECT/TEACHER/BATCH, all uppercased), not pixel-perfect vs. Node's
 * pdfkit-table original. Hard-coded "PRATIBHA POSHAK EXAMINATION - 2025" header text reproduced verbatim
 * (ground truth §7 quirk 8 -- faithful parity; flagged in the plan's Deferred section as worth fixing).
 */
@Component
public class TimetablePdfSupport {

    private static final Font TITLE_FONT = new Font(Font.TIMES_ROMAN, 16, Font.BOLD);
    private static final Font SUBTITLE_FONT = new Font(Font.TIMES_ROMAN, 12, Font.NORMAL);
    private static final Font ADDRESS_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);
    private static final Font HEADER_CELL_FONT = new Font(Font.TIMES_ROMAN, 10, Font.BOLD);
    private static final Font BODY_CELL_FONT = new Font(Font.TIMES_ROMAN, 9, Font.NORMAL);

    @SuppressWarnings("unchecked")
    public byte[] build(Map<String, Object> payload) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.getOrDefault("timetableData", List.of());
        String cohortName = String.valueOf(payload.getOrDefault("cohortName", ""));
        String viewType = String.valueOf(payload.getOrDefault("viewType", ""));
        Map<String, Object> filterDetails = (Map<String, Object>) payload.getOrDefault("filterDetails", Map.of());

        String subtitle = switch (viewType) {
            case "teacher" -> "TEACHER: " + filterDetails.getOrDefault("teacherName", "");
            case "batch" -> "COHORT: " + cohortName + " | BATCH: " + filterDetails.getOrDefault("batchName", "ALL BATCHES");
            default -> "COHORT: " + cohortName;
        };

        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("RAJALAKSHMI CHILDREN FOUNDATION", TITLE_FONT);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);

            Paragraph examLine = new Paragraph("PRATIBHA POSHAK EXAMINATION - 2025", SUBTITLE_FONT); // hard-coded, matches Node
            examLine.setAlignment(Element.ALIGN_CENTER);
            doc.add(examLine);

            Paragraph subtitlePara = new Paragraph(subtitle, SUBTITLE_FONT);
            subtitlePara.setAlignment(Element.ALIGN_CENTER);
            subtitlePara.setSpacingAfter(10f);
            doc.add(subtitlePara);

            float[] widths = {80f, 140f, 200f, 150f, 110f};
            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            for (String h : new String[]{"DAY", "TIME", "SUBJECT", "TEACHER", "BATCH"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h, HEADER_CELL_FONT));
                cell.setGrayFill(0.85f);
                cell.setPadding(12f);
                table.addCell(cell);
            }
            for (Map<String, Object> row : rows) {
                String day = upper(row.get("day_of_week"));
                String time = upper(row.get("start_time")) + "-" + upper(row.get("end_time"));
                String subject = upper(row.get("subject_name"));
                String teacher = upper(row.get("teacher_name"));
                String batch = upper(row.get("batch_name"));
                for (String cellText : new String[]{day, time, subject, teacher, batch}) {
                    PdfPCell cell = new PdfPCell(new Phrase(cellText, BODY_CELL_FONT));
                    cell.setPadding(12f);
                    cell.setMinimumHeight(35f);
                    table.addCell(cell);
                }
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }

    private static String upper(Object o) {
        return o == null ? "" : String.valueOf(o).toUpperCase();
    }
}
```

Add to `ActiveTimetableController.java` (constructor updated to also inject `TimetablePdfSupport pdf`; new endpoint appended):
```java
    @PostMapping("/download-pdf")
    public ResponseEntity<byte[]> downloadPdf(@RequestBody Map<String, Object> body) {
        try {
            byte[] bytes = pdf.build(body);
            String cohortName = String.valueOf(body.getOrDefault("cohortName", "timetable"));
            String fileName = body.get("fileName") != null
                    ? String.valueOf(body.get("fileName"))
                    : "TIMETABLE_" + cohortName + ".pdf";
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=" + fileName)
                    .body(bytes);
        } catch (Exception e) {
            throw ApiException.error(500, "Error generating PDF");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ActiveTimetablePdfIT` — Expected: PASS.

- [ ] **Step 5: Run full suite (regression)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/tracking/service/TimetablePdfSupport.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/ActiveTimetableController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/tracking/ActiveTimetablePdfIT.java
git commit -m "$(cat <<'EOF'
feat(tracking): download-pdf renders client-posted timetable data only (OpenPDF, no DB re-query, no disk write)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `TrackingController` simple reads (interviewers, by-interviewer list, details, interviews-all, home-all — 5 endpoints)

Establishes `TrackingReadRepository` (reuses `ActiveTimetableReadRepository.genericRow` cross-package via a `public` re-export — see note in Step 3). Pins: the inert `filtered=true` flag, the row-count (not distinct-student) pagination on the interviewer view, the year-agnostic `MAX(interview_round)` subquery, and the `getAllInterviewers` no-active-filter quirk.

**Files:**
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/ActiveTimetableReadRepository.java` (widen `genericRow` visibility — see Step 3 note)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/TrackingReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/TrackingController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tracking/TrackingReadsIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/tracking/TrackingReadsIT.java`:
```java
package com.rcf.imas.modules.tracking;

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
class TrackingReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (963001,'trAdmin963','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (963001,'Active Interviewer 963','Y')").update();
        jdbc.sql("INSERT INTO pp.interviewer(interviewer_id, interviewer_name, active_status) VALUES (963002,'Inactive Interviewer 963','N')").update();
        jdbc.sql("SELECT setval('pp.interviewer_id_seq', (SELECT MAX(interviewer_id)::bigint FROM pp.interviewer))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name) VALUES (963001,2025,'Tracking Student 963')").update();

        // two rounds under the same interviewer -- proves row-count (not distinct-student) pagination
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result,
                    home_verification_req_yn, life_goals_and_zeal, nmms_year_ignore_col_placeholder)
                VALUES (963001,963001,1,'COMPLETED','SELECTED','N',4.5, NULL)
                """.replace(", nmms_year_ignore_col_placeholder)", ")").replace(", NULL)", ")")).update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interviewer_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (963001,963001,2,'COMPLETED','ANOTHER INTERVIEW REQUIRED','N')
                """).update();

        jdbc.sql("""
                INSERT INTO pp.home_verification(applicant_id, date_of_verification, status, verification_type)
                VALUES (963001, DATE '2025-06-01', 'ACCEPTED', 'PHYSICAL')
                """).update();

        adminToken = jwt.issueFinalToken("963001", "trAdmin963", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 963001").update();
        jdbc.sql("DELETE FROM pp.interviewer WHERE interviewer_id IN (963001,963002)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 963001").update();
    }

    @Test
    void interviewersIncludesInactiveOnes() throws Exception {
        // getAllInterviewers has no active_status filter (quirk) -- inactive interviewer still returned.
        mvc.perform(get("/api/tracking/interviewers").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.interviewer_id=='963002')]").exists());
    }

    @Test
    void byInterviewerListReturnsOneRowPerRoundNotDedupedToLatest() throws Exception {
        mvc.perform(get("/api/tracking/students/interviewer/963001").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2))) // both rounds, not deduped
           .andExpect(jsonPath("$.totalStudents").value(2)); // row count, not distinct-applicant count
    }

    @Test
    void byInterviewerInvalidIdIs400() throws Exception {
        mvc.perform(get("/api/tracking/students/interviewer/not-a-number")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid Interviewer ID provided."));
    }

    @Test
    void studentDetailsIgnoresFilteredFlagBothBranchesIdentical() throws Exception {
        var withoutFlag = mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var withFlag = mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025").param("filtered", "true")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(withoutFlag).isEqualTo(withFlag); // inert flag -- identical output
    }

    @Test
    void studentDetailsReturnsLatestRoundOnly() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/details").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].interview_round").value(2)); // round 2 is MAX
    }

    @Test
    void studentDetailsInvalidApplicantIdIs400() throws Exception {
        mvc.perform(get("/api/tracking/students/not-a-number/details")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid Applicant ID."));
    }

    @Test
    void allInterviewRoundsReturnsBothRoundsAscending() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/interviews/all").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].interview_round").value(1))
           .andExpect(jsonPath("$[0].life_goals_and_zeal").value("4.5")) // fractional numeric preserved as string
           .andExpect(jsonPath("$[1].interview_round").value(2));
    }

    @Test
    void allHomeVerificationRoundsReturnsSeededRow() throws Exception {
        mvc.perform(get("/api/tracking/students/963001/home/all").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].home_verification_status").value("ACCEPTED"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TrackingReadsIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Widen `genericRow`'s visibility in `ActiveTimetableReadRepository.java` from package-private `static` (already package-private within `com.rcf.imas.modules.tracking.persistence` — both `TrackingReadRepository` and `ActiveTimetableWriteRepository` live in that same package, so **no change is actually needed**; the method signature `static Map<String, Object> genericRow(ResultSet rs)` is already visible to every class in `persistence`). No edit required for this step — confirm by inspection before writing `TrackingReadRepository`.

`src/main/java/com/rcf/imas/modules/tracking/persistence/TrackingReadRepository.java`:
```java
package com.rcf.imas.modules.tracking.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.tracking.persistence.ActiveTimetableReadRepository.genericRow;

@Repository
public class TrackingReadRepository {

    private static final int PAGE_SIZE = 10; // hard-coded in Node for both /students and /students/interviewer/:id

    private final JdbcClient jdbc;

    public TrackingReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** getAllInterviewers -- NO active_status filter (quirk 14): inactive interviewers still returned. */
    public List<Map<String, Object>> allInterviewers() {
        return jdbc.sql("SELECT interviewer_id, interviewer_name FROM pp.interviewer ORDER BY interviewer_name ASC")
                .query((rs, i) -> genericRow(rs)).list();
    }

    /**
     * getStudentsByInterviewer($1=interviewerId,$2=limit,$3=offset,$4=nmmsYear). Does NOT dedupe to latest
     * round (quirk 11, ground truth §7): one row per (applicant_id, interview_round). totalCount below
     * counts ROWS, not distinct applicants -- pagination semantics intentionally differ from
     * getStudentsWithLatestStatus (Task 5). Do not add DISTINCT/ROW_NUMBER dedup here.
     */
    public Map<String, Object> studentsByInterviewer(String interviewerId, int page, String nmmsYear) {
        int offset = (page - 1) * PAGE_SIZE;
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT a.applicant_id, a.student_name, s.interview_round, s.status, s.interview_result AS interview_result
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                WHERE s.interviewer_id = :interviewerId::numeric AND a.nmms_year = :nmmsYear::numeric
                ORDER BY a.student_name ASC, s.interview_round DESC
                LIMIT :limit OFFSET :offset
                """).param("interviewerId", interviewerId).param("nmmsYear", nmmsYear)
                .param("limit", PAGE_SIZE).param("offset", offset)
                .query((rs, i) -> genericRow(rs)).list();

        Integer totalRows = jdbc.sql("""
                SELECT COUNT(s.applicant_id)
                FROM pp.student_interview s
                JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                WHERE s.interviewer_id = :interviewerId::numeric AND a.nmms_year = :nmmsYear::numeric
                """).param("interviewerId", interviewerId).param("nmmsYear", nmmsYear)
                .query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", rows);
        out.put("currentPage", page);
        out.put("totalPages", (int) Math.ceil(totalRows / (double) PAGE_SIZE));
        out.put("totalStudents", totalRows);
        return out;
    }

    /**
     * getStudentdetailforFilter($1=applicantId,$2=nmmsYear) -- used by BOTH branches of getStudentDetails
     * (the ?filtered=true query flag is inert, quirk 4b). The MAX(interview_round) sub-select does NOT
     * filter by nmms_year (quirk 4f) -- reproduce exactly, do not add a year filter to the subquery.
     */
    public List<Map<String, Object>> studentDetailForFilter(String applicantId, String nmmsYear) {
        return jdbc.sql("""
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
                WHERE a.applicant_id = :applicantId::numeric AND a.nmms_year = :nmmsYear::numeric
                AND s.interview_round = (
                    SELECT MAX(interview_round)
                    FROM pp.student_interview
                    WHERE applicant_id = a.applicant_id
                )
                ORDER BY s.interview_round DESC
                """).param("applicantId", applicantId).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllInterviewRounds($1=applicantId,$2=nmmsYear). */
    public List<Map<String, Object>> allInterviewRounds(String applicantId, String nmmsYear) {
        return jdbc.sql("""
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
                WHERE s.applicant_id = :applicantId::numeric AND a.nmms_year = :nmmsYear::numeric
                ORDER BY s.interview_round ASC
                """).param("applicantId", applicantId).param("nmmsYear", nmmsYear)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllHomeVerificationRounds($1=applicantId). */
    public List<Map<String, Object>> allHomeVerificationRounds(String applicantId) {
        return jdbc.sql("""
                SELECT
                    h.verification_id,
                    TO_CHAR(h.date_of_verification, 'YYYY-MM-DD') AS date_of_verification,
                    h.status AS home_verification_status, h.verified_by,
                    h.verification_type AS home_verification_type,
                    h.doc_name AS home_verification_doc_name, h.doc_type AS home_verification_doc_type,
                    h.remarks
                FROM pp.home_verification h
                WHERE h.applicant_id = :applicantId::numeric
                ORDER BY h.date_of_verification ASC
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/tracking/web/TrackingController.java` (Task 4 portion — endpoints 8-12; Task 5 adds the remaining 2):
```java
package com.rcf.imas.modules.tracking.web;

import com.rcf.imas.modules.tracking.persistence.TrackingReadRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tracking")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node applies zero auth middleware to this mount (Firm Decision 1)
class TrackingController {

    private final TrackingReadRepository reads;

    TrackingController(TrackingReadRepository reads) {
        this.reads = reads;
    }

    @GetMapping("/interviewers")
    public List<Map<String, Object>> interviewers() {
        try {
            return reads.allInterviewers();
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch interviewers.");
        }
    }

    @GetMapping("/students/interviewer/{interviewerId}")
    public Map<String, Object> studentsByInterviewer(@PathVariable String interviewerId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        if (!interviewerId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Interviewer ID provided.");
        }
        try {
            return reads.studentsByInterviewer(interviewerId, page, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch students assigned to interviewer.");
        }
    }

    @GetMapping("/students/{applicantId}/details")
    public List<Map<String, Object>> studentDetails(@PathVariable String applicantId,
                                                      @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear,
                                                      @RequestParam(required = false) String filtered) {
        // filtered=true is INERT (quirk 4b) -- both branches call the identical repository method.
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            List<Map<String, Object>> rows = reads.studentDetailForFilter(applicantId, nmmsYear);
            if (rows.isEmpty()) {
                throw ApiException.error(404, "Student or interview data not found.");
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch student interview details.");
        }
    }

    @GetMapping("/students/{applicantId}/interviews/all")
    public List<Map<String, Object>> allInterviewRounds(@PathVariable String applicantId,
                                                          @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            return reads.allInterviewRounds(applicantId, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch all interview rounds.");
        }
    }

    @GetMapping("/students/{applicantId}/home/all")
    public List<Map<String, Object>> allHomeVerificationRounds(@PathVariable String applicantId) {
        if (!applicantId.matches("\\d+")) {
            throw ApiException.error(400, "Invalid Applicant ID.");
        }
        try {
            return reads.allHomeVerificationRounds(applicantId);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch home verification records.");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TrackingReadsIT` — Expected: PASS.

- [ ] **Step 5: Run full suite (regression)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/TrackingReadRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/TrackingController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/tracking/TrackingReadsIT.java
git commit -m "$(cat <<'EOF'
feat(tracking): interviewers/by-interviewer/details/interviews-all/home-all reads (inert filtered flag, row-count pagination, year-agnostic MAX(round) subquery all preserved)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `getStudents` (dynamic SQL) + `/document` redirect (2 endpoints)

The riskiest task: `getStudentsWithLatestStatus`'s fully dynamic WHERE (Firm Decision 5), and the file-serving redirect endpoint (Firm Decision 6).

**Files:**
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/TrackingReadRepository.java` (add `studentsWithLatestStatus` + `interviewDocument`/`homeVerificationDocument`)
- Edit: `imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/TrackingController.java` (add 2 endpoints)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/tracking/TrackingStudentsAndDocumentIT.java`

**`application.yml`/test property note:** the document endpoint needs a configurable storage root. Add (if not already present) a Spring property `imas.file-storage-path` bound from env var `FILE_STORAGE_PATH` (Node's `PC_STORAGE_ROOT`), e.g. in `application.yml`:
```yaml
imas:
  file-storage-path: ${FILE_STORAGE_PATH:./data}
```
The test overrides this per-class via `@DynamicPropertySource` pointing at a `@TempDir`.

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/tracking/TrackingStudentsAndDocumentIT.java`:
```java
package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TrackingStudentsAndDocumentIT extends PgIntegrationTest {

    @TempDir static Path storageRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("imas.file-storage-path", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() throws IOException {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (964001,'tsAdmin964','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // Student A: latest round SELECTED, no home-verification-required flag.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name) VALUES (964001,2025,'Zed Student 964')").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn, doc_name, doc_type)
                VALUES (964001,1,'COMPLETED','SELECTED','N','report964a.pdf','application/pdf')
                """).update();

        // Student B: latest round REJECTED but home_verification_req_yn='Y' (persistent across rounds via MAX OVER).
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name) VALUES (964002,2025,'Amy Student 964')").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (964002,1,'COMPLETED','REJECTED','Y')
                """).update();

        // Student C: different nmms_year -- must be excluded by the year filter.
        jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, student_name) VALUES (964003,2024,'Other Year Student 964')").update();
        jdbc.sql("""
                INSERT INTO pp.student_interview(applicant_id, interview_round, status, interview_result, home_verification_req_yn)
                VALUES (964003,1,'COMPLETED','SELECTED','N')
                """).update();

        jdbc.sql("""
                INSERT INTO pp.home_verification(applicant_id, date_of_verification, status, verification_type, doc_name, doc_type)
                VALUES (964001, DATE '2025-06-01', 'ACCEPTED', 'PHYSICAL', 'homeverif964.pdf', 'application/pdf')
                """).update();

        Path folder = storageRoot.resolve("Interview-data").resolve("cohort-2025");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("report964a.pdf"), "fake-pdf-bytes");

        adminToken = jwt.issueFinalToken("964001", "tsAdmin964", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.home_verification WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.student_interview WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (964001,964002,964003)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 964001").update();
    }

    @Test
    void studentsNoFiltersReturnsBothCurrentYearStudentsOrderedByName() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964")) // alphabetical
           .andExpect(jsonPath("$.students[1].student_name").value("Zed Student 964"))
           .andExpect(jsonPath("$.totalStudents").value(2));
    }

    @Test
    void studentsFilteredByStatusInList() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("status", "COMPLETED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2)));
    }

    @Test
    void studentsFilteredByResultOnly() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("results", "SELECTED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Zed Student 964"));
    }

    @Test
    void studentsFilteredByHomeVerificationSyntheticValue() throws Exception {
        // 'HOME VERIFICATION REQUIRED' is NOT a real interview_result -- it maps to
        // persistent_verification_req = 'Y' (Firm Decision 4g). Student B qualifies (Y), Student A does not.
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025").param("results", "HOME VERIFICATION REQUIRED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964"));
    }

    @Test
    void studentsFilteredByResultsPlusHomeVerificationCombinedOr() throws Exception {
        // results=SELECTED OR home-verification-required -> both students qualify (A via SELECTED, B via Y flag).
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .param("results", "SELECTED").param("results", "HOME VERIFICATION REQUIRED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(2)));
    }

    @Test
    void studentsFilteredByStatusAndResultCombinedAnd() throws Exception {
        mvc.perform(get("/api/tracking/students").param("nmms_year", "2025")
                .param("status", "COMPLETED").param("results", "REJECTED")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.students", hasSize(1)))
           .andExpect(jsonPath("$.students[0].student_name").value("Amy Student 964"));
    }

    @Test
    void downloadInterviewDocumentRedirectsToDataPath() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "interview")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isFound())
           .andExpect(header().string("Location", "/Data/Interview-data/cohort-2025/report964a.pdf"));
    }

    @Test
    void downloadHomeDocumentRedirectsToDataPath() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "home")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isFound())
           .andExpect(header().string("Location", "/Data/home-verification-data/cohort-2025/homeverif964.pdf"));
    }

    @Test
    void downloadDocumentBadTypeIs400() throws Exception {
        mvc.perform(get("/api/tracking/document/964001/cohort-2025").param("type", "bogus")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest())
           .andExpect(content().string("Invalid parameters."));
    }

    @Test
    void downloadDocumentNoMetadataIs404() throws Exception {
        mvc.perform(get("/api/tracking/document/964002/cohort-2025").param("type", "interview")
                .header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(content().string("Document metadata not found."));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TrackingStudentsAndDocumentIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `TrackingReadRepository.java` (new field + methods):
```java
    // add near the top of the class, alongside PAGE_SIZE:
    // (unchanged PAGE_SIZE = 10 reused for getStudents too, per ground truth quirk 19)

    /**
     * getStudentsWithLatestStatus parity (Firm Decision 5) -- dynamic WHERE built with a StringBuilder +
     * named params shared in lockstep between the data query (adds ORDER BY/LIMIT/OFFSET) and the count
     * query (same WHERE, no LIMIT/OFFSET). 'HOME VERIFICATION REQUIRED' is peeled out of `results` before
     * building the IN-list and instead ORs in persistent_verification_req='Y' (Firm Decision 4g).
     */
    public Map<String, Object> studentsWithLatestStatus(int page, List<String> statuses, List<String> results,
                                                          String nmmsYear) {
        boolean homeVerificationSelected = results != null && results.contains("HOME VERIFICATION REQUIRED");
        List<String> realResults = results == null ? List.of()
                : results.stream().filter(r -> !"HOME VERIFICATION REQUIRED".equals(r)).toList();
        boolean hasStatuses = statuses != null && !statuses.isEmpty();
        boolean hasResults = !realResults.isEmpty();

        String cte = """
                WITH RankedInterviews AS (
                    SELECT
                        a.applicant_id, a.student_name,
                        s.interview_round, s.status, s.interview_result,
                        MAX(s.home_verification_req_yn) OVER (PARTITION BY a.applicant_id) as persistent_verification_req,
                        ROW_NUMBER() OVER (PARTITION BY a.applicant_id ORDER BY s.interview_round DESC) as rn
                    FROM pp.student_interview s
                    JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
                    WHERE a.nmms_year = :nmmsYear::numeric
                ),
                LatestInterviews AS (
                    SELECT * FROM RankedInterviews WHERE rn = 1
                )
                """;

        StringBuilder where = new StringBuilder();
        if (hasStatuses) {
            where.append(" AND UPPER(TRIM(status)) IN (:statuses)");
        }
        if (hasResults || homeVerificationSelected) {
            StringBuilder orClause = new StringBuilder();
            if (hasResults) orClause.append("UPPER(TRIM(interview_result)) IN (:results)");
            if (homeVerificationSelected) {
                if (!orClause.isEmpty()) orClause.append(" OR ");
                orClause.append("UPPER(TRIM(persistent_verification_req)) = 'Y'");
            }
            where.append(" AND (").append(orClause).append(")");
        }

        String selectCore = """
                SELECT applicant_id, student_name, interview_round, status,
                       interview_result AS result, persistent_verification_req as home_verification_req_yn
                FROM LatestInterviews
                """;
        String countCore = "SELECT COUNT(*) FROM LatestInterviews";

        String dataSql = cte + selectCore + where + " ORDER BY student_name ASC LIMIT :limit OFFSET :offset";
        String countSql = cte + countCore + where;

        var dataQuery = jdbc.sql(dataSql).param("nmmsYear", nmmsYear)
                .param("limit", PAGE_SIZE).param("offset", (page - 1) * PAGE_SIZE);
        var countQuery = jdbc.sql(countSql).param("nmmsYear", nmmsYear);
        if (hasStatuses) {
            List<String> upperStatuses = statuses.stream().map(s -> s.toUpperCase().trim()).toList();
            dataQuery = dataQuery.param("statuses", upperStatuses);
            countQuery = countQuery.param("statuses", upperStatuses);
        }
        if (hasResults) {
            List<String> upperResults = realResults.stream().map(r -> r.toUpperCase().trim()).toList();
            dataQuery = dataQuery.param("results", upperResults);
            countQuery = countQuery.param("results", upperResults);
        }

        List<Map<String, Object>> rows = dataQuery.query((rs, i) -> genericRow(rs)).list();
        Integer totalRows = countQuery.query(Integer.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", rows);
        out.put("currentPage", page);
        out.put("totalPages", (int) Math.ceil(totalRows / (double) PAGE_SIZE));
        out.put("totalStudents", totalRows);
        return out;
    }

    /** getInterviewDocument($1=applicantId) -- most recent round with a non-null doc_name. */
    public Map<String, Object> interviewDocument(String applicantId) {
        return jdbc.sql("""
                SELECT doc_name, doc_type, interview_round
                FROM pp.student_interview
                WHERE applicant_id = :applicantId::numeric AND doc_name IS NOT NULL
                ORDER BY interview_round DESC LIMIT 1
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** getHomeVerificationDocument($1=applicantId). */
    public Map<String, Object> homeVerificationDocument(String applicantId) {
        return jdbc.sql("""
                SELECT doc_name, doc_type
                FROM pp.home_verification
                WHERE applicant_id = :applicantId::numeric AND doc_name IS NOT NULL
                ORDER BY date_of_verification DESC, verification_id DESC LIMIT 1
                """).param("applicantId", applicantId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }
```

Add to `TrackingController.java` (constructor updated to also inject a `@Value("${imas.file-storage-path}") String fileStoragePath`; two new endpoints appended):
```java
    // --- add to imports ---
    // import org.springframework.beans.factory.annotation.Value;
    // import org.springframework.http.HttpHeaders;
    // import org.springframework.http.ResponseEntity;
    // import java.nio.file.Files;
    // import java.nio.file.Path;

    // --- constructor becomes ---
    // private final String fileStoragePath;
    // TrackingController(TrackingReadRepository reads, @Value("${imas.file-storage-path}") String fileStoragePath) {
    //     this.reads = reads;
    //     this.fileStoragePath = fileStoragePath;
    // }

    @GetMapping("/students")
    public Map<String, Object> students(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(required = false) List<String> status,
                                         @RequestParam(required = false) List<String> results,
                                         @RequestParam(name = "nmms_year", defaultValue = "2025") String nmmsYear) {
        // Firm Decision 7: the 2025 literal is kept (matches Node's req.query.nmms_year || 2025, ground
        // truth §7 quirk 17) rather than moved to config -- faithful-parity phase; flagged in Deferred.
        try {
            return reads.studentsWithLatestStatus(page, status, results, nmmsYear);
        } catch (Exception e) {
            throw ApiException.error(500, "Could not fetch student tracking data.");
        }
    }

    @GetMapping("/document/{applicantId}/{cohortId}")
    public ResponseEntity<Void> downloadDocument(@PathVariable String applicantId, @PathVariable String cohortId,
                                                  @RequestParam String type) {
        if (!applicantId.matches("\\d+") || cohortId.isBlank()
                || !("interview".equals(type) || "home".equals(type))) {
            return ResponseEntity.badRequest().header(HttpHeaders.CONTENT_TYPE, "text/plain").body(null)
                    .status(400).contentType(org.springframework.http.MediaType.TEXT_PLAIN).body(null);
        }
        try {
            Map<String, Object> meta = "interview".equals(type)
                    ? reads.interviewDocument(applicantId)
                    : reads.homeVerificationDocument(applicantId);
            if (meta == null || meta.get("doc_name") == null) {
                throw ApiException.error(404, "Document metadata not found.").with("__plainText", true);
            }
            String rawDocName = String.valueOf(meta.get("doc_name"));
            // basic traversal guard: strip to the last path segment on either separator (Firm Decision 6)
            String[] parts = rawDocName.split("[\\\\/]");
            String cleanDocName = parts.length == 0 ? rawDocName : parts[parts.length - 1];

            String folder = "interview".equals(type) ? "Interview-data" : "home-verification-data";
            Path onDisk = Path.of(fileStoragePath, folder, cohortId, cleanDocName);
            if (!Files.exists(onDisk)) {
                throw ApiException.error(404, "File not found on storage.").with("__plainText", true);
            }
            String location = "/Data/" + folder + "/" + cohortId + "/" + cleanDocName;
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Server Error.").with("__plainText", true);
        }
    }
```

**Important correction for the `downloadDocument` 400/plain-text handling:** the inline sketch above is malformed (chained `.body(null)` calls). Replace the 400 branch and add a small controller-advice hook so this endpoint's errors render as `text/plain` (matching Node's `res.status(400).send("Invalid parameters.")` etc., not JSON) instead of the module's usual `{error:...}` JSON envelope. Implement it directly in the handler rather than via `ApiException` (whose `GlobalExceptionHandler` path always renders JSON) — this is the one endpoint in the whole module with plain-text error bodies:
```java
    @GetMapping("/document/{applicantId}/{cohortId}")
    public ResponseEntity<?> downloadDocument(@PathVariable String applicantId, @PathVariable String cohortId,
                                               @RequestParam String type) {
        if (!applicantId.matches("\\d+") || cohortId.isBlank()
                || !("interview".equals(type) || "home".equals(type))) {
            return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body("Invalid parameters.");
        }
        try {
            Map<String, Object> meta = "interview".equals(type)
                    ? reads.interviewDocument(applicantId)
                    : reads.homeVerificationDocument(applicantId);
            if (meta == null || meta.get("doc_name") == null) {
                return ResponseEntity.status(404).contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body("Document metadata not found.");
            }
            String rawDocName = String.valueOf(meta.get("doc_name"));
            String[] parts = rawDocName.split("[\\\\/]");
            String cleanDocName = parts.length == 0 ? rawDocName : parts[parts.length - 1];

            String folder = "interview".equals(type) ? "Interview-data" : "home-verification-data";
            Path onDisk = Path.of(fileStoragePath, folder, cohortId, cleanDocName);
            if (!Files.exists(onDisk)) {
                return ResponseEntity.status(404).contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body("File not found on storage.");
            }
            String location = "/Data/" + folder + "/" + cohortId + "/" + cleanDocName;
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body("Server Error.");
        }
    }
```
(This replaces the malformed sketch above — use only this final version.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TrackingStudentsAndDocumentIT` — Expected: PASS.

- [ ] **Step 5: Run full suite (regression)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all 5 tasks' tests green alongside every prior phase.

- [ ] **Step 6: Commit**

```
git add imas-backend/src/main/java/com/rcf/imas/modules/tracking/persistence/TrackingReadRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/tracking/web/TrackingController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/tracking/TrackingStudentsAndDocumentIT.java \
        imas-backend/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(tracking): dynamic getStudents (status/result/home-verification filters, StringBuilder+named-params query builder) + document redirect (plain-text errors, path-traversal guard)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Final review (after all 5 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/tracking` package against this plan + the ground truth, checking:
- **Auth uniformity:** both `ActiveTimetableController` and `TrackingController` are `@PreAuthorize("hasRole('ADMIN')")` class-level, no method-level overrides. Cross-check all 14 endpoints against the endpoint contract table.
- **`genericRow`'s convention:** confirm the single definition (in `ActiveTimetableReadRepository`) uses `toPlainString()` (module-specific deviation from Plan 4b's `toBigInteger()`), and that `TrackingReadRepository`/`ActiveTimetableWriteRepository` call it via the same-package static reference. Spot-check `life_goals_and_zeal`/`commitment_to_learning`/`integrity`/`communication_skills` render with decimals intact (e.g. `"4.5"`, not `"4"`) in at least one IT assertion.
- **Day-of-week sort quirk:** confirm `combinedByCohort` uses the Sun→Sat `CASE` and `teacherWise`/`batchWise` use plain `ORDER BY tt.day_of_week` (alphabetical) — no accidental unification of the three orderings.
- **`teacherWise` cohort-blindness:** confirm no `WHERE ... cohort` clause was added to `teacherWise`'s SQL even though the controller receives a `cohort` param on `/fetch` — the param must be silently ignored for `type=teacher`.
- **`deleteTeacherSkill` case-sensitivity:** confirm no `UPPER()` wraps `medium` in the DELETE, and no rowcount check throws/warns on a 0-row delete.
- **`created_by` on `addSubject`:** confirm it reads `principal.userId()` from `@AuthenticationPrincipal JwtService.FinalToken`, never a request-body field — flag any regression toward the client-controlled Node original.
- **`/download-pdf`:** confirm it never queries the database (no `@Autowired` repository dependency in `TimetablePdfSupport`, no repository call in the controller's `downloadPdf` handler beyond building the PDF from the request body) and never writes to disk (`ByteArrayOutputStream` only).
- **`getStudentsWithLatestStatus`'s dynamic SQL:** confirm the `WHERE` `StringBuilder` is built identically (same string) for both the data query and the count query, and unit/integration-test coverage exists for all 6 combinations called out in Firm Decision 5 (no filters; statuses only; results only; home-verification only; results+home-verification OR; statuses+results AND). Confirm `'HOME VERIFICATION REQUIRED'` is peeled out of `results` before the IN-list is built, never passed through as a literal `interview_result` value.
- **`getStudentsByInterviewer` vs `getStudentsWithLatestStatus` pagination semantics:** confirm the former counts rows (no DISTINCT/ROW_NUMBER) and the latter dedupes via `ROW_NUMBER()...rn=1` — genuinely different pagination bases, both intentional, not unified.
- **`getStudentdetailforFilter`'s year-agnostic subquery:** confirm the inner `MAX(interview_round)` subquery has no `nmms_year` predicate, matching the ground truth exactly (a deliberately preserved edge case, not a bug to silently fix).
- **`downloadDocument`'s response content type:** confirm 400/404/500 on this one endpoint render as `text/plain` bodies (not the module's usual `{error:...}` JSON), matching Node's `res.send(...)` calls; confirm the path-traversal guard (`split` on both separators, take the last segment) is present without an over-engineered allowlist (Firm Decision 6 — deliberately minimal).
- **Numeric serialization spot-check:** `interview_round`/`subject_id`/`teacher_id`/`batch_id`/`cohort_number` render as JSON numbers; `interviewer_id`/`applicant_id`/`interview_id`/`verification_id`/`created_by`/`updated_by` render as JSON strings; `life_goals_and_zeal` etc. render as decimal-preserving JSON strings (not truncated integers).

Update `imas-migration-status` memory: Phase 4c complete, new test count, ready for Phase 5.

## Deferred / parity decisions carried into this plan

- **`/api/timetable` (old `timeTableRoutes.js`/`timetableController.js`) is dead — not ported.** Commented out at Node's `index.js:306`; out of scope entirely (ground truth §7 quirk 1).
- **`getStudentDetails` is ported despite being effectively unreachable from the current frontend** (`EvaluationTracking.js` only calls `/interviews/all` and `/home/all` for its detail view) — kept for parity/back-compat since it is a real, live, documented route in Node (ground truth §1/§7 quirk 12). If a future audit confirms zero external callers, this endpoint is a safe candidate to drop.
- **`/download-pdf`'s two disk-logo images are NOT ported** (`fs.existsSync`-gated `rcf_logo-removebg-preview.png`/`logo.png`) — text-only header instead, matching `CustomListPdfSupport`'s precedent (Firm Decision 3). A future UX pass could add real logo embedding via `Image.getInstance(bytes)` if the assets are made available to the Java build.
- **The hard-coded `"PRATIBHA POSHAK EXAMINATION - 2025"` PDF header text is reproduced verbatim, not parameterized** (ground truth §7 quirk 8) — will read wrong for future admission cycles. Flagged for the team as a genuine "worth fixing" item, consistent with `CustomListPdfSupport`'s own analogous hard-coded-year precedent; out of scope for a faithful-parity port.
- **The default `nmms_year=2025` fallback is kept as a literal** on `/students`, `/students/interviewer/{id}`, `/students/{id}/details`, `/students/{id}/interviews/all` (ground truth §7 quirk 17: Node's `req.query.nmms_year || 2025` appears in three-plus places) — not moved to `pp.system_config` or any other config source in this phase. Frontend always sends the param when admissions are open, so real-world risk is low; flagged for a follow-up once the 2025 cycle closes.
- **`getAllInterviewers` has no active/inactive filter** — inactive interviewers still populate the dropdown (ground truth §7 quirk 14). Preserved as-is; a product decision, not a parity bug.
- **`downloadDocument`'s filename handling is a basic traversal guard only** (split-and-take-last-segment), not a full allowlist/regex validator (ground truth §7 quirk 15, Firm Decision 6) — the value originates from the DB, not directly from user input, but the DB is seedable via other admin endpoints outside this phase's scope. Flagged as a known minor residual risk, matching Node exactly; a stricter fix (validate the resolved path stays under the expected directory) is a reasonable follow-up ticket, not done here to avoid a real, if narrow, behavior change.
- **`cohortId`/`cohortFolder` in `/document/{applicantId}/{cohortId}` is treated as an opaque path-segment string**, never resolved against `pp.cohort.cohort_name` server-side (ground truth §7 quirk 16, matching the frontend's own `cohort-${academic_year.split('-')[0]}` convention, which is a derived string, not a DB column). No validation added in Java beyond non-blank.
- **ADMIN enforcement on all 14 endpoints is NEW** vs Node's fully-open routes (audit CRITICAL, per Firm Decision 1) — add to the fetch audit alongside Plan 4a/4b's equivalent findings. Also flag Firm Decision 2 (`created_by` now server-derived, not client-posted) as a related, small, intentional behavior change on `POST /api/activetimetable/subject/add`.
