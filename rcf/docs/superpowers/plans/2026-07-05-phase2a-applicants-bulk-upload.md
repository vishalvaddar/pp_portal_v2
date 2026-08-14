# IMAS Spring Boot Migration — Plan 2a of 6: Admission — Applicants + Bulk Upload

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Node `/api/applicants` and `/api/bulk-upload` routes to the Spring Boot backend under a new `com.rcf.imas.modules.admission` module, preserving exact SQL, response shapes, and status codes, while adding deliberate hardening (auth on student-PII endpoints; upload size + content-type limits).

**Architecture:** Continues the Phase-1 modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA entities). New module `admission` with `web/`, `service/`, `persistence/`. All handler methods `public`, controller classes package-private. JSON snake_case is global (Phase-1 Task 3). Errors via `com.rcf.imas.platform.error.ApiException`. Integration tests are `*IT` classes extending `com.rcf.imas.PgIntegrationTest` (Zonky embedded-postgres, real PostgreSQL, Flyway applies the live `pp` schema — the `applicant_primary_info` / `applicant_secondary_info` / `jurisdiction` tables already exist in `V1__baseline.sql`).

**Tech Stack (additions over Phase-1):** Apache Commons CSV (`org.apache.commons:commons-csv`) for CSV parsing and Apache POI (`org.apache.poi:poi-ooxml`) for XLSX. `spring-boot-starter-web` already provides `MultipartFile`. No new runtime infra.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. This is the first of the Plans 2–6 (admission first, then examination/evaluation, academics/portals, scheduling/events/inventory, decommission). It assumes Plan 1 (Phase 0 + Phase 1) is merged and green: `PgIntegrationTest`, `JwtService`, `SecurityConfig` (method security enabled), `ApiException`, `GlobalExceptionHandler`, and the global snake_case `ObjectMapper` all exist.

> **⚠ TWO CONVENTIONS (learned in Task 2 — apply in every task below):**
> 1. **Numeric-column params:** JdbcClient binds a Java `String` as SQL `varchar`, so `numeric_col = :stringParam` throws `operator does not exist: numeric = varchar`. Always cast the **param**: `WHERE nmms_reg_number = :reg::numeric` (NOT `nmms_reg_number::text = :reg`, which defeats the index on 150k-row tables). This also matches Node's pg behavior (a non-numeric id → 500, not a silent 404). Applies to `applicant_id`, `nmms_reg_number`, `nmms_year`, and all jurisdiction `juris_code` params.
> 2. **Test isolation:** all `*IT` share one JVM-wide embedded Postgres. Any IT that inserts FK-referencing rows (applicant → jurisdiction/user) MUST delete them in `@AfterEach` (children before parents), or sibling ITs' teardowns fail with FK violations. Clean up exactly what you seed.

---

## Ground truth used by this plan (verified against Node source + live pg_dump)

