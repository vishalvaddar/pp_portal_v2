# IMAS Spring Boot Migration — Plan 3a: Exams (Exam Centres, Scheduling, Hall Tickets)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `server/controllers/examControllers.js` (1837 lines) + `server/models/examModels.js` (337 lines) + `server/routes/examRoutes.js` (86 lines) to a new `com.rcf.imas.modules.exams` module, base `/api/exams`. 19 live endpoints ported (of 20 wired routes + 1 dead route + 1 broken inline route in Node — see Firm Decisions below for exactly what is dropped/de-duplicated/fixed).

All endpoints are `@PreAuthorize("hasRole('ADMIN')")` **except** `GET /hallticket/{hallTicketNo}`, which is PUBLIC (method-level override) — the one endpoint the frozen React app calls unauthenticated from `StudentHallticketPage.js`. Node left every route in this module wide open; ADMIN enforcement everywhere else is new, intended hardening (same posture as Plans 2a–3d).

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `exams` with `web/`, `persistence/`, `service/`:
- `ExamsReadRepository` — genericRow mapper (extended with `java.sql.Array` → `List<String>` handling for the `ARRAY_AGG` columns in `/assigned`) + every read query in the module.
- `ExamsWriteRepository` (`@Repository`, dedicated bean — self-invocation does not honor `@Transactional`) — exam-centre create/update/delete (single-statement, autocommit, matching Node), plus the three genuinely multi-statement flows that get **real** `@Transactional`: `deleteExam` (Firm Decision 3 — Node's version is not actually atomic), `createExamOnly` (time-overlap check + insert), `assignStudents` (verify exam → shortlist query → per-applicant sequence bump + insert loop).
- `ExamCallingListXlsxSupport` — POI, in-memory `ByteArrayOutputStream`, no disk write (Firm Decision 9).
- `HallTicketPdfSupport` — OpenPDF (`com.lowagie.text.*`, already on the classpath, no pom change), in-memory, embeds the Kannada TTF.
- `HallTicketZipSupport` — `java.util.zip.ZipOutputStream` (JDK built-in), one `HallTicketPdfSupport.build(...)` call per student, streamed via `ByteArrayOutputStream`.
- `ExamsController` — one controller (mirrors Node's one-controller-file structure), 19 handlers.

**Tech Stack:** No new dependencies. `com.github.librepdf:openpdf:2.0.3` and `org.apache.poi:poi-ooxml:5.3.0` are already in `imas-backend/pom.xml` (confirmed — added for the evaluation module's custom-list PDF/XLSX exports in Plan 3b). `java.util.zip.ZipOutputStream` is JDK-built-in.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Assumes Plans 1, 2a, 2b, 2c, 3b, 3d are merged and green.

---

> **⚠ LOCKED CONVENTIONS (from Plans 1/2a/2b/2c/3b/3d — apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON.
> 2. **Numeric-column params: cast the PARAM** — `pp_exam_centre_id = :id::numeric`, `stateId::numeric`, `divisionId::numeric`, `districtId::numeric`, `blockId::numeric`, etc. `exam_year`/`academic_year` are `varchar`, never cast. Postgres arrays (`juris_code = ANY(:blocks)`) bind as `BigDecimal[]`/`String[]` per column type.
> 3. **Numeric + bigint columns serialize as Strings** via the shared `genericRow` mapper, **except**: `GET /used-blocks`, which is the one deliberate exception in this module — real JSON numbers (Firm Decision 6 / ground-truth quirk 8), because the frontend does `usedBlocks.includes(Number(b.id))`. `DATE` → `"yyyy-MM-dd"`. `TIME` → `"HH:mm:ss"`. `TIMESTAMP` → ISO-Z. **Postgres `ARRAY` columns** (the `district_ids`/`district_names`/`block_ids`/`block_names` from `ARRAY_AGG` in `/assigned`) → `List<String>` (each numeric element `BigDecimal.toBigInteger().toString()`, each text element passthrough) — this is a NEW extension to `genericRow` needed by this module; add it once in `ExamsReadRepository.genericRow`, do not duplicate. Everything else passes through `rs.getObject(i)`.
> 4. **snake_case JSON** global default (POJO fields only — Map keys pass through literally).
> 5. **Errors:** throw `ApiException.error(status,msg)` → `{error:...}` or `.message(status,msg)` → `{message:...}`; `.with(key,value)` adds extra keys (e.g. `POST /exam-centres`'s 409 `field`). This module's error-key mapping is **NOT uniform** — Node's per-endpoint catch blocks use inconsistent keys; reproduce each exactly (see the endpoint contract table). Several endpoints' failure paths coincide with `GlobalExceptionHandler`'s generic `{error:"Internal Server Error"}` fallback (the 4 jurisdiction-cascade endpoints) — no try/catch needed there; most others need an explicit try/catch to reproduce a distinct `{message:...}` body or an `{error:..., message:...}` combination.
> 6. **Controllers:** class package-private; every handler method **`public`**. One `ExamsController` class (mirrors Node's single `examControllers.js`), constructor grows task-by-task.
> 7. **Auth (NEW enforcement):** `@PreAuthorize("hasRole('ADMIN')")` at class level; `GET /hallticket/{hallTicketNo}` overrides with its own `@PreAuthorize("permitAll()")` (Spring method-level `@PreAuthorize` replaces, not ANDs with, the class-level one). `SecurityConfig` **already has** a forward-declared `.requestMatchers(HttpMethod.GET, "/api/exams/hallticket/**").permitAll()` matcher (added in Plan 1, comment says "forward-declared... the endpoint itself is built in the examination phase") — Task 6 just needs to update that stale comment, not add the matcher.
> 8. **Transactions:** only `deleteExam`, `createExamOnly`, and `assignStudents` are `@Transactional`, all three in the dedicated `ExamsWriteRepository` bean (never self-invoked). Exam-centre create/update/delete and freeze are single-statement, autocommit, no `@Transactional` (matches Node, which never wraps these either).
> 9. **Test isolation:** all `*IT` extend `PgIntegrationTest` (one JVM-wide embedded Postgres). Seed `jurisdiction_type` before `jurisdiction`. Seed `pp."user"` before any row with a `created_by`/`updated_by` FK. `@AfterEach`-clean children-before-parents. Advance sequences (`setval`) after explicit-PK seeds.
> 10. **`pp."user"`** is a quoted reserved word; `pp.user` (unquoted after the dot) is accepted.
> 11. **File generation is in-memory (Firm Decision 9)** — no disk writes, no `FILE_STORAGE_PATH` env var, no temp-file cleanup dance. XLSX/PDF/ZIP all build directly to `ByteArrayOutputStream` and stream `byte[]` via `ResponseEntity<byte[]>`.
> 12. **Dropped/fixed vs. Node (do not "faithfully" reproduce these):** `GET /count` dropped entirely (Firm Decision 1, permanently broken in Node). `POST /:examId/assign-students`'s duplicate route registration de-duplicated to one `@PostMapping` (Firm Decision 4, Spring would throw ambiguous-mapping on a literal duplicate). `createExamAndAssignApplicants` not ported at all (Firm Decision 5, dead code, route commented out, internally broken). `GET /viewcentres` gets a real 500 on DB failure instead of Node's hang (Firm Decision 2). `deleteExam` gets a real `@Transactional` instead of Node's non-atomic `pool.query("BEGIN")` two-step (Firm Decision 3).

---

## Ground truth used by this plan

Full detail: `docs/superpowers/plans/artifacts/phase3a-exams-ground-truth.md` (476 lines, 7-section deep dive with 14 ranked quirks). Node source read in full: `server/controllers/examControllers.js` (1837 lines), `server/models/examModels.js` (337 lines), `server/routes/examRoutes.js` (86 lines).

### Table facts relevant to this module (from `live-schema.sql`)

- **`pp.pp_exam_centre`** — `pp_exam_centre_id numeric(10,0)` PK; `pp_exam_centre_code varchar(20)`; `pp_exam_centre_name varchar(200) NOT NULL`; `address/village varchar`; `pincode varchar(12)`; `contact_person varchar(100)`; `contact_phone varchar(12)`; `contact_email varchar(200)`; `sitting_capacity integer CHECK(>=0)`; `active_yn char(1) DEFAULT 'Y' CHECK IN('Y','N')`; `latitude/longitude numeric(15,2)`; `google_map_link text GENERATED ALWAYS AS (...) STORED` — **never write to it**, `SELECT *`/`RETURNING *` includes it. Only real UNIQUE constraint: `pp_exam_centre_pp_exam_centre_code_key UNIQUE(pp_exam_centre_code)` (note the doubled `pp_exam_centre_` prefix — Node's `error.constraint==='pp_exam_centre_code_key'` branch never matches this, confirming it's dead code; not ported, per Firm Decision 7).
- **`pp.examination`** — `exam_id numeric(14,0)` PK; `exam_name varchar(100) NOT NULL`; `exam_date date NOT NULL`; `exam_start_time/exam_end_time time NOT NULL`; `pp_exam_centre_id numeric(10,0)`; `frozen_yn char(1) DEFAULT 'N'`; `exam_year varchar(10)` — **nullable, no format constraint** (Firm Decision 11d orphan quirk).
- **`pp.applicant_exam`** — composite PK `(applicant_id, exam_id)`; `pp_hall_ticket_no varchar(20)` with its own separate `UNIQUE(pp_hall_ticket_no)` constraint; FKs to `applicant_primary_info`/`examination`.
- **`pp.hall_ticket_sequence`** — `id` PK; `academic_year varchar(9) NOT NULL`; `juris_code varchar(20) NOT NULL` (text, while `jurisdiction.juris_code` is `numeric(12,0)` — implicit cast on write); `last_sequence integer DEFAULT 0 NOT NULL`; `UNIQUE(academic_year, juris_code)` — the `ON CONFLICT` target.
- **`pp.jurisdiction`** — `juris_code numeric(12,0) NOT NULL`; `juris_name varchar(100)`; `juris_type varchar(100)`; `parent_juris numeric(12,0)`.
- **`pp.applicant_primary_info`** — relevant columns: `applicant_id`, `nmms_reg_number numeric(11,0) NOT NULL`, `district/nmms_block numeric(12,0)`, `student_name/father_name/mother_name varchar`, `gmat_score/sat_score numeric(2,0)`, `contact_no1/contact_no2 varchar(12)`, `current_institute_dise_code varchar(15)`, `aadhaar`, `dob`.
- **`pp.applicant_shortlist_info`** — `applicant_id`, `shortlisted_yn char(1)`, `shortlist_batch_id numeric(6,0)`.
- **`pp.shortlist_batch`** — `shortlist_batch_id` PK, `shortlisted_year numeric(4,0) NOT NULL`.
- **`pp.institute`** — `dise_code varchar(15)`, `institute_name varchar(200)`.

### Endpoint contract (19 handlers, all `@PreAuthorize("hasRole('ADMIN')")` except #19)

| # | Method + Path | Task | Success | Errors |
|---|---|---|---|---|
| 1 | GET `/exam-centres` | 1 | `200 [{pp_exam_centre_id, pp_exam_centre_name}]` (id as String) | `500 {error:"Failed to fetch exam centres"}` |
| 2 | POST `/exam-centres` | 1 | `201 {success:true, message:"Exam centre created successfully", centre:{...RETURNING *...}}` | `400 {message}` (5 distinct validation messages) / `409 {message, field}` (dup precheck) / `500 {message:"Failed to create centre"}` |
| 3 | DELETE `/exam-centres/{id}` | 1 | `204` | `400 {message:"Centre already used in exam: <name>"}` / `500 {message:"Failed to delete centre"}` |
| 4 | PUT `/exam-centres/{id}` | 1 | `200 {message:"Updated successfully", centre:{...}}` | `404 {message:"Centre not found"}` / `500 {message:"Update failed", error}` |
| 5 | GET `/viewcentres` | 1 | `200 [{...every column...}]` (all rows, incl. inactive) | `500 {error:"Internal Server Error"}` (Node hangs here — Firm Decision 2 fix) |
| 6 | GET `/divisions-by-state/{stateId}` | 2 | `200 [{id, name}]` | `500 {error:"Internal Server Error"}` |
| 7 | GET `/education-districts-by-division/{divisionId}` | 2 | `200 [{id, name}]` | same |
| 8 | GET `/blocks-by-district/{districtId}` | 2 | `200 [{id, name}]` | same |
| 9 | GET `/clusters-by-block/{blockId}` | 2 | `200 [{id, name}]` | same |
| 10 | GET `/used-blocks?year=` | 2 | `200 [12345, ...]` **JSON numbers** | `500 {error:"Failed to fetch used blocks"}` |
| 11 | GET `/assigned?year=` | 3 | `200 [{exam_id, exam_name, exam_date, frozen_yn, pp_exam_centre_id, pp_exam_centre_name, exam_start_time, exam_end_time, district_ids[], district_names[], block_ids[], block_names[]}]` | `400 {message:"Year is required"}` / `500 {message:"Failed to fetch exams"}` |
| 12 | GET `/notassigned?year=` | 3 | `200 [{...same 8 fields, no arrays...}]` | same as #11 |
| 13 | PUT `/{examId}/freeze` | 3 | `200 {message:"✅ Exam frozen successfully"}` (unconditional, no existence check) | `500 {message:"Failed to freeze exam"}` |
| 14 | DELETE `/{examId}` | 3 | `200 {message:"Exam and related data deleted successfully"}` (unconditional) | `500 {message:"Failed to delete exam"}` |
| 15 | POST `/create` | 4 | `201 {message:"Exam created successfully", examId}` | `400 {error:"Missing required fields."}` / `409 {error:"Time conflict", message}` / `500 {message:"Server error", error}` |
| 16 | POST `/{examId}/assign-students` | 4 | `201 {message:"Applicants assigned to exam successfully ✅", examId, totalAssigned, applicants:[{applicant_id, applicant_name, hall_ticket_no}]}` | `400 {error:"Missing required fields: examId, division, educationDistrict, blocks[]"}` / `404 {error:"Exam does not exist."}` OR `{message:"No shortlisted applicants found for the selected region."}` (inconsistent key, both preserved) / `500 {message:"Server error", error}` |
| 17 | GET `/{examId}/student-list` | 5 | `200` XLSX bytes, `filename="<exam_with_underscores>_Calling_List.xlsx"` | `404 {message:"No students found for this exam."}` / `500 {message:"Failed to generate Excel file", error}` |
| 18 | GET `/{examId}/{examName}/download-all-hall-tickets` | 6 | `200` ZIP bytes, `filename=All_Hall_Tickets_<id>_<sanitized name>.zip` | `404 {message:"No hall tickets found"}` / `500 {message:"Failed to download hall tickets", error}` |
| 19 | GET `/hallticket/{hallTicketNo}` **PUBLIC** | 6 | `200` PDF bytes, `filename="<hallTicketNo>.pdf"` | `404 {message:"Hall ticket not found"}` / `500 {message:"Failed to download hall ticket", error}` |

**Dropped:** `GET /count` (Firm Decision 1, permanently broken — `db` undefined + nonexistent `pp.exam` table). **Not ported:** `POST /create` (`createExamAndAssignApplicants`, dead — route commented out in Node, calls an undefined 2-arg `generateHallTicket`). **De-duplicated:** `assignApplicantsToExam`'s doubled route registration → one `@PostMapping`.

## Firm decisions baked into this plan (from the lead — do not "port verbatim" past these)

1. `GET /count` dropped permanently. FLAG USER: implement `SELECT COUNT(*) FROM pp.examination WHERE pp_exam_centre_id=? AND exam_date=?` only if a caller needs it.
2. `GET /viewcentres` implemented WORKING (all columns/rows) with a real 500 on failure (Node hangs — genuine bug, not reproduced).
3. `deleteExam` gets a real `@Transactional` in `ExamsWriteRepository` (Node's `pool.query("BEGIN")` on the shared pool is not atomic).
4. `assign-students` route de-duplicated to one `@PostMapping`.
5. `createExamAndAssignApplicants` not ported (dead, route commented out, internally broken).
6. `GET /used-blocks?year=` returns a bare array of JSON **numbers** — the one numeric-typed-array exception in this module.
7. `createExamCentre` dup detection via the pre-insert `checkExistingCentre` SELECT only; the fictitious `error.constraint` name-matching branches are not ported (those constraint names don't exist in the schema). TOCTOU race accepted and documented.
8. `GET /hallticket/{hallTicketNo}` is PUBLIC — method-level `@PreAuthorize("permitAll()")` override; `SecurityConfig`'s forward-declared permit matcher already covers the filter-chain side.
9. File generation entirely in-memory (`ByteArrayOutputStream`) — no disk writes anywhere in this module.
10. Hall-ticket PDF (OpenPDF) reproduces the FUNCTIONAL content verbatim (all data fields, hardcoded institutional strings including "PRATIBHA POSHAK EXAMINATION - 2026", 4 signature boxes, Kannada instructions block) with the 5 assets shipped as classpath resources under `exam-assets/`. Pixel-perfect parity is NOT required; Kannada shaping fidelity is best-effort (documented risk).
11. Quirks preserved VERBATIM (pinned where testable): (a) `updateExamCentre`'s `active_yn || 'Y'` fallback; (b) `freezeExam` has no existence check; (c) `deleteExam` has no existence check; (d) `createExamOnly` allows `academic_year` omitted → `exam_year` NULL (orphan quirk); (e) hall-ticket sequence "gap not collision" on `ON CONFLICT DO NOTHING`; (f) the two distinct "year" values (`exam.exam_year` vs. request-body `academicYear`) never cross-validated.
12. Hall-ticket number algorithm (verbatim): `yearSuffix = academicYear.substring(2,4)`; `jurisLast2 = last 2 chars of juris_code-as-plain-decimal-string, left-padded to 2`; `seq = sequenceNumber left-padded to 4`; ticket = `yearSuffix + jurisLast2 + seq`.

## File structure (created by this plan)

```
imas-backend/src/main/java/com/rcf/imas/modules/exams/
├── web/ExamsController.java                    (all 6 tasks: 19 handlers, grows incrementally)
├── persistence/ExamsReadRepository.java         (Tasks 1,2,3,5,6: genericRow w/ ARRAY support + all reads)
├── persistence/ExamsWriteRepository.java        (Tasks 1,3,4: centre CRUD + @Transactional deleteExam/createExamOnly/assignStudents)
├── service/ExamCallingListXlsxSupport.java       (Task 5: POI calling-list XLSX)
├── service/HallTicketPdfSupport.java             (Task 6: OpenPDF single hall ticket)
└── service/HallTicketZipSupport.java              (Task 6: ZipOutputStream, N per-student PDFs)

imas-backend/src/main/resources/exam-assets/
├── rcf_logo-removebg-preview.png
├── logo.png
├── ravi_sir_sign-removebg-preview.png
├── rcf_stamp-removebg-preview.png
└── NotoSansKannada-Regular.ttf

imas-backend/src/test/java/com/rcf/imas/modules/exams/
├── ExamCentresIT.java             (Task 1: 5 exam-centre/viewcentres endpoints)
├── ExamJurisdictionIT.java        (Task 2: 4 cascades + used-blocks numeric-array pin)
├── ExamListingIT.java             (Task 3: assigned/notassigned ARRAY_AGG mapping, freeze, delete-transactional)
├── ExamCreateAssignIT.java        (Task 4: createExamOnly time-conflict + assign-students sequence/numbering)
├── ExamStudentListIT.java         (Task 5: calling-list XLSX)
└── ExamHallTicketIT.java          (Task 6: public single PDF + admin ZIP, full suite regression)
```

Notes for the implementing engineer:
- Repo root `C:\work\rcf`. One test: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`; full suite: drop `-Dtest`.
- Every task = red test → confirm fail → implement → confirm pass → commit. Serialize tasks.
- Tokens: `jwt.issueFinalToken("<userId>","<name>","ADMIN"|"STUDENT")`.
- `{examId}`/`{id}`/`{stateId}`/etc. bind as Strings, cast `::numeric` in SQL per convention #2 — a non-numeric segment throws a Postgres cast error → generic 500 (matches Node's NaN-bound-param behavior).

---

## Task 1: module skeleton + `ExamsReadRepository` + `ExamsWriteRepository` + exam-centre CRUD + `/viewcentres`

Port `GET /exam-centres`, `POST /exam-centres`, `DELETE /exam-centres/{id}`, `PUT /exam-centres/{id}`, `GET /viewcentres`. Establish the `genericRow` mapper (with the `ARRAY` extension needed later by Task 3) and both repository beans.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamCentresIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamCentresIT.java`:
```java
package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamCentresIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin, student;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('ecseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='ecseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "ecseed", "ADMIN");
        student = jwt.issueFinalToken("999", "s", "STUDENT");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn, contact_phone, contact_email)
            VALUES (80001,'EC001','Active Centre','Y','9000000001','active@x.com') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_code, pp_exam_centre_name, active_yn)
            VALUES (80002,'EC002','Inactive Centre','N') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id IN (80001,80002) OR pp_exam_centre_name LIKE 'New Centre%'").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'ecseed'").update();
    }

    @Test
    void listActiveCentresOnlyProjectsIdAndName() throws Exception {
        mvc.perform(get("/api/exams/exam-centres").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80001')].pp_exam_centre_name").value(org.hamcrest.Matchers.hasItem("Active Centre")))
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80002')]").isEmpty()); // inactive excluded
    }

    @Test
    void viewcentresReturnsAllColumnsAllRows() throws Exception {
        mvc.perform(get("/api/exams/viewcentres").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.pp_exam_centre_id=='80002')].active_yn").value(org.hamcrest.Matchers.hasItem("N"))); // inactive included
    }

    @Test
    void createCentreRejectsBlankName() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"  \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Centre name is required."));
    }

    @Test
    void createCentreRejectsInvalidPincode() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"New Centre X\",\"pincode\":\"abc\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Invalid pincode."));
    }

    @Test
    void createCentreDuplicateCodeIs409WithField() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"New Centre Dup\",\"pp_exam_centre_code\":\"EC001\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value("Centre code already exists. Please use a different value."))
           .andExpect(jsonPath("$.field").value("centre_code"));
    }

    @Test
    void createCentreSuccessReturnsFullRowIncludingGeneratedGoogleMapLink() throws Exception {
        mvc.perform(post("/api/exams/exam-centres").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"New Centre Full\",\"latitude\":12.97,\"longitude\":77.59,\"sitting_capacity\":\"50\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.centre.pp_exam_centre_name").value("New Centre Full"))
           .andExpect(jsonPath("$.centre.sitting_capacity").value("50"))
           .andExpect(jsonPath("$.centre.google_map_link").value(org.hamcrest.Matchers.containsString("google.com/maps")));
    }

    @Test
    void deleteCentreBlockedWhenUsedInExam() throws Exception {
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, pp_exam_centre_id)
            VALUES (80101,'Blocker Exam','2026-01-01','09:00:00','11:00:00',80001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        try {
            mvc.perform(delete("/api/exams/exam-centres/80001").header("Authorization", "Bearer " + admin))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message").value("Centre already used in exam: Blocker Exam"));
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 80101").update();
        }
    }

    @Test
    void deleteCentreSucceedsWhenUnused() throws Exception {
        mvc.perform(delete("/api/exams/exam-centres/80002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNoContent());
    }

    @Test
    void updateCentreFalsyActiveYnResetsToY() throws Exception {
        mvc.perform(put("/api/exams/exam-centres/80002").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"pp_exam_centre_name\":\"Inactive Centre Renamed\"}")) // active_yn omitted
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.centre.active_yn").value("Y")); // reset quirk (Firm Decision 11a)
    }

    @Test
    void updateCentreMissingIdIs404() throws Exception {
        mvc.perform(put("/api/exams/exam-centres/999999999").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"pp_exam_centre_name\":\"X\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Centre not found"));
    }

    @Test
    void examCentreEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/exams/exam-centres").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/viewcentres").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamCentresIT` — Expected: FAIL (no controller/repositories yet).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java`:
```java
package com.rcf.imas.modules.exams.persistence;

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
public class ExamsReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public ExamsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** node-pg parity: NUMERIC/BIGINT -> String; DATE -> "yyyy-MM-dd"; TIME -> "HH:mm:ss"; TIMESTAMP -> ISO-Z;
     *  Postgres ARRAY (e.g. ARRAY_AGG(juris_code)/ARRAY_AGG(juris_name) in /assigned) -> List&lt;String&gt;, each
     *  element following the same NUMERIC->String / text->passthrough rule; else passthrough. Map keys are the
     *  column label verbatim (handles the query's own AS aliases unchanged). */
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
                    val = arr == null ? null : arrayToStringList(arr);
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    private static List<String> arrayToStringList(Array arr) throws SQLException {
        Object raw = arr.getArray();
        List<String> out = new ArrayList<>();
        int len = java.lang.reflect.Array.getLength(raw);
        for (int i = 0; i < len; i++) {
            Object el = java.lang.reflect.Array.get(raw, i);
            if (el == null) { out.add(null); }
            else if (el instanceof BigDecimal bd) { out.add(bd.toBigInteger().toString()); }
            else { out.add(String.valueOf(el)); }
        }
        return out;
    }

    /** getExamCentres() parity: active-only, id+name projection. */
    public List<Map<String, Object>> activeCentres() {
        return jdbc.sql("""
                SELECT pp_exam_centre_id, pp_exam_centre_name
                FROM pp.pp_exam_centre
                WHERE active_yn = 'Y'
                ORDER BY pp_exam_centre_name ASC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    /** getexamcentresview() parity: every column, every row (active + inactive). Firm Decision 2: unlike Node,
     *  a DB failure here surfaces as a real 500 via GlobalExceptionHandler, not a hang. */
    public List<Map<String, Object>> allCentresAllColumns() {
        return jdbc.sql("SELECT * FROM pp.pp_exam_centre").query((rs, i) -> genericRow(rs)).list();
    }

    /** checkExistingCentre() parity: pre-insert dup guard, code OR name OR phone OR email match. */
    public Map<String, Object> findExistingCentre(String code, String name, String phone, String email) {
        return jdbc.sql("""
                SELECT * FROM pp.pp_exam_centre
                WHERE pp_exam_centre_code = :code
                   OR pp_exam_centre_name = :name
                   OR contact_phone = :phone
                   OR contact_email = :email
                LIMIT 1
                """).param("code", code).param("name", name).param("phone", phone).param("email", email)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** deleteExamCentre's usage guard: any exam already referencing this centre. */
    public String examNameUsingCentre(String centreId) {
        return jdbc.sql("SELECT exam_name FROM pp.examination WHERE pp_exam_centre_id = :id::numeric LIMIT 1")
                .param("id", centreId).query(String.class).optional().orElse(null);
    }
}
```

`src/main/java/com/rcf/imas/modules/exams/persistence/ExamsWriteRepository.java` (this task: centre create/update/delete only — `deleteExam`/`createExamOnly`/`assignStudents` land in Tasks 3 and 4):
```java
package com.rcf.imas.modules.exams.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

import static com.rcf.imas.modules.exams.persistence.ExamsReadRepository.genericRow;

@Repository
public class ExamsWriteRepository {

    private final JdbcClient jdbc;

    public ExamsWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** addExamCentre() parity: created_at=now(), active_yn hardcoded 'Y', sitting_capacity/lat/long best-effort
     *  numeric parse-or-null (Node: parseInt/parseFloat(...) || null). Single autocommit statement, no @Transactional
     *  needed (matches Node). */
    public Map<String, Object> insertCentre(String code, String name, String address, String village, String pincode,
                                             String contactPerson, String contactPhone, String contactEmail,
                                             Integer sittingCapacity, java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                             String createdBy) {
        return jdbc.sql("""
                INSERT INTO pp.pp_exam_centre (
                  pp_exam_centre_code, pp_exam_centre_name, address, village, pincode,
                  contact_person, contact_phone, contact_email, sitting_capacity,
                  latitude, longitude, created_at, created_by, active_yn
                ) VALUES (:code, :name, :address, :village, :pincode, :contactPerson, :contactPhone, :contactEmail,
                          :capacity, :lat, :lng, :createdAt, :createdBy::numeric, 'Y')
                RETURNING *
                """)
                .param("code", code).param("name", name).param("address", address).param("village", village)
                .param("pincode", pincode).param("contactPerson", contactPerson).param("contactPhone", contactPhone)
                .param("contactEmail", contactEmail).param("capacity", sittingCapacity).param("lat", latitude)
                .param("lng", longitude).param("createdAt", java.sql.Timestamp.from(Instant.now()))
                .param("createdBy", createdBy)
                .query((rs, i) -> genericRow(rs)).single();
    }

    /** deleteExamCentre() parity: usage-guard SELECT (in ExamsReadRepository) then this DELETE. Not @Transactional
     *  (matches Node, which never wraps this pair either — the guard-then-delete race is accepted). */
    public void deleteCentre(String id) {
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = :id::numeric").param("id", id).update();
    }

    /** updateExamCentre() parity. activeYn is the ALREADY-DEFAULTED value (caller applies `active_yn || 'Y'`
     *  before calling this, per Firm Decision 11a). Returns null if 0 rows updated (id not found). */
    public Map<String, Object> updateCentre(String id, String name, String code, Integer sittingCapacity,
                                             java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                             String address, String village, String pincode, String contactPerson,
                                             String contactPhone, String contactEmail, String activeYn) {
        return jdbc.sql("""
                UPDATE pp.pp_exam_centre
                SET pp_exam_centre_name=:name, pp_exam_centre_code=:code, sitting_capacity=:capacity,
                    latitude=:lat, longitude=:lng, address=:address, village=:village, pincode=:pincode,
                    contact_person=:contactPerson, contact_phone=:contactPhone, contact_email=:contactEmail,
                    active_yn=:activeYn
                WHERE pp_exam_centre_id=:id::numeric
                RETURNING *
                """)
                .param("name", name).param("code", code).param("capacity", sittingCapacity).param("lat", latitude)
                .param("lng", longitude).param("address", address).param("village", village).param("pincode", pincode)
                .param("contactPerson", contactPerson).param("contactPhone", contactPhone).param("contactEmail", contactEmail)
                .param("activeYn", activeYn).param("id", id)
                .query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }
}
```

`src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (this task: 5 handlers; grows through Task 6):
```java
package com.rcf.imas.modules.exams.web;

import com.rcf.imas.modules.exams.persistence.ExamsReadRepository;
import com.rcf.imas.modules.exams.persistence.ExamsWriteRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/exams")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: Node left every route in this module open, except #19 below
class ExamsController {

    private static final Pattern PINCODE = Pattern.compile("^\\d{5,12}$");
    private static final Pattern PHONE = Pattern.compile("^\\d{7,12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final ExamsReadRepository reads;
    private final ExamsWriteRepository writes;

    ExamsController(ExamsReadRepository reads, ExamsWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/exam-centres")
    public List<Map<String, Object>> examCentres() {
        try {
            return reads.activeCentres();
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch exam centres");
        }
    }

    @GetMapping("/viewcentres")
    public List<Map<String, Object>> viewCentres() {
        // Firm Decision 2: any DB failure here surfaces via GlobalExceptionHandler's generic
        // {error:"Internal Server Error"} fallback -- Node's equivalent (`console(...)` is a TypeError)
        // leaves the request hanging with no response at all. Deliberately no try/catch: let it propagate.
        return reads.allCentresAllColumns();
    }

    @PostMapping("/exam-centres")
    public Map<String, Object> createCentre(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = str(b.get("pp_exam_centre_name"));
        String code = str(b.get("pp_exam_centre_code"));
        String phone = str(b.get("contact_phone"));
        String email = str(b.get("contact_email"));
        String pincode = str(b.get("pincode"));

        if (name == null || name.isBlank()) throw ApiException.message(400, "Centre name is required.");
        if (name.length() > 100) throw ApiException.message(400, "Centre name too long (max 100 characters).");
        if (code != null && code.length() > 20) throw ApiException.message(400, "Centre code too long (max 20 characters).");
        if (pincode != null && !PINCODE.matcher(pincode).matches()) throw ApiException.message(400, "Invalid pincode.");
        if (phone != null && !PHONE.matcher(phone).matches()) throw ApiException.message(400, "Invalid contact phone number.");
        if (email != null && !EMAIL.matcher(email).matches()) throw ApiException.message(400, "Invalid email address.");

        Map<String, Object> existing = reads.findExistingCentre(code, name, phone, email);
        if (existing != null) {
            String field, label;
            if (eq(existing.get("pp_exam_centre_code"), code)) { label = "Centre code"; field = "centre_code"; }
            else if (eq(existing.get("pp_exam_centre_name"), name)) { label = "Centre name"; field = "centre_name"; }
            else if (eq(existing.get("contact_phone"), phone)) { label = "Contact phone"; field = "contact_phone"; }
            else { label = "Contact email"; field = "contact_email"; }
            throw ApiException.message(409, label + " already exists. Please use a different value.").with("field", field);
        }

        try {
            Integer capacity = parseIntOrNull(b.get("sitting_capacity"));
            BigDecimal lat = parseDecimalOrNull(b.get("latitude"));
            BigDecimal lng = parseDecimalOrNull(b.get("longitude"));
            Map<String, Object> centre = writes.insertCentre(code, name, str(b.get("address")), str(b.get("village")),
                    pincode, str(b.get("contact_person")), phone, email, capacity, lat, lng, str(b.get("created_by")));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("success", true);
            out.put("message", "Exam centre created successfully");
            out.put("centre", centre);
            return out;
        } catch (Exception e) {
            // Firm Decision 7: the fictitious error.constraint name-matching branches are NOT ported (those
            // constraint names don't exist in the schema -- dead code in Node). A genuine TOCTOU race falls
            // through to this same generic message, matching Node's ultimate behavior for that path.
            throw ApiException.message(500, "Failed to create centre");
        }
    }

    @DeleteMapping("/exam-centres/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCentre(@PathVariable String id) {
        try {
            String usedBy = reads.examNameUsingCentre(id);
            if (usedBy != null) {
                throw ApiException.message(400, "Centre already used in exam: " + usedBy);
            }
            writes.deleteCentre(id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to delete centre");
        }
    }

    @PutMapping("/exam-centres/{id}")
    public Map<String, Object> updateCentre(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            String activeYnRaw = str(b.get("active_yn"));
            String activeYn = (activeYnRaw == null || activeYnRaw.isBlank()) ? "Y" : activeYnRaw; // Firm Decision 11a
            Map<String, Object> centre = writes.updateCentre(id, str(b.get("pp_exam_centre_name")), str(b.get("pp_exam_centre_code")),
                    parseIntOrNull(b.get("sitting_capacity")), parseDecimalOrNull(b.get("latitude")), parseDecimalOrNull(b.get("longitude")),
                    str(b.get("address")), str(b.get("village")), str(b.get("pincode")), str(b.get("contact_person")),
                    str(b.get("contact_phone")), str(b.get("contact_email")), activeYn);
            if (centre == null) throw ApiException.message(404, "Centre not found");
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("message", "Updated successfully");
            out.put("centre", centre);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Update failed").with("error", e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static boolean eq(Object a, String b) { return a != null && b != null && String.valueOf(a).equals(b); }
    private static Integer parseIntOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
    private static BigDecimal parseDecimalOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
```

> **`@ResponseStatus(HttpStatus.NO_CONTENT)` note.** `import org.springframework.web.bind.annotation.ResponseStatus;` is already covered by the wildcard `org.springframework.web.bind.annotation.*` import at the top of the controller; only `org.springframework.http.HttpStatus` needs its own explicit import (already added above).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamCentresIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/test/java/com/rcf/imas/modules/exams
git commit -m "feat(exams): module skeleton + exam-centre CRUD + viewcentres (fixed hang -> real 500)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: jurisdiction cascades (4 endpoints) + `/used-blocks` (numeric-array exception)

Port `GET /divisions-by-state/{stateId}`, `/education-districts-by-division/{divisionId}`, `/blocks-by-district/{districtId}`, `/clusters-by-block/{blockId}` (all identical shape, different `juris_type`/parent), plus `GET /used-blocks?year=` — the one endpoint in this module that returns a bare JSON-**number** array (Firm Decision 6).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java` (add 5 methods)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (add 5 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamJurisdictionIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamJurisdictionIT.java`:
```java
package com.rcf.imas.modules.exams;

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
class ExamJurisdictionIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('juseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='juseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "juseed", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK'),('CLUSTER') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (810001,'KARNATAKA','STATE') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810002,'BELAGAVI DIV','DIVISION',810001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810003,'BELAGAVI EDU DIST','EDUCATION DISTRICT',810002) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810004,'GOKAK BLOCK','BLOCK',810003) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (810005,'GOKAK CLUSTER','CLUSTER',810004) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (810101,2027,24081000001,810004,'JurisKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year)
            VALUES (810201,'UB Exam','2027-06-01','09:00:00','11:00:00','2027')
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (810101, 810201)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 810101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 810201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 810101").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (810001,810002,810003,810004,810005)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'juseed'").update();
    }

    @Test
    void divisionsByStateReturnsIdAndNameAsStrings() throws Exception {
        mvc.perform(get("/api/exams/divisions-by-state/810001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("810002"))
           .andExpect(jsonPath("$[0].name").value("BELAGAVI DIV"));
    }

    @Test
    void educationDistrictsByDivision() throws Exception {
        mvc.perform(get("/api/exams/education-districts-by-division/810002").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("BELAGAVI EDU DIST"));
    }

    @Test
    void blocksByDistrict() throws Exception {
        mvc.perform(get("/api/exams/blocks-by-district/810003").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("GOKAK BLOCK"));
    }

    @Test
    void clustersByBlock() throws Exception {
        mvc.perform(get("/api/exams/clusters-by-block/810004").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("GOKAK CLUSTER"));
    }

    @Test
    void usedBlocksReturnsJsonNumbersNotStrings() throws Exception {
        mvc.perform(get("/api/exams/used-blocks").param("year", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(content().json("[810004]")) // bare numeric array; would fail as "\"810004\"" if wrongly stringified
           .andExpect(jsonPath("$[0]").isNumber());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamJurisdictionIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ExamsReadRepository`:
```java
    public List<Map<String, Object>> divisionsByState(String stateId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'DIVISION' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", stateId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> educationDistrictsByDivision(String divisionId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", divisionId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> blocksByDistrict(String districtId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'BLOCK' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", districtId).query((rs, i) -> genericRow(rs)).list();
    }

    public List<Map<String, Object>> clustersByBlock(String blockId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'CLUSTER' AND parent_juris = :parent::numeric
                ORDER BY juris_name
                """).param("parent", blockId).query((rs, i) -> genericRow(rs)).list();
    }

    /** getUsedBlocks() parity: e.exam_year is varchar, no cast. Firm Decision 6: returns real Long values (JSON
     *  numbers), NOT the genericRow String convention -- the one deliberate exception in this module because the
     *  frontend does `usedBlocks.includes(Number(b.id))`. */
    public List<Long> usedBlocks(String year) {
        return jdbc.sql("""
                SELECT DISTINCT api.nmms_block
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
                INNER JOIN pp.examination e ON ae.exam_id = e.exam_id
                WHERE e.exam_year = :year
                """).param("year", year).query(Long.class).list();
    }
```

Add to `ExamsController` (no try/catch for the 4 jurisdiction cascades — a DB failure propagates straight to `GlobalExceptionHandler`'s generic `{error:"Internal Server Error"}` fallback, which is byte-for-byte what Node's own catch blocks return for these 4 endpoints):
```java
    @GetMapping("/divisions-by-state/{stateId}")
    public List<Map<String, Object>> divisionsByState(@PathVariable String stateId) {
        return reads.divisionsByState(stateId);
    }

    @GetMapping("/education-districts-by-division/{divisionId}")
    public List<Map<String, Object>> educationDistrictsByDivision(@PathVariable String divisionId) {
        return reads.educationDistrictsByDivision(divisionId);
    }

    @GetMapping("/blocks-by-district/{districtId}")
    public List<Map<String, Object>> blocksByDistrict(@PathVariable String districtId) {
        return reads.blocksByDistrict(districtId);
    }

    @GetMapping("/clusters-by-block/{blockId}")
    public List<Map<String, Object>> clustersByBlock(@PathVariable String blockId) {
        return reads.clustersByBlock(blockId);
    }

    @GetMapping("/used-blocks")
    public List<Long> usedBlocks(@RequestParam(required = false) String year) {
        try {
            return reads.usedBlocks(year);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch used blocks");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamJurisdictionIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamJurisdictionIT.java
git commit -m "feat(exams): jurisdiction cascades + used-blocks (numeric-array response exception)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: `/assigned`, `/notassigned` (ARRAY_AGG mapping) + `/{examId}/freeze` + `DELETE /{examId}` (real `@Transactional`)

Port the two exam-listing partitions (INNER-join "has applicants" vs. NOT-EXISTS "has none"), `freezeExam` (no existence check, preserved verbatim), and `deleteExam` — this last one gets a **genuine** `@Transactional` in `ExamsWriteRepository` (Firm Decision 3), fixing Node's non-atomic `pool.query("BEGIN")`-on-the-shared-pool bug.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java` (add `assignedExams`, `notAssignedExams`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsWriteRepository.java` (add `@Transactional deleteExam`, `freezeExam`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (add 4 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamListingIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamListingIT.java`:
```java
package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamListingIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('elseed2','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='elseed2'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "elseed2", "ADMIN");

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (820003,'ELIST DIST','EDUCATION DISTRICT') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (820004,'ELIST BLOCK','BLOCK',820003) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, active_yn) VALUES (82001,'ELIST Centre','Y')
            ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (820101,'Assigned Exam','2027-06-01','09:00:00','11:00:00','2027',82001)
            """).update();
        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (820102,'Unassigned Exam','2027-06-02','09:00:00','11:00:00','2027',82001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (820201,2027,24082000001,820003,820004,'ListedKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id) VALUES (820201, 820101)").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 820201").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id IN (820101,820102)").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 820201").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 82001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (820003,820004)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'elseed2'").update();
    }

    @Test
    void assignedRequiresYearAnd400sWithoutIt() throws Exception {
        mvc.perform(get("/api/exams/assigned").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Year is required"));
    }

    @Test
    void assignedSplitsYearYYYYDashYYAndReturnsArrayAggAsStringLists() throws Exception {
        mvc.perform(get("/api/exams/assigned").param("year", "2027-28").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].exam_id").value("820101"))
           .andExpect(jsonPath("$[0].district_ids[0]").value("820003"))
           .andExpect(jsonPath("$[0].block_names[0]").value("ELIST BLOCK"))
           .andExpect(jsonPath("$[?(@.exam_id=='820102')]").isEmpty()); // zero-applicant exam excluded (INNER JOIN)
    }

    @Test
    void notAssignedReturnsOnlyTheZeroApplicantExam() throws Exception {
        mvc.perform(get("/api/exams/notassigned").param("year", "2027").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].exam_id").value("820102"))
           .andExpect(jsonPath("$[?(@.exam_id=='820101')]").isEmpty());
    }

    @Test
    void freezeExamHasNoExistenceCheck() throws Exception {
        mvc.perform(put("/api/exams/999999999/freeze").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("✅ Exam frozen successfully")); // no existence check quirk

        mvc.perform(put("/api/exams/820102/freeze").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk());
        String frozen = jdbc.sql("SELECT frozen_yn FROM pp.examination WHERE exam_id = 820102").query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(frozen).isEqualTo("Y");
    }

    @Test
    void deleteExamIsTransactionalAndRemovesChildRowsFirst() throws Exception {
        mvc.perform(delete("/api/exams/820101").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Exam and related data deleted successfully"));

        Integer remainingApplicantExam = jdbc.sql("SELECT COUNT(*)::int FROM pp.applicant_exam WHERE exam_id = 820101")
                .query(Integer.class).single();
        Integer remainingExam = jdbc.sql("SELECT COUNT(*)::int FROM pp.examination WHERE exam_id = 820101")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(remainingApplicantExam).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(remainingExam).isEqualTo(0);
    }

    @Test
    void deleteExamNoExistenceCheckStill200sForMissingId() throws Exception {
        mvc.perform(delete("/api/exams/999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Exam and related data deleted successfully"));
    }

    @Test
    void listingEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/assigned").param("year", "2027").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(put("/api/exams/820101/freeze").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
        mvc.perform(delete("/api/exams/820101").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamListingIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ExamsReadRepository`:
```java
    /** getAllExams(year) parity: exam_year is varchar, plain string equality, no cast. INNER JOINs on
     *  applicant_exam/applicant_primary_info mean a zero-applicant exam is excluded entirely -- this is how
     *  /assigned and /notassigned partition. ARRAY_AGG columns come back via genericRow's new ARRAY case. */
    public List<Map<String, Object>> assignedExams(String year) {
        return jdbc.sql("""
                SELECT
                  e.exam_id, e.exam_name, e.exam_date, e.frozen_yn, e.pp_exam_centre_id,
                  c.pp_exam_centre_name, e.exam_start_time, e.exam_end_time,
                  ARRAY_AGG(DISTINCT jd.juris_code) AS district_ids,
                  ARRAY_AGG(DISTINCT jd.juris_name) AS district_names,
                  ARRAY_AGG(DISTINCT jb.juris_code) AS block_ids,
                  ARRAY_AGG(DISTINCT jb.juris_name) AS block_names
                FROM pp.examination e
                LEFT JOIN pp.pp_exam_centre c ON e.pp_exam_centre_id = c.pp_exam_centre_id
                JOIN pp.applicant_exam ae ON ae.exam_id = e.exam_id
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                LEFT JOIN pp.jurisdiction jd ON api.district = jd.juris_code
                LEFT JOIN pp.jurisdiction jb ON api.nmms_block = jb.juris_code
                WHERE e.exam_year = :year
                GROUP BY e.exam_id, e.exam_name, e.exam_date, e.pp_exam_centre_id, c.pp_exam_centre_name
                ORDER BY e.exam_date DESC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }

    /** getAllExamsnotassigned(year) parity: NOT EXISTS complement of assignedExams. */
    public List<Map<String, Object>> notAssignedExams(String year) {
        return jdbc.sql("""
                SELECT e.exam_id, e.exam_name, e.exam_date, e.frozen_yn, e.pp_exam_centre_id,
                       c.pp_exam_centre_name, e.exam_start_time, e.exam_end_time
                FROM pp.examination e
                LEFT JOIN pp.pp_exam_centre c ON e.pp_exam_centre_id = c.pp_exam_centre_id
                WHERE e.exam_year = :year
                  AND NOT EXISTS (SELECT 1 FROM pp.applicant_exam ae WHERE ae.exam_id = e.exam_id)
                ORDER BY e.exam_date DESC
                """).param("year", year).query((rs, i) -> genericRow(rs)).list();
    }
```

Add to `ExamsWriteRepository` (add `import org.springframework.transaction.annotation.Transactional;`):
```java
    /** deleteExamById(examId) parity, made GENUINELY atomic (Firm Decision 3) -- Node's version runs
     *  pool.query("BEGIN")/"COMMIT" on the shared pool, which is not a real transaction (each statement may hit
     *  a different pooled connection). No existence check (Firm Decision 11c) -- 0-row deletes are not an error. */
    @Transactional
    public void deleteExam(String examId) {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = :id::numeric").param("id", examId).update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
    }

    /** freezeExam parity: single autocommit UPDATE, NO existence check (Firm Decision 11b) -- 0 rows affected is
     *  not treated as an error, matching Node exactly. */
    public void freezeExam(String examId) {
        jdbc.sql("UPDATE pp.examination SET frozen_yn = 'Y' WHERE exam_id = :id::numeric").param("id", examId).update();
    }
```

Add to `ExamsController`:
```java
    @GetMapping("/assigned")
    public List<Map<String, Object>> assigned(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.message(400, "Year is required");
        try {
            return reads.assignedExams(year.split("-")[0]);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch exams");
        }
    }

    @GetMapping("/notassigned")
    public List<Map<String, Object>> notAssigned(@RequestParam(required = false) String year) {
        if (year == null || year.isBlank()) throw ApiException.message(400, "Year is required");
        try {
            return reads.notAssignedExams(year.split("-")[0]);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to fetch exams");
        }
    }

    @PutMapping("/{examId}/freeze")
    public Map<String, Object> freeze(@PathVariable String examId) {
        try {
            writes.freezeExam(examId);
            return Map.of("message", "✅ Exam frozen successfully");
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to freeze exam");
        }
    }

    @DeleteMapping("/{examId}")
    public Map<String, Object> deleteExam(@PathVariable String examId) {
        try {
            writes.deleteExam(examId);
            return Map.of("message", "Exam and related data deleted successfully");
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to delete exam");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamListingIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamListingIT.java
git commit -m "feat(exams): assigned/notassigned (ARRAY_AGG mapping) + freeze + transactional delete

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: `POST /create` (time-overlap check) + `POST /{examId}/assign-students` (sequence/numbering transaction)

The algorithmic core of the module. Both handlers are backed by `@Transactional` methods in `ExamsWriteRepository`.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsWriteRepository.java` (add `createExamOnly`, `assignStudents`, `generateHallTicket`, two custom exceptions)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (add 2 handlers)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamCreateAssignIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamCreateAssignIT.java`:
```java
package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamCreateAssignIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('caseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='caseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "caseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, active_yn) VALUES (83001,'CA Centre','Y')
            ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('DIVISION'),('EDUCATION DISTRICT'),('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (830001,'CA DIVISION','DIVISION') ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830002,'CA EDU DIST','EDUCATION DISTRICT',830001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (830003,'CA BLOCK','BLOCK',830002) ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (830201,'CA Exam','2027-06-01','09:00:00','11:00:00','2027',83001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, student_name, father_name, created_by, updated_by)
            VALUES (830101,2027,24083000001,830003,'AssignKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_id, shortlisted_year) VALUES (8301, 2027) ON CONFLICT (shortlist_batch_id) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.applicant_shortlist_info(applicant_id, shortlist_batch_id, shortlisted_yn) VALUES (830101, 8301, 'Y')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.hall_ticket_sequence WHERE juris_code = '830003'").update();
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE applicant_id = 830101 OR exam_id IN (830201, 830202, 830203)").update();
        jdbc.sql("DELETE FROM pp.applicant_shortlist_info WHERE applicant_id = 830101").update();
        jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = 8301").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 830101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id IN (830201, 830202, 830203)").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 83001").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (830001,830002,830003)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'caseed'").update();
    }

    @Test
    void createExamOnlyMissingFieldsIs400WithErrorKey() throws Exception {
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"examName\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields."));
    }

    @Test
    void createExamOnlyAllowsOmittedAcademicYearOrphanQuirk() throws Exception {
        var result = mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"Orphan Exam\",\"date\":\"2027-08-01\",\"startTime\":\"09:00\",\"endTime\":\"11:00\"}"))
           .andExpect(status().isCreated()).andReturn();
        String examId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.examId").toString();
        try {
            String examYear = jdbc.sql("SELECT exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                    .param("id", examId).query(String.class).optional().orElse("NOT_NULL_SENTINEL");
            org.assertj.core.api.Assertions.assertThat(examYear).isNull(); // Firm Decision 11d: NULL exam_year, no error
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
        }
    }

    @Test
    void createExamOnlyDetectsTimeConflict() throws Exception {
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"Conflicting\",\"date\":\"2027-06-01\",\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"academic_year\":\"2027-28\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.error").value("Time conflict"))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("09:00")));
    }

    @Test
    void createExamOnlySuccessDerivesExamYearFromAcademicYear() throws Exception {
        var result = mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"centreId\":83001,\"examName\":\"CA Exam 2\",\"date\":\"2027-09-01\",\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"academic_year\":\"2027-28\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Exam created successfully")).andReturn();
        String examId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.examId").toString();
        try {
            String examYear = jdbc.sql("SELECT exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                    .param("id", examId).query(String.class).single();
            org.assertj.core.api.Assertions.assertThat(examYear).isEqualTo("2027"); // split("-")[0]
        } finally {
            jdbc.sql("DELETE FROM pp.examination WHERE exam_id = :id::numeric").param("id", examId).update();
        }
    }

    @Test
    void assignStudentsMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{\"division\":830001}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields: examId, division, educationDistrict, blocks[]"));
    }

    @Test
    void assignStudentsNonexistentExamIs404WithErrorKey() throws Exception {
        mvc.perform(post("/api/exams/999999999/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Exam does not exist."));
    }

    @Test
    void assignStudentsNoShortlistedApplicantsIs404WithMessageKey() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[999999],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No shortlisted applicants found for the selected region."));
    }

    @Test
    void assignStudentsSuccessGeneratesHallTicketNumberFromRequestBodyAcademicYearNotExamYear() throws Exception {
        // exam.exam_year='2027' (drives shortlist eligibility) vs academicYear='2028-29' in the request body
        // (drives hall-ticket numbering) -- Firm Decision 11f, two distinct years, never cross-validated.
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.totalAssigned").value(1))
           .andExpect(jsonPath("$.applicants[0].applicant_id").value("830101"))
           .andExpect(jsonPath("$.applicants[0].hall_ticket_no").value("28030001")); // "28"+"03"(last2 of 830003)+"0001"
    }

    @Test
    void assignStudentsRerunBurnsSequenceGapButDoesNotDuplicateRow() throws Exception {
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated());

        // Re-run against the SAME already-assigned cohort: ON CONFLICT (applicant_id, exam_id) DO NOTHING means no
        // new applicant_exam row, but hall_ticket_sequence.last_sequence is still bumped (gap, not collision).
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"division\":830001,\"educationDistrict\":830002,\"blocks\":[830003],\"academicYear\":\"2028-29\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.applicants[0].hall_ticket_no").value("28030002")); // sequence bumped to 2...

        Integer rows = jdbc.sql("SELECT COUNT(*)::int FROM pp.applicant_exam WHERE applicant_id = 830101 AND exam_id = 830201")
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1); // ...but still only ONE applicant_exam row
        String storedTicket = jdbc.sql("SELECT pp_hall_ticket_no FROM pp.applicant_exam WHERE applicant_id = 830101 AND exam_id = 830201")
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(storedTicket).isEqualTo("28030001"); // first-assignment ticket kept (DO NOTHING)
    }

    @Test
    void createAndAssignEndpointsAreAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(post("/api/exams/create").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/830201/assign-students").header("Authorization", "Bearer " + studentTok)
                .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamCreateAssignIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ExamsWriteRepository` (add imports `java.time.format.DateTimeFormatter`, `java.util.ArrayList`, `java.util.LinkedHashMap`):
```java
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record CreateExamResult(boolean conflict, String message, String examId) {}

    public static class ExamNotFoundException extends RuntimeException {
        public ExamNotFoundException() { super("Exam does not exist."); }
    }

    public static class NoShortlistedApplicantsException extends RuntimeException {
        public NoShortlistedApplicantsException() { super("No shortlisted applicants found for the selected region."); }
    }

    public record AssignedApplicant(String applicantId, String applicantName, String hallTicketNo) {}
    public record AssignResult(int totalAssigned, List<AssignedApplicant> applicants) {}

    /**
     * addcreateExamonly() parity. examYear is ALREADY the caller-computed academic_year.split("-")[0] value, or
     * null if academic_year was omitted (Firm Decision 11d orphan quirk -- allowed, not validated). startTime/
     * endTime are compared as zero-padded "HH:MM"/"HH:MM:SS" strings -- Java String.compareTo on such strings is
     * lexicographic and correct for this format, matching Node's JS string comparison operators exactly.
     */
    @Transactional
    public CreateExamResult createExamOnly(String centreId, String examName, String date, String startTime,
                                            String endTime, String examYear) {
        List<Map<String, Object>> existingExams = jdbc.sql("""
                SELECT exam_id, exam_start_time, exam_end_time
                FROM pp.examination
                WHERE pp_exam_centre_id = :centreId::numeric AND exam_date = :date::date AND exam_year = :year
                """).param("centreId", centreId).param("date", date).param("year", examYear)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("start", TIME_FMT.format(rs.getTime("exam_start_time").toLocalTime()));
                    m.put("end", TIME_FMT.format(rs.getTime("exam_end_time").toLocalTime()));
                    return m;
                }).list();

        for (Map<String, Object> existing : existingExams) {
            String existingStart = (String) existing.get("start");
            String existingEnd = (String) existing.get("end");
            boolean overlapping =
                    (startTime.compareTo(existingStart) >= 0 && startTime.compareTo(existingEnd) < 0) ||
                    (endTime.compareTo(existingStart) > 0 && endTime.compareTo(existingEnd) <= 0) ||
                    (startTime.compareTo(existingStart) <= 0 && endTime.compareTo(existingEnd) >= 0);
            if (overlapping) {
                return new CreateExamResult(true, "Exam exists from " + existingStart + " to " + existingEnd, null);
            }
        }

        String examId = jdbc.sql("""
                INSERT INTO pp.examination (exam_name, exam_date, pp_exam_centre_id, exam_start_time, exam_end_time, exam_year)
                VALUES (:name, :date::date, :centreId::numeric, :start::time, :end::time, :year)
                RETURNING exam_id
                """).param("name", examName).param("date", date).param("centreId", centreId)
                .param("start", startTime).param("end", endTime).param("year", examYear)
                .query((rs, i) -> rs.getBigDecimal("exam_id").toBigInteger().toString()).single();

        return new CreateExamResult(false, null, examId);
    }

    /**
     * assignApplicantsToExam() parity -- genuinely transactional in Node too (single client, BEGIN/COMMIT/ROLLBACK),
     * so this port just needs Spring's equivalent. Two distinct "year" values, deliberately never cross-validated
     * (Firm Decision 11f): `examYear` (fetched from the exam row, filters shortlist eligibility) vs. `academicYear`
     * (the raw request-body value, drives hall-ticket sequence numbering). An exam with a NULL exam_year (allowed
     * per createExamOnly's orphan quirk) makes `sb.shortlisted_year = NULL::numeric` always false -> 404.
     */
    @Transactional
    public AssignResult assignStudents(String examId, String division, String educationDistrict,
                                        List<String> blocks, String academicYear) {
        Map<String, Object> exam = jdbc.sql("SELECT exam_id, exam_year FROM pp.examination WHERE exam_id = :id::numeric")
                .param("id", examId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
        if (exam == null) throw new ExamNotFoundException();
        String examYear = (String) exam.get("exam_year");

        List<Map<String, Object>> shortlisted = jdbc.sql("""
                SELECT
                  api.applicant_id, api.student_name, api.nmms_year,
                  edu_district_juris.juris_code
                FROM pp.applicant_primary_info api
                INNER JOIN pp.applicant_shortlist_info asi ON api.applicant_id = asi.applicant_id
                INNER JOIN pp.shortlist_batch sb ON asi.shortlist_batch_id = sb.shortlist_batch_id
                INNER JOIN pp.jurisdiction block_juris
                  ON api.nmms_block = block_juris.juris_code AND block_juris.juris_type = 'BLOCK'
                INNER JOIN pp.jurisdiction edu_district_juris
                  ON block_juris.parent_juris = edu_district_juris.juris_code
                  AND edu_district_juris.juris_type = 'EDUCATION DISTRICT'
                INNER JOIN pp.jurisdiction division_juris
                  ON edu_district_juris.parent_juris = division_juris.juris_code
                  AND division_juris.juris_type = 'DIVISION'
                WHERE division_juris.juris_code = :division::numeric
                  AND edu_district_juris.juris_code = :eduDistrict::numeric
                  AND block_juris.juris_code = ANY(:blocks::numeric[])
                  AND asi.shortlisted_yn = 'Y'
                  AND sb.shortlisted_year = :examYear::numeric
                """)
                .param("division", division).param("eduDistrict", educationDistrict)
                .param("blocks", blocks.toArray(new String[0])).param("examYear", examYear)
                .query((rs, i) -> genericRow(rs)).list();

        if (shortlisted.isEmpty()) throw new NoShortlistedApplicantsException();

        List<AssignedApplicant> assigned = new ArrayList<>();
        for (Map<String, Object> applicant : shortlisted) {
            String applicantId = (String) applicant.get("applicant_id");
            String applicantName = (String) applicant.get("student_name");
            String jurisCode = (String) applicant.get("juris_code");

            // Firm Decision 11e: bumped even when the applicant_exam insert below is a DO NOTHING no-op --
            // a "gap not collision" quirk, preserved verbatim, do NOT peek-before-increment.
            long sequence = jdbc.sql("""
                    INSERT INTO pp.hall_ticket_sequence (academic_year, juris_code, last_sequence)
                    VALUES (:year, :juris, 1)
                    ON CONFLICT (academic_year, juris_code)
                    DO UPDATE SET last_sequence = pp.hall_ticket_sequence.last_sequence + 1
                    RETURNING last_sequence
                    """).param("year", academicYear).param("juris", jurisCode)
                    .query(Long.class).single();

            String hallTicketNo = generateHallTicket(sequence, jurisCode, academicYear);

            jdbc.sql("""
                    INSERT INTO pp.applicant_exam (applicant_id, exam_id, pp_hall_ticket_no)
                    VALUES (:applicantId::numeric, :examId::numeric, :ticket)
                    ON CONFLICT (applicant_id, exam_id) DO NOTHING
                    """).param("applicantId", applicantId).param("examId", examId).param("ticket", hallTicketNo).update();

            assigned.add(new AssignedApplicant(applicantId, applicantName, hallTicketNo));
        }

        return new AssignResult(assigned.size(), assigned);
    }

    /** generateHallTicket(sequenceNumber, juris_code, academicYear) parity (Firm Decision 12), verbatim:
     *  yearSuffix = academicYear[2:4]; jurisLast2 = last-2-chars-of-jurisCode padded to 2 with '0';
     *  sequence = sequenceNumber padded to 4 with '0'. */
    static String generateHallTicket(long sequenceNumber, String jurisCode, String academicYear) {
        if (jurisCode == null || academicYear == null) {
            throw new IllegalStateException("Missing required values for hall ticket generation");
        }
        String yearSuffix = academicYear.substring(2, 4);
        String tail = jurisCode.length() >= 2 ? jurisCode.substring(jurisCode.length() - 2) : jurisCode;
        String jurisLast2 = tail.length() < 2 ? "0".repeat(2 - tail.length()) + tail : tail;
        String sequence = String.format("%04d", sequenceNumber);
        return yearSuffix + jurisLast2 + sequence;
    }
```

Add to `ExamsController` (add import `java.util.LinkedHashMap`):
```java
    @PostMapping("/create")
    public Map<String, Object> createExamOnly(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String centreId = str(b.get("centreId"));
        String examName = str(b.get("examName"));
        String date = str(b.get("date"));
        String startTime = str(b.get("startTime"));
        String endTime = str(b.get("endTime"));
        String academicYear = str(b.get("academic_year"));

        if (isBlank(centreId) || isBlank(examName) || isBlank(date) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields.");
        }
        String examYear = isBlank(academicYear) ? null : academicYear.split("-")[0];

        try {
            var result = writes.createExamOnly(centreId, examName, date, startTime, endTime, examYear);
            if (result.conflict()) {
                throw ApiException.error(409, "Time conflict").with("message", result.message());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Exam created successfully");
            out.put("examId", result.examId());
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.message(500, "Server error").with("error", e.getMessage());
        }
    }

    @PostMapping("/{examId}/assign-students")
    public Map<String, Object> assignStudents(@PathVariable String examId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String division = str(b.get("division"));
        String educationDistrict = str(b.get("educationDistrict"));
        List<String> blocks = b.get("blocks") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        String academicYear = str(b.get("academicYear"));

        if (isBlank(division) || isBlank(educationDistrict) || blocks.isEmpty()) {
            throw ApiException.error(400, "Missing required fields: examId, division, educationDistrict, blocks[]");
        }

        try {
            var result = writes.assignStudents(examId, division, educationDistrict, blocks, academicYear);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Applicants assigned to exam successfully ✅");
            out.put("examId", examId);
            out.put("totalAssigned", result.totalAssigned());
            out.put("applicants", result.applicants().stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("applicant_id", a.applicantId());
                m.put("applicant_name", a.applicantName());
                m.put("hall_ticket_no", a.hallTicketNo());
                return m;
            }).toList());
            return out;
        } catch (com.rcf.imas.modules.exams.persistence.ExamsWriteRepository.ExamNotFoundException e) {
            throw ApiException.error(404, "Exam does not exist.");
        } catch (com.rcf.imas.modules.exams.persistence.ExamsWriteRepository.NoShortlistedApplicantsException e) {
            throw ApiException.message(404, "No shortlisted applicants found for the selected region.");
        } catch (Exception e) {
            throw ApiException.message(500, "Server error").with("error", e.getMessage());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
```

> **`freezeExam`'s success message and `assignStudents`'s message both carry a literal emoji** (`"✅"` / the check-mark), matching Node's `res.status(200).json({ message: "✅ Exam frozen successfully" })` and `"Applicants assigned to exam successfully ✅"` byte-for-byte — use the literal UTF-8 character (or the `✅` escape, identical), not a stripped-down ASCII message.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamCreateAssignIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamCreateAssignIT.java
git commit -m "feat(exams): createExamOnly (time-overlap check) + assign-students (sequence/hall-ticket numbering)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: `GET /{examId}/student-list` — calling-list XLSX (POI, in-memory)

Port `generateStudentList` — the ROW_NUMBER query, header info block, 10-column student table with score coloring, optional Score Summary sheet, all generated to a `ByteArrayOutputStream` (Firm Decision 9 — no disk write, unlike Node's real temp-file round-trip).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java` (add `studentListRows`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/service/ExamCallingListXlsxSupport.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (add 1 handler)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamStudentListIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamStudentListIT.java`:
```java
package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamStudentListIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('slseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='slseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "slseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, contact_person, active_yn)
            VALUES (84001,'SL Centre','Mr Contact','Y') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (840201,'SL Exam','2027-06-01','09:00:00','11:00:00','2027',84001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.institute(dise_code, institute_name) VALUES ('DISE001','SL School') ON CONFLICT (dise_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('BLOCK') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (840003,'SL BLOCK','BLOCK') ON CONFLICT (juris_code) DO NOTHING").update();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, current_institute_dise_code,
                student_name, father_name, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (840101,2027,24084000001,840003,'DISE001','HighScorer','f','9000000001',75,80,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, nmms_block, current_institute_dise_code,
                student_name, father_name, contact_no1, gmat_score, sat_score, created_by, updated_by)
            VALUES (840102,2027,24084000002,840003,'DISE001','LowScorer','f','9000000002',40,55,:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (840101, 840201, '27000001')").update();
        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (840102, 840201, '27000002')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = 840201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (840101,840102)").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 840201").update();
        jdbc.sql("DELETE FROM pp.institute WHERE dise_code = 'DISE001'").update();
        jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code = 840003").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 84001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'slseed'").update();
    }

    @Test
    void studentListMissingExamIs404() throws Exception {
        mvc.perform(get("/api/exams/999999999/student-list").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No students found for this exam."));
    }

    @Test
    void studentListBuildsWorkbookWithHeaderInfoAndScoreColoring() throws Exception {
        byte[] bytes = mvc.perform(get("/api/exams/840201/student-list").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"SL_Exam_Calling_List.xlsx\""))
           .andReturn().getResponse().getContentAsByteArray();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Student Calling List");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("STUDENT CALLING LIST");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Exam Name:");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("SL Exam");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("Mr Contact"); // Contact Person

            int headerRowIdx = 8; // examInfoData has 10 rows (0..9); header row is at index equal to examInfoData.size()
            Row header = sheet.getRow(headerRowIdx);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Sl. No.");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Student Name");

            Row highScorer = sheet.getRow(headerRowIdx + 1);
            assertThat(highScorer.getCell(3).getStringCellValue()).isEqualTo("HighScorer");
            assertThat(highScorer.getCell(4).getStringCellValue()).isEqualTo("SL School"); // dise_code -> institute_name
            assertThat(highScorer.getCell(5).getStringCellValue()).isEqualTo("SL BLOCK");  // nmms_block -> juris_name
            Cell gmatHigh = highScorer.getCell(8);
            assertThat(gmatHigh.getCellStyle().getFillForegroundColorColor().getARGBHex()).isEqualToIgnoringCase("FFE6F3E6"); // >=70 green

            Row lowScorer = sheet.getRow(headerRowIdx + 2);
            Cell gmatLow = lowScorer.getCell(8);
            assertThat(gmatLow.getCellStyle().getFillForegroundColorColor().getARGBHex()).isEqualToIgnoringCase("FFFFE6E6"); // <70 red

            Row totalRow = sheet.getRow(headerRowIdx + 2 + 2); // blank row + total row
            assertThat(totalRow.getCell(0).getStringCellValue()).isEqualTo("Total Students: 2");

            assertThat(wb.getSheet("Score Summary")).isNotNull(); // present because scores exist
        }
    }

    @Test
    void studentListEndpointIsAdminOnly() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/840201/student-list").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamStudentListIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ExamsReadRepository`:
```java
    /** generateStudentList's query parity. sl_no (ROW_NUMBER) is fetched but NOT used for the "Sl. No." column --
     *  Node re-derives it from the JS array index during mapping (`result.rows.map((row, index) => [index+1, ...])`),
     *  which is equivalent here since the query is already ORDER BY api.student_name (same order the index walks). */
    public List<Map<String, Object>> studentListRows(String examId) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, i.dise_code, i.institute_name,
                  api.contact_no1, api.contact_no2, ee.exam_name, ee.exam_date,
                  api.gmat_score, api.sat_score, ee.exam_start_time, ee.exam_end_time,
                  ec.pp_exam_centre_name, api.nmms_reg_number, ec.contact_person,
                  j.juris_name AS block_name,
                  ROW_NUMBER() OVER (ORDER BY api.student_name) AS sl_no
                FROM pp.examination ee
                JOIN pp.applicant_exam ae ON ee.exam_id = ae.exam_id
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.pp_exam_centre ec ON ee.pp_exam_centre_id = ec.pp_exam_centre_id
                LEFT JOIN pp.institute i ON api.current_institute_dise_code = i.dise_code
                LEFT JOIN pp.jurisdiction j ON api.nmms_block = j.juris_code
                WHERE ae.exam_id = :examId::numeric
                ORDER BY api.student_name
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }
```

`src/main/java/com/rcf/imas/modules/exams/service/ExamCallingListXlsxSupport.java`:
```java
package com.rcf.imas.modules.exams.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** POI port of examControllers.js generateStudentList: header info block, 10-column table w/ score coloring,
 *  optional Score Summary sheet -- all in-memory (Firm Decision 9), no disk write, no res.download/setTimeout dance. */
@Component
public class ExamCallingListXlsxSupport {

    private static final DateTimeFormatter DATE_DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] build(List<Map<String, Object>> rows) {
        Map<String, Object> examInfo = rows.get(0);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Student Calling List");

            CellStyle titleStyle = boldStyle(wb, 14, "1B5E20", null, HorizontalAlignment.CENTER);
            CellStyle labelStyle = boldStyle(wb, 11, null, "F5F5F5", null);
            CellStyle headerStyle = boldStyle(wb, 11, "000000", "D4F1D4", HorizontalAlignment.CENTER);

            String[][] infoLines = {
                {"STUDENT CALLING LIST"}, {},
                {"Exam Name:", str(examInfo.get("exam_name"))},
                {"Exam Date:", formatDate(examInfo.get("exam_date"))},
                {"Exam Time:", str(examInfo.get("exam_start_time")) + " - " + str(examInfo.get("exam_end_time"))},
                {"Exam Centre:", str(examInfo.get("pp_exam_centre_name"))},
                {"Contact Person:", orDefault(str(examInfo.get("contact_person")), "Not specified")},
                {"Generated on:", DATE_DDMMYYYY.format(LocalDate.now())},
                {}, {}
            };
            for (int r = 0; r < infoLines.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < infoLines[r].length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(infoLines[r][c] == null ? "" : infoLines[r][c]);
                    if (r == 0) cell.setCellStyle(titleStyle);
                    else if (c == 0 && !infoLines[r][c].isEmpty()) cell.setCellStyle(labelStyle);
                }
            }

            int headerRowIdx = infoLines.length; // 10
            String[] headers = {"Sl. No.", "NMMS Reg. No.", "Hall Ticket No.", "Student Name", "School Name",
                    "Block Name", "Contact No. 1", "Contact No. 2", "GMAT Score", "SAT Score"};
            Row headerRow = sheet.createRow(headerRowIdx);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headerStyle);
            }

            CellStyle greenScore = scoreStyle(wb, "006100", "E6F3E6", true);
            CellStyle redScore = scoreStyle(wb, "9C0000", "FFE6E6", false);

            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> s = rows.get(i);
                Row row = sheet.createRow(headerRowIdx + 1 + i);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(str(s.get("nmms_reg_number")));
                row.createCell(2).setCellValue(str(s.get("pp_hall_ticket_no")));
                row.createCell(3).setCellValue(str(s.get("student_name")));
                row.createCell(4).setCellValue(str(s.get("institute_name")));
                row.createCell(5).setCellValue(str(s.get("block_name")));
                row.createCell(6).setCellValue(str(s.get("contact_no1")));
                row.createCell(7).setCellValue(str(s.get("contact_no2")));
                Cell gmat = row.createCell(8);
                gmat.setCellValue(str(s.get("gmat_score")));
                styleScoreCell(gmat, s.get("gmat_score"), greenScore, redScore);
                Cell sat = row.createCell(9);
                sat.setCellValue(str(s.get("sat_score")));
                styleScoreCell(sat, s.get("sat_score"), greenScore, redScore);
            }

            int totalRowIdx = headerRowIdx + 1 + rows.size() + 1; // blank row then total row
            sheet.createRow(totalRowIdx).createCell(0).setCellValue("Total Students: " + rows.size());

            for (int c = 0; c < 10; c++) sheet.setColumnWidth(c, colWidth(c));

            addScoreSummaryIfPresent(wb, rows); // must run BEFORE the single final write below

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void addScoreSummaryIfPresent(Workbook wb, List<Map<String, Object>> rows) {
        List<Double> gmat = rows.stream().map(r -> parseOrNull(r.get("gmat_score"))).filter(java.util.Objects::nonNull).toList();
        List<Double> sat = rows.stream().map(r -> parseOrNull(r.get("sat_score"))).filter(java.util.Objects::nonNull).toList();
        boolean anyScore = rows.stream().anyMatch(r -> parseOrNull(r.get("gmat_score")) != null || parseOrNull(r.get("sat_score")) != null);
        if (!anyScore) return;

        Sheet summary = wb.createSheet("Score Summary");
        CellStyle titleStyle = boldStyle(wb, 14, "1B5E20", null, null);
        int r = 0;
        setCell(summary, r++, 0, "SCORE SUMMARY", titleStyle);
        r++; // blank
        setCell(summary, r++, 0, "GMAT Score Statistics:", null);
        setCell(summary, r, 0, "Highest Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "max"), null);
        setCell(summary, r, 0, "Lowest Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "min"), null);
        setCell(summary, r, 0, "Average Score:", null); setCell(summary, r++, 1, statOrNA(gmat, "avg"), null);
        r++; // blank
        setCell(summary, r++, 0, "SAT Score Statistics:", null);
        setCell(summary, r, 0, "Highest Score:", null); setCell(summary, r++, 1, statOrNA(sat, "max"), null);
        setCell(summary, r, 0, "Lowest Score:", null); setCell(summary, r++, 1, statOrNA(sat, "min"), null);
        setCell(summary, r, 0, "Average Score:", null); setCell(summary, r++, 1, statOrNA(sat, "avg"), null);
        r++; // blank
        int withScores = (int) rows.stream().filter(row -> parseOrNull(row.get("gmat_score")) != null || parseOrNull(row.get("sat_score")) != null).count();
        setCell(summary, r++, 0, "Total Students with Scores: " + withScores, null);
        setCell(summary, r, 0, "Total Students Overall: " + rows.size(), null);
        summary.setColumnWidth(0, 25 * 256);
        summary.setColumnWidth(1, 15 * 256);
    }

    private static void setCell(Sheet sheet, int r, int c, String value, CellStyle style) {
        Row row = sheet.getRow(r);
        if (row == null) row = sheet.createRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static String statOrNA(List<Double> vals, String kind) {
        if (vals.isEmpty()) return "N/A";
        double v = switch (kind) {
            case "max" -> vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "min" -> vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            default -> vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };
        return kind.equals("avg") ? String.format(Locale.US, "%.2f", v) : String.valueOf(v);
    }

    private static Double parseOrNull(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static void styleScoreCell(Cell cell, Object rawScore, CellStyle greenStyle, CellStyle redStyle) {
        Double v = parseOrNull(rawScore);
        if (v == null) return;
        cell.setCellStyle(v >= 70 ? greenStyle : redStyle);
    }

    private static CellStyle boldStyle(Workbook wb, int size, String fontArgb, String fillArgb, HorizontalAlignment align) {
        CellStyle style = wb.createCellStyle();
        org.apache.poi.xssf.usermodel.XSSFFont font = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) size);
        if (fontArgb != null) font.setColor(new XSSFColor(colorFromHex(fontArgb), null));
        style.setFont(font);
        if (fillArgb != null) {
            style.setFillForegroundColor(new XSSFColor(colorFromHex(fillArgb), null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (align != null) style.setAlignment(align);
        return style;
    }

    /** fontHex colors the score TEXT (green "006100"/red "9C0000"); fillHex colors the cell BACKGROUND
     *  ("E6F3E6"/"FFE6E6") -- matches Node's ExcelJS `font.color` + `fill.fgColor` pair exactly. */
    private static CellStyle scoreStyle(Workbook wb, String fontHex, String fillHex, boolean bold) {
        CellStyle style = wb.createCellStyle();
        org.apache.poi.xssf.usermodel.XSSFFont font = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
        font.setBold(bold);
        font.setColor(new XSSFColor(colorFromHex(fontHex), null));
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(colorFromHex(fillHex), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static Color colorFromHex(String hex) {
        return new Color(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
    }

    private static int colWidth(int c) {
        int[] widths = {8, 15, 15, 25, 35, 20, 15, 15, 12, 12};
        return widths[c] * 256;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }

    private static String formatDate(Object dateVal) {
        if (dateVal == null) return "";
        LocalDate d = LocalDate.parse(String.valueOf(dateVal)); // genericRow emits DATE as "yyyy-MM-dd"
        return DATE_DDMMYYYY.format(d);
    }
}
```

> **`XSSFFont` cast note.** `wb.createFont()` returns the workbook's native `Font` type; since this support class always constructs an `XSSFWorkbook` (never HSSF), the cast to `org.apache.poi.xssf.usermodel.XSSFFont` in `boldStyle`/`scoreStyle` is always safe and is required to call `setColor(XSSFColor)` (the base `Font` interface only exposes the legacy indexed-palette `setColor(short)`).

Add to `ExamsController` (constructor now also takes `ExamCallingListXlsxSupport xlsx`; add imports `org.springframework.http.ResponseEntity`, `org.springframework.http.MediaType`):
```java
    private final ExamCallingListXlsxSupport xlsx;

    // constructor signature grows again in Task 6 -- see that task's full constructor listing.

    @GetMapping("/{examId}/student-list")
    public ResponseEntity<byte[]> studentList(@PathVariable String examId) {
        List<Map<String, Object>> rows;
        try {
            rows = reads.studentListRows(examId);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to generate Excel file").with("error", e.getMessage());
        }
        if (rows.isEmpty()) throw ApiException.message(404, "No students found for this exam.");

        byte[] bytes;
        try {
            bytes = xlsx.build(rows);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to generate Excel file").with("error", e.getMessage());
        }
        String examName = String.valueOf(rows.get(0).get("exam_name")).replaceAll("\\s+", "_");
        String filename = examName + "_Calling_List.xlsx";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamStudentListIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamStudentListIT.java
git commit -m "feat(exams): student-list calling-list XLSX (POI, in-memory, score-coloring, optional summary sheet)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Hall tickets — asset copy, `HallTicketPdfSupport` (OpenPDF), `HallTicketZipSupport` (ZipOutputStream), public single PDF + admin ZIP, then FULL suite

Copy the 5 static assets to classpath resources, build the OpenPDF hall-ticket PDF (functional content, readable layout — not a pixel clone, per Firm Decision 10), wire the ZIP-of-all-tickets endpoint and the PUBLIC single-ticket endpoint, confirm `SecurityConfig`'s forward-declared permit matcher, then run the full regression suite.

**Files:**
- Copy: 5 asset files into `imas-backend/src/main/resources/exam-assets/`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/persistence/ExamsReadRepository.java` (add `hallTicketByNumber`, `hallTicketsForExam`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/service/HallTicketPdfSupport.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/exams/service/HallTicketZipSupport.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/exams/web/ExamsController.java` (add 2 handlers, final constructor)
- Modify: `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java` (comment only — the matcher already exists)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamHallTicketIT.java`

- [ ] **Step 1: Copy the 5 static assets**

```bash
mkdir -p imas-backend/src/main/resources/exam-assets
cp "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/public/assets/rcf_logo-removebg-preview.png" imas-backend/src/main/resources/exam-assets/
cp "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/public/assets/logo.png" imas-backend/src/main/resources/exam-assets/
cp "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/public/assets/ravi_sir_sign-removebg-preview.png" imas-backend/src/main/resources/exam-assets/
cp "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/public/assets/rcf_stamp-removebg-preview.png" imas-backend/src/main/resources/exam-assets/
cp "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/public/fonts/NotoSansKannada-Regular.ttf" imas-backend/src/main/resources/exam-assets/
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/exams/ExamHallTicketIT.java`:
```java
package com.rcf.imas.modules.exams;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ExamHallTicketIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    long uid;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('htseed','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='htseed'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "htseed", "ADMIN");

        jdbc.sql("""
            INSERT INTO pp.pp_exam_centre(pp_exam_centre_id, pp_exam_centre_name, address, village, pincode, latitude, longitude, active_yn)
            VALUES (85001,'HT Centre','123 Main Rd','HT Village','590001',15.85,74.50,'Y') ON CONFLICT (pp_exam_centre_id) DO NOTHING
            """).update();
        jdbc.sql("SELECT setval('pp.pp_exam_centre_seq', (SELECT MAX(pp_exam_centre_id)::bigint FROM pp.pp_exam_centre))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.examination(exam_id, exam_name, exam_date, exam_start_time, exam_end_time, exam_year, pp_exam_centre_id)
            VALUES (850201,'HT Exam','2027-06-01','09:00:00','11:00:00','2027',85001)
            """).update();
        jdbc.sql("SELECT setval('pp.examination_seq', (SELECT MAX(exam_id)::bigint FROM pp.examination))").query(Long.class).single();

        jdbc.sql("""
            INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, district, student_name, father_name, created_by, updated_by)
            VALUES (850101,2027,24085000001,850001,'HallTicketKid','f',:u,:u)
            """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.applicant_exam(applicant_id, exam_id, pp_hall_ticket_no) VALUES (850101, 850201, '27HT0001')").update();
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.applicant_exam WHERE exam_id = 850201").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 850101").update();
        jdbc.sql("DELETE FROM pp.examination WHERE exam_id = 850201").update();
        jdbc.sql("DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = 85001").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_name = 'htseed'").update();
    }

    @Test
    void hallTicketSingleWorksWithNoAuthorizationHeaderAtAll() throws Exception {
        // The critical parity assertion: this endpoint must be reachable with ZERO Authorization header --
        // not 401, not 403 -- matching the public, unauthenticated StudentHallticketPage.js call.
        byte[] pdf = mvc.perform(get("/api/exams/hallticket/27HT0001"))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/pdf"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"27HT0001.pdf\""))
           .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void hallTicketSingleMissingIs404() throws Exception {
        mvc.perform(get("/api/exams/hallticket/DOES-NOT-EXIST"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Hall ticket not found"));
    }

    @Test
    void downloadAllHallTicketsReturnsZipWithOneEntryPerStudent() throws Exception {
        byte[] zip = mvc.perform(get("/api/exams/850201/HT Exam/download-all-hall-tickets").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/zip"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=All_Hall_Tickets_850201_HT_Exam.zip"))
           .andReturn().getResponse().getContentAsByteArray();

        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                assertThat(entry.getName()).isEqualTo("HallTicketKid_27HT0001.pdf");
                entryCount++;
            }
        }
        assertThat(entryCount).isEqualTo(1);
    }

    @Test
    void downloadAllHallTicketsMissingExamIs404() throws Exception {
        mvc.perform(get("/api/exams/999999999/NoSuchExam/download-all-hall-tickets").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("No hall tickets found"));
    }

    @Test
    void downloadAllHallTicketsRequiresAdmin() throws Exception {
        String studentTok = jwt.issueFinalToken("999", "s", "STUDENT");
        mvc.perform(get("/api/exams/850201/HT Exam/download-all-hall-tickets").header("Authorization", "Bearer " + studentTok))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamHallTicketIT` — Expected: FAIL.

- [ ] **Step 4: Implement**

Add to `ExamsReadRepository`:
```java
    /** singlestudentdownloadhallticket() parity. */
    public Map<String, Object> hallTicketByNumber(String hallTicketNo) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, api.district AS juris_code,
                  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
                  ec.latitude, ec.longitude, ec.address, ec.village, ec.pincode, api.nmms_reg_number
                FROM pp.applicant_exam ae
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.examination e ON ae.exam_id = e.exam_id
                JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
                WHERE ae.pp_hall_ticket_no = :ticket
                """).param("ticket", hallTicketNo).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** downloadAllHallTickets() parity. */
    public List<Map<String, Object>> hallTicketsForExam(String examId) {
        return jdbc.sql("""
                SELECT
                  ae.pp_hall_ticket_no, api.student_name, api.nmms_reg_number,
                  api.district AS juris_code,
                  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
                  ec.latitude, ec.address, ec.village, ec.pincode, ec.longitude
                FROM pp.applicant_exam ae
                JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
                JOIN pp.examination e ON ae.exam_id = e.exam_id
                JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
                WHERE ae.exam_id = :examId::numeric
                """).param("examId", examId).query((rs, i) -> genericRow(rs)).list();
    }
```

`src/main/java/com/rcf/imas/modules/exams/service/HallTicketPdfSupport.java`:
```java
package com.rcf.imas.modules.exams.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenPDF port of examControllers.js generateStudentPDF (Firm Decision 10). Reproduces the FUNCTIONAL content
 * verbatim -- all data fields, the hardcoded institutional strings (incl. "PRATIBHA POSHAK EXAMINATION - 2026"),
 * the 4 signature boxes, the Kannada instructions block with the embedded TTF -- as a simple top-down flow
 * document (Paragraphs/PdfPTables), NOT a pixel-for-pixel clone of pdfkit's absolute x/y layout. Kannada shaping
 * fidelity is best-effort (documented risk -- verify visually against a Node-generated reference PDF).
 */
@Component
public class HallTicketPdfSupport {

    private static final String HEADER_TITLE = "RAJALAKSHMI CHILDREN FOUNDATION";
    private static final String HEADER_SUBTITLE = "PRATIBHA POSHAK EXAMINATION - 2026";
    private static final String HEADER_ADDRESS = "Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016";
    private static final String HEADER_CONTACT = "Contact No. +91 9444900755, +91 9606930208";

    private static final String[] KANNADA_INSTRUCTIONS = {
        "೧) ವಿದ್ಯಾರ್ಥಿಗಳು ತಮ್ಮ ಆಧಾರ್ ಕಾರ್ಡ್ ಫೋಟೋಕಾಪಿ ಮತ್ತು ಇತ್ತೀಚಿನ ಪಾಸ್ಪೋರ್ಟ್ ಗಾತ್ರದ ಒಂದು ಫೋಟೋ ಕಡ್ಡಾಯವಾಗಿ ತರಬೇಕು.",
        "೨) ದಯವಿಟ್ಟು ನಿಮ್ಮ ಜ್ಯಾಮೆಟ್ರಿ ಬಾಕ್ಸ್, ಪೆನ್ ಮತ್ತು ಪರೀಕ್ಷಾ ಪ್ಯಾಡ್ ತರಬೇಕು.",
        "೩) ವಿದ್ಯಾರ್ಥಿಗಳು ಪರೀಕ್ಷಾ ಕೇಂದ್ರಕ್ಕೆ ನಿಗದಿತ ಸಮಯಕ್ಕಿಂತ ಕನಿಷ್ಠ ೩೦ ನಿಮಿಷಗಳ ಮುಂಚಿತವಾಗಿ ಆಗಮಿಸಬೇಕು.",
        "೪) ಮೊಬೈಲ್, ಟ್ಯಾಬ್, ಸ್ಮಾರ್ಟ್ ವಾಚ್ ಮತ್ತು ಇತರ ಎಲೆಕ್ಟ್ರಾನಿಕ್ ಸಾಧನಗಳು ನಿಷೇಧಿತ.",
        "೫) ವಿದ್ಯಾರ್ಥಿಗಳು ಪರೀಕ್ಷೆಯ ವೇಳೆ ಮೇಲ್ವಿಚಾರಕರ ಸೂಚನೆಗಳನ್ನು ಅನುಸರಿಸಬೇಕು.",
        "೬) ಇತರರಿಗೆ ಅಡ್ಡಿಪಡಿಸದಂತೆ ಪರೀಕ್ಷೆಯ ಅವಧಿಯಲ್ಲಿ ಮೌನವನ್ನು ಕಾಪಾಡಿ.",
        "೭) ಯಾವುದೇ ರೀತಿಯ ನಕಲು (ಚೀಟಿ) ಕಂಡುಬಂದಲ್ಲಿ, ವಿದ್ಯಾರ್ಥಿಯನ್ನು ತಕ್ಷಣವೇ ಆನರ್ಹಗೊಳಿಸಲಾಗುವುದು.",
        "೮) ಪರೀಕ್ಷೆಯ ಸಮಯದಲ್ಲಿ ವಿದ್ಯಾರ್ಥಿಗಳ ಮಧ್ಯೆ ಸಂಭಾಷಣೆ ಅನುಮತಿ ಇಲ್ಲ.",
        "೯) ಸಹಾಯ ಬೇಕಾದರೆ ಅಥವಾ ಅನುಮಾನ ಇದ್ದರೆ, ಕೈ ಎತ್ತಿ ಮೇಲ್ವಿಚಾರಕರ ಸಹಾಯಕ್ಕಾಗಿ ಕೇಳಬೇಕು."
    };

    private final byte[] logoLeft;
    private final byte[] logoRight;
    private final byte[] kannadaTtf;
    private final byte[] authoritySignature;
    private final byte[] stamp;

    public HallTicketPdfSupport() {
        this.logoLeft = readClasspathBytes("exam-assets/rcf_logo-removebg-preview.png");
        this.logoRight = readClasspathBytes("exam-assets/logo.png");
        this.kannadaTtf = readClasspathBytes("exam-assets/NotoSansKannada-Regular.ttf");
        this.authoritySignature = readClasspathBytes("exam-assets/ravi_sir_sign-removebg-preview.png");
        this.stamp = readClasspathBytes("exam-assets/rcf_stamp-removebg-preview.png");
    }

    private static byte[] readClasspathBytes(String path) {
        try {
            return new ClassPathResource(path).getInputStream().readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Missing required exam-asset resource: " + path, e);
        }
    }

    public byte[] build(Map<String, Object> student) {
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            BaseFont kannadaBase = BaseFont.createFont("NotoSansKannada-Regular.ttf", BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, kannadaTtf, null);
            Font kannadaTitleFont = new Font(kannadaBase, 16);
            Font kannadaBodyFont = new Font(kannadaBase, 10);

            addHeader(doc);
            addHallTicketTitle(doc);
            addStudentDetails(doc, student);
            addExamCentreDetails(doc, student);
            addExamDateAndReportingTime(doc, student);
            addKannadaInstructions(doc, kannadaTitleFont, kannadaBodyFont);
            addSignatureBoxes(doc);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException(e);
        }
    }

    private void addHeader(Document doc) throws DocumentException {
        PdfPTable headerRow = new PdfPTable(new float[]{1f, 4f, 1f});
        headerRow.setWidthPercentage(100);
        headerRow.addCell(borderlessImageCell(logoLeft));
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph(HEADER_TITLE, new Font(Font.HELVETICA, 18, Font.BOLD));
        title.setAlignment(Element.ALIGN_CENTER);
        Paragraph subtitle = new Paragraph(HEADER_SUBTITLE, new Font(Font.HELVETICA, 16, Font.BOLD));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(title);
        titleCell.addElement(subtitle);
        headerRow.addCell(titleCell);
        headerRow.addCell(borderlessImageCell(logoRight));
        doc.add(headerRow);

        Paragraph address = new Paragraph(HEADER_ADDRESS, new Font(Font.HELVETICA, 8));
        address.setAlignment(Element.ALIGN_CENTER);
        Paragraph contact = new Paragraph(HEADER_CONTACT, new Font(Font.HELVETICA, 8));
        contact.setAlignment(Element.ALIGN_CENTER);
        contact.setSpacingAfter(10f);
        doc.add(address);
        doc.add(contact);
    }

    private PdfPCell borderlessImageCell(byte[] imageBytes) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        try {
            Image img = Image.getInstance(imageBytes);
            img.scaleToFit(70, 70);
            cell.addElement(img);
        } catch (Exception e) {
            // logo genuinely missing/corrupt -- omit silently, matching Node's `if (fs.existsSync(...))` guard
        }
        return cell;
    }

    private void addHallTicketTitle(Document doc) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell cell = new PdfPCell(new Phrase("HALL TICKET", new Font(Font.HELVETICA, 24, Font.BOLD)));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(10f);
        table.addCell(cell);
        doc.add(table);
    }

    private void addStudentDetails(Document doc, Map<String, Object> student) throws DocumentException {
        PdfPTable outer = new PdfPTable(new float[]{3f, 1f});
        outer.setWidthPercentage(100);
        outer.setSpacingBefore(10f);

        PdfPCell details = new PdfPCell();
        details.setPadding(8f);
        details.addElement(new Paragraph("STUDENT DETAILS", new Font(Font.HELVETICA, 14, Font.BOLD)));
        details.addElement(fieldLine("Name:", str(student.get("student_name"))));
        details.addElement(fieldLine("Hall Ticket No:", str(student.get("pp_hall_ticket_no"))));
        details.addElement(fieldLine("NMMS Register No:", str(student.get("nmms_reg_number"))));
        outer.addCell(details);

        PdfPCell photo = new PdfPCell(new Phrase("Passport Photo\n3.5cm x 4.5cm", new Font(Font.HELVETICA, 8)));
        photo.setHorizontalAlignment(Element.ALIGN_CENTER);
        photo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        photo.setFixedHeight(110f);
        outer.addCell(photo);

        doc.add(outer);
    }

    private Paragraph fieldLine(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", new Font(Font.HELVETICA, 12, Font.BOLD)));
        p.add(new Chunk(value == null || value.isBlank() ? "N/A" : value, new Font(Font.HELVETICA, 12)));
        return p;
    }

    private void addExamCentreDetails(Document doc, Map<String, Object> student) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10f);
        PdfPCell cell = new PdfPCell();
        cell.setPadding(8f);
        cell.addElement(new Paragraph("Exam Center Details:", new Font(Font.HELVETICA, 10, Font.BOLD)));
        cell.addElement(new Paragraph(orDefault(str(student.get("pp_exam_centre_name")), "Exam Center"),
                new Font(Font.HELVETICA, 10, Font.BOLD)));

        java.util.List<String> parts = new java.util.ArrayList<>();
        if (notBlank(student.get("address"))) parts.add(str(student.get("address")));
        if (notBlank(student.get("village"))) parts.add(str(student.get("village")));
        if (notBlank(student.get("pincode"))) parts.add(str(student.get("pincode")));
        String fullAddress = parts.isEmpty() ? "Address not available" : String.join(", ", parts);

        Object lat = student.get("latitude");
        Object lng = student.get("longitude");
        if (notBlank(lat) && notBlank(lng)) {
            Anchor link = new Anchor(fullAddress, new Font(Font.HELVETICA, 9, Font.UNDERLINE, java.awt.Color.BLUE));
            link.setReference("https://www.google.com/maps?q=" + str(lat) + "," + str(lng));
            cell.addElement(new Paragraph(link));
        } else {
            cell.addElement(new Paragraph(fullAddress, new Font(Font.HELVETICA, 9)));
        }
        table.addCell(cell);
        doc.add(table);
    }

    private void addExamDateAndReportingTime(Document doc, Map<String, Object> student) throws DocumentException {
        String formattedExamDateTime = formatDate(str(student.get("exam_date"))) + ", "
                + formatTimeManual(str(student.get("exam_start_time"))) + " to " + formatTimeManual(str(student.get("exam_end_time")));

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10f);

        PdfPCell dateCell = new PdfPCell();
        dateCell.setPadding(8f);
        dateCell.addElement(new Paragraph("Exam Date & Time", new Font(Font.HELVETICA, 14, Font.BOLD)));
        dateCell.addElement(new Paragraph(formattedExamDateTime, new Font(Font.HELVETICA, 12)));
        table.addCell(dateCell);

        PdfPCell reportingCell = new PdfPCell();
        reportingCell.setPadding(8f);
        reportingCell.addElement(new Paragraph("Reporting Time", new Font(Font.HELVETICA, 14, Font.BOLD)));
        reportingCell.addElement(new Paragraph(formatTimeManual(str(student.get("exam_start_time"))), new Font(Font.HELVETICA, 12)));
        table.addCell(reportingCell);

        doc.add(table);
    }

    private void addKannadaInstructions(Document doc, Font titleFont, Font bodyFont) throws DocumentException {
        Paragraph title = new Paragraph("ಸೂಚನೆಗಳು", titleFont); // "Instructions" (Kannada, verbatim)
        title.setSpacingBefore(12f);
        doc.add(title);
        LineSeparator line = new LineSeparator();
        doc.add(new Chunk(line));

        Paragraph instructions = new Paragraph();
        instructions.setSpacingBefore(6f);
        for (String line1 : KANNADA_INSTRUCTIONS) {
            instructions.add(new Paragraph(line1, bodyFont));
        }
        doc.add(instructions);
    }

    private void addSignatureBoxes(Document doc) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingBefore(20f);

        table.addCell(signatureCell("Authority Signature", authoritySignature, 60f, 25f));
        table.addCell(signatureCell("Invigilator Signature", null, 0, 0));
        table.addCell(signatureCell("Student Signature", null, 0, 0));
        table.addCell(signatureCell("Official Seal", stamp, 60f, 45f));

        doc.add(table);
    }

    private PdfPCell signatureCell(String label, byte[] imageBytes, float w, float h) {
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(60f);
        cell.setPadding(6f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph p = new Paragraph(label, new Font(Font.HELVETICA, 10, Font.BOLD));
        p.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p);
        if (imageBytes != null) {
            try {
                Image img = Image.getInstance(imageBytes);
                img.scaleToFit(w, h);
                cell.addElement(img);
            } catch (Exception ignored) {
                // signature/stamp genuinely missing -- omit silently, matching Node's fs.existsSync guard
            }
        }
        return cell;
    }

    private static boolean notBlank(Object v) { return v != null && !String.valueOf(v).isBlank(); }
    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String orDefault(String v, String def) { return (v == null || v.isBlank()) ? def : v; }

    /** formatDate(dateString) parity (examControllers.js:1503-1515): DD-MM-YYYY, zero-padded. */
    static String formatDate(String dateString) {
        if (dateString == null || dateString.isBlank()) return "N/A";
        try {
            LocalDate d = LocalDate.parse(dateString.length() > 10 ? dateString.substring(0, 10) : dateString);
            return DateTimeFormatter.ofPattern("dd-MM-yyyy").format(d);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static final Pattern TIME_RE = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?");

    /** formatTimeManual(timeStr) parity (examControllers.js:1518-1532): 12-hour AM/PM. */
    static String formatTimeManual(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return "N/A";
        Matcher m = TIME_RE.matcher(timeStr);
        if (!m.find()) return timeStr;
        int hh = Integer.parseInt(m.group(1));
        String mm = m.group(2);
        String ampm = hh >= 12 ? "PM" : "AM";
        hh = hh % 12;
        if (hh == 0) hh = 12;
        return String.format("%02d:%s %s", hh, mm, ampm);
    }
}
```

`src/main/java/com/rcf/imas/modules/exams/service/HallTicketZipSupport.java`:
```java
package com.rcf.imas.modules.exams.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** downloadAllHallTickets() parity: one HallTicketPdfSupport.build(...) call per student, zipped in-memory
 *  via java.util.zip.ZipOutputStream (JDK built-in, no new dependency, Firm Decision 9 -- no disk writes,
 *  unlike Node's per-student temp PDF files + archiver). */
@Component
public class HallTicketZipSupport {

    private final HallTicketPdfSupport pdfSupport;

    public HallTicketZipSupport(HallTicketPdfSupport pdfSupport) { this.pdfSupport = pdfSupport; }

    public byte[] build(List<Map<String, Object>> students) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map<String, Object> student : students) {
                String safeName = sanitize(String.valueOf(student.get("student_name")));
                String safeTicket = sanitize(String.valueOf(student.get("pp_hall_ticket_no")));
                byte[] pdfBytes = pdfSupport.build(student);
                zip.putNextEntry(new ZipEntry(safeName + "_" + safeTicket + ".pdf"));
                zip.write(pdfBytes);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** sanitizeFilename(name) parity: `[<>:"/\\|?*]` -> '_', truncate to 100 chars, null -> "unknown". */
    public static String sanitize(String name) {
        if (name == null || "null".equals(name)) return "unknown";
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "_");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
```

Add to `ExamsController` (final constructor form — all 6 tasks' dependencies; add imports `com.rcf.imas.modules.exams.service.HallTicketPdfSupport`, `com.rcf.imas.modules.exams.service.HallTicketZipSupport`):
```java
    private final HallTicketPdfSupport hallTicketPdf;
    private final HallTicketZipSupport hallTicketZip;

    ExamsController(ExamsReadRepository reads, ExamsWriteRepository writes, ExamCallingListXlsxSupport xlsx,
                     HallTicketPdfSupport hallTicketPdf, HallTicketZipSupport hallTicketZip) {
        this.reads = reads;
        this.writes = writes;
        this.xlsx = xlsx;
        this.hallTicketPdf = hallTicketPdf;
        this.hallTicketZip = hallTicketZip;
    }

    /** PUBLIC (Firm Decision 8) -- overrides the class-level @PreAuthorize("hasRole('ADMIN')"); Spring Method
     *  Security evaluates the METHOD annotation instead of the class one when both are present (they do not
     *  combine/AND). SecurityConfig's filter-chain permit matcher for GET /api/exams/hallticket/** already exists
     *  (added in Plan 1, forward-declared) so the request never even reaches JwtAuthFilter's auth requirement. */
    @GetMapping("/hallticket/{hallTicketNo}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<byte[]> hallTicket(@PathVariable String hallTicketNo) {
        Map<String, Object> student;
        try {
            student = reads.hallTicketByNumber(hallTicketNo);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall ticket").with("error", e.getMessage());
        }
        if (student == null) throw ApiException.message(404, "Hall ticket not found");

        byte[] pdf;
        try {
            pdf = hallTicketPdf.build(student);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall ticket").with("error", e.getMessage());
        }
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + hallTicketNo + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @GetMapping("/{examId}/{examName}/download-all-hall-tickets")
    public ResponseEntity<byte[]> downloadAllHallTickets(@PathVariable String examId, @PathVariable String examName) {
        List<Map<String, Object>> students;
        try {
            students = reads.hallTicketsForExam(examId);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall tickets").with("error", e.getMessage());
        }
        if (students.isEmpty()) throw ApiException.message(404, "No hall tickets found");

        byte[] zip;
        try {
            zip = hallTicketZip.build(students);
        } catch (Exception e) {
            throw ApiException.message(500, "Failed to download hall tickets").with("error", e.getMessage());
        }
        String filename = "All_Hall_Tickets_" + examId + "_" + com.rcf.imas.modules.exams.service.HallTicketZipSupport.sanitize(examName) + ".zip";
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=" + filename)
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }
```

> **Route-pattern note.** `GET /{examId}/{examName}/download-all-hall-tickets` (2 path segments + literal suffix) and `GET /{examId}/student-list` (1 path segment + literal suffix) and `PUT /{examId}/freeze` / `DELETE /{examId}` (1 segment) coexist without ambiguity in Spring because they differ in segment count and/or HTTP method — mirrors the ground truth's note that Node's Express route stack has no real collision here either.

Update `SecurityConfig`'s stale comment (the matcher itself was already added in Plan 1 — only the comment needs updating now that the endpoint is real):
```java
                // Public hall-ticket PDF download -- ExamsController.hallTicket() (GET /api/exams/hallticket/{no}),
                // method-level @PreAuthorize("permitAll()") override, per Plan 3a Firm Decision 8.
                .requestMatchers(HttpMethod.GET, "/api/exams/hallticket/**").permitAll()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ExamHallTicketIT` — Expected: PASS.

- [ ] **Step 6: Run the FULL suite (regression) + commit**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test` — Expected: BUILD SUCCESS, all prior tests + all new exams-module tests green.

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/exams imas-backend/src/main/resources/exam-assets \
        imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java \
        imas-backend/src/test/java/com/rcf/imas/modules/exams/ExamHallTicketIT.java
git commit -m "feat(exams): hall tickets -- public single PDF (OpenPDF, Kannada TTF) + admin ZIP (ZipOutputStream)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final review (after all 6 tasks)

Dispatch a consolidated `superpowers:code-reviewer` over the whole `modules/exams` package against this plan + the spec, checking:
- **`GET /count` truly absent** — grep the module for any trace of a `/count` mapping or `pp.exam`-table reference; confirm it's flagged in Deferred below, not silently dropped without a paper trail.
- **`createExamAndAssignApplicants` truly absent** — no `/create` handler other than `createExamOnly`'s; no leftover 2-arg `generateHallTicket`.
- **`assign-students` mapped exactly once** — grep for `@PostMapping("/{examId}/assign-students")`, confirm a single occurrence (Spring would have failed to start at all otherwise, so a green test suite already proves this, but confirm no duplicate was silently merged into one overloaded method with dead branches).
- **`GET /viewcentres` returns a real 500 on failure**, not a hang — this can't be pinned by an IT easily (would require breaking the DB mid-test); confirm by code inspection that no try/catch swallows the exception silently in this handler.
- **`deleteExam`/`createExamOnly`/`assignStudents` are the ONLY 3 `@Transactional` methods** in `ExamsWriteRepository`; exam-centre create/update/delete and `freezeExam` remain single-statement/autocommit, matching Node exactly (no gratuitous new transactions added beyond the 3 Firm-Decision-mandated ones).
- **Quirks preserved verbatim, all 6:** (a) `updateExamCentre`'s `active_yn || 'Y'` fallback — pinned by `updateCentreFalsyActiveYnResetsToY`; (b) `freezeExam` no existence check — pinned by `freezeExamHasNoExistenceCheck`; (c) `deleteExam` no existence check — pinned by `deleteExamNoExistenceCheckStill200sForMissingId`; (d) `createExamOnly` allows `academic_year` omitted → `exam_year` NULL — pinned by `createExamOnlyAllowsOmittedAcademicYearOrphanQuirk`; (e) hall-ticket sequence gap-not-collision on `ON CONFLICT DO NOTHING` — pinned by `assignStudentsRerunBurnsSequenceGapButDoesNotDuplicateRow`; (f) the two distinct "year" values in `assignStudents` never cross-validated — pinned by `assignStudentsSuccessGeneratesHallTicketNumberFromRequestBodyAcademicYearNotExamYear` (exam_year=`'2027'` drives shortlist eligibility, request-body `academicYear='2028-29'` drives the `"28"` prefix in the generated ticket).
- **`used-blocks` returns real JSON numbers**, not strings — pinned by `usedBlocksReturnsJsonNumbersNotStrings`; confirm `ExamsReadRepository.usedBlocks` returns `List<Long>` (typed query), not a `genericRow`-mapped list.
- **`ARRAY_AGG` mapping is exact** — `genericRow`'s new `java.sql.Types.ARRAY` case produces `List<String>` (numeric elements `BigDecimal.toBigInteger().toString()`, text elements passthrough), confirmed by `assignedSplitsYearYYYYDashYYAndReturnsArrayAggAsStringLists`. Confirm this extension lives ONLY in `ExamsReadRepository.genericRow` and wasn't accidentally duplicated or drifted from the evaluation/results modules' copies of the same helper (each module keeps its own static `genericRow` per the established pattern — no shared base class was introduced).
- **Error-key exactness, the module's most inconsistent area** — spot-check against the endpoint contract table: `createExamOnly`'s 400 uses `error` (not `message`); `assignStudents`'s two 404s use different keys (`error` for exam-not-found, `message` for no-shortlisted-applicants) — both pinned by dedicated tests, don't let a "consistency" refactor collapse them to one key.
- **Hall-ticket number algorithm exactness** — `generateHallTicket`'s juris-code-last-2-digits-padded logic, pinned by the literal expected value `"28030001"` in `assignStudentsSuccessGeneratesHallTicketNumberFromRequestBodyAcademicYearNotExamYear`. Also verify the `IllegalStateException` guard (juris code / academic year null) mirrors Node's `throw new Error(...)` — an error here rolls back the whole `@Transactional` method, matching Node's ROLLBACK-then-500 behavior.
- **`GET /hallticket/{hallTicketNo}` is genuinely reachable with NO Authorization header** — re-run `hallTicketSingleWorksWithNoAuthorizationHeaderAtAll` specifically and confirm it is not merely passing because `JwtAuthFilter` treats a missing header as anonymous-but-still-200 for every endpoint (it must NOT — every other endpoint in this module correctly 403s a student token and would 401 with no token at all; only this one specific method is exempt via its own `@PreAuthorize("permitAll()")`).
- **PDF/ZIP are genuinely in-memory** — grep the whole module for `FILE_STORAGE_PATH`, `File.createTempFile`, or any `java.io.File`/`FileOutputStream` write; there should be none (Firm Decision 9).
- **Asset resources present and loaded correctly** — confirm all 5 files exist under `imas-backend/src/main/resources/exam-assets/` and that `HallTicketPdfSupport`'s constructor doesn't throw at Spring context startup (a missing/misnamed asset would fail application boot, not just one request — this is a stronger safety net than Node's per-request `fs.existsSync` check, and should be called out as a deliberate improvement, not a regression).
- **SecurityConfig**: the `/api/exams/hallticket/**` permit matcher was already present pre-Task-6 (added in Plan 1) — confirm only the comment changed, not the matcher itself, and that no other exams path accidentally became public.

Update `imas-migration-status` memory: Phase 3a complete, new test count, `GET /count` and `createExamAndAssignApplicants` explicitly flagged as not-ported, Kannada PDF rendering flagged as a visual-fidelity risk needing manual spot-check, ready for the next Phase-3 sub-module.

## Deferred / parity decisions carried into this plan

- **`GET /count` NOT ported** (Firm Decision 1) — permanently broken in Node (`db` undefined, `pp.exam` table doesn't exist). No frontend caller found. **FLAGGED FOR USER:** implement `SELECT COUNT(*) FROM pp.examination WHERE pp_exam_centre_id=? AND exam_date=?` only if a caller needs it; the schema table is `pp.examination`, not `pp.exam`.
- **`createExamAndAssignApplicants` NOT ported** (Firm Decision 5) — dead code, route commented out in Node, internally broken (calls an undefined 2-arg `generateHallTicket` that would `ReferenceError` if ever invoked). Confirmed unreachable via any live route.
- **`assign-students`'s duplicate route registration de-duplicated** (Firm Decision 4) — Node registers the same handler on the same path twice on one source line; ported as a single `@PostMapping`.
- **`GET /viewcentres`'s hang fixed to a real 500** (Firm Decision 2) — Node's catch block calls `console("...")` (a `TypeError`, since `console` is not callable as a function), leaving the HTTP response permanently unresolved on any DB failure. The Java port relies on `GlobalExceptionHandler`'s generic `{error:"Internal Server Error"}` fallback instead — a deliberate bug fix, not a parity target.
- **`deleteExam` made genuinely transactional** (Firm Decision 3) — Node's `pool.query("BEGIN")`/`"COMMIT"` runs on the shared connection pool, not a single checked-out client, so it provides no real atomicity guarantee; a real `@Transactional` in `ExamsWriteRepository` is a deliberate improvement with no compatibility cost (nothing depended on the old non-atomicity).
- **`createExamCentre`'s fictitious `error.constraint` duplicate-detection branches NOT ported** (Firm Decision 7) — those constraint names (`pp_exam_centre_code_key`, `pp_exam_centre_name_key`, `contact_phone_key`, `contact_email_key`) don't exist in the schema (the real one is `pp_exam_centre_pp_exam_centre_code_key`, and only for the code column); they're dead code in Node. The pre-insert `checkExistingCentre` SELECT is the only real duplicate-detection path; a TOCTOU race between that SELECT and the INSERT is accepted (falls through to the generic `{message:"Failed to create centre"}` 500), matching Node's actual (not intended) behavior.
- **Hall-ticket PDF is a readable top-down flow document, not a pixel clone of pdfkit's absolute-coordinate layout** (Firm Decision 10) — all FUNCTIONAL content is present verbatim (institutional header strings including the literal "PRATIBHA POSHAK EXAMINATION - 2026" and the exact address/phone strings, both logos, student/centre/exam-time data fields, the 9 Kannada instruction lines with the embedded TTF, all 4 signature boxes with their 2 images). **Kannada script shaping fidelity is explicitly flagged as best-effort** — OpenPDF's `BaseFont.createFont(..., IDENTITY_H, EMBEDDED, ...)` embeds the TTF and renders the glyphs, but complex-script (conjunct/vowel-sign) shaping correctness for Kannada has not been visually verified against a Node-generated reference PDF in this plan; the implementing/reviewing engineer should generate one hall ticket via each stack and eyeball-compare the Kannada block before considering Task 6 fully done.
- **File generation entirely in-memory** (Firm Decision 9) — XLSX (`ExamCallingListXlsxSupport`), single PDF and ZIP-of-PDFs (`HallTicketPdfSupport`/`HallTicketZipSupport`) all build to `ByteArrayOutputStream`/`ZipOutputStream` and stream `byte[]` via `ResponseEntity`; no `FILE_STORAGE_PATH`, no temp files, no cleanup dance, no concurrent-request same-filename collision risk (which Node had for `/student-list` — two simultaneous requests for the same exam name would race on the same temp path in Node; the Java port has no such race since nothing touches disk).
- **`/used-blocks`'s real-JSON-number response is a deliberate, isolated exception** to this module's (and the whole backend's) numeric-as-String convention — the frontend's `usedBlocks.includes(Number(b.id))` depends on it; do not "fix" to a string for cross-module consistency.
- **The two-distinct-"year"-values design in `assignStudents` is preserved, not "fixed"** — `exam.exam_year` (DB-fetched, filters shortlist eligibility against `shortlist_batch.shortlisted_year`) and the request-body `academicYear` (drives hall-ticket sequence/number generation) are never cross-validated in Node and are not cross-validated here either; a caller could legally assign against one exam year while numbering tickets against a different academic year. Flagged as a design smell inherited from Node, not remediated in this phase (remediating it would change the request contract and is out of scope unless explicitly requested).
- **ADMIN enforcement is NEW** across 18 of the 19 endpoints (audit CRITICAL) — Node left every route in this module wide open, including the hall-ticket downloads. Only `GET /hallticket/{hallTicketNo}` remains intentionally public post-port, matching its real-world unauthenticated caller (`StudentHallticketPage.js`). Add to the fetch audit alongside Plans 3b/3d's identical findings for their respective modules.

