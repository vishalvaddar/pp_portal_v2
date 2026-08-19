# EXAMS Module — Ground Truth (for Plan 3a)

Captured from a full read of the Node source. Base mount: `app.use("/api/exams", examRoutes)` (`server/index.js:312`). Files: `server/routes/examRoutes.js` (86 lines, all live except one commented route), `server/controllers/examControllers.js` (1837 lines), `server/models/examModels.js` (337 lines). **No auth middleware is applied anywhere in this router** — every endpoint below (including hall-ticket downloads) is currently PUBLIC in Node. The design spec's "hallticket is public" intent is trivially true today because *nothing* is protected; per RESUME-migration.md convention #7, the Java port should add `ADMIN` enforcement to everything except `/api/auth/*` and `hallticket/**`.

## 1. Endpoint Inventory (17 wired routes + 1 dead route + 1 broken inline route)

Router registers routes in this literal order (`examRoutes.js:34-83`):

| # | Method | Path | Handler | Notes |
|---|--------|------|---------|-------|
| 1 | GET | `/exam-centres` | `fetchExamCentres` | list active centres |
| 2 | POST | `/exam-centres` | `createExamCentre` | create centre (dup-check + insert) |
| 3 | DELETE | `/exam-centres/:id` | `removeExamCentre` | blocked if centre used in an exam |
| 4 | PUT | `/exam-centres/:id` | `updateExamCentre` | full-row update |
| 5 | GET | `/divisions-by-state/:stateId` | `fetchDivisionsByState` | jurisdiction lookup |
| 6 | GET | `/education-districts-by-division/:divisionId` | `fetchEducationDistrictsByDivision` | jurisdiction lookup |
| 7 | GET | `/blocks-by-district/:districtId` | `fetchBlocksByDistrict` | jurisdiction lookup |
| 8 | GET | `/clusters-by-block/:blockId` | `fetchClustersByBlock` | jurisdiction lookup |
| 9 | GET | `/used-blocks` | `fetchUsedBlocks` | `?year=` blocks already assigned to an exam that year |
| 10 | GET | `/notassigned` | `fetchAllExamsnotassigned` | `?year=` (accepts `"YYYY-YY"`, splits to `"YYYY"`) |
| 11 | GET | `/assigned` | `fetchAllExams` | `?year=`, same split; exams **with** assigned applicants |
| — | POST | `/create` | ~~`createExamAndAssignApplicants`~~ | **commented out** (`examRoutes.js:51`) — dead route, handler still exported but unreachable via this path |
| 12 | GET | `/:examId/student-list` | `generateStudentList` | XLSX "calling list", writes to disk then streams+deletes |
| 13 | DELETE | `/:examId` | `deleteExam` | delete exam + its applicant_exam rows |
| 14 | GET | `/:examId/:exam_name/download-all-hall-tickets` | `downloadAllHallTickets` | ZIP of per-student PDFs |
| 15 | PUT | `/:examId/freeze` | `freezeExam` | sets `frozen_yn='Y'` |
| 16 | POST | `/create` | `createExamOnly` | **live** create-exam-only (re-registers `/create` after the commented line — no conflict since the earlier one is commented out) |
| 17 | POST | `/:examId/assign-students` | `assignApplicantsToExam` | **registered twice on the same source line** (`examRoutes.js:59`: `router.post(...); router.post(...);` — literal duplicate statement, same handler reference both times). Harmless in practice: Express matches route stack in order; the handler never calls `next()`, so the 2nd registration is dead/unreachable, but it is still a code smell to flag as unintentional duplication when porting (do not port the duplicate). |
| 18 | GET | `/viewcentres` | `fetchexamcentresview` | `SELECT * FROM pp.pp_exam_centre` (all centres, incl. inactive) |
| 19 | GET | `/hallticket/:hallTicketNo` | `singlestudentdownloadhallticket` | single-PDF public download |
| 20 | GET | `/count` | inline handler in `examRoutes.js:65-80` | **BROKEN**: calls `db.query(...)` but `db` is never imported in `examRoutes.js` (only `pool` is) → `ReferenceError: db is not defined` on every call, caught by the route's own try/catch → **always returns `500 {error:"Internal server error"}`**. Confirmed no frontend caller references `/api/exams/count`. Treat as dead/broken; do not port unless intentionally fixing it (flag decision to product owner). |