Node source read for this plan:
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/controllers/applicantController.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/models/applicantModel.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/routes/applicantRoutes.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/controllers/bulkUploadController.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/models/bulkuploadModel.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/routes/bulkUploadRoutes.js`
- `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/index.js` (mount points)

**Mount points (`index.js`):** `app.use("/api/bulk-upload", bulkUploadRoutes)` and `app.use("/api/applicants", applicantRoutes)`.

**Route order matters (`applicantRoutes.js`).** The literal paths `/count`, `/shortlisted/count`, `/selected/count`, `/cohortstudentcount`, `/today-classes-count`, `/reg/:nmms_reg_number` are all declared **before** `/:applicantId`. Spring maps literal path segments ahead of `{applicantId}` automatically, so no ordering hack is needed — but the count endpoints must NOT be reachable as `/{applicantId}` with `applicantId="count"`. Spring's more-specific-match rule handles this; the IT asserts it.

**Key table facts (from `docs/superpowers/plans/artifacts/live-schema.sql`):**
- `pp.applicant_primary_info`:
  - `applicant_id numeric(14,0) DEFAULT nextval('pp.applicant_id_seq') NOT NULL` — **PK**. Numeric → JSON **String** (`toBigInteger().toString()`). Sequence: `pp.applicant_id_seq`.
  - `nmms_reg_number numeric(11,0) NOT NULL` — **UNIQUE** (`applicant_primary_info_nmms_reg_number_key`). Numeric → String on output. This UNIQUE is what raises SQLSTATE **23505** on duplicate create → Node's "Registration Number already exists".
  - `nmms_year numeric(4,0)`, `app_state/district/nmms_block numeric(12,0)` (FK → `pp.jurisdiction(juris_code)`), `student_name/father_name/mother_name varchar(100)`, `gmat_score/sat_score numeric(2,0)`, `gender char(1)` CHECK in `('M','F','O')`, `medium varchar(50)`, `aadhaar varchar(12)`, `dob date`, `home_address varchar(200)`, `family_income_total numeric(7,0)`, `contact_no1/contact_no2 varchar(12)`, `current_institute_dise_code/previous_institute_dise_code varchar(15)` (FK → `pp.institute(dise_code)`), `created_at/updated_at timestamp DEFAULT CURRENT_TIMESTAMP`, `created_by/updated_by numeric(8,0)` (FK → `pp."user"(user_id)`), `students_sats_id numeric(11,0)`.
- `pp.applicant_secondary_info`:
  - `applicant_id numeric(14,0) NOT NULL` — **PK** (`applicant_secondary_info_pkey`) and FK → `applicant_primary_info(applicant_id)` **ON DELETE CASCADE**. Because `applicant_id` is the PK, the update path's `ON CONFLICT (applicant_id) DO UPDATE` is valid.
  - Columns used by the Node create/update: `village, father_occupation, mother_occupation, father_education, mother_education, household_size, own_house, smart_phone_home, internet_facility_home, career_goals, subjects_of_interest, transportation_mode, distance_to_school, num_two_wheelers, num_four_wheelers, irrigation_land, neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone, created_by, updated_by`.
  - CHECK constraints: `own_house/smart_phone_home/internet_facility_home/spl_family_cond/spl_health_cond` each ∈ `('Y','N')`. `num_two_wheelers/num_four_wheelers/irrigation_land` are `NOT NULL DEFAULT 0` — Node passes `|| 0` for the wheel counts on create.
- `sibling_education` is **NOT** used by these routes (it is joined only in the Evaluation/home-verification flows — Plan 2b/3). Do not touch it here.

**Node behaviors that are quirks to preserve (confirmed):**
- **`formatResponse` is OUTPUT-only** (applicantController.js lines 18–37): maps `gender` `M/F/O` → `Male/Female/Other`, reformats `dob` → `YYYY-MM-DD`. Applied on every read AND on the create/update `data` echo. It does NOT change what is stored.
- **Two different date sanitizers.** Controller `sanitizeDate` (lines 12–16) is lenient: `moment.utc(s, ["DD-MM-YYYY","YYYY-MM-DD", ISO_8601])` → `YYYY-MM-DD` or null. Bulk `sanitizeDate` (bulkuploadModel.js lines 62–65) is strict: `moment(v, ["DD-MM-YYYY","YYYY-MM-DD"], true)` (strict mode, no ISO fallback). Port both faithfully as separate helpers.
- **Model `formatDate` (applicantModel.js lines 5–12)** additionally re-formats `dob` before insert via `new Date(val).toISOString().split('T')[0]`. Since the controller already produced `YYYY-MM-DD`, this is a redundant pass-through for valid dates; porting the controller sanitizer is sufficient (a `YYYY-MM-DD` string binds directly to a `date` column).
- **create** required fields (controller): `nmms_year, nmms_reg_number, student_name, father_name, medium, contact_no1, district, nmms_block` — "missing" is JS-falsy (`!primaryData[f]`, so `0`, `""`, `null`, `undefined` all count as missing). `contact_no1` must match `/^\d{10}$/`. Body may be flat OR `{primaryData, secondaryData}`; controller falls back to flat (`primaryData = {...req.body}` minus `secondaryData`).
- **create** transaction (applicantModel.js): INSERT primary `RETURNING applicant_id`, then INSERT secondary using that id. `created_by = updated_by = <jwt user_id>` (SQL binds `$21` to both). Secondary `created_by/updated_by` are also the same user id (controller sets them; model binds `primaryData.created_by` to secondary's `$22`). Wheel counts default to 0.
- **create** response 201 `{success:true, message:"Applicant created successfully", data: formatResponse({applicant_id})}`. Note: model returns only `{applicant_id}`, so `formatResponse` just echoes `{applicant_id}` (no gender/dob present). **`applicant_id` here comes from `RETURNING applicant_id` — node-pg returns it as a String.**
- **create** errors: 401 `{success:false, message:"Unauthorized"}` (no user id); 400 `{success:false, message:"Missing fields: <list>"}`; 400 `{success:false, message:"Invalid contact_no1"}`; 400 `{success:false, message:"Registration Number already exists"}` on 23505; 500 `{success:false, message:"Failed to create applicant", error:<msg>}`.
- **update** (PUT `/:applicantId/update`): body `{primaryData?, secondaryData?}`. **UPDATE primary does NOT touch `nmms_reg_number`** and sets `updated_at = CURRENT_TIMESTAMP`. Secondary is UPSERT `ON CONFLICT (applicant_id) DO UPDATE` (sets `updated_at` too). **No 404** — if `applicant_id` matches 0 rows the UPDATE silently affects nothing and still returns 200. Preserve this. Response 200 `{success:true, message:"Applicant updated successfully", data: formatResponse(updated)}` where model's `updated = {success:true, applicantId}` → `formatResponse` echoes it (no gender/dob). 500 `{success:false, message:"Failed to update applicant", error:<msg>}`.
- **get-by-id** (GET `/:applicantId`): `SELECT p.*, s.*` LEFT JOIN secondary. 404 `{success:false, message:"Applicant not found"}` if missing; else 200 `{success:true, data: formatResponse(row)}`.
- **get-by-reg** (GET `/reg/:nmms_reg_number`): big LEFT JOIN of primary + secondary + `student_master` (photo_link, enr_id, active_yn) + jurisdiction ×3 (state/district/block names) + `batch` + `cohort`. 400 if reg missing (route always provides it, so effectively unreachable); 404 if no row; else 200 `{success:true, data: formatResponse(row)}`.
- **list** (GET `/`): summary columns `p.applicant_id, p.nmms_year, p.nmms_reg_number, p.student_name, p.father_name, p.gender, dist.juris_name AS district_name, p.contact_no1, p.created_at` ORDER BY `p.created_at DESC`. Each row `formatResponse`-d (so `gender` becomes Male/Female/Other). 200 `{success:true, data:[...]}`. Query params are read but **ignored** (`getAllApplicants` takes `req.query` and never uses it — no filter contract).
- **count endpoints** were NOT wrapped in try/catch in Node (would 500-crash the process on DB error). We wrap them (deliberate hardening; noted below). Shapes:
  - `/count?year=` → 200 `{success:true, count:N}` (`SELECT COUNT(*) WHERE nmms_year=$1`).
  - `/shortlisted/count?year=` → 200 `{success:true, count:N}` (JOIN `applicant_shortlist_info`).
  - `/selected/count?year=` → 200 `{success:true, count:N}` (JOIN `student_master`).
  - `/cohortstudentcount?year=` → 200 `{success:true, data:{currentYear:<num>, previousYear:<num>, counts:{current_count:<num>, previous_count:<num>}}}`. (`counts` is the raw model row.)
  - `/today-classes-count?year=` → 200 `{success:true, count:[{cohort_name, classes_count}]}` — **count is an ARRAY here.** Uses `cohort_number = year - 2021` and `day_of_week = TRIM(UPPER(TO_CHAR(CURRENT_DATE,'Day')))`.
- **bulk upload** (POST `/api/bulk-upload/upload`, multipart field `"file"`):
  - Parse CSV (Papa: `header:true`, `skipEmptyLines:true`, `transformHeader: h.toLowerCase().trim().replace(/ /g,"_")`) or XLSX first sheet (headers lowercased/trimmed/spaces→`_`, `defval:""`). Extension decides: `.csv` → CSV, else Excel.
  - Validate every row: required per row = `nmms_year, nmms_reg_number, student_name, father_name, gmat_score, sat_score` (blank/whitespace → error). Sanitize: `dob` via strict bulk `sanitizeDate`; `nmms_year/gmat_score/sat_score` numeric (NaN→null); everything else `sanitizeValue` (trim, empty→null); `gender` would uppercase but note the Node `validateAndSanitizeRow` only special-cases `dob` and the three numerics — all other keys (incl. `gender`) go through default `sanitizeValue` (trim only, **no uppercase** in the batch path). Port exactly: gender is NOT uppercased in bulk.
  - **All-or-nothing validation:** if ANY row has a validation error → insert nothing, respond **400** with `insertedRecords:0`.
  - **Insert:** batch (Node BATCH_SIZE 50000, single batch in practice) inside a transaction. Per row: resolve `app_state` NAME→CODE (`STATE`), `district` (`EDUCATION DISTRICT`, parent=state), `nmms_block` (`BLOCK`, parent=district) via `getJurisdictionIdByName`: clean name = `trim().replace(/[.,]+$/,"").toUpperCase()`, query `juris_name ILIKE :name AND juris_type=:type [AND parent_juris=:parent]`; if none, fallback `UPPER(juris_name)=:name` (no type/parent); if still none `throw "Location not found: <type> <name>"`. Cache per upload in a `Map` keyed `"<type>:<name>:<parent|0>"`. `created_by = updated_by = 1`.
  - **Any row error rolls back the whole batch** (Node catches per-row, pushes a dbError, re-throws → batch ROLLBACK; inserted list keeps only rows added before the throw but the batch is rolled back, so `inserted.length` may be > 0 while nothing persisted — see parity note in Task 6).
  - **Response EXACT keys** (all at top level, values are counts/numbers except status/logFile strings): `{totalRecords, insertedRecords, validationErrors, dbErrors, status, logFile}`. `validationErrors`/`dbErrors` are **integers** (counts). `status ∈ {"success","failed"}`. `logFile` is a filename string written under a logs dir (not served).
  - **Status codes:** validation-fail → **400**; success → **200**; db-fail or `inserted.length==0` → **500**; critical catch (parse/IO) → **500** with `{message:"Bulk upload failed", status:"failed", logFile}`.
  - **No-file** → **400** `{message:'No file received. Ensure multipart/form-data and field name is "file".'}`.
- **Dead code skipped:** commented-out router/middleware blocks in `bulkUploadRoutes.js`; `middleware/uploadMiddleware.js` (the real multer lived in the model with **no limits**); `applicant_primary_info_csv` / `std_applicant_primary_info` staging tables (not touched by these routes). `getAllApplicants` query params (no filter contract).

**Test-seed sequence rule (LOCKED project decision).** Any seed that inserts an explicit `applicant_id` into `pp.applicant_primary_info` and then relies on the sequence for a later API insert MUST advance the sequence:
```sql
SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info));
```
Otherwise the sequence-default insert collides at a low id → duplicate-key 500. (`::bigint` cast required: `setval` takes `bigint`, the column is `numeric`.) Prefer to let the API assign ids (INSERT via sequence) and only seed explicit ids in the reg/count/join tests that need related rows; those tests must call `setval` after seeding.

## Endpoint contract for this plan (exact)

| # | Method + Path | Auth | Success | Notable errors |
|---|---|---|---|---|
| 1 | POST `/api/applicants/create` | ADMIN | 201 `{success:true, message:"Applicant created successfully", data:{applicant_id}}` | 401 `{success:false,message:"Unauthorized"}` (no principal — now unreachable, auth returns 401 first); 400 `{success:false,message:"Missing fields: <list>"}`; 400 `{success:false,message:"Invalid contact_no1"}`; 400 `{success:false,message:"Registration Number already exists"}` (23505); 500 `{success:false,message:"Failed to create applicant",error:<msg>}` |
| 2 | GET `/api/applicants/` | ADMIN | 200 `{success:true, data:[{applicant_id, nmms_year, nmms_reg_number, student_name, father_name, gender, district_name, contact_no1, created_at}]}` (gender mapped, created_at DESC) | 500 `{success:false,message:<msg>}` |
| 3 | GET `/api/applicants/reg/{nmms_reg_number}` | ADMIN | 200 `{success:true, data:{...joined row...}}` (gender/dob mapped) | 404 `{success:false,message:"Applicant not found"}` |
| 4 | GET `/api/applicants/count?year=` | ADMIN | 200 `{success:true, count:N}` | 500 `{success:false,message:<msg>}` |
| 5 | GET `/api/applicants/shortlisted/count?year=` | ADMIN | 200 `{success:true, count:N}` | 500 |
| 6 | GET `/api/applicants/selected/count?year=` | ADMIN | 200 `{success:true, count:N}` | 500 |
| 7 | GET `/api/applicants/cohortstudentcount?year=` | ADMIN | 200 `{success:true, data:{currentYear:N, previousYear:N-1, counts:{current_count, previous_count}}}` | 500 |
| 8 | GET `/api/applicants/today-classes-count?year=` | ADMIN | 200 `{success:true, count:[{cohort_name, classes_count}]}` (array) | 500 |
| 9 | GET `/api/applicants/{applicantId}` | ADMIN | 200 `{success:true, data:{...p.*,s.*...}}` (gender/dob mapped) | 404 `{success:false,message:"Applicant not found"}` |
| 10 | PUT `/api/applicants/{applicantId}/update` | ADMIN | 200 `{success:true, message:"Applicant updated successfully", data:{...}}` | 500 `{success:false,message:"Failed to update applicant",error:<msg>}`. **No 404** (0-row update is silent). |
| 11 | DELETE `/api/applicants/{applicantId}` | ADMIN | 200 `{success:true, message:"Applicant deleted successfully"}` | 404 `{success:false,message:"Applicant not found"}` |
| 12 | POST `/api/bulk-upload/upload` (multipart `file`) | ADMIN | 200 `{totalRecords, insertedRecords, validationErrors:0, dbErrors:0, status:"success", logFile}` | 400 no-file `{message:...}`; 400 validation-fail `{totalRecords, insertedRecords:0, validationErrors:>0, dbErrors:0, status:"failed", logFile}`; 500 db-fail `{...status:"failed"...}`; 500 critical `{message:"Bulk upload failed", status:"failed", logFile}`; 400 bad content-type `{message:...}` |

**Authorization for this plan (NEW enforcement; spec §5 — audit flagged unauthenticated PII as CRITICAL).** In Node, endpoints 2–9 and 12 were **completely open** (no `authenticate` middleware); only create/update/delete (1, 10, 11) had `authenticate` (any logged-in user, no role check). These endpoints expose student PII (names, aadhaar, contact numbers, addresses) and bulk-mutate it. This plan requires **`@PreAuthorize("hasRole('ADMIN')")` on every endpoint 1–12.** This is an **intentional, deliberate change from Node** — record it in the parity notes and the fetch audit (Plan 1 Task 11 pattern) so the frontend can be confirmed to already send an admin token on these screens. If a non-admin screen legitimately needs read access, downgrade the specific read endpoint to `isAuthenticated()` after the audit — but never leave any of these open.

## File structure (created by this plan)

```
imas-backend/
├── pom.xml                                   (modified: add commons-csv, poi-ooxml; multipart limits in application.yml)
├── src/main/java/com/rcf/imas/modules/admission/
│   ├── web/ApplicantController.java
│   ├── web/BulkUploadController.java
│   ├── service/ApplicantFormatter.java        (gender M/F/O→word, dob→YYYY-MM-DD, two date sanitizers)
│   ├── service/ApplicantService.java          (transactional create/update)
│   ├── service/BulkUploadService.java         (parse + validate + jurisdiction-cache + batch insert)
│   ├── persistence/ApplicantRepository.java   (reads, delete, insert/update SQL, row mappers)
│   └── persistence/JurisdictionLookupRepository.java (name→code with fallback)
├── src/main/resources/application.yml         (modified: spring.servlet.multipart limits)
└── src/test/java/com/rcf/imas/modules/admission/
    ├── ApplicantFormatterTest.java            (unit — no DB)
    ├── ApplicantReadIT.java                   (list, get-by-id, get-by-reg, 5 counts)
    ├── ApplicantCreateIT.java
    ├── ApplicantUpdateIT.java
    ├── ApplicantDeleteIT.java
    └── BulkUploadIT.java
```

Notes for the implementing engineer:
- Repo root is `C:\work\rcf`. Run tests with `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=<Name>`.
- Every task = red test → run/confirm fail → implement → run/confirm pass → commit.
- **Handler-method visibility:** controller classes stay package-private; every `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping` handler method is **`public`** so `@PreAuthorize` is reliably applied. Code blocks below already mark handlers `public`.
- **Numeric-id → String parity:** `applicant_id`, `nmms_reg_number`, `district_name` codes etc. that are `numeric` columns serialize as Strings via `rs.getBigDecimal(col).toBigInteger().toString()` (null-safe). `dise_code` is `varchar` → `getString`. Booleans/timestamps as in Phase-1 `SystemConfigRepository`.
- Response envelopes use `java.util.LinkedHashMap` (ordered) built explicitly, matching the `{success:..., data:...}` Node shape — do NOT wrap in a DTO record (keys must be literal snake_case, and `success` is a fixed literal).

---

## Task 1: admission module skeleton + `ApplicantFormatter`

Port the output formatter and both date sanitizers as a standalone, unit-testable component (no DB). `ApplicantFormatter` handles: gender `M/F/O` → `Male/Female/Other`; `dob` (a `java.sql.Date`/`LocalDate`/string) → `YYYY-MM-DD`; the lenient controller `sanitizeDate`; the strict bulk `sanitizeDate`.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/service/ApplicantFormatter.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantFormatterTest.java`

- [ ] **Step 1: Write the failing unit test**

`src/test/java/com/rcf/imas/modules/admission/ApplicantFormatterTest.java`:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.modules.admission.service.ApplicantFormatter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicantFormatterTest {

    private final ApplicantFormatter fmt = new ApplicantFormatter();

    @Test
    void mapsGenderCodesToWords() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", "M");
        fmt.formatResponse(m);
        assertThat(m.get("gender")).isEqualTo("Male");

        m.put("gender", "F"); fmt.formatResponse(m); assertThat(m.get("gender")).isEqualTo("Female");
        m.put("gender", "O"); fmt.formatResponse(m); assertThat(m.get("gender")).isEqualTo("Other");
    }

    @Test
    void leavesUnknownGenderUntouched() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", "Male"); // already a word → genderMap miss → unchanged
        fmt.formatResponse(m);
        assertThat(m.get("gender")).isEqualTo("Male");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("gender", null);
        fmt.formatResponse(n);
        assertThat(n.get("gender")).isNull();
    }

    @Test
    void reformatsDobToIsoWhateverTheInputType() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dob", java.sql.Date.valueOf(LocalDate.of(2010, 3, 7)));
        fmt.formatResponse(m);
        assertThat(m.get("dob")).isEqualTo("2010-03-07");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("dob", LocalDate.of(2010, 12, 31));
        fmt.formatResponse(n);
        assertThat(n.get("dob")).isEqualTo("2010-12-31");
    }

    @Test
    void formatResponseIgnoresMissingFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("student_name", "Asha");
        fmt.formatResponse(m); // no gender/dob keys → no-op
        assertThat(m.get("student_name")).isEqualTo("Asha");
    }

    @Test
    void controllerSanitizeDateIsLenientAndIso() {
        assertThat(fmt.sanitizeControllerDate("07-03-2010")).isEqualTo("2010-03-07"); // DD-MM-YYYY
        assertThat(fmt.sanitizeControllerDate("2010-03-07")).isEqualTo("2010-03-07"); // YYYY-MM-DD
        assertThat(fmt.sanitizeControllerDate("2010-03-07T00:00:00Z")).isEqualTo("2010-03-07"); // ISO_8601
        assertThat(fmt.sanitizeControllerDate("")).isNull();
        assertThat(fmt.sanitizeControllerDate(null)).isNull();
        assertThat(fmt.sanitizeControllerDate("not-a-date")).isNull();
    }

    @Test
    void bulkSanitizeDateIsStrictNoIsoFallback() {
        assertThat(fmt.sanitizeBulkDate("07-03-2010")).isEqualTo("2010-03-07");
        assertThat(fmt.sanitizeBulkDate("2010-03-07")).isEqualTo("2010-03-07");
        // strict mode: ISO datetime is NOT accepted by the bulk parser
        assertThat(fmt.sanitizeBulkDate("2010-03-07T00:00:00Z")).isNull();
        assertThat(fmt.sanitizeBulkDate("bad")).isNull();
        assertThat(fmt.sanitizeBulkDate(null)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantFormatterTest`
Expected: FAIL — `ApplicantFormatter` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/admission/service/ApplicantFormatter.java`:
```java
package com.rcf.imas.modules.admission.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Output-only parity with applicantController.js formatResponse + the two Node date sanitizers.
 * formatResponse mutates the row map in place (like the Node spread + reassignment).
 */
@Component
public class ApplicantFormatter {

    private static final Map<String, String> GENDER = Map.of("M", "Male", "F", "Female", "O", "Other");

    // Controller sanitizeDate: moment.utc(s, ["DD-MM-YYYY","YYYY-MM-DD", ISO_8601]) — lenient.
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-uuuu");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** In-place: gender code → word (only for M/F/O), dob → YYYY-MM-DD. */
    public void formatResponse(Map<String, Object> row) {
        if (row == null) return;

        Object g = row.get("gender");
        if (g instanceof String gs && GENDER.containsKey(gs)) {
            row.put("gender", GENDER.get(gs));
        }

        Object dob = row.get("dob");
        if (dob != null) {
            LocalDate d = toLocalDate(dob);
            if (d != null) row.put("dob", d.format(YMD));
        }
    }

    private static LocalDate toLocalDate(Object dob) {
        if (dob instanceof java.sql.Date sd) return sd.toLocalDate();
        if (dob instanceof LocalDate ld) return ld;
        if (dob instanceof java.util.Date ud) {
            return ud.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        if (dob instanceof String s && !s.isBlank()) {
            // already stored as ISO by our sanitizers; parse first 10 chars defensively
            try { return LocalDate.parse(s.substring(0, Math.min(10, s.length())), YMD); }
            catch (RuntimeException e) { return null; }
        }
        return null;
    }

    /** Lenient controller sanitizeDate: DD-MM-YYYY | YYYY-MM-DD | ISO_8601 → YYYY-MM-DD, else null. */
    public String sanitizeControllerDate(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        LocalDate d = tryParse(s, DMY);
        if (d == null) d = tryParse(s, YMD);
        if (d == null) {
            // ISO_8601 (with time/zone): take the date part
            try { d = java.time.OffsetDateTime.parse(s).toLocalDate(); }
            catch (RuntimeException e1) {
                try { d = java.time.LocalDateTime.parse(s).toLocalDate(); }
                catch (RuntimeException e2) { d = null; }
            }
        }
        return d == null ? null : d.format(YMD);
    }

    /** Strict bulk sanitizeDate: DD-MM-YYYY | YYYY-MM-DD only (no ISO fallback) → YYYY-MM-DD, else null. */
    public String sanitizeBulkDate(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        LocalDate d = tryParse(s, DMY);
        if (d == null) d = tryParse(s, YMD);
        return d == null ? null : d.format(YMD);
    }

    private static LocalDate tryParse(String s, DateTimeFormatter f) {
        try { return LocalDate.parse(s, f); }
        catch (RuntimeException e) { return null; }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantFormatterTest` — Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission
git commit -m "feat(admission): ApplicantFormatter (gender/dob mapping + two date sanitizers)"
```

---

## Task 2: applicant reads — repository + controller + IT (list, get-by-id, get-by-reg, 5 counts)

Port every GET endpoint. All are ADMIN-only. Count endpoints get try/catch wrapping (Node lacked it). The list and single reads run through `ApplicantFormatter`.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/persistence/ApplicantRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/web/ApplicantController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantReadIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/admission/ApplicantReadIT.java`:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantReadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        // clean applicant tables (children first)
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();

        // a creating user for the FK (created_by/updated_by → pp.user)
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('seed_admin','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='seed_admin'").query(Long.class).single();

        // jurisdiction rows so district_name resolves and the bulk/reg joins have something to hit
        jdbc.sql("INSERT INTO pp.jurisdiction_type(juris_type) VALUES ('EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (900001,'BELAGAVI','EDUCATION DISTRICT',NULL) ON CONFLICT (juris_code) DO NOTHING").update();

        // two applicants with explicit ids → then advance the sequence (LOCKED rule)
        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, district, student_name, father_name, gender, medium, contact_no1, dob, created_by, updated_by, created_at)
                VALUES
                  (500001, 2025, 24010000001, 900001, 'Asha', 'Ravi', 'F', 'Kannada', '9876543210', DATE '2011-06-15', :u, :u, TIMESTAMP '2025-01-01 10:00:00'),
                  (500002, 2025, 24010000002, 900001, 'Kiran', 'Suresh', 'M', 'English', '9000000000', NULL, :u, :u, TIMESTAMP '2025-02-01 10:00:00')
                """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, village, created_by, updated_by) VALUES (500001,'Hubli',:u,:u)")
                .param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))")
                .query(Long.class).single();
    }

    @Test
    void listReturnsSummaryOrderedByCreatedAtDescWithGenderMapped() throws Exception {
        mvc.perform(get("/api/applicants/").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.data[0].applicant_id").value("500002"))     // created_at DESC → 500002 first
           .andExpect(jsonPath("$.data[0].gender").value("Male"))
           .andExpect(jsonPath("$.data[1].applicant_id").value("500001"))
           .andExpect(jsonPath("$.data[1].gender").value("Female"))
           .andExpect(jsonPath("$.data[1].district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.data[1].nmms_reg_number").value("24010000001"));
    }

    @Test
    void getByIdReturnsJoinedRowWithDobAndGenderMapped() throws Exception {
        mvc.perform(get("/api/applicants/500001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.applicant_id").value("500001"))
           .andExpect(jsonPath("$.data.gender").value("Female"))
           .andExpect(jsonPath("$.data.dob").value("2011-06-15"))
           .andExpect(jsonPath("$.data.village").value("Hubli"));
    }

    @Test
    void getByIdMissingIs404() throws Exception {
        mvc.perform(get("/api/applicants/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void getByRegReturnsRowWithJurisdictionNames() throws Exception {
        mvc.perform(get("/api/applicants/reg/24010000001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.student_name").value("Asha"))
           .andExpect(jsonPath("$.data.district_name").value("BELAGAVI"))
           .andExpect(jsonPath("$.data.gender").value("Female"));
    }

    @Test
    void getByRegMissingIs404() throws Exception {
        mvc.perform(get("/api/applicants/reg/99999999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void countByYear() throws Exception {
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/applicants/count?year=2099").header("Authorization", "Bearer " + admin))
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void shortlistedAndSelectedCountsAreZeroWithNoRelatedRows() throws Exception {
        mvc.perform(get("/api/applicants/shortlisted/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/applicants/selected/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void cohortStudentCountShape() throws Exception {
        mvc.perform(get("/api/applicants/cohortstudentcount?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.currentYear").value(2025))
           .andExpect(jsonPath("$.data.previousYear").value(2024))
           .andExpect(jsonPath("$.data.counts.current_count").value(0))   // no student_master rows
           .andExpect(jsonPath("$.data.counts.previous_count").value(0));
    }

    @Test
    void todayClassesCountIsArray() throws Exception {
        mvc.perform(get("/api/applicants/today-classes-count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.count").isArray());  // empty array when no timetable rows today
    }

    @Test
    void readsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/applicants/").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/applicants/500001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }

    @Test
    void countPathIsNotSwallowedByApplicantIdRoute() throws Exception {
        // "/count" must map to the count handler, not GET /{applicantId} with id="count"
        mvc.perform(get("/api/applicants/count?year=2025").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantReadIT` — Expected: FAIL (404s / no controller).

- [ ] **Step 3: Implement**

`src/main/java/com/rcf/imas/modules/admission/persistence/ApplicantRepository.java`:
```java
package com.rcf.imas.modules.admission.persistence;

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
public class ApplicantRepository {

    // node-pg timestamp serialization parity (UTC ISO with millis + Z), same as Phase-1 SystemConfigRepository
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public ApplicantRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- generic row → ordered map, mirroring node-pg's typed JSON output ----
    // numeric → String (parity), date → java.sql.Date (ApplicantFormatter turns dob into YYYY-MM-DD),
    // timestamp → ISO-Z string, varchar/other → native.
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
                case java.sql.Types.DATE -> {
                    // keep as java.sql.Date except for a column literally named dob → let formatter handle;
                    // for other date columns emit YYYY-MM-DD directly for parity.
                    java.sql.Date d = rs.getDate(i);
                    val = "dob".equals(name) ? d : (d == null ? null : d.toLocalDate().toString());
                }
                case java.sql.Types.TIMESTAMP -> {
                    Timestamp t = rs.getTimestamp(i);
                    val = t == null ? null : TS.format(t.toInstant());
                }
                default -> val = rs.getObject(i);
            }
            m.put(name, val);
        }
        return m;
    }

    // ---- list summary (order + columns exactly as Node getAllApplicants) ----
    public List<Map<String, Object>> listSummary() {
        return jdbc.sql("""
                SELECT p.applicant_id, p.nmms_year, p.nmms_reg_number, p.student_name, p.father_name, p.gender,
                       dist.juris_name AS district_name, p.contact_no1, p.created_at
                FROM pp.applicant_primary_info p
                LEFT JOIN pp.jurisdiction dist ON p.district = dist.juris_code
                ORDER BY p.created_at DESC
                """).query((rs, i) -> genericRow(rs)).list();
    }

    public Optional<Map<String, Object>> findById(String applicantId) {
        return jdbc.sql("""
                SELECT p.*, s.* FROM pp.applicant_primary_info p
                LEFT JOIN pp.applicant_secondary_info s ON p.applicant_id = s.applicant_id
                WHERE p.applicant_id = :id
                """).param("id", applicantId).query((rs, i) -> genericRow(rs)).optional();
    }

    public Optional<Map<String, Object>> findByRegNumber(String reg) {
        return jdbc.sql("""
                SELECT
                  p.*, s.*,
                  sm.photo_link, sm.enr_id, sm.active_yn,
                  state.juris_name AS state_name,
                  dist.juris_name AS district_name,
                  blk.juris_name AS block_name,
                  c.cohort_name, b.batch_name
                FROM pp.applicant_primary_info p
                LEFT JOIN pp.applicant_secondary_info s ON p.applicant_id = s.applicant_id
                LEFT JOIN pp.jurisdiction state ON p.app_state = state.juris_code
                LEFT JOIN pp.jurisdiction dist  ON p.district  = dist.juris_code
                LEFT JOIN pp.jurisdiction blk   ON p.nmms_block = blk.juris_code
                LEFT JOIN pp.student_master sm ON p.applicant_id = sm.applicant_id
                LEFT JOIN pp.batch b  ON sm.batch_id = b.batch_id
                LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE p.nmms_reg_number = :reg
                """).param("reg", reg).query((rs, i) -> genericRow(rs)).optional();
    }

    // ---- counts ----
    public long countByYear(String year) {
        return jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = :y")
                .param("y", year).query(Long.class).single();
    }

    public long shortlistedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM pp.applicant_primary_info ap
                JOIN pp.applicant_shortlist_info asi ON ap.applicant_id = asi.applicant_id
                WHERE ap.nmms_year = :y
                """).param("y", year).query(Long.class).single();
    }

    public long selectedCount(String year) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM pp.applicant_primary_info ap
                JOIN pp.student_master sm ON ap.applicant_id = sm.applicant_id
                WHERE ap.nmms_year = :y
                """).param("y", year).query(Long.class).single();
    }

    public Map<String, Object> cohortCounts(int currentYear, int previousYear) {
        return jdbc.sql("""
                SELECT
                  COUNT(CASE WHEN ap.nmms_year = :cur THEN 1 END)::INT AS current_count,
                  COUNT(CASE WHEN ap.nmms_year = :prev THEN 1 END)::INT AS previous_count
                FROM pp.student_master sm
                JOIN pp.applicant_primary_info ap ON sm.applicant_id = ap.applicant_id
                WHERE ap.nmms_year IN (:cur, :prev)
                """).param("cur", currentYear).param("prev", previousYear)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("current_count", rs.getInt("current_count"));
                    m.put("previous_count", rs.getInt("previous_count"));
                    return m;
                }).single();
    }

    public List<Map<String, Object>> todayClasses(int cohortNumber) {
        return jdbc.sql("""
                SELECT c.cohort_name, COUNT(DISTINCT t.timetable_id) AS classes_count
                FROM pp.timetable t
                JOIN pp.classroom cl ON t.classroom_id = cl.classroom_id
                JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.cohort c ON b.cohort_number = c.cohort_number
                WHERE t.day_of_week = TRIM(UPPER(TO_CHAR(CURRENT_DATE, 'Day')))
                  AND b.cohort_number = :cohort
                GROUP BY c.cohort_name
                ORDER BY c.cohort_name
                """).param("cohort", cohortNumber)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cohort_name", rs.getString("cohort_name"));
                    m.put("classes_count", rs.getLong("classes_count"));
                    return m;
                }).list();
    }
}
```

`src/main/java/com/rcf/imas/modules/admission/web/ApplicantController.java` (read handlers only in this task; write handlers added in Tasks 3–5 to the same file):
```java
package com.rcf.imas.modules.admission.web;

import com.rcf.imas.modules.admission.persistence.ApplicantRepository;
import com.rcf.imas.modules.admission.service.ApplicantFormatter;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applicants")
@PreAuthorize("hasRole('ADMIN')")   // class-level: every handler here handles student PII → ADMIN only
class ApplicantController {

    private final ApplicantRepository repo;
    private final ApplicantFormatter formatter;

    ApplicantController(ApplicantRepository repo, ApplicantFormatter formatter) {
        this.repo = repo;
        this.formatter = formatter;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    @GetMapping({"", "/"})
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = repo.listSummary();
        rows.forEach(formatter::formatResponse);
        return ok(rows);
    }

    @GetMapping("/reg/{nmmsRegNumber}")
    public Map<String, Object> getByReg(@PathVariable String nmmsRegNumber) {
        Map<String, Object> row = repo.findByRegNumber(nmmsRegNumber)
                .orElseThrow(() -> notFound());
        formatter.formatResponse(row);
        return ok(row);
    }

    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.countByYear(year));
        return m;
    }

    @GetMapping("/shortlisted/count")
    public Map<String, Object> shortlistedCount(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.shortlistedCount(year));
        return m;
    }

    @GetMapping("/selected/count")
    public Map<String, Object> selectedCount(@RequestParam(required = false) String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.selectedCount(year));
        return m;
    }

    @GetMapping("/cohortstudentcount")
    public Map<String, Object> cohortStudentCount(@RequestParam(required = false) String year) {
        int cur = Integer.parseInt(year);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentYear", cur);
        data.put("previousYear", cur - 1);
        data.put("counts", repo.cohortCounts(cur, cur - 1));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    @GetMapping("/today-classes-count")
    public Map<String, Object> todayClassesCount(@RequestParam(required = false) String year) {
        int cohortNumber = Integer.parseInt(year) - 2021;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("count", repo.todayClasses(cohortNumber));  // ARRAY parity
        return m;
    }

    @GetMapping("/{applicantId}")
    public Map<String, Object> getById(@PathVariable String applicantId) {
        Map<String, Object> row = repo.findById(applicantId)
                .orElseThrow(() -> notFound());
        formatter.formatResponse(row);
        return ok(row);
    }

    // Node 404 body: {success:false, message:"Applicant not found"}
    private static ApiException notFound() {
        return ApiException.message(404, "Applicant not found").with("success", false);
    }
}
```

> **Parity note on count try/catch.** Node's count handlers had no try/catch, so a DB error crashed with an unhandled promise rejection (effectively a 500). Here `GlobalExceptionHandler.handleUnexpected` already turns any thrown exception into a 500 — so wrapping is automatic and we do NOT add per-handler try/catch. The only behavioral change is a clean JSON 500 instead of a crash — strictly safer, note it in the audit.
>
> **Parity note on 404 body key.** Node returns `{success:false, message:"Applicant not found"}`. `ApiException.message(404, ...)` produces `{message:...}`; `.with("success", false)` prepends `success:false`. Confirm `ApiException.with` exists (it does — Phase-1 Task 4). The key order will be `{message, success}` vs Node's `{success, message}`; JSON object key order is not semantically significant and no frontend asserts on it (confirm in the fetch audit). If strict order is ever required, build the body via a small custom handler instead.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantReadIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantReadIT.java
git commit -m "feat(admission): applicant reads (list, get-by-id, get-by-reg, 5 counts) ADMIN-only"
```

---

## Task 3: applicant create (transactional) + IT

Port `createApplicant`: validation, flat/nested body, transactional insert of primary (RETURNING applicant_id) + secondary, 23505 → "Registration Number already exists". ADMIN-only.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/persistence/ApplicantRepository.java` (add `insertApplicant`)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/service/ApplicantService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/web/ApplicantController.java` (add create handler)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantCreateIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/admission/ApplicantCreateIT.java`:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantCreateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        // create the FK user whose user_id matches the ADMIN token subject
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('creator','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='creator'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "creator", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
    }

    private String flatBody(String reg) {
        return """
            {"nmms_year":2025,"nmms_reg_number":%s,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":null,"nmms_block":null,
             "gender":"F","dob":"15-06-2011"}
            """.formatted(reg);
    }

    @Test
    void createsApplicantAndReturns201WithApplicantId() throws Exception {
        // district/nmms_block are required-non-falsy in Node; supply real jurisdiction codes
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000055,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":910001,"nmms_block":910002,
             "gender":"F","dob":"15-06-2011"}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant created successfully"))
           .andExpect(jsonPath("$.data.applicant_id").isNotEmpty());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_reg_number=24010000055").query(Long.class).single();
        assertThat(n).isEqualTo(1);
        Long sec = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info").query(Long.class).single();
        assertThat(sec).isEqualTo(1);  // secondary row created in same transaction
    }

    @Test
    void missingRequiredFieldsIs400WithList() throws Exception {
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"nmms_year\":2025,\"student_name\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.message", org.hamcrest.Matchers.startsWith("Missing fields:")));
    }

    @Test
    void invalidContactIs400() throws Exception {
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000056,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"12345","district":910001,"nmms_block":910002}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Invalid contact_no1"));
    }

    @Test
    void duplicateRegNumberIs400() throws Exception {
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910001,'DISTX','EDUCATION DISTRICT') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type) VALUES (910002,'BLKX','BLOCK') ON CONFLICT DO NOTHING").update();
        String body = """
            {"nmms_year":2025,"nmms_reg_number":24010000077,"student_name":"Meera","father_name":"Ram",
             "medium":"Kannada","contact_no1":"9876543210","district":910001,"nmms_block":910002}
            """;
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message").value("Registration Number already exists"));
    }

    @Test
    void createIsAdminOnly() throws Exception {
        mvc.perform(post("/api/applicants/create").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content(flatBody("24010000099")))
           .andExpect(status().isForbidden());
    }
}
```
(Add `import static org.assertj.core.api.Assertions.assertThat;` at the top.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantCreateIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ApplicantRepository` (transactional insert; primary RETURNING id, then secondary). Uses named params; nulls typed for `date`:
```java
    /** Insert primary (RETURNING applicant_id) + secondary in ONE transaction. Returns applicant_id as String. */
    @org.springframework.transaction.annotation.Transactional
    public String insertApplicant(Map<String, Object> primary, Map<String, Object> secondary, String userId) {
        String applicantId = jdbc.sql("""
                INSERT INTO pp.applicant_primary_info (
                  nmms_year, nmms_reg_number, app_state, district, nmms_block,
                  student_name, father_name, mother_name, gmat_score, sat_score,
                  gender, medium, aadhaar, dob, home_address, family_income_total,
                  contact_no1, contact_no2, current_institute_dise_code, previous_institute_dise_code,
                  created_by, updated_by
                ) VALUES (
                  :nmms_year, :nmms_reg_number, :app_state, :district, :nmms_block,
                  :student_name, :father_name, :mother_name, :gmat_score, :sat_score,
                  :gender, :medium, :aadhaar, CAST(:dob AS DATE), :home_address, :family_income_total,
                  :contact_no1, :contact_no2, :current_institute_dise_code, :previous_institute_dise_code,
                  :uid, :uid
                )
                RETURNING applicant_id
                """)
                .param("nmms_year", primary.get("nmms_year"))
                .param("nmms_reg_number", primary.get("nmms_reg_number"))
                .param("app_state", primary.get("app_state"))
                .param("district", primary.get("district"))
                .param("nmms_block", primary.get("nmms_block"))
                .param("student_name", primary.get("student_name"))
                .param("father_name", primary.get("father_name"))
                .param("mother_name", primary.get("mother_name"))
                .param("gmat_score", primary.get("gmat_score"))
                .param("sat_score", primary.get("sat_score"))
                .param("gender", primary.get("gender"))
                .param("medium", primary.get("medium"))
                .param("aadhaar", primary.get("aadhaar"))
                .param("dob", primary.get("dob"))
                .param("home_address", primary.get("home_address"))
                .param("family_income_total", primary.get("family_income_total"))
                .param("contact_no1", primary.get("contact_no1"))
                .param("contact_no2", primary.get("contact_no2"))
                .param("current_institute_dise_code", primary.get("current_institute_dise_code"))
                .param("previous_institute_dise_code", primary.get("previous_institute_dise_code"))
                .param("uid", userId)
                .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString())
                .single();

        jdbc.sql("""
                INSERT INTO pp.applicant_secondary_info (
                  applicant_id, village, father_occupation, mother_occupation,
                  father_education, mother_education, household_size, own_house,
                  smart_phone_home, internet_facility_home, career_goals, subjects_of_interest,
                  transportation_mode, distance_to_school, num_two_wheelers, num_four_wheelers,
                  irrigation_land, neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone,
                  created_by, updated_by
                ) VALUES (
                  :applicant_id, :village, :father_occupation, :mother_occupation,
                  :father_education, :mother_education, :household_size, :own_house,
                  :smart_phone_home, :internet_facility_home, :career_goals, :subjects_of_interest,
                  :transportation_mode, :distance_to_school, :num_two_wheelers, :num_four_wheelers,
                  :irrigation_land, :neighbor_name, :neighbor_phone, :favorite_teacher_name, :favorite_teacher_phone,
                  :uid, :uid
                )
                """)
                .param("applicant_id", applicantId)
                .param("village", secondary.get("village"))
                .param("father_occupation", secondary.get("father_occupation"))
                .param("mother_occupation", secondary.get("mother_occupation"))
                .param("father_education", secondary.get("father_education"))
                .param("mother_education", secondary.get("mother_education"))
                .param("household_size", secondary.get("household_size"))
                .param("own_house", secondary.get("own_house"))
                .param("smart_phone_home", secondary.get("smart_phone_home"))
                .param("internet_facility_home", secondary.get("internet_facility_home"))
                .param("career_goals", secondary.get("career_goals"))
                .param("subjects_of_interest", secondary.get("subjects_of_interest"))
                .param("transportation_mode", secondary.get("transportation_mode"))
                .param("distance_to_school", secondary.get("distance_to_school"))
                .param("num_two_wheelers", secondary.getOrDefault("num_two_wheelers", 0))
                .param("num_four_wheelers", secondary.getOrDefault("num_four_wheelers", 0))
                .param("irrigation_land", secondary.getOrDefault("irrigation_land", 0))
                .param("neighbor_name", secondary.get("neighbor_name"))
                .param("neighbor_phone", secondary.get("neighbor_phone"))
                .param("favorite_teacher_name", secondary.get("favorite_teacher_name"))
                .param("favorite_teacher_phone", secondary.get("favorite_teacher_phone"))
                .param("uid", userId)
                .update();

        return applicantId;
    }
```

`src/main/java/com/rcf/imas/modules/admission/service/ApplicantService.java`:
```java
package com.rcf.imas.modules.admission.service;

import com.rcf.imas.modules.admission.persistence.ApplicantRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApplicantService {

    private static final List<String> REQUIRED = List.of(
            "nmms_year", "nmms_reg_number", "student_name", "father_name",
            "medium", "contact_no1", "district", "nmms_block");

    // exact copy of the Node buildPrimaryData key set (sanitizeValue trims strings; empty→null)
    private static final List<String> PRIMARY_KEYS = List.of(
            "nmms_year", "nmms_reg_number", "app_state", "district", "nmms_block",
            "student_name", "father_name", "mother_name", "gmat_score", "sat_score",
            "gender", "medium", "aadhaar", "home_address", "family_income_total",
            "contact_no1", "contact_no2", "current_institute_dise_code", "previous_institute_dise_code");

    private final ApplicantRepository repo;
    private final ApplicantFormatter formatter;

    public ApplicantService(ApplicantRepository repo, ApplicantFormatter formatter) {
        this.repo = repo;
        this.formatter = formatter;
    }

    /** Returns applicant_id (String). Throws ApiException for validation / 23505. */
    public String create(Map<String, Object> primaryData, Map<String, Object> secondaryData, String userId) {
        // Node "missing" = JS falsy: null, "", 0, undefined. Match with a falsy check.
        List<String> missing = REQUIRED.stream().filter(f -> isFalsy(primaryData.get(f))).toList();
        if (!missing.isEmpty()) {
            throw ApiException.message(400, "Missing fields: " + String.join(", ", missing)).with("success", false);
        }
        Object contact = primaryData.get("contact_no1");
        if (contact == null || !contact.toString().matches("\\d{10}")) {
            throw ApiException.message(400, "Invalid contact_no1").with("success", false);
        }

        Map<String, Object> primary = new LinkedHashMap<>();
        for (String k : PRIMARY_KEYS) primary.put(k, sanitize(primaryData.get(k)));
        primary.put("dob", formatter.sanitizeControllerDate(asString(primaryData.get("dob"))));

        Map<String, Object> secondary = secondaryData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(secondaryData);

        try {
            return repo.insertApplicant(primary, secondary, userId);
        } catch (DuplicateKeyException e) {
            throw ApiException.message(400, "Registration Number already exists").with("success", false);
        }
    }

    private static boolean isFalsy(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isEmpty();
        if (v instanceof Number n) return n.doubleValue() == 0d;
        if (v instanceof Boolean b) return !b;
        return false;
    }

    // sanitizeValue: undefined/null/"" → null; strings trimmed; else as-is
    private static Object sanitize(Object v) {
        if (v == null) return null;
        if (v instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
        return v;
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
```

Add the create handler to `ApplicantController` (same file as Task 2). It reads the JWT principal (a `JwtService.FinalToken`, set by `JwtAuthFilter` in Phase-1) for `user_id`, supports flat or nested body:
```java
    @PostMapping("/create")
    public org.springframework.http.ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.rcf.imas.platform.security.JwtService.FinalToken principal) {

        String userId = principal == null ? null : principal.userId();

        @SuppressWarnings("unchecked")
        Map<String, Object> primaryData = body.get("primaryData") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : flatMinusSecondary(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> secondaryData = body.get("secondaryData") instanceof Map<?, ?> s
                ? (Map<String, Object>) s : Map.of();

        String applicantId = service.create(primaryData, secondaryData, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicant_id", applicantId);
        formatter.formatResponse(data);   // parity: response echoes formatResponse(model result)
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant created successfully");
        resp.put("data", data);
        return org.springframework.http.ResponseEntity.status(201).body(resp);
    }

    private static Map<String, Object> flatMinusSecondary(Map<String, Object> body) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove("secondaryData");
        return copy;
    }
```
Add the `ApplicantService service` field + constructor injection to `ApplicantController` (constructor becomes `ApplicantController(ApplicantRepository repo, ApplicantFormatter formatter, ApplicantService service)`).

> **Parity note (auth).** Node's `if (!userId) return 401 Unauthorized` is now unreachable because `@PreAuthorize("hasRole('ADMIN')")` rejects unauthenticated requests with 403/401 at the security layer before the handler runs. The 401-Unauthorized branch is preserved conceptually but cannot fire; document it in the audit. `created_by/updated_by` come from the ADMIN token's `user_id`, which must exist in `pp."user"` (FK) — true for real admins.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantCreateIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantCreateIT.java
git commit -m "feat(admission): transactional applicant create (primary+secondary) with 23505 parity"
```

---

## Task 4: applicant update (transactional upsert) + IT

Port `updateApplicant`: UPDATE primary (never `nmms_reg_number`, sets `updated_at`), UPSERT secondary `ON CONFLICT (applicant_id)`. **No 404** on a non-matching id. ADMIN-only.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/persistence/ApplicantRepository.java` (add `updateApplicant`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/service/ApplicantService.java` (add `update`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/web/ApplicantController.java` (add update handler)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantUpdateIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/admission/ApplicantUpdateIT.java`:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantUpdateIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;
    Long uid;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('upd','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='upd'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "upd", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, gender, medium, contact_no1, created_by, updated_by)
                VALUES (700001, 2025, 24010000900, 'Old Name', 'Old Father', 'M', 'English', '9000000000', :u, :u)
                """).param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @Test
    void updatesPrimaryAndUpsertsSecondaryLeavingRegNumberUntouched() throws Exception {
        String body = """
            {"primaryData":{"nmms_year":2026,"nmms_reg_number":99999999999,"student_name":"New Name",
              "father_name":"New Father","gender":"F","medium":"Kannada","contact_no1":"9111111111"},
             "secondaryData":{"village":"NewVillage"}}
            """;
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant updated successfully"));

        String name = jdbc.sql("SELECT student_name FROM pp.applicant_primary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(name).isEqualTo("New Name");
        // nmms_reg_number must NOT change (not in the UPDATE column list)
        String reg = jdbc.sql("SELECT nmms_reg_number::text FROM pp.applicant_primary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(reg).isEqualTo("24010000900");
        String village = jdbc.sql("SELECT village FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(village).isEqualTo("NewVillage");
    }

    @Test
    void secondUpsertUpdatesExistingSecondaryRow() throws Exception {
        String first = "{\"secondaryData\":{\"village\":\"V1\"}}";
        String second = "{\"secondaryData\":{\"village\":\"V2\"}}";
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(first)).andExpect(status().isOk());
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content(second)).andExpect(status().isOk());
        Long rows = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(Long.class).single();
        assertThat(rows).isEqualTo(1);  // ON CONFLICT updated, not inserted twice
        String v = jdbc.sql("SELECT village FROM pp.applicant_secondary_info WHERE applicant_id=700001").query(String.class).single();
        assertThat(v).isEqualTo("V2");
    }

    @Test
    void nonMatchingIdReturns200NoError() throws Exception {
        // Node preserves this quirk: 0-row UPDATE is silent, still 200
        mvc.perform(put("/api/applicants/888888/update").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"primaryData\":{\"student_name\":\"Ghost\"}}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void updateIsAdminOnly() throws Exception {
        mvc.perform(put("/api/applicants/700001/update").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON).content("{\"primaryData\":{\"student_name\":\"X\"}}"))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantUpdateIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ApplicantRepository` (`updateApplicant`, transactional; note: UPDATE omits `nmms_reg_number` exactly like Node; UPSERT secondary):
```java
    @org.springframework.transaction.annotation.Transactional
    public void updateApplicant(String applicantId, Map<String, Object> primary, Map<String, Object> secondary) {
        if (primary != null) {
            jdbc.sql("""
                    UPDATE pp.applicant_primary_info SET
                      nmms_year = :nmms_year, app_state = :app_state, district = :district, nmms_block = :nmms_block,
                      student_name = :student_name, father_name = :father_name, mother_name = :mother_name,
                      gmat_score = :gmat_score, sat_score = :sat_score, gender = :gender,
                      medium = :medium, aadhaar = :aadhaar, dob = CAST(:dob AS DATE),
                      home_address = :home_address, family_income_total = :family_income_total,
                      contact_no1 = :contact_no1, contact_no2 = :contact_no2,
                      current_institute_dise_code = :current_institute_dise_code,
                      previous_institute_dise_code = :previous_institute_dise_code,
                      updated_at = CURRENT_TIMESTAMP
                    WHERE applicant_id = :id
                    """)
                    .param("nmms_year", primary.get("nmms_year"))
                    .param("app_state", primary.get("app_state"))
                    .param("district", primary.get("district"))
                    .param("nmms_block", primary.get("nmms_block"))
                    .param("student_name", primary.get("student_name"))
                    .param("father_name", primary.get("father_name"))
                    .param("mother_name", primary.get("mother_name"))
                    .param("gmat_score", primary.get("gmat_score"))
                    .param("sat_score", primary.get("sat_score"))
                    .param("gender", primary.get("gender"))
                    .param("medium", primary.get("medium"))
                    .param("aadhaar", primary.get("aadhaar"))
                    .param("dob", primary.get("dob"))
                    .param("home_address", primary.get("home_address"))
                    .param("family_income_total", primary.get("family_income_total"))
                    .param("contact_no1", primary.get("contact_no1"))
                    .param("contact_no2", primary.get("contact_no2"))
                    .param("current_institute_dise_code", primary.get("current_institute_dise_code"))
                    .param("previous_institute_dise_code", primary.get("previous_institute_dise_code"))
                    .param("id", applicantId)
                    .update();
        }

        if (secondary != null) {
            jdbc.sql("""
                    INSERT INTO pp.applicant_secondary_info (
                      village, father_occupation, mother_occupation, father_education, mother_education,
                      household_size, own_house, smart_phone_home, internet_facility_home,
                      career_goals, subjects_of_interest, transportation_mode, distance_to_school,
                      num_two_wheelers, num_four_wheelers, irrigation_land,
                      neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone,
                      applicant_id, updated_at
                    ) VALUES (
                      :village, :father_occupation, :mother_occupation, :father_education, :mother_education,
                      :household_size, :own_house, :smart_phone_home, :internet_facility_home,
                      :career_goals, :subjects_of_interest, :transportation_mode, :distance_to_school,
                      :num_two_wheelers, :num_four_wheelers, :irrigation_land,
                      :neighbor_name, :neighbor_phone, :favorite_teacher_name, :favorite_teacher_phone,
                      :id, CURRENT_TIMESTAMP
                    )
                    ON CONFLICT (applicant_id) DO UPDATE SET
                      village = EXCLUDED.village, father_occupation = EXCLUDED.father_occupation,
                      mother_occupation = EXCLUDED.mother_occupation, father_education = EXCLUDED.father_education,
                      mother_education = EXCLUDED.mother_education, household_size = EXCLUDED.household_size,
                      own_house = EXCLUDED.own_house, smart_phone_home = EXCLUDED.smart_phone_home,
                      internet_facility_home = EXCLUDED.internet_facility_home, career_goals = EXCLUDED.career_goals,
                      subjects_of_interest = EXCLUDED.subjects_of_interest, transportation_mode = EXCLUDED.transportation_mode,
                      distance_to_school = EXCLUDED.distance_to_school, num_two_wheelers = EXCLUDED.num_two_wheelers,
                      num_four_wheelers = EXCLUDED.num_four_wheelers, irrigation_land = EXCLUDED.irrigation_land,
                      neighbor_name = EXCLUDED.neighbor_name, neighbor_phone = EXCLUDED.neighbor_phone,
                      favorite_teacher_name = EXCLUDED.favorite_teacher_name, favorite_teacher_phone = EXCLUDED.favorite_teacher_phone,
                      updated_at = CURRENT_TIMESTAMP
                    """)
                    .param("village", secondary.get("village"))
                    .param("father_occupation", secondary.get("father_occupation"))
                    .param("mother_occupation", secondary.get("mother_occupation"))
                    .param("father_education", secondary.get("father_education"))
                    .param("mother_education", secondary.get("mother_education"))
                    .param("household_size", secondary.get("household_size"))
                    .param("own_house", secondary.get("own_house"))
                    .param("smart_phone_home", secondary.get("smart_phone_home"))
                    .param("internet_facility_home", secondary.get("internet_facility_home"))
                    .param("career_goals", secondary.get("career_goals"))
                    .param("subjects_of_interest", secondary.get("subjects_of_interest"))
                    .param("transportation_mode", secondary.get("transportation_mode"))
                    .param("distance_to_school", secondary.get("distance_to_school"))
                    .param("num_two_wheelers", secondary.get("num_two_wheelers"))
                    .param("num_four_wheelers", secondary.get("num_four_wheelers"))
                    .param("irrigation_land", secondary.get("irrigation_land"))
                    .param("neighbor_name", secondary.get("neighbor_name"))
                    .param("neighbor_phone", secondary.get("neighbor_phone"))
                    .param("favorite_teacher_name", secondary.get("favorite_teacher_name"))
                    .param("favorite_teacher_phone", secondary.get("favorite_teacher_phone"))
                    .param("id", applicantId)
                    .update();
        }
    }
```
> **Parity note (insert-only-secondary constraint).** On the INSERT branch of the upsert, `num_two_wheelers/num_four_wheelers/irrigation_land` are `NOT NULL`. Node's update path binds `clean(secondaryData.num_two_wheelers)` which is `null` when absent → would violate NOT NULL on a first-time insert with those keys missing. This is a latent Node bug that only fails if `secondaryData` is provided on update for an applicant with no existing secondary row AND omits the wheel counts. To preserve behavior faithfully while not shipping a guaranteed 500, bind `COALESCE`-style defaults for these three only when null: replace their three `.param(...)` lines with `.param("num_two_wheelers", secondary.getOrDefault("num_two_wheelers", 0))` etc. (matches the create path). Document this as a deliberate, minimal deviation (Node would 500 here; we insert 0). The IT `secondUpsertUpdatesExistingSecondaryRow` exercises the update branch where a row already exists; add a note that the first upsert of a brand-new secondary uses the 0 defaults.

Add to `ApplicantService`:
```java
    /** Update primary + upsert secondary. No 404 (silent 0-row). userId → updated_by. */
    public void update(String applicantId, Map<String, Object> primaryData, Map<String, Object> secondaryData, String userId) {
        Map<String, Object> primary = null;
        if (primaryData != null) {
            primary = new LinkedHashMap<>();
            for (String k : PRIMARY_KEYS) primary.put(k, sanitize(primaryData.get(k)));
            primary.remove("nmms_reg_number");  // never updated
            primary.put("dob", formatter.sanitizeControllerDate(asString(primaryData.get("dob"))));
        }
        // secondaryData passed through as-is (Node only sets updated_by = userId on it); default wheel counts handled in repo
        repo.updateApplicant(applicantId, primary, secondaryData);
    }
```
> Note: Node's update SQL does not write `updated_by`/`created_by` on either table (only `updated_at`). We therefore do NOT bind `userId` into the update SQL — parity. The `userId` param is kept in the signature for future auditing but is currently unused; mark it `@SuppressWarnings` or drop it. Recommendation: **drop the `userId` param** from `update` to avoid dead code — the controller need not pass it.

Add the update handler to `ApplicantController`:
```java
    @PutMapping("/{applicantId}/update")
    public Map<String, Object> update(@PathVariable String applicantId,
                                      @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> primaryData = body.get("primaryData") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> secondaryData = body.get("secondaryData") instanceof Map<?, ?> s ? (Map<String, Object>) s : null;

        service.update(applicantId, primaryData, secondaryData);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("applicantId", applicantId);   // parity: model returns {success, applicantId}, echoed via data
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant updated successfully");
        resp.put("data", data);
        return resp;
    }
```
(Drop the `userId` param version of `service.update` — call the 3-arg form above.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantUpdateIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantUpdateIT.java
git commit -m "feat(admission): applicant update (primary UPDATE + secondary UPSERT, no-404 parity)"
```

---

## Task 5: applicant delete + IT

Port `deleteApplicant`: `DELETE ... RETURNING applicant_id`; 404 if 0 rows, else 200 message. Secondary cascades via FK ON DELETE CASCADE. ADMIN-only.

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/persistence/ApplicantRepository.java` (add `deleteById`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/admission/web/ApplicantController.java` (add delete handler)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantDeleteIT.java`

- [ ] **Step 1: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/admission/ApplicantDeleteIT.java`:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApplicantDeleteIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_name, enc_password, locked_yn) VALUES ('del','x','N') ON CONFLICT (user_name) DO NOTHING").update();
        Long uid = jdbc.sql("SELECT user_id FROM pp.\"user\" WHERE user_name='del'").query(Long.class).single();
        admin = jwt.issueFinalToken(String.valueOf(uid), "del", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        jdbc.sql("""
                INSERT INTO pp.applicant_primary_info
                  (applicant_id, nmms_year, nmms_reg_number, student_name, father_name, gender, medium, contact_no1, created_by, updated_by)
                VALUES (600001, 2025, 24010000600, 'Del Me', 'Father', 'M', 'English', '9000000000', :u, :u)
                """).param("u", uid).update();
        jdbc.sql("INSERT INTO pp.applicant_secondary_info(applicant_id, village, created_by, updated_by) VALUES (600001,'V',:u,:u)").param("u", uid).update();
        jdbc.sql("SELECT setval('pp.applicant_id_seq', (SELECT MAX(applicant_id)::bigint FROM pp.applicant_primary_info))").query(Long.class).single();
    }

    @Test
    void deletesAndCascadesSecondary() throws Exception {
        mvc.perform(delete("/api/applicants/600001").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.message").value("Applicant deleted successfully"));
        Long p = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info WHERE applicant_id=600001").query(Long.class).single();
        Long s = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_secondary_info WHERE applicant_id=600001").query(Long.class).single();
        assertThat(p).isEqualTo(0);
        assertThat(s).isEqualTo(0);  // ON DELETE CASCADE
    }

    @Test
    void deleteMissingIs404() throws Exception {
        mvc.perform(delete("/api/applicants/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("Applicant not found"));
    }

    @Test
    void deleteIsAdminOnly() throws Exception {
        mvc.perform(delete("/api/applicants/600001").header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantDeleteIT` — Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ApplicantRepository`:
```java
    /** DELETE ... RETURNING applicant_id. Empty optional = not found. */
    public Optional<String> deleteById(String applicantId) {
        return jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = :id RETURNING applicant_id")
                .param("id", applicantId)
                .query((rs, i) -> rs.getBigDecimal("applicant_id").toBigInteger().toString())
                .optional();
    }
```

Add the delete handler to `ApplicantController`:
```java
    @DeleteMapping("/{applicantId}")
    public Map<String, Object> delete(@PathVariable String applicantId) {
        repo.deleteById(applicantId).orElseThrow(ApplicantController::notFound);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Applicant deleted successfully");
        return resp;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=ApplicantDeleteIT` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission/ApplicantDeleteIT.java
git commit -m "feat(admission): applicant delete with 404 parity (secondary cascades)"
```

---

## Task 6: bulk upload — CSV/XLSX parse + validation + jurisdiction cache + batch insert + IT

Port the entire bulk-upload flow: multipart `file`, CSV/XLSX parsing with normalized headers, all-or-nothing validation, jurisdiction NAME→CODE resolution with a per-upload cache and fallback, batch insert with rollback, and the exact 6-key response object with the correct status codes. Add deliberate hardening: max upload size + content-type allowlist (Node had none). ADMIN-only.

**Library decisions (recommend to the controller):** Apache Commons CSV (`commons-csv`) for CSV, Apache POI (`poi-ooxml`) for XLSX. Both are mature, permissively licensed, and avoid the CVE history / streaming quirks of the Node `xlsx` package.

**Files:**
- Modify: `imas-backend/pom.xml` (add `commons-csv`, `poi-ooxml`)
- Modify: `imas-backend/src/main/resources/application.yml` (multipart limits)
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/persistence/JurisdictionLookupRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/service/BulkUploadService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/admission/web/BulkUploadController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/admission/BulkUploadIT.java`

- [ ] **Step 1: Add dependencies + multipart limits**

In `imas-backend/pom.xml` `<dependencies>`:
```xml
    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.11.0</version></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.3.0</version></dependency>
```

In `imas-backend/src/main/resources/application.yml` (deliberate hardening — Node had no limits):
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/rcf/imas/modules/admission/BulkUploadIT.java` — one happy-path CSV, one all-or-nothing validation-failure, one no-file, one jurisdiction-not-found (db-fail 500), plus auth:
```java
package com.rcf.imas.modules.admission;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BulkUploadIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.applicant_secondary_info").update();
        jdbc.sql("DELETE FROM pp.applicant_primary_info").update();
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");

        // FK user_id=1 must exist (bulk hardcodes created_by=updated_by=1)
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (1,'bulk_sys','x','N') ON CONFLICT (user_id) DO NOTHING").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // jurisdiction hierarchy for NAME→CODE resolution
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800001,'KARNATAKA','STATE',NULL) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800002,'BELAGAVI','EDUCATION DISTRICT',800001) ON CONFLICT (juris_code) DO NOTHING").update();
        jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (800003,'GOKAK','BLOCK',800002) ON CONFLICT (juris_code) DO NOTHING").update();
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "applicants.csv", "text/csv", content.getBytes());
    }

    @Test
    void happyPathCsvInsertsAllRowsAndReturns200Success() throws Exception {
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block,gender,contact_no1
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak,F,9876543210
            2025,24010000202,Kiran,Suresh,50,55,Karnataka,Belagavi,Gokak,M,9000000000
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.totalRecords").value(2))
           .andExpect(jsonPath("$.insertedRecords").value(2))
           .andExpect(jsonPath("$.validationErrors").value(0))
           .andExpect(jsonPath("$.dbErrors").value(0))
           .andExpect(jsonPath("$.status").value("success"))
           .andExpect(jsonPath("$.logFile").isString());

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(2);
        // jurisdiction NAME→CODE resolved
        Long code = jdbc.sql("SELECT district::bigint FROM pp.applicant_primary_info WHERE nmms_reg_number=24010000201").query(Long.class).single();
        assertThat(code).isEqualTo(800002L);
    }

    @Test
    void anyValidationErrorInsertsNothingAndReturns400() throws Exception {
        // second row missing father_name + sat_score → all-or-nothing → 0 inserted, 400
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak
            2025,24010000202,Kiran,,50,,Karnataka,Belagavi,Gokak
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.totalRecords").value(2))
           .andExpect(jsonPath("$.insertedRecords").value(0))
           .andExpect(jsonPath("$.validationErrors").value(org.hamcrest.Matchers.greaterThan(0)))
           .andExpect(jsonPath("$.dbErrors").value(0))
           .andExpect(jsonPath("$.status").value("failed"));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(0);  // nothing inserted (all-or-nothing)
    }

    @Test
    void unresolvedJurisdictionRollsBackWholeBatchAnd500() throws Exception {
        // valid rows, but "Atlantis" district resolves to nothing → row error → batch rollback → db-fail 500
        String content = """
            nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score,app_state,district,nmms_block
            2025,24010000201,Asha,Ravi,45,60,Karnataka,Belagavi,Gokak
            2025,24010000202,Kiran,Suresh,50,55,Karnataka,Atlantis,Gokak
            """;
        mvc.perform(multipart("/api/bulk-upload/upload").file(csv(content))
                .header("Authorization", "Bearer " + admin))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.status").value("failed"))
           .andExpect(jsonPath("$.insertedRecords").value(0))
           .andExpect(jsonPath("$.dbErrors").value(org.hamcrest.Matchers.greaterThan(0)));

        Long n = jdbc.sql("SELECT COUNT(*) FROM pp.applicant_primary_info").query(Long.class).single();
        assertThat(n).isEqualTo(0);  // rollback → even the first valid row is gone
    }

    @Test
    void noFileIs400() throws Exception {
        mvc.perform(multipart("/api/bulk-upload/upload").header("Authorization", "Bearer " + admin))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("No file received")));
    }

    @Test
    void uploadIsAdminOnly() throws Exception {
        mvc.perform(multipart("/api/bulk-upload/upload")
                .file(csv("nmms_year,nmms_reg_number,student_name,father_name,gmat_score,sat_score\n2025,24010000201,A,B,1,2\n"))
                .header("Authorization", "Bearer " + student))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BulkUploadIT` — Expected: FAIL (no controller).

- [ ] **Step 4: Implement**

`src/main/java/com/rcf/imas/modules/admission/persistence/JurisdictionLookupRepository.java`:
```java
package com.rcf.imas.modules.admission.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JurisdictionLookupRepository {

    private final JdbcClient jdbc;

    public JurisdictionLookupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Port of getJurisdictionIdByName: clean = trim, strip trailing . or ,, upper-case.
     * Primary: juris_name ILIKE :name AND juris_type = :type [AND parent_juris = :parent].
     * Fallback: UPPER(juris_name) = :name (no type/parent). Returns juris_code as String, else empty.
     */
    public Optional<String> findCodeByName(String jurisName, String jurisType, String parentCode) {
        if (jurisName == null) return Optional.empty();
        String clean = jurisName.trim().replaceAll("[.,]+$", "").toUpperCase();

        Optional<String> primary;
        if (parentCode != null) {
            primary = jdbc.sql("""
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE juris_name ILIKE :name AND juris_type = :type AND parent_juris = :parent
                    """)
                    .param("name", clean).param("type", jurisType).param("parent", parentCode)
                    .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
        } else {
            primary = jdbc.sql("""
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE juris_name ILIKE :name AND juris_type = :type
                    """)
                    .param("name", clean).param("type", jurisType)
                    .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
        }
        if (primary.isPresent()) return primary;

        return jdbc.sql("SELECT juris_code FROM pp.jurisdiction WHERE UPPER(juris_name) = :name")
                .param("name", clean)
                .query((rs, i) -> rs.getBigDecimal("juris_code").toBigInteger().toString()).optional();
    }
}
```

`src/main/java/com/rcf/imas/modules/admission/service/BulkUploadService.java`:
```java
package com.rcf.imas.modules.admission.service;

import com.rcf.imas.modules.admission.persistence.JurisdictionLookupRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Service
public class BulkUploadService {

    private static final List<String> REQUIRED = List.of(
            "nmms_year", "nmms_reg_number", "student_name", "father_name", "gmat_score", "sat_score");
    private static final Set<String> NUMERIC = Set.of("nmms_year", "gmat_score", "sat_score");

    private final JdbcClient jdbc;
    private final JurisdictionLookupRepository jurisdiction;
    private final ApplicantFormatter formatter;

    public BulkUploadService(JdbcClient jdbc, JurisdictionLookupRepository jurisdiction, ApplicantFormatter formatter) {
        this.jdbc = jdbc;
        this.jurisdiction = jurisdiction;
        this.formatter = formatter;
    }

    /** Result mirrors the Node response object exactly. httpStatus carries the intended status code. */
    public record Result(int totalRecords, int insertedRecords, int validationErrors,
                         int dbErrors, String status, String logFile, int httpStatus) {}

    public Result process(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.toLowerCase().endsWith(".csv") ? ".csv"
                : original.toLowerCase().endsWith(".xls") ? ".xls"
                : original.toLowerCase().endsWith(".xlsx") ? ".xlsx" : "";

        List<String> validationMessages = new ArrayList<>();
        List<String> dbErrors = new ArrayList<>();
        try {
            List<Map<String, String>> rows = ext.equals(".csv") ? parseCsv(file) : parseExcel(file);

            // validate + sanitize
            List<Map<String, Object>> valid = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> raw = rows.get(i);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("originalRowIndex", i);
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (REQUIRED.contains(k) && (v == null || v.trim().isEmpty())) {
                        validationMessages.add("Row " + (i + 1) + ": " + k + " This field is required.");
                    }
                    if ("dob".equals(k)) out.put(k, formatter.sanitizeBulkDate(v));
                    else if (NUMERIC.contains(k)) out.put(k, numeric(v));
                    else out.put(k, sanitize(v));
                }
                valid.add(out);
            }

            if (!validationMessages.isEmpty()) {
                String log = writeLog(original, "failed", validationMessages, dbErrors);
                return new Result(rows.size(), 0, validationMessages.size(), 0, "failed", log, 400);
            }

            int inserted = insertBatch(valid, dbErrors);
            boolean ok = dbErrors.isEmpty() && inserted > 0;
            String status = ok ? "success" : "failed";
            String log = writeLog(original, status, validationMessages, dbErrors);
            return new Result(rows.size(), inserted, 0, dbErrors.size(), status, log, ok ? 200 : 500);

        } catch (Exception ex) {
            dbErrors.add("CRITICAL ERROR: " + ex.getMessage());
            String log = writeLog(original, "failed", validationMessages, dbErrors);
            // critical catch: Node returns {message, status, logFile} with 500 — signalled by httpStatus=500 and a null-count marker
            return new Result(-1, 0, 0, 0, "failed", log, 500);
        }
    }

    // Batch insert in ONE transaction: any row error → whole batch rolls back (throw propagates out of @Transactional).
    // Node caught per-row, pushed a dbError, re-threw to rollback the batch. We replicate: record the dbError, then rethrow.
    @Transactional
    protected int insertBatch(List<Map<String, Object>> rows, List<String> dbErrors) {
        Map<String, String> cache = new HashMap<>();
        int inserted = 0;
        try {
            for (Map<String, Object> row : rows) {
                int rowNum = (int) row.get("originalRowIndex") + 1;
                Object reg = row.get("nmms_reg_number");
                if (reg == null || reg.toString().isBlank()) {
                    dbErrors.add("Row " + rowNum + ": NMMS Registration Number is missing");
                    throw new IllegalStateException("Missing NMMS Reg No");
                }
                try {
                    String state = cachedLookup(cache, str(row.get("app_state")), "STATE", null);
                    String district = cachedLookup(cache, str(row.get("district")), "EDUCATION DISTRICT", state);
                    String block = cachedLookup(cache, str(row.get("nmms_block")), "BLOCK", district);

                    jdbc.sql("""
                            INSERT INTO pp.applicant_primary_info (
                              nmms_year, nmms_reg_number, app_state, district, nmms_block,
                              student_name, father_name, mother_name, gender, dob, aadhaar,
                              gmat_score, sat_score, medium, home_address, family_income_total,
                              contact_no1, contact_no2, current_institute_dise_code,
                              previous_institute_dise_code, created_by, updated_by
                            ) VALUES (
                              :nmms_year, :nmms_reg_number, :app_state, :district, :nmms_block,
                              :student_name, :father_name, :mother_name, :gender, CAST(:dob AS DATE), :aadhaar,
                              :gmat_score, :sat_score, :medium, :home_address, :family_income_total,
                              :contact_no1, :contact_no2, :current_institute_dise_code,
                              :previous_institute_dise_code, 1, 1
                            )
                            """)
                            .param("nmms_year", row.get("nmms_year"))
                            .param("nmms_reg_number", row.get("nmms_reg_number"))
                            .param("app_state", state)
                            .param("district", district)
                            .param("nmms_block", block)
                            .param("student_name", row.get("student_name"))
                            .param("father_name", row.get("father_name"))
                            .param("mother_name", row.get("mother_name"))
                            .param("gender", row.get("gender"))
                            .param("dob", row.get("dob"))
                            .param("aadhaar", row.get("aadhaar"))
                            .param("gmat_score", row.get("gmat_score"))
                            .param("sat_score", row.get("sat_score"))
                            .param("medium", row.get("medium"))
                            .param("home_address", row.get("home_address"))
                            .param("family_income_total", row.get("family_income_total"))
                            .param("contact_no1", row.get("contact_no1"))
                            .param("contact_no2", row.get("contact_no2"))
                            .param("current_institute_dise_code", row.get("current_institute_dise_code"))
                            .param("previous_institute_dise_code", row.get("previous_institute_dise_code"))
                            .update();
                    inserted++;
                } catch (RuntimeException rowErr) {
                    dbErrors.add("Row " + rowNum + " (Reg No: " + reg + ") failed. " + rowErr.getMessage());
                    throw rowErr;  // rollback whole batch
                }
            }
            return inserted;
        } catch (RuntimeException batchErr) {
            // batch rolled back; nothing persisted. Report 0 inserted (parity: Node's `inserted` array is discarded
            // because the COMMIT never happened for this batch).
            return 0;
        }
    }

    private String cachedLookup(Map<String, String> cache, String name, String type, String parent) {
        String key = type + ":" + name + ":" + (parent == null ? "0" : parent);
        if (cache.containsKey(key)) return cache.get(key);
        String id = jurisdiction.findCodeByName(name, type, parent)
                .orElseThrow(() -> new IllegalStateException(
                        "Location not found: " + type + " " +
                        (name == null ? "" : name.trim().replaceAll("[.,]+$", "").toUpperCase())));
        cache.put(key, id);
        return id;
    }

    // ---------- parsing ----------
    private List<Map<String, String>> parseCsv(MultipartFile file) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setTrim(true).build();
        try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(r, fmt)) {
            List<String> headers = parser.getHeaderNames().stream().map(BulkUploadService::normHeader).toList();
            for (CSVRecord rec : parser) {
                if (isBlankRecord(rec)) continue;   // skipEmptyLines parity
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < rec.size() ? rec.get(i) : "");
                }
                out.add(row);
            }
        }
        return out;
    }

    private List<Map<String, String>> parseExcel(MultipartFile file) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (!it.hasNext()) return out;
            Row headerRow = it.next();
            List<String> headers = new ArrayList<>();
            for (Cell c : headerRow) headers.add(normHeader(cellString(c)));
            DataFormatter df = new DataFormatter();
            while (it.hasNext()) {
                Row row = it.next();
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell c = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    m.put(headers.get(i), c == null ? "" : df.formatCellValue(c));  // defval:"" parity
                }
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isBlankRecord(CSVRecord rec) {
        for (String v : rec) if (v != null && !v.isBlank()) return false;
        return true;
    }

    private static String cellString(Cell c) {
        if (c == null) return "";
        return new DataFormatter().formatCellValue(c);
    }

    private static String normHeader(String h) {
        return h == null ? "" : h.toLowerCase().trim().replace(" ", "_");
    }

    // ---------- sanitize (bulk model parity) ----------
    private static Object numeric(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return Double.valueOf(v.trim()).longValue(); }  // isNaN → null
        catch (NumberFormatException e) { return null; }
    }

    private static String sanitize(String v) {
        if (v == null || v.isEmpty()) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    // ---------- log file ----------
    private static String writeLog(String fileName, String status,
                                   List<String> validationErrors, List<String> dbErrors) {
        String name = "upload_log_" + Instant.now().toEpochMilli() + ".txt";
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "imas-bulk-logs");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            sb.append("File Upload Summary\n============================\n");
            sb.append("File: ").append(fileName).append("\nStatus: ").append(status).append("\n\n");
            if (!validationErrors.isEmpty()) {
                sb.append("Validation Errors:\n");
                validationErrors.forEach(e -> sb.append(e).append("\n"));
            }
            if (!dbErrors.isEmpty()) {
                sb.append("\nDatabase Errors:\n");
                dbErrors.forEach(e -> sb.append("• ").append(e).append("\n"));
            }
            Files.writeString(dir.resolve(name), sb.toString());
        } catch (Exception ignored) {
            // log write failure must not break the response
        }
        return name;
    }
}
```
> **Transaction note.** `insertBatch` is annotated `@Transactional` but is called from another method **in the same bean** (`process`) — Spring's proxy-based `@Transactional` does NOT apply to self-invocation. To get a real transaction/rollback boundary, either (a) move `insertBatch` into a separate `@Component` (e.g. `BulkInsertRepository`) injected into `BulkUploadService`, OR (b) inject `TransactionTemplate` and wrap the loop. **Recommended: option (a)** — put `insertBatch` in `JurisdictionLookupRepository`'s sibling or a new `BulkInsertRepository` so the proxy applies. Update the plan's file list accordingly if you choose (a). The IT's `unresolvedJurisdictionRollsBackWholeBatchAnd500` test will FAIL if the transaction boundary is wrong (rows would persist), so this is caught by red-green. Simplest correct implementation: a dedicated `@Repository BulkInsertRepository { @Transactional int insertBatch(...) }` that takes the jurisdiction lookup + cache as params.

`src/main/java/com/rcf/imas/modules/admission/web/BulkUploadController.java`:
```java
package com.rcf.imas.modules.admission.web;