**Route-ordering hazard:** none of the literal segments (`/exam-centres`, `/divisions-by-state/:x`, `/notassigned`, `/assigned`, `/viewcentres`, `/hallticket/:x`, `/count`) collide with `/:examId` (DELETE) or `/:examId/...` (GET/PUT) because they differ in HTTP method or segment count — but this is fragile; the literal single-segment routes (`/notassigned`, `/assigned`, `/viewcentres`, `/count`) MUST be registered/matched before any hypothetical single-segment `GET /:examId` is added in future (there isn't one today). When porting to Spring `@RequestMapping`, keep literal-path mappings distinct method-per-controller as Spring resolves by full pattern + method, so there is no order-dependent shadowing risk in Java (unlike Express) — but preserve the *set* of paths exactly.

## 2. Exact SQL (verbatim from `examModels.js` + inline `pool.query` in the controller)

### 2.1 Exam Centres

```sql
-- getExamCentres()
SELECT pp_exam_centre_id, pp_exam_centre_name
FROM pp.pp_exam_centre
WHERE active_yn = 'Y'
ORDER BY pp_exam_centre_name ASC;
```

```sql
-- checkExistingCentre() (examControllers.js:54-62), pre-insert dup guard
SELECT * FROM pp.pp_exam_centre
WHERE
  pp_exam_centre_code = $1
  OR pp_exam_centre_name = $2
  OR contact_phone = $3
  OR contact_email = $4
LIMIT 1;
-- params: [pp_exam_centre_code||null, pp_exam_centre_name, contact_phone||null, contact_email||null]
```

```sql
-- addExamCentre() insert (examModels.js:32-51)
INSERT INTO pp.pp_exam_centre (
  pp_exam_centre_code, pp_exam_centre_name, address, village, pincode,
  contact_person, contact_phone, contact_email, sitting_capacity,
  latitude, longitude, created_at, created_by, active_yn
)
VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
RETURNING *;
-- created_at = new Date() (JS Date, sent as timestamp); active_yn hardcoded 'Y';
-- sitting_capacity: parseInt(...) or null; latitude/longitude: parseFloat(...) or null
```

```sql
-- deleteExamCentre(id): usage guard then delete (examModels.js:79-100)
SELECT exam_name FROM pp.examination WHERE pp_exam_centre_id = $1 LIMIT 1;
-- if any row -> throw Error(`Centre already used in exam: ${exam_name}`) -> controller maps to 400
DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = $1;
```

```sql
-- updateExamCentre inline pool.query (examControllers.js:227-243)
UPDATE pp.pp_exam_centre
SET pp_exam_centre_name=$1, pp_exam_centre_code=$2, sitting_capacity=$3,
    latitude=$4, longitude=$5, address=$6, village=$7, pincode=$8,
    contact_person=$9, contact_phone=$10, contact_email=$11, active_yn=$12
WHERE pp_exam_centre_id = $13
RETURNING *;
-- active_yn defaults to 'Y' if falsy (`active_yn || 'Y'`) -- NOTE: this means PUT
-- with active_yn omitted/empty ALWAYS resets to 'Y', even if caller intended to
-- keep it 'N' or didn't send the field. Reproduce this exact behavior for parity.
```

### 2.2 Jurisdiction lookups (all identical shape, different `juris_type`/parent column)

```sql
-- getDivisionsByState(stateId)
SELECT JURIS_CODE AS id, JURIS_NAME AS name
FROM PP.JURISDICTION
WHERE JURIS_TYPE = 'DIVISION' AND PARENT_JURIS = $1
ORDER BY JURIS_NAME

-- getEducationDistrictsByDivision(divisionId): JURIS_TYPE = 'EDUCATION DISTRICT', PARENT_JURIS = $1
-- getBlocksByDistrict(districtId):            JURIS_TYPE = 'BLOCK',              PARENT_JURIS = $1
-- getClustersByBlock(blockId):                 JURIS_TYPE = 'CLUSTER',            PARENT_JURIS = $1
```
(Column names are written upper-case in source; Postgres identifiers are case-insensitive when unquoted, so this is equivalent to lower-case. Response keys are literally `id`/`name` — numeric `juris_code` comes back as a string via node-pg.)

```sql
-- getUsedBlocks(year) (examModels.js:151-169)
SELECT DISTINCT api.nmms_block
FROM pp.applicant_primary_info api
INNER JOIN pp.applicant_exam ae ON api.applicant_id = ae.applicant_id
INNER JOIN pp.examination e ON ae.exam_id = e.exam_id
WHERE e.exam_year = $1
-- controller then does `.map(row => Number(row.nmms_block))` -- response is a
-- bare JSON array of NUMBERS (not strings!), unlike almost everywhere else in
-- this codebase where numeric ids serialize as strings. Must reproduce: JSON
-- number type here, not string.
```

### 2.3 Exam listing

```sql
-- getAllExams(year) (examModels.js:172-210) -- "assigned" (has applicants)
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
WHERE e.exam_year = $1
GROUP BY e.exam_id, e.exam_name, e.exam_date, e.pp_exam_centre_id, c.pp_exam_centre_name
ORDER BY e.exam_date DESC
```
Because `JOIN pp.applicant_exam`/`applicant_primary_info` are INNER joins, an exam with zero assigned applicants is **excluded entirely** from `/assigned` — this is how `notassigned` and `assigned` partition (see next query, which is the NOT EXISTS complement). `district_ids`/`block_ids` etc. are Postgres arrays; node-pg returns them as JS arrays of strings (numeric juris_code → string per array element). The frontend (`CreateExamHooks.js:170-181`) reads `district_ids[0]`/`district_names[0]` — i.e. **only the first array element is used**, meaning multi-district exams (should not normally happen since block→district is many-to-one, but with `DISTINCT` on both district and block arrays independently, if an exam somehow has applicants from 2 districts, only the first shown) — reproduce the arrays faithfully; do not "fix" to a scalar.

```sql
-- getAllExamsnotassigned(year) (examModels.js:213-241)
SELECT e.exam_id, e.exam_name, e.exam_date, e.frozen_yn, e.pp_exam_centre_id,
       c.pp_exam_centre_name, e.exam_start_time, e.exam_end_time
FROM pp.examination e
LEFT JOIN pp.pp_exam_centre c ON e.pp_exam_centre_id = c.pp_exam_centre_id
WHERE e.exam_year = $1
  AND NOT EXISTS (SELECT 1 FROM pp.applicant_exam ae WHERE ae.exam_id = e.exam_id)
ORDER BY e.exam_date DESC
```

Both `fetchAllExams` and `fetchAllExamsnotassigned` controllers (examControllers.js:339-378) require `?year` (400 `{message:"Year is required"}` if absent) then do `const examYear = year.split("-")[0]` — i.e. accepts `"2025-26"` and truncates to `"2025"`, or accepts a bare `"2025"` unchanged (`"2025".split("-")[0] === "2025"`). This is comparing a JS string against `examination.exam_year` (`varchar(10)`), a plain string `=` comparison — no numeric cast.

### 2.4 Exam creation / deletion / freeze

```sql
-- addcreateExamonly() conflict check (examModels.js:257-264), inside pool.connect() + BEGIN
SELECT exam_id, exam_name, exam_start_time, exam_end_time
FROM pp.examination
WHERE pp_exam_centre_id = $1 AND exam_date = $2 AND exam_year = $3
```
Then for **every** row returned, JS does an interval-overlap check:
```js
(startTime >= existingStart && startTime < existingEnd) ||
(endTime > existingStart && endTime <= existingEnd) ||
(startTime <= existingStart && endTime >= existingEnd)
```
`startTime`/`endTime`/`existingStart`/`existingEnd` are `time` values coming back from pg as strings like `"14:30:00"` — comparison is **lexicographic string comparison**, which happens to be correct for zero-padded `HH:MM:SS` but would break for non-zero-padded times; input is validated by the DB `time` type on write so this is safe as long as the client always sends `HH:MM` or `HH:MM:SS`. On first overlap found: `ROLLBACK`, return `{conflict:true, message:"Exam exists from <start> to <end>"}` → controller maps to `409 {error:"Time conflict", message}`. No overlap in any existing exam → insert:
```sql
INSERT INTO pp.examination (exam_name, exam_date, pp_exam_centre_id, exam_start_time, exam_end_time, exam_year)
VALUES ($1,$2,$3,$4,$5,$6)
RETURNING exam_id;
```
`examYear` passed in is `academic_year ? academic_year.split("-")[0] : null` (controller, `createExamOnly`, examControllers.js:1567-1570) — same `"YYYY-YY"→"YYYY"` truncation, or `null` if `academic_year` omitted (allowed! `academic_year` is NOT in the required-fields check at examControllers.js:1563, only `centreId, examName, date, startTime, endTime` are required). This means `exam_year` can legally be inserted as NULL — the later `/assigned`/`/notassigned` queries filtering `WHERE e.exam_year = $1` would then never match this exam (NULL never equals anything) — an orphan-exam quirk to preserve or explicitly decide to fix.

```sql
-- deleteExamById(examId) (examModels.js:311-316) -- NOT ACTUALLY TRANSACTIONAL, see §6
DELETE FROM pp.applicant_exam WHERE exam_id = $1;
DELETE FROM pp.examination WHERE exam_id = $1;
```

```sql
-- freezeExam (examControllers.js:1535-1549), single autocommit statement
UPDATE pp.examination SET frozen_yn = 'Y' WHERE exam_id = $1;
```
No existence check — freezing a non-existent `examId` still returns `200 {message:"✅ Exam frozen successfully"}` (0 rows affected is not checked).

```sql
-- getexamcentresview() (examModels.js:318-321)
select *from pp.pp_exam_centre
```
Returns **every** centre (active and inactive) with **every** column — unlike `getExamCentres()` which filters `active_yn='Y'` and projects only 2 columns. Two different centre-listing endpoints with materially different shapes — do not conflate `/exam-centres` (id+name, active only) with `/viewcentres` (all columns, all rows) when porting.

### 2.5 Assign applicants to an existing exam (`assignApplicantsToExam`, examControllers.js:1605-1791)

Body: `{division, educationDistrict, blocks:[], academicYear}`. Required: `examId` (path) + `division` + `educationDistrict` + non-empty array `blocks`. 400 if missing (`{error:"Missing required fields: examId, division, educationDistrict, blocks[]"}`).

Runs inside `pool.connect()` + `BEGIN`/`COMMIT`/`ROLLBACK` (genuinely transactional, single client used throughout):

```sql
-- 1. verify exam exists, fetch its exam_year
SELECT exam_id, exam_year FROM pp.examination WHERE exam_id = $1;
-- 404 {error:"Exam does not exist."} if 0 rows
```

```sql
-- 2. fetch shortlisted applicants for the division/edu-district/blocks/year
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
WHERE division_juris.juris_code = $1
  AND edu_district_juris.juris_code = $2
  AND block_juris.juris_code = ANY($3)
  AND asi.shortlisted_yn = 'Y'
  AND sb.shortlisted_year = $4
-- params: [division, educationDistrict, blocks, examYear] -- examYear here is
-- examination.exam_year (varchar), bound against shortlist_batch.shortlisted_year
-- (numeric(4,0)) -- Postgres implicitly casts the text parameter to numeric for
-- the comparison. If exam_year is null (see §2.4 orphan-exam quirk) this WHERE
-- clause becomes `sb.shortlisted_year = NULL` => always false => 404 below.
```
0 rows → `ROLLBACK`, `404 {message:"No shortlisted applicants found for the selected region."}`.

```sql
-- 3. per-applicant, atomically bump a per-(academicYear, juris_code) sequence
INSERT INTO pp.hall_ticket_sequence (academic_year, juris_code, last_sequence)
VALUES ($1, $2, 1)
ON CONFLICT (academic_year, juris_code)
DO UPDATE SET last_sequence = pp.hall_ticket_sequence.last_sequence + 1
RETURNING last_sequence;
-- $1 = academicYear from REQUEST BODY (e.g. "2026-27", NOT examYear/exam.exam_year!)
-- $2 = applicant.juris_code (numeric juris_code of the applicant's EDUCATION
--      DISTRICT from the query above, NOT the block) -- bound into a
--      varchar(20) column; Postgres casts numeric->text automatically.
```
**Two distinct "year" values are in play**: `exam.exam_year` (fetched from DB, used to filter shortlisted applicants) vs. `academicYear` (raw request body field, used for hall-ticket numbering/sequencing). They are supposed to represent the same academic year but are never cross-validated — a caller could assign against exam year "2025" while generating hall tickets keyed to academic year "2026-27" sequences. Reproduce both fields distinctly; do not collapse to one.

```js
// generateHallTicket(sequenceNumber, juris_code, academicYear) (examControllers.js:1720-1735)
const yearSuffix = academicYear.slice(2, 4);              // "2026-27" -> "26"
const jurisLast2 = juris_code.toString().slice(-2).padStart(2, "0");
const sequence = sequenceNumber.toString().padStart(4, "0");
return `${yearSuffix}${jurisLast2}${sequence}`;           // e.g. "26" + "21" + "0007" = "26210007"
```
Throws if any of `juris_code`/`sequenceNumber`/`academicYear` is falsy — inside the loop this would reject the whole transaction (caught by outer try/catch → ROLLBACK, 500).

```sql
-- 4. insert one hall-ticket row per applicant
INSERT INTO pp.applicant_exam (applicant_id, exam_id, pp_hall_ticket_no)
VALUES ($1, $2, $3)
ON CONFLICT (applicant_id, exam_id) DO NOTHING;
-- PK is (applicant_id, exam_id) -- re-assigning the same applicant to the same
-- exam is a silent no-op (no error), but the hall_ticket_sequence STILL
-- incremented in step 3 for that applicant even though no row was written --
-- i.e. re-running assign-students on an already-assigned cohort burns sequence
-- numbers without using them (a gap, not a collision -- reproduce faithfully,
-- do not "fix" by skipping the sequence bump).
```
COMMIT; response `201 {message, examId, totalAssigned, applicants:[{applicant_id, applicant_name, hall_ticket_no}]}`.

**Dead/legacy sibling**: `createExamAndAssignApplicants` (examControllers.js:398-505, exported but unreachable — its route is commented out) does something similar but generates PDFs synchronously per applicant with a **buggy** `generateHallTicket(applicant.applicant_id, examId)` call (that 2-arg function is never defined in this scope — it would throw `ReferenceError` if ever invoked, since the working `generateHallTicket(sequenceNumber, juris_code, academicYear)` is a different, later-defined 3-arg function scoped inside `assignApplicantsToExam`). Confirms this whole block is truly dead; do not port.

### 2.6 Generate student list (XLSX calling list) — `generateStudentList` (examControllers.js:509-816)

```sql
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
WHERE ae.exam_id = $1
ORDER BY api.student_name
```
404 `{message:"No students found for this exam."}` if empty. Otherwise: builds an in-memory XLSX (library **`xlsx`/SheetJS**, `XLSX.utils.aoa_to_sheet` + manual cell styling) with:
- Sheet 1 "Student Calling List": a header info block (title, exam name/date/time/centre/contact person/generated-on) then a 10-column table `Sl.No, NMMS Reg.No, Hall Ticket No, Student Name, School Name, Block Name, Contact No.1, Contact No.2, GMAT Score, SAT Score`, with conditional cell coloring (green if score≥70, red otherwise) and a trailing `Total Students: N` row.
- Sheet 2 "Score Summary" (only added if any row has a gmat/sat score): min/max/avg for each score type + counts.
- **Writes to disk**: `path.join(process.env.FILE_STORAGE_PATH, "Admission", "Exam", "callinglists", "<exam_name_with_underscores>_Calling_List.xlsx")`, throws if `FILE_STORAGE_PATH` env var unset. Sends via `res.download(filePath, fileName, cb)`, then `setTimeout(1000ms)` deletes the file. **This is real filesystem persistence (temporary), unlike the merge module's in-memory CSV generation** — the Java port needs an equivalent temp-file (or in-memory `ByteArrayOutputStream` via Apache POI, no disk write needed) — recommend switching to in-memory streaming in Java since the disk round-trip serves no purpose here (file is deleted moments later) as long as concurrent request safety is preserved (two simultaneous requests for the *same* exam name would collide on the same temp filename in Node; POI + `ByteArrayOutputStream` sidesteps this entirely).

### 2.7 Hall tickets — PDF generation & download

Two nearly-identical PDF builders share one core function `generateStudentPDF(student, ticketPath, assets)` (examControllers.js:961-1361), using **`pdfkit`**. Both callers pass rows with the same column set: `pp_hall_ticket_no, student_name, nmms_reg_number, district AS juris_code (unused inside the PDF body), pp_exam_centre_name, exam_date, exam_name, exam_start_time, exam_end_time, latitude, longitude, address, village, pincode`.

```sql
-- downloadAllHallTickets(examId, exam_name) — ZIP of all students in the exam
SELECT
  ae.pp_hall_ticket_no, api.student_name, api.nmms_reg_number,
  api.district AS juris_code,
  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
  ec.latitude, ec.address, ec.village, ec.pincode, ec.longitude
FROM pp.applicant_exam ae
JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
JOIN pp.examination e ON ae.exam_id = e.exam_id
JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
WHERE ae.exam_id = $1
```
404 `{message:"No hall tickets found"}` if empty. Otherwise: for each row, generates a per-student PDF to a temp file under `FILE_STORAGE_PATH/Admission/Exam/halltickets/<sanitized name>_<sanitized hallticket>.pdf`, adds it to an **`archiver`** zip stream piped directly to the HTTP response (`Content-Type: application/zip`, `Content-Disposition: attachment; filename=All_Hall_Tickets_<examId>_<sanitized exam_name>.zip`), deletes the temp PDFs on `archive.on("end")`. Filename sanitizer: `name.replace(/[<>:"/\\|?*]/g, "_").substring(0,100)`. **Requires 5 static asset files to exist on disk** (checked up front, else `500` with `"Missing required file: <path>"`): two logo PNGs, a Kannada TTF font, an authority-signature PNG, and a stamp PNG, all under `server/public/{assets,fonts}/...`. These MUST ship with the Java app (classpath resources) and the exact filenames/paths preserved: `assets/rcf_logo-removebg-preview.png`, `assets/logo.png`, `fonts/NotoSansKannada-Regular.ttf`, `assets/ravi_sir_sign-removebg-preview.png`, `assets/rcf_stamp-removebg-preview.png`.

```sql
-- singlestudentdownloadhallticket(hallTicketNo) — PUBLIC single-PDF download
SELECT
  ae.pp_hall_ticket_no, api.student_name, api.district AS juris_code,
  ec.pp_exam_centre_name, e.exam_date, e.exam_name, e.exam_start_time, e.exam_end_time,
  ec.latitude, ec.longitude, ec.address, ec.village, ec.pincode, api.nmms_reg_number
FROM pp.applicant_exam ae
JOIN pp.applicant_primary_info api ON ae.applicant_id = api.applicant_id
JOIN pp.examination e ON ae.exam_id = e.exam_id
JOIN pp.pp_exam_centre ec ON e.pp_exam_centre_id = ec.pp_exam_centre_id
WHERE ae.pp_hall_ticket_no = $1
```
400 if `hallTicketNo` param empty (never actually reachable since it's a required path segment). 404 `{message:"Hall ticket not found"}` if no row. On success: writes single PDF to `FILE_STORAGE_PATH/Admission/Exam/temp_halltickets/<hallTicketNo>.pdf`, sends via `res.download`, deletes after. **This is the endpoint the public student-facing page (`StudentHallticketPage.js`) calls with `responseType:"blob"`** — confirms it is meant to be public and unauthenticated by design.

**PDF content/layout (identical for both endpoints, `generateStudentPDF`)**: A4, 50pt margin, `lang:'kn'`. Outer border rect; header box with left/right logo images ("RAJALAKSHMI CHILDREN FOUNDATION" / "PRATIBHA POSHAK EXAMINATION - 2026" titles + address/contact text, hardcoded); "HALL TICKET" title box; Student Details box (name, hall ticket no, NMMS register no, plus a photo placeholder box "Passport Photo 3.5cm × 4.5cm"); Exam Center Details box (name + address, with a clickable Google-Maps hyperlink built from `student.latitude`/`longitude` when present, else plain text); Exam Date&Time box + Reporting Time box (formatted via local `formatDate`/`formatTimeManual` helpers — 12-hour AM/PM, `DD-MM-YYYY`); a Kannada-language instructions block (9 fixed instruction lines, requires the Kannada TTF registered via `doc.registerFont('Kannada', ...)`); four signature boxes (Authority w/ signature image, Invigilator, Student, Official Seal w/ stamp image). All hardcoded strings including "PRATIBHA POSHAK EXAMINATION - 2026", address, phone numbers — **these must be reproduced verbatim, not parameterized**, unless the product owner wants them made configurable (flag for the plan, don't silently hardcode a different year).

## 3. Table DDL Facts

**`pp.pp_exam_centre`** (`live-schema.sql:1295-1320`)
```
pp_exam_centre_id  numeric(10,0) DEFAULT nextval('pp.pp_exam_centre_seq') NOT NULL  -- PK
pp_exam_centre_code varchar(20)
pp_exam_centre_name varchar(200) NOT NULL
address varchar(200); village varchar(100); pincode varchar(12)
contact_person varchar(100); contact_phone varchar(12); contact_email varchar(200)
sitting_capacity integer  CHECK (>= 0)
active_yn char(1) DEFAULT 'Y' NOT NULL  CHECK (IN 'Y','N')
latitude numeric(15,2); longitude numeric(15,2)
created_at/updated_at timestamp DEFAULT now(); created_by/updated_by numeric(8,0)
google_map_link text GENERATED ALWAYS AS (
  CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL
  THEN 'https://www.google.com/maps/search/?api=1&query=' || replace(name,' ','%20') || '%20' || latitude || ',' || longitude
  ELSE NULL END) STORED   -- a real Postgres generated column; NEVER write to it, SELECT * will include it
```
Constraints: `pp_exam_centre_pkey PRIMARY KEY (pp_exam_centre_id)`; `pp_exam_centre_pp_exam_centre_code_key UNIQUE (pp_exam_centre_code)` — **note the actual constraint name**; the Node code's `error.constraint === 'pp_exam_centre_code_key'` (examControllers.js:170) **never matches** (real name has the `pp_exam_centre_` prefix twice: `pp_exam_centre_pp_exam_centre_code_key`), so that specific-duplicate-message branch is dead code. There is **no unique constraint at all** on `pp_exam_centre_name`, `contact_phone`, or `contact_email` — the corresponding `error.constraint === 'pp_exam_centre_name_key'` / `'contact_phone_key'` / `'contact_email_key'` branches are *also* dead (those constraints don't exist in the schema). In practice duplicates are caught only by the pre-insert `checkExistingCentre` SELECT (§2.1) — a TOCTOU race is possible and would fall through to the generic `500 {message:"Failed to create centre"}`, not a 409. **Do not port fictitious constraint-name matching** — either add real unique constraints in a migration (out of scope unless requested) or rely solely on the pre-check + accept the race.

**`pp.examination`** (`live-schema.sql:907-921`)
```
exam_id numeric(14,0) DEFAULT nextval('pp.examination_seq') NOT NULL  -- PK (examination_pkey)
exam_name varchar(100) NOT NULL
exam_date date NOT NULL
exam_start_time time NOT NULL; exam_end_time time NOT NULL
pp_exam_centre_id numeric(10,0)   -- FK not declared in this dump snippet check but centre join assumes it
frozen_yn char(1) DEFAULT 'N'  CHECK (IN 'Y','N')
created_at/updated_at timestamp DEFAULT now(); created_by/updated_by numeric(8,0)
exam_year varchar(10)    -- NOTE: nullable, no CHECK/format constraint; stores "YYYY" strings by convention only
```

**`pp.applicant_exam`** (`live-schema.sql:84-91`, constraints at `2718-2722`, `3046-3050`, `3205-3225`)
```
applicant_id numeric(14,0) NOT NULL
exam_id numeric(14,0) NOT NULL
pp_hall_ticket_no varchar(20)
PRIMARY KEY (applicant_id, exam_id)               -- pk_applicant_exam
UNIQUE (pp_hall_ticket_no)                          -- unique_hall_ticket
FK applicant_id -> pp.applicant_primary_info(applicant_id)
FK exam_id -> pp.examination(exam_id)
```
`ON CONFLICT (applicant_id, exam_id) DO NOTHING` (assign-students) relies on the composite PK; `pp_hall_ticket_no` has its own separate UNIQUE constraint, so a hall-ticket-number collision (extremely unlikely given the algorithm, but possible if sequences reset) would throw a `23505` uncaught by any specific handling — falls to generic `500 {message:"Server error", error: error.message}`.

**`pp.hall_ticket_sequence`** (`live-schema.sql:959-967`, constraints `2638-2650`)
```
id integer DEFAULT nextval('pp.hall_ticket_sequence_id_seq') NOT NULL  -- PK
academic_year varchar(9) NOT NULL
juris_code varchar(20) NOT NULL           -- NOTE: text, but jurisdiction.juris_code is numeric(12,0);
                                            -- Node passes the numeric juris_code straight through, pg
                                            -- casts numeric->text implicitly on insert/upsert
last_sequence integer DEFAULT 0 NOT NULL
UNIQUE (academic_year, juris_code)          -- hall_ticket_sequence_academic_year_juris_code_key -- the ON CONFLICT target
```

**`pp.jurisdiction`** (`live-schema.sql:1177-1189`)
```
juris_code numeric(12,0) NOT NULL   -- no PK declared in this excerpt but functions as one
juris_name varchar(100); juris_type varchar(100); parent_juris numeric(12,0)
created_at/updated_at/created_by/updated_by as usual
```

**`pp.applicant_primary_info`** (`live-schema.sql:166-194`, relevant columns only)
```
applicant_id numeric(14,0) PK-like (seq default)
nmms_year numeric(4,0); nmms_reg_number numeric(11,0) NOT NULL
app_state/district/nmms_block numeric(12,0)
student_name/father_name/mother_name varchar(100)
gmat_score/sat_score numeric(2,0)
contact_no1/contact_no2 varchar(12)
current_institute_dise_code/previous_institute_dise_code varchar(15)
students_sats_id numeric(11,0)
```

**`pp.applicant_shortlist_info`** (`live-schema.sql:328-338`) — `applicant_id numeric(14,0)`, `shortlisted_yn char(1) CHECK IN('Y','N')`, `shortlist_batch_id numeric(6,0)`.

**`pp.shortlist_batch`** (`live-schema.sql:1387-1398`) — `shortlist_batch_id numeric(6,0) PK`, `shortlisted_year numeric(4,0) NOT NULL`, `frozen_yn`/`medium_filtered_yn char(1)`.

**`pp.institute`** (`live-schema.sql:1064-1093`, relevant columns) — `dise_code varchar(15)`, `institute_name varchar(200)`, `juris_code numeric(12,0)`.

## 4. Response Shapes & Status Codes

| Endpoint | Success | Failure |
|---|---|---|
| GET `/exam-centres` | `200 [{pp_exam_centre_id, pp_exam_centre_name}, ...]` (bare array; `pp_exam_centre_id` comes back as a **string** — numeric via node-pg) | `500 {error:"Failed to fetch exam centres"}` |
| POST `/exam-centres` | `201 {success:true, message:"Exam centre created successfully", centre:{...full row incl. google_map_link...}}` | `400 {message}` (validation) / `409 {message, field}` (dup, pre-check or the — mostly dead — `23505` branch) / `500 {message:"Failed to create centre"}` |
| DELETE `/exam-centres/:id` | `204` (no body) | `400 {message}` if centre in use / `500 {message:"Failed to delete centre"}` |
| PUT `/exam-centres/:id` | `200 {message:"Updated successfully", centre:{...}}` | `404 {message:"Centre not found"}` (0 rows updated) / `500 {message:"Update failed", error}` |
| GET `/divisions-by-state/:id` etc. (4 endpoints) | `200 [{id, name}, ...]` (bare array; `id` is a **string**) | `500 {error:"Internal Server Error"}` |
| GET `/used-blocks?year=` | `200 [12345, 67890, ...]` bare array of **JS numbers** (only endpoint in this module that returns numeric-typed array elements, not strings) | `500 {error:"Failed to fetch used blocks"}` |
| GET `/notassigned?year=` | `200 [{exam_id, exam_name, exam_date, frozen_yn, pp_exam_centre_id, pp_exam_centre_name, exam_start_time, exam_end_time}, ...]` | `400 {message:"Year is required"}` / `500 {message:"Failed to fetch exams"}` |
| GET `/assigned?year=` | `200 [{...same 8 fields..., district_ids[], district_names[], block_ids[], block_names[]}, ...]` (arrays of strings) | same as above |
| DELETE `/:examId` | `200 {message:"Exam and related data deleted successfully"}` (even if examId didn't exist — no existence check, DELETEs simply affect 0 rows) | `500 {message:"Failed to delete exam"}` |
| GET `/:examId/student-list` | `200`, binary XLSX stream, `Content-Disposition: attachment; filename="<exam>_Calling_List.xlsx"` (set by `res.download`) | `404 {message:"No students found for this exam."}` / `500 {message:"Failed to generate Excel file", error}` |
| GET `/:examId/:exam_name/download-all-hall-tickets` | `200`, binary ZIP, `Content-Type: application/zip`, `Content-Disposition: attachment; filename=All_Hall_Tickets_<id>_<name>.zip` | `404 {message:"No hall tickets found"}` / `500 {message:"Failed to download hall tickets", error}` (also thrown if the 5 static asset files are missing) |
| PUT `/:examId/freeze` | `200 {message:"✅ Exam frozen successfully"}` (unconditional, no existence check) | `500 {message:"Failed to freeze exam"}` |
| POST `/create` (createExamOnly) | `201 {message:"Exam created successfully", examId}` (numeric id — check whether string or number: `result.examId` comes straight off `insertResult.rows[0].exam_id`, a node-pg numeric→string, so **string**) | `400 {error:"Missing required fields."}` / `409 {error:"Time conflict", message}` / `500 {message:"Server error", error}` |
| POST `/:examId/assign-students` | `201 {message:"Applicants assigned to exam successfully ✅", examId, totalAssigned, applicants:[{applicant_id, applicant_name, hall_ticket_no}]}` | `400 {error:"Missing required fields: ..."}` / `404 {error:"Exam does not exist."}` or `{message:"No shortlisted applicants found for the selected region."}` (inconsistent key: `error` vs `message` between the two 404s — reproduce both exactly) / `500 {message:"Server error", error}` |
| GET `/viewcentres` | `200 [{...every pp_exam_centre column...}, ...]` | **no error response at all** — the catch block calls `console("failed...")` (`console` is not callable as a function! `TypeError: console is not a function`) and never calls `res.*` — **the request hangs with no response until client/proxy timeout**. This is a genuine bug: any DB error here leaves the HTTP response unresolved. Must fix in Java (send a proper 500) rather than reproduce the hang. |
| GET `/hallticket/:hallTicketNo` | `200`, binary PDF, headers set explicitly (`Content-Type: application/pdf`, `Content-Disposition: attachment; filename="<hallTicketNo>.pdf"`) then also passed to `res.download` (which sets its own Content-Disposition — the explicit `setHeader` calls are redundant/overwritten by `res.download`, but filename ends up the same either way) | `400 {message:"Hall Ticket Number is required"}` (dead — route always has the param) / `404 {message:"Hall ticket not found"}` / `500 {message:"Failed to download hall ticket", error}` |
| GET `/count?centreId&date` | **always `500 {error:"Internal server error"}`** — see §1 | n/a |

## 5. File-Generating Endpoints (summary)

| Endpoint | Library | Output | Disk write? |
|---|---|---|---|
| `/:examId/student-list` | `xlsx` (SheetJS) | .xlsx (2 sheets, styled cells) | Yes — writes to `FILE_STORAGE_PATH/Admission/Exam/callinglists/`, streamed via `res.download`, deleted 1s after |
| `/:examId/:exam_name/download-all-hall-tickets` | `pdfkit` (per student) + `archiver` (zip) | .zip of N PDFs | Yes — per-student temp PDFs under `FILE_STORAGE_PATH/Admission/Exam/halltickets/`, deleted on `archive.on('end')`; zip itself is streamed directly to the response, never touches disk |
| `/hallticket/:hallTicketNo` | `pdfkit` | single .pdf | Yes — temp file under `FILE_STORAGE_PATH/Admission/Exam/temp_halltickets/`, streamed via `res.download`, deleted after |

**Java port recommendation**: Apache POI (already used for admission bulk-upload per Phase 2a) for the XLSX; a Java PDF library (e.g. **OpenPDF** or **Apache PDFBox** — new dependency, does not exist yet in the imas-backend POM) for hall tickets, generating directly to a `ByteArrayOutputStream`/`StreamingResponseBody` to avoid disk I/O and the temp-file cleanup dance entirely. Kannada text rendering requires embedding the `NotoSansKannada-Regular.ttf` font (must ship as a classpath resource) with a PDF library that supports embedding TrueType fonts with complex script shaping (verify OpenPDF/PDFBox render Kannada glyphs correctly — this is a real risk item, test early with a golden PDF/text-extraction comparison).

**Confirmed PUBLIC by design**: `/hallticket/:hallTicketNo` is called directly from `StudentHallticketPage.js` (a public, unauthenticated page per the client Router) with no Authorization header — this is the one endpoint that should explicitly bypass `@PreAuthorize`/`SecurityConfig` in the Java port, matching RESUME-migration.md's "Public only: `/api/auth/*` and (later) `/api/exams/hallticket/**`" note. All other 19 endpoints in this module currently have no auth in Node but per convention #7 should get `@PreAuthorize("hasRole('ADMIN')")` in the port (new, intended hardening).

## 6. Transactions

| Handler | Mechanism | Genuinely atomic? |
|---|---|---|
| `createExamCentre` / `updateExamCentre` / `removeExamCentre` | single `pool.query` calls | autocommit, no transaction needed |
| `addExamCentre` insert | single `pool.query` | autocommit |
| `addcreateExamonly` (createExamOnly) | `pool.connect()` + `BEGIN`/`COMMIT`/`ROLLBACK` on one client | **Yes** — real transaction |
| `assignApplicantsToExam` | `pool.connect()` + `BEGIN`/`COMMIT`/`ROLLBACK` on one client, loop of per-applicant INSERTs inside | **Yes** — real transaction; all sequence bumps + inserts roll back together on any failure |
| `deleteExamById` | `pool.query("BEGIN")`, `pool.query("DELETE ...")` ×2, `pool.query("COMMIT")` — **using the shared `pool` directly, not a single checked-out client** | **NOT actually transactional** — each `pool.query` call may be served by a *different* connection from the pool; `BEGIN`/`COMMIT` sent on arbitrary/possibly-different connections have no atomicity guarantee across the two DELETEs, and if an error is thrown between them there is no ROLLBACK at all (no try/catch in this function — an exception would propagate uncaught to the controller's own try/catch, leaving whatever DELETEs already committed as committed, with no compensating rollback of the first DELETE). **This is a real bug to consciously fix (use a proper single-connection transaction) or explicitly decide to preserve as best-effort two-step delete** — recommend fixing in Java via `@Transactional` on a dedicated repository method (per RESUME-migration.md convention #8), since preserving the bug offers no compatibility benefit (nothing depends on it) and only risks orphaned `applicant_exam` rows if the second DELETE fails. |
| `createExamAndAssignApplicants` (dead) | `pool.connect()` + transaction | N/A — unreachable code, do not port |

## 7. Quirks & Complexity Warnings (ranked hardest-to-port first)

1. **`/api/exams/count` is permanently broken** (`examRoutes.js:69`, `db.query` where `db` is never imported — only `pool` is). Always 500s. No frontend caller found. **Decision needed**: drop it entirely from the Java port (recommended — it's dead weight), or implement it correctly as `SELECT COUNT(*) FROM pp.exam WHERE pp_exam_centre_id=$1 AND exam_date=$2` if a future caller needs it. Note the SQL as written also references a nonexistent table `pp.exam` (should probably be `pp.examination`) — a second latent bug layered on top of the `db`-undefined bug, so even fixing the `db`→`pool` typo would not make this endpoint work without also fixing the table name and adding the correct WHERE column (`pp_exam_centre_id` does exist on `examination`).
2. **`deleteExamById` is not really transactional** (§6) — uses `pool.query("BEGIN"/"COMMIT")` without a checked-out client. Must be reimplemented with a real transaction in Java; this is a deliberate improvement, not a parity requirement, since Node's version provides no real atomicity to match.
3. **Two different "year" representations collide without validation** in `assignApplicantsToExam`: `exam.exam_year` (DB, filters shortlist eligibility) vs. request-body `academicYear` (used for hall-ticket sequence numbering/format). An exam created with `exam_year=NULL` (allowed — `academic_year` is optional in `createExamOnly`) makes `/assigned`/`/notassigned` silently exclude it forever, and makes `assignApplicantsToExam`'s shortlist-eligibility filter always return 0 rows (`sb.shortlisted_year = NULL`). Decide explicitly whether to require `academic_year` at exam-creation time in the Java port (recommended) or faithfully reproduce the NULL-orphan possibility.
4. **`hall_ticket_sequence.juris_code` is `varchar(20)` while `jurisdiction.juris_code` is `numeric(12,0)`** — Node relies on implicit numeric→text casting on write and does `juris_code.toString().slice(-2)` for the hall-ticket-number suffix. Java/JdbcClient must format the numeric juris_code as a plain decimal string (no leading zero padding beyond the existing `.padStart(2,'0')` on the *last two digits*, not the whole code) before using it as the varchar key/format input — get this exactly right, since a mismatched cast changes generated hall-ticket numbers.
5. **Hall ticket sequence "gap not collision" on `ON CONFLICT (applicant_id, exam_id) DO NOTHING`** (§2.5 step 4) — re-running assign for an already-assigned cohort still burns `hall_ticket_sequence.last_sequence` even though no `applicant_exam` row is written (no-op INSERT). Numbers become non-contiguous but never reused/duplicated — preserve this behavior (it's intentional-by-accident but changing it to "peek before increment" would require restructuring the atomic upsert and is out of scope unless requested).
6. **`GET /viewcentres` swallows all errors into a hang** (`console("...")` — `TypeError`, response never sent) — a genuine bug (not a parity target); the Java equivalent must return a proper `500` on any DB failure.
7. **Fictitious duplicate-constraint names in `createExamCentre`'s `catch` block** (§3) — `error.constraint === 'pp_exam_centre_code_key'` / `'pp_exam_centre_name_key'` / `'contact_phone_key'` / `'contact_email_key'` never match any real constraint (only `pp_exam_centre_pp_exam_centre_code_key` exists, and only for the code column). In practice the pre-insert `checkExistingCentre` SELECT does the real duplicate detection; the DB-constraint-based fallback is effectively dead code protecting against a race condition it can't actually classify. In Java, either (a) reproduce as dead code for pure parity (simplest, matches current behavior including the race-condition gap), or (b) add real unique indexes + correct constraint-name matching as a deliberate improvement — flag this choice for the plan.
8. **Response numeric-typing inconsistency**: almost every id here follows the "numeric → string" convention (matches RESUME-migration.md convention #3), EXCEPT `/used-blocks` which explicitly does `.map(row => Number(row.nmms_block))` producing **JSON numbers**. This is the one deliberate exception in this module — do not "fix" it to a string for consistency; the frontend (`CreateExamHooks.js:168`, `usedBlocks.includes(Number(b.id))`) depends on numeric comparison.
9. **`updateExamCentre`'s `active_yn || 'Y'` fallback** (§2.1) silently resets `active_yn` to `'Y'` on any falsy/omitted value in a PUT — including a client that intended to leave a currently-inactive centre inactive but forgot to include the field. Reproduce exactly (do not add a "preserve existing value if omitted" fix) unless explicitly asked to.
10. **Dead/duplicate route registration**: `router.post("/:examId/assign-students", assignApplicantsToExam)` appears twice on one source line (`examRoutes.js:59`). Harmless in Express (2nd copy unreachable since handler doesn't call `next()`), but do not port the duplicate `@PostMapping` — Spring would likely throw an "ambiguous mapping" error at startup if the same method+path is mapped twice on the same controller, so this must be de-duplicated, not reproduced.
11. **Dead legacy handler `createExamAndAssignApplicants`** (route commented out, and internally calls an undefined 2-arg `generateHallTicket` that would `ReferenceError` if ever invoked) — confirmed fully dead, must NOT be ported.
12. **Kannada PDF rendering fidelity** — the instructions block uses hardcoded Kannada Unicode strings rendered via an embedded TTF (`NotoSansKannada-Regular.ttf`). Verify the chosen Java PDF library correctly shapes/renders Kannada script (complex indic script with conjuncts) — this is the highest visual-fidelity risk item in the whole module; budget for a manual visual diff against a Node-generated reference PDF.
13. **Hardcoded institutional strings/year** in the PDF header ("RAJALAKSHMI CHILDREN FOUNDATION", "PRATIBHA POSHAK EXAMINATION - 2026", address, phone numbers) — reproduce verbatim; flag to product owner if the "2026" should instead be dynamic per `exam_year`/`academic_year` (currently it is not — it's a literal string).
14. **`google_map_link` is a Postgres STORED GENERATED column** on `pp.pp_exam_centre` — `SELECT *`/`RETURNING *` includes it; never attempt to INSERT/UPDATE it directly (Postgres will reject writes to a generated column). Row-mapper code must simply read it as a string (possibly NULL when lat/long absent).