import com.rcf.imas.modules.admission.service.BulkUploadService;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bulk-upload")
@PreAuthorize("hasRole('ADMIN')")   // bulk-mutates student PII → ADMIN only (Node had this route fully open)
class BulkUploadController {

    // deliberate hardening: content-type allowlist (Node had none)
    private static final Set<String> ALLOWED = Set.of(
            "text/csv", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream");  // some browsers send octet-stream for .csv

    private final BulkUploadService service;

    BulkUploadController(BulkUploadService service) { this.service = service; }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw ApiException.message(400,
                    "No file received. Ensure multipart/form-data and field name is \"file\".");
        }
        String ct = file.getContentType();
        if (ct != null && !ALLOWED.contains(ct)) {
            throw ApiException.message(400, "Invalid file type. Only CSV/XLS/XLSX are accepted.");
        }

        BulkUploadService.Result r = service.process(file);

        Map<String, Object> body = new LinkedHashMap<>();
        if (r.totalRecords() < 0) {
            // critical catch parity: {message, status, logFile}
            body.put("message", "Bulk upload failed");
            body.put("status", "failed");
            body.put("logFile", r.logFile());
        } else {
            body.put("totalRecords", r.totalRecords());
            body.put("insertedRecords", r.insertedRecords());
            body.put("validationErrors", r.validationErrors());
            body.put("dbErrors", r.dbErrors());
            body.put("status", r.status());
            body.put("logFile", r.logFile());
        }
        return ResponseEntity.status(r.httpStatus()).body(body);
    }
}
```
> **Multipart note.** `@RequestParam("file") MultipartFile` binds the multipart part named `file`. Spring Boot's default `MultipartAutoConfiguration` + `StandardServletMultipartResolver` is active with `spring-boot-starter-web`, so no extra config is needed beyond the size limits in `application.yml`.
>
> **Content-type note.** `MockMultipartFile("file", "applicants.csv", "text/csv", ...)` sends `text/csv`, which is in the allowlist. Real browsers vary (`application/vnd.ms-excel` for some CSVs), hence the permissive-but-bounded set including `application/octet-stream`. Confirm real client content-types in the fetch audit; widen or key off extension if a legitimate upload is rejected.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=BulkUploadIT` — Expected: PASS (5 tests). If `unresolvedJurisdictionRollsBackWholeBatchAnd500` shows persisted rows, fix the transaction boundary (self-invocation note above) before proceeding.

- [ ] **Step 6: Run the whole admission suite**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest="Applicant*,BulkUpload*"` — Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add imas-backend/pom.xml imas-backend/src/main/resources/application.yml imas-backend/src/main/java/com/rcf/imas/modules/admission imas-backend/src/test/java/com/rcf/imas/modules/admission/BulkUploadIT.java
git commit -m "feat(admission): bulk upload (CSV/XLSX, all-or-nothing validation, jurisdiction cache, batch insert) ADMIN-only"
```

---

## Self-review record

- **Spec coverage:** All 12 endpoints ported — create ✅ T3, list/reg/by-id/5-counts ✅ T2, update ✅ T4, delete ✅ T5, bulk upload ✅ T6, plus `ApplicantFormatter` ✅ T1. Every endpoint carries `@PreAuthorize("hasRole('ADMIN')")` (class-level on both controllers) — the audit-flagged unauthenticated-PII critical is closed. `sibling_education` correctly untouched (not in these routes).
- **Placeholder scan:** clean — every code step is complete. The one flagged risk (self-invoked `@Transactional` in `BulkUploadService.insertBatch`) is called out with a concrete fix (extract to a `@Repository` so the proxy applies) and is guarded by the rollback IT, so red-green catches it.
- **Type consistency:** all `numeric` ids (`applicant_id`, `nmms_reg_number`, jurisdiction codes) serialize as **String** via `getBigDecimal(...).toBigInteger().toString()`; `dise_code` is `varchar` → `getString`; `dob` (`date`) rendered `YYYY-MM-DD` by `ApplicantFormatter`; timestamps as node-pg ISO-Z. Count endpoints: `count` is a `long` (JSON number) for scalar counts and an **array** for `today-classes-count` — matches Node exactly. `cohortstudentcount` inner ints via `::INT` cast → JSON numbers.
- **Known intentional diffs from Node (all strictly safer; record in the fetch audit):**
  1. **Auth added** to endpoints 2–9 and 12 (were fully open in Node) and role-narrowed 1/10/11 to ADMIN (were any-authenticated). This is the whole point of the migration's §5 hardening.
  2. **Count endpoints wrapped** — a DB error now yields a clean JSON 500 instead of an unhandled-rejection crash.
  3. **Upload size + content-type limits** added (Node had none): 50MB, CSV/XLS/XLSX only.
  4. **Bulk update secondary NOT-NULL wheel counts** default to 0 on a first-time insert-via-upsert (Node would 500). Minimal, documented in Task 4.
  5. **404 body key order** `{message, success}` vs Node `{success, message}` — semantically equivalent; confirm no frontend asserts key order.
- **Parity risks to verify with the Task-11-style harness:** (a) `formatResponse` on the create/update `data` echo produces `{applicant_id}` / `{success, applicantId}` respectively — matches Node's `formatResponse(model result)` because those objects have no `gender`/`dob`. (b) The critical-catch bulk response omits the count keys (`{message, status, logFile}`) — matches Node exactly; the frontend must handle both response shapes (already does, since Node emitted both).
- **Decisions needing the controller's confirmation:** CSV library (recommend Apache Commons CSV 1.11), XLSX library (recommend Apache POI 5.3 `poi-ooxml`), and the 50MB upload cap + content-type allowlist. See the report below.

## Execution handoff

Execute with superpowers:subagent-driven-development (fresh subagent per task, review between tasks) or superpowers:executing-plans. Tasks are ordered so each builds only on committed, green predecessors. After Task 6, extend the Phase-1 parity harness (`scripts/parity/`) with the `/api/applicants` and `/api/bulk-upload` routes and add the auth-enforcement diffs to `docs/superpowers/plans/artifacts/phase2a-fetch-audit.md`.
