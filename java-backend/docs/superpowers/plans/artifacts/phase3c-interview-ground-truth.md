# INTERVIEW Module — Ground Truth (for Plan 3c)

Captured from a full read of the Node source. Base mount: `app.use("/api/interview", interviewRoutes)` (`server/index.js:315`; router required at `server/index.js:268`). Files: `server/routes/interviewRoutes.js` (95 lines, all live — no commented blocks), `server/controllers/interviewController.js` (625 lines, all live), `server/models/interviewModel.js` (1229 lines, all live). No global auth middleware wraps `/api` generically in `index.js` (only `studentSearchRoutes`, `jurisdictionRoutes`, `eventRoutes`, `userRoleRoutes`, `searchRoutes`, `tabInventoryRoutes` are separately mounted under `/api`) — `interviewRoutes` is mounted directly with **no auth middleware in front of it**, consistent with other un-hardened Node modules. Per RESUME conventions, Java should add `@PreAuthorize` (likely ADMIN for assignment; INTERVIEWER-scoped for `/students/:interviewerName`) as new intentional hardening.

**Quirk (dead code):** `InterviewModel.assignStudents` is defined **twice**, verbatim identical, at `interviewModel.js:294-428` and `interviewModel.js:430-564`. In a JS object literal the second definition silently wins (overwrites the first) — no functional bug, but do not port both; treat as one function. Only the second occurrence is "live" in the sense of being what actually executes, but they are byte-identical so it doesn't matter which you read.

## 1. Endpoint Inventory (16 routes under `/api/interview`)

Router file groups routes into 4 numbered sections (comments in source). Route registration order in `interviewRoutes.js`:

| # | Method | Path | Controller fn | Section |
|---|--------|------|----------------|---------|
| 1 | GET | `/students-for-verification` | `getStudentsForVerification` | 1. Home verification |
| 2 | POST | `/submit-home-verification` | `submitHomeVerification` (multer `.single('verificationDocument')`) | 1. Home verification |
| 3 | POST | `/download-assignment-report` | `downloadAssignmentReport` | 2. Report & geographic |
| 4 | GET | `/exam-centers` | `getExamCenters` | 2. Report & geographic |
| 5 | GET | `/states` | `getAllStates` | 2. Report & geographic |
| 6 | GET | `/divisions` | `getDivisionsByState` | 2. Report & geographic |
| 7 | GET | `/districts` | `getDistrictsByDivision` | 2. Report & geographic |
| 8 | GET | `/blocks` | `getBlocksByDistrict` | 2. Report & geographic |
| 9 | GET | `/interviewers` | `getInterviewers` | 3. Interviewer & assignment |
| 10 | GET | `/students/:interviewerName` | `getStudentsByInterviewer` | 3. Interviewer & assignment |
| 11 | GET | `/unassigned-students` | `getUnassignedStudents` | 3. Interviewer & assignment |
| 12 | GET | `/unassigned-students-by-block` | `getUnassignedStudentsByBlock` | 3. Interviewer & assignment |
| 13 | GET | `/reassignable-students` | `getReassignableStudents` | 3. Interviewer & assignment |
| 14 | GET | `/reassignable-students-by-block` | `getReassignableStudentsByBlock` | 3. Interviewer & assignment |
| 15 | POST | `/assign-students` | `assignStudents` | 3. Interviewer & assignment |
| 16 | POST | `/reassign-students` | `reassignStudents` | 3. Interviewer & assignment |
| 17 | POST | `/submit-interview` | `submitInterviewDetails` (multer `.single('file')`) | 4. Results submission |

That's **17 routes**, not 18 as originally estimated — verified count from the router file exhaustively (task brief said "~18", actual is 17).

**Route-ordering hazards:** None severe — there is no `/:id`-style catch-all in this router that could shadow a literal path (unlike shortlist's catch-all). The only parametrized route is `GET /students/:interviewerName` (route #10); it is registered **after** the literal `/students-for-verification` (route #1, different path entirely — no collision since `students-for-verification` is a full literal segment, not a sibling of `/students/:x`). No hazard in practice, but note for Spring `@GetMapping`: register `/students-for-verification` and `/students/{interviewerName}` as distinct mappings — Spring MVC handles this fine regardless of declaration order since they're different path shapes, but keep documented for parity notes.

**Multer wiring quirk:** the router installs a `router.use()` middleware (before routes 1-17) that pulls `req.app.get('multerUpload')` into `req.uploadMiddleware`, logging an error if missing. Each of the two upload routes (`/submit-home-verification`, `/submit-interview`) then wraps `req.uploadMiddleware.single(fieldName)(...)` in an inline function with its own multer-error handler (`400 {message: "File upload failed: ..."}`). Field names: `verificationDocument` for home verification, `file` for interview submission. Disk storage config lives in `server/index.js:199-243` (`dynamicUploadStorage`) — destination directory depends on `file.fieldname` (`verificationDocument` → `Home-verification-data/cohort-<nmmsYear>`, else → `Interview-data/cohort-<nmmsYear>`) and filename is `${fieldname}-${applicantId}-${Date.now()}${ext}`. `nmmsYear`/`applicantId` are read from `req.body` at destination-resolution time (multer runs `destination`/`filename` callbacks before the rest of the body may be fully parsed for multipart — but since Express parses fields before files by declared order in typical multipart bodies, this works if the client puts `nmmsYear`/`applicantId` fields before the file field in the FormData).

## 2. Exact SQL (verbatim) — read/dropdown queries

### `getExamCenters()` (interviewModel.js:10-22)
```sql
SELECT pp_exam_centre_id, pp_exam_centre_name
FROM pp.pp_exam_centre
ORDER BY pp_exam_centre_name ASC;
```
No params.

### `getAllStates()` (interviewModel.js:24-36)
```sql
SELECT juris_code, juris_name
FROM pp.jurisdiction
WHERE LOWER(juris_type) = 'state';
```
No params.

### `getDivisionsByState(stateName)` (interviewModel.js:38-58)
```sql
SELECT juris_code, juris_name
FROM pp.jurisdiction AS division
WHERE division.parent_juris IN (
  SELECT state.juris_code
  FROM pp.jurisdiction AS state
  WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM($1))
)
AND LOWER(division.juris_type) = 'division';
```
`$1 = stateName` (from `req.query.stateName`).

### `getDistrictsByDivision(divisionName)` (interviewModel.js:60-80)
```sql
SELECT juris_code, juris_name
FROM pp.jurisdiction AS district
WHERE district.parent_juris IN (
  SELECT division.juris_code
  FROM pp.jurisdiction AS division
  WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM($1))
)
AND LOWER(district.juris_type) = 'education district';
```
`$1 = divisionName` (from `req.query.divisionName`).

### `getBlocksByDistrict(stateName, divisionName, districtName)` (interviewModel.js:82-129)
```sql
SELECT
    j.juris_code,
    j.juris_name,
    CASE
        WHEN j.juris_code IN (
            SELECT sbj.juris_code
            FROM pp.shortlist_batch_jurisdiction AS sbj
            JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
            WHERE sb.frozen_yn = 'Y'
        )
        THEN TRUE ELSE FALSE
    END AS is_frozen_block
FROM pp.jurisdiction AS j
WHERE LOWER(j.juris_type) = 'block'
    AND j.parent_juris IN (
        SELECT district.juris_code
        FROM pp.jurisdiction AS district
        WHERE LOWER(TRIM(district.juris_name)) = LOWER(TRIM($3))  -- District Name
          AND LOWER(district.juris_type) = 'education district'
          AND district.parent_juris IN (
            SELECT division.juris_code
            FROM pp.jurisdiction AS division
            WHERE LOWER(TRIM(division.juris_name)) = LOWER(TRIM($2))  -- Division Name
              AND LOWER(division.juris_type) = 'division'
              AND division.parent_juris IN (
                SELECT state.juris_code
                FROM pp.jurisdiction AS state
                WHERE LOWER(TRIM(state.juris_name)) = LOWER(TRIM($1))  -- State Name
                  AND LOWER(state.juris_type) = 'state'
              )
          )
    );
```
Params `[stateName, divisionName, districtName]` — **note param order is state, division, district** but used as `$1, $2, $3` matching that order in the nested WHERE (district uses `$3`, division `$2`, state `$1`). `is_frozen_block` flags blocks that belong to any **frozen** shortlist batch (`shortlist_batch.frozen_yn = 'Y'`) via `shortlist_batch_jurisdiction`.

### `getInterviewers()` (interviewModel.js:163-175)
```sql
SELECT interviewer_id, interviewer_name
FROM pp.interviewer
ORDER BY interviewer_name ASC;
```
No params. **No filter on `active_status`** — inactive interviewers are still returned.

### `getStudentsByInterviewer(interviewerName, nmmsYear)` (interviewModel.js:133-161)
```sql
SELECT
    a.student_name,
    a.applicant_id,
    s.interview_round
FROM pp.student_interview s
JOIN pp.interviewer i ON i.interviewer_id = s.interviewer_id
JOIN pp.applicant_primary_info a ON a.applicant_id = s.applicant_id
WHERE
    LOWER(TRIM(i.interviewer_name)) = LOWER(TRIM($1))
    AND a.nmms_year = $2
    AND UPPER(TRIM(s.status)) = 'SCHEDULED'
    AND s.interview_result IS NULL;
```
Params `[interviewerName, nmmsYear]`. Route param `:interviewerName` (path) + query `nmmsYear`.

### `getUnassignedStudents(centerName, nmmsYear)` (interviewModel.js:177-233)
```sql
WITH LatestInterview AS (
    SELECT
        si.applicant_id,
        si.interview_round,
        si.status,
        si.interview_result,
        ROW_NUMBER() OVER (
            PARTITION BY si.applicant_id
            ORDER BY si.interview_round DESC,
                     si.interview_date DESC NULLS LAST
        ) AS rn
    FROM pp.student_interview si
    JOIN pp.applicant_primary_info api_sub
        ON si.applicant_id = api_sub.applicant_id
    WHERE api_sub.nmms_year = $2
)
SELECT
    api.applicant_id,
    api.student_name,
    exam.pp_exam_score
FROM pp.applicant_primary_info api
JOIN pp.exam_results exam
    ON api.applicant_id = exam.applicant_id
    AND exam.pp_exam_cleared = 'Y'
    AND exam.interview_required_yn = 'Y'
JOIN pp.applicant_exam ap
    ON ap.applicant_id = exam.applicant_id
JOIN pp.examination e
    ON e.exam_id = ap.exam_id
JOIN pp.pp_exam_centre centre
    ON e.pp_exam_centre_id = centre.pp_exam_centre_id
LEFT JOIN LatestInterview li
    ON api.applicant_id = li.applicant_id
    AND li.rn = 1
WHERE
    LOWER(TRIM(centre.pp_exam_centre_name)) = LOWER(TRIM($1))
    AND api.nmms_year = $2
    AND (
        li.applicant_id IS NULL
        OR (
            TRIM(UPPER(li.status)) = 'RESCHEDULED'
            AND TRIM(UPPER(li.interview_result)) = 'ANOTHER INTERVIEW REQUIRED'
            AND li.interview_round < 3
        )
        OR TRIM(UPPER(li.status)) = 'CANCELLED'
    );
```
Params `[centerName, nmmsYear]`. **"Unassigned"** = (a) never interviewed at all, OR (b) latest interview round is RESCHEDULED with result "ANOTHER INTERVIEW REQUIRED" and round < 3 (needs next round), OR (c) latest round was CANCELLED (needs re-scheduling). `LatestInterview` CTE picks the single most-recent row per applicant via `ROW_NUMBER()` ordered by `interview_round DESC, interview_date DESC NULLS LAST`.

### `getUnassignedStudentsByBlock(stateName, districtName, blockName, nmmsYear)` (interviewModel.js:235-292)
Same `LatestInterview` CTE shape but **without** the `nmms_year` filter inside the CTE (computed over ALL years, then filtered outside) — a subtle divergence from `getUnassignedStudents`. Main query joins `pp.jurisdiction` three times (state/district/block) via `api.app_state`, `api.district`, `api.nmms_block`, filters by `nmms_year` at the outer level only:
```sql
WITH LatestInterview AS (
    SELECT
        si.applicant_id,
        si.interview_round,
        si.status,
        si.interview_result,
        ROW_NUMBER() OVER (
            PARTITION BY si.applicant_id
            ORDER BY si.interview_round DESC,
                     si.interview_date DESC NULLS LAST
        ) AS rn
    FROM pp.student_interview si
)
SELECT
     api.applicant_id,
     api.student_name,
     exam.pp_exam_score
FROM pp.applicant_primary_info api
JOIN pp.exam_results exam
    ON api.applicant_id = exam.applicant_id
    AND exam.pp_exam_cleared = 'Y'
    AND exam.interview_required_yn = 'Y'
LEFT JOIN LatestInterview li
    ON api.applicant_id = li.applicant_id
    AND li.rn = 1
LEFT JOIN pp.jurisdiction sj ON api.app_state = sj.juris_code
LEFT JOIN pp.jurisdiction dj ON api.district = dj.juris_code
LEFT JOIN pp.jurisdiction bj ON api.nmms_block = bj.juris_code
WHERE
    LOWER(TRIM(sj.juris_name)) = LOWER(TRIM($1))
    AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM($2))
    AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM($3))
    AND api.nmms_year = $4
    AND (
        li.applicant_id IS NULL
        OR (
            UPPER(TRIM(li.status)) = 'RESCHEDULED'
            AND UPPER(TRIM(li.interview_result)) = 'ANOTHER INTERVIEW REQUIRED'
            AND li.interview_round < 3
        )
        OR (
            UPPER(TRIM(li.status)) = 'CANCELLED'
        )
    );
```
Params `[stateName, districtName, blockName, nmmsYear]`. **Note the joins to `applicant_exam`/`examination`/`pp_exam_centre` present in `getUnassignedStudents` are ABSENT here** — by-block variant does not require the student to have sat a `pp_exam_centre`-linked exam, only that `exam_results` shows cleared+interview-required. This is a real behavioral divergence between the two "unassigned" queries — preserve both distinctly.

### `getReassignableStudents(centerName, nmmsYear)` (interviewModel.js:566-618)
```sql
SELECT
    api.applicant_id,
    api.student_name,
    inst.institute_name,
    exam.pp_exam_score,
    centre.pp_exam_centre_name,
    si.interview_round,
    i.interviewer_name AS current_interviewer,
    si.interviewer_id AS current_interviewer_id
FROM pp.applicant_primary_info api
JOIN pp.exam_results exam
    ON api.applicant_id = exam.applicant_id
JOIN pp.applicant_exam ap
    ON ap.applicant_id = exam.applicant_id
JOIN pp.examination e
    ON e.exam_id = ap.exam_id
JOIN pp.pp_exam_centre centre
    ON e.pp_exam_centre_id = centre.pp_exam_centre_id
LEFT JOIN pp.institute inst
    ON api.current_institute_dise_code = inst.dise_code
JOIN pp.student_interview si
    ON api.applicant_id = si.applicant_id
LEFT JOIN pp.interviewer i
    ON si.interviewer_id = i.interviewer_id
WHERE
    LOWER(TRIM(centre.pp_exam_centre_name)) = LOWER(TRIM($1))
    AND api.nmms_year = $2
    AND exam.pp_exam_cleared = 'Y'
    AND exam.interview_required_yn = 'Y'
    AND (UPPER(TRIM(si.status)) = 'SCHEDULED' OR UPPER(TRIM(si.status)) = 'RESCHEDULED')
    AND (UPPER(TRIM(si.interview_result)) = 'ANOTHER INTERVIEW REQUIRED' OR si.interview_result IS NULL)
    AND si.interview_round = (
        SELECT MAX(sub_si.interview_round)
        FROM pp.student_interview sub_si
        JOIN pp.applicant_primary_info sub_api
            ON sub_si.applicant_id = sub_api.applicant_id
        WHERE sub_si.applicant_id = si.applicant_id
            AND sub_api.nmms_year = $2
    )
ORDER BY api.student_name ASC;
```
Params `[centerName, nmmsYear]`. "Reassignable" = has a currently SCHEDULED/RESCHEDULED interview (not yet resolved to a terminal result), is on the latest round for that applicant, and either has no result yet or explicitly needs another interview.

### `getReassignableStudentsByBlock(stateName, districtName, blockName, nmmsYear)` (interviewModel.js:620-664)
Same shape as `getReassignableStudents` but joins jurisdiction (`sj`/`dj`/`bj`) instead of `pp_exam_centre`, and **uses `JOIN` (not `LEFT JOIN`) for all three jurisdiction tables** (student is excluded if any jurisdiction link is missing):
```sql
SELECT
    api.applicant_id,
    api.student_name,
    inst.institute_name,
    exam.pp_exam_score,
    si.interview_round,
    i.interviewer_name AS current_interviewer,
    si.interviewer_id AS current_interviewer_id
FROM pp.applicant_primary_info api
JOIN pp.exam_results exam ON api.applicant_id = exam.applicant_id
LEFT JOIN pp.institute inst ON api.current_institute_dise_code = inst.dise_code
JOIN pp.student_interview si ON api.applicant_id = si.applicant_id
LEFT JOIN pp.interviewer i ON si.interviewer_id = i.interviewer_id
JOIN pp.jurisdiction sj ON api.app_state = sj.juris_code
JOIN pp.jurisdiction dj ON api.district = dj.juris_code
JOIN pp.jurisdiction bj ON api.nmms_block = bj.juris_code
WHERE
    api.nmms_year = $4
    AND LOWER(TRIM(sj.juris_name)) = LOWER(TRIM($1))
    AND LOWER(TRIM(dj.juris_name)) = LOWER(TRIM($2))
    AND LOWER(TRIM(bj.juris_name)) = LOWER(TRIM($3))
    AND exam.pp_exam_cleared = 'Y'
    AND exam.interview_required_yn = 'Y'
    AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
    AND (UPPER(TRIM(si.interview_result)) = 'ANOTHER INTERVIEW REQUIRED' OR si.interview_result IS NULL)
    AND si.interview_round = (
        SELECT MAX(sub_si.interview_round)
        FROM pp.student_interview sub_si
        JOIN pp.applicant_primary_info sub_api ON sub_si.applicant_id = sub_api.applicant_id
        WHERE sub_si.applicant_id = si.applicant_id
            AND sub_api.nmms_year = $4
    )
ORDER BY api.student_name ASC;
```
Params `[stateName, districtName, blockName, nmmsYear]`.

### `getStudentsForVerification(nmmsYear)` (interviewModel.js:746-773)
```sql
SELECT
    a.student_name,
    a.applicant_id
FROM pp.student_interview s
JOIN pp.applicant_primary_info a
    ON a.applicant_id = s.applicant_id
WHERE
    UPPER(TRIM(s.home_verification_req_yn)) = 'Y'
    AND a.nmms_year = $1
    AND a.applicant_id NOT IN (
        SELECT applicant_id
        FROM pp.home_verification
    );
```
Params `[nmmsYear]`. Note `home_verification_req_yn` is `character(1)` (`'Y'`/`'N'`) — `TRIM` handles the bpchar's trailing-space padding, `UPPER` is defensive (data is already 'Y'/'N'). `applicant_id NOT IN (subquery)` — the subquery is un-filtered by year; if `home_verification.applicant_id` were ever NULL this pattern would return zero rows (classic NOT IN NULL trap), but `applicant_id` there is a FK to `applicant_primary_info` and non-NULL in practice.

## 3. The assignment algorithm — `assignStudents(applicantIds, interviewerId, nmmsYear)` (interviewModel.js:430-564, the live copy; identical dead copy at 294-428)

**Transactional** (`client.query("BEGIN")` ... `COMMIT`/`ROLLBACK` on any thrown error, `client.release()` in `finally`). Iterates `applicantIds` **one at a time in a for-loop** (no batching) — each iteration issues up to 4 sequential queries on the same client/transaction.

Per-applicant algorithm:

1. **Fetch last interview** (by round, not by date):
   ```sql
   SELECT interview_id, interview_round, status, interview_result
   FROM pp.student_interview
   WHERE applicant_id = $1
   ORDER BY interview_round DESC
   LIMIT 1;
   ```
   `$1 = applicantId`.

2. **If no prior interview row** → `nextRound = 1`, fall through to step 3 (duplicate-check) then step 4 (insert).

3. **If a prior interview exists**, normalize `status`/`interview_result` to `UPPERCASE().trim()` (JS-side, not SQL) and branch:
   - **(A) Max rounds:** `lastInterview.interview_round >= 3` → push `{applicantId, status:"Skipped", reason:"Max rounds reached (3 rounds completed)."}`, `continue` (no DB write this iteration).
   - **(B) Eligible for next round:** `status === "RESCHEDULED" && result === "ANOTHER INTERVIEW REQUIRED"` → `nextRound = lastInterview.interview_round + 1`; falls through to duplicate-check + insert (step 3b/4 below).
   - **(C) Fix a CANCELLED record (UPDATE in place, no new row):** `status === "CANCELLED"` →
     ```sql
     UPDATE pp.student_interview
     SET interviewer_id = $1,
         status = 'SCHEDULED'
     WHERE interview_id = $2
       AND applicant_id = $3
     RETURNING interview_round;
     ```
     params `[interviewerId, lastInterview.interview_id, applicantId]`. If `rowCount > 0` → push `{applicantId, status:"Assigned", interviewRound: lastInterview.interview_round}` and `continue` (skips duplicate-check/insert entirely for this applicant — reuses the same row, does NOT create a new round).
   - **(D) Anything else** (e.g. already SCHEDULED, or RESCHEDULED without the specific result, or a terminal SELECTED/REJECTED) → push `{applicantId, status:"Skipped", reason:"Current status (<STATUS>) or result (<RESULT|NONE>) does not allow reassignment."}`, `continue`.

3b. **Duplicate-assignment guard** (only reached for "no prior interview" or scenario B):
   ```sql
   SELECT 1 FROM pp.student_interview
   WHERE applicant_id = $1 AND interviewer_id = $2;
   ```
   params `[applicantId, interviewerId]` — checks **ANY row in ANY round**, not just the latest. If found → push `{applicantId, status:"Skipped", reason:"Already assigned to this interviewer in a previous round."}`, `continue`.

4. **Insert new round:**
   ```sql
   INSERT INTO pp.student_interview (interviewer_id, applicant_id, interview_round, status)
   SELECT $1, $2, $3, 'SCHEDULED'
   FROM pp.applicant_primary_info api
   WHERE api.applicant_id = $2 AND api.nmms_year = $4
   RETURNING interview_round;
   ```
   params `[interviewerId, applicantId, nextRound, nmmsYear]`. The `SELECT ... FROM pp.applicant_primary_info` guard means the INSERT is a no-op if the applicant doesn't exist for that `nmmsYear` (guards against year/applicant mismatch without a separate validation query). If `rowCount > 0` → push `{applicantId, status:"Assigned", interviewRound: insertRes.rows[0].interview_round}`; else → push `{applicantId, status:"Skipped", reason:"Student data not found for the specified year."}`.

Returns `{ results: [...] }` (one entry per input `applicantId`, in input order). Controller wraps as `{ message: "Assignment process completed.", results: modelResponse.results }`, `200` always (errors bubble to `500 {message:"Internal server error while assigning students."}`).

**No max-round check on interviewer duplication across rounds vs the specific-interviewer guard** — an applicant could be assigned to interviewer A in round 1, then (after being rescheduled) to interviewer B in round 2, and then round 3 back to... no, the duplicate-check would block reassigning to A again in round 3, but assigning to a brand-new interviewer C is always allowed regardless of round.

## 4. The reassignment algorithm — `reassignStudents(applicantIds, newInterviewerId, nmmsYear)` (interviewModel.js:666-743)

Transactional, same BEGIN/COMMIT/ROLLBACK/`finally release()` shape, same per-applicant for-loop (no batching).

- `isCancellation = String(newInterviewerId) === 'NO_ONE'` (module constant `NO_INTERVIEWER_ID = "NO_ONE"`, also duplicated in the controller file as its own local constant `NO_INTERVIEWER_ID` — same string literal, defined independently in both files).
- `numericNewInterviewerId = isCancellation ? null : Number(newInterviewerId)`.

**Cancellation branch** (`isCancellation === true`):
```sql
UPDATE pp.student_interview si
SET interviewer_id = NULL,
    status = 'CANCELLED'
FROM pp.applicant_primary_info api
WHERE si.applicant_id = api.applicant_id
  AND si.applicant_id = $1
  AND api.nmms_year = $2
  AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
RETURNING si.interview_round, si.status;
```
params `[applicantId, nmmsYear]`. Note: **no `LIMIT`** — if an applicant somehow has multiple SCHEDULED/RESCHEDULED rows (shouldn't happen given the assign algorithm, but not DB-enforced), ALL matching rows are cancelled in one statement, but only `rows[0]` is read for the response.

**Reassignment branch** (`isCancellation === false`):
```sql
UPDATE pp.student_interview si
SET interviewer_id = $1,
    status = 'RESCHEDULED'
FROM pp.applicant_primary_info api
WHERE si.applicant_id = $2
  AND api.applicant_id = si.applicant_id
  AND api.nmms_year = $3
  AND UPPER(TRIM(si.status)) IN ('SCHEDULED', 'RESCHEDULED')
  AND si.interview_result IS NULL
  AND si.interviewer_id IS DISTINCT FROM $1
RETURNING si.interview_round, si.status;
```
params `[numericNewInterviewerId, applicantId, nmmsYear]`. Sets status to **'RESCHEDULED' unconditionally** (even if it was already 'SCHEDULED') — this is the "hand this student to a different interviewer, same round" operation; it does NOT bump `interview_round` (that only happens via `assignStudents`'s scenario B once the interviewer marks the interview result as "ANOTHER INTERVIEW REQUIRED"). Guard `si.interviewer_id IS DISTINCT FROM $1` prevents a no-op reassignment to the same interviewer (NULL-safe comparison).

Both branches: if `rowCount > 0` → push `{applicantId, status: updateRes.rows[0].status, interviewRound: updateRes.rows[0].interview_round}` (status here is literally `'CANCELLED'` or `'RESCHEDULED'` as returned from the DB — **not** the string `"Assigned"` used by `assignStudents`). Else → push `{applicantId, status:"Skipped", reason: isCancellation ? "Already unassigned or not in a cancellable state" : "Student is already assigned to this interviewer or has a finalized result"}`.

Controller wraps: `{message:"Reassignment process completed.", results: modelResponse.results}`, `200`.

## 5. Table DDL facts (from `live-schema.sql`)

### `pp.student_interview` (live-schema.sql:1638-1667) — the assignment ledger
```sql
CREATE TABLE pp.student_interview (
    interview_id numeric(12,0) DEFAULT nextval('pp.interview_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    interviewer_id numeric(10,0),
    interview_date date,
    interview_time time without time zone,
    interview_mode character varying(20),
    interview_round integer,
    status character varying(15),
    life_goals_and_zeal numeric(3,1),
    commitment_to_learning numeric(3,1),
    integrity numeric(3,1),
    communication_skills numeric(3,1),
    interview_result character varying(50),
    home_verification_req_yn character(1) DEFAULT 'N'::bpchar,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    remarks character varying(500),
    doc_name character varying(100),
    doc_type character varying(50),
    CONSTRAINT chk_interview_result CHECK (interview_result IN ('SELECTED','REJECTED','ANOTHER INTERVIEW REQUIRED')),
    CONSTRAINT student_interview_home_verification_req_yn_check CHECK (home_verification_req_yn IN ('Y','N')),
    CONSTRAINT student_interview_interview_mode_check CHECK (interview_mode IN ('ONLINE','OFFLINE')),
    CONSTRAINT student_interview_status_check CHECK (status IN ('SCHEDULED','COMPLETED','CANCELLED','RESCHEDULED'))
);
-- PK: student_interview_pkey PRIMARY KEY (interview_id)   [live-schema.sql:2865-2866]
-- FK: student_interview_applicant_id_fkey  → pp.applicant_primary_info(applicant_id)  [4104-4105]
-- FK: student_interview_interviewer_id_fkey → pp.interviewer(interviewer_id)          [4120-4121]
-- FK: student_interview_created_by_fkey / _updated_by_fkey → pp."user"(user_id)
-- No UNIQUE constraint on (applicant_id, interview_round) or (applicant_id, interviewer_id) — both
--   "no duplicate round" and "no duplicate interviewer" are enforced ONLY in application code, not the DB.
```
**Quirk:** `chk_interview_result` does **NOT** include `'CANCELLED'`/`'SKIPPED'`/`'PENDING'` as values — the check is only on `interview_result`, and the model code never writes those into `interview_result` (it writes NULL or one of the three allowed values); `status` (separate column) carries `CANCELLED`/`SCHEDULED`/`RESCHEDULED`/`COMPLETED`. `getAssignmentReportData`'s categorization logic (section 5 below) reads `record["Assignment Status"]` (== `status`) for `CANCELLED`/`SKIPPED` checks — `'SKIPPED'` is never actually a possible `status` value per the CHECK constraint (dead branch, but harmless).

### `pp.interviewer` (live-schema.sql:1156-1171)
```sql
CREATE TABLE pp.interviewer (
    interviewer_id numeric(10,0) DEFAULT nextval('pp.interviewer_id_seq'::regclass) NOT NULL,
    interviewer_name character varying(100),
    email character varying(100),
    mobile1 character varying(12),
    mobile2 character varying(12),
    active_status character(1),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT interviewer_active_status_check CHECK (active_status IN ('Y','N'))
);
-- PK: interviewer_pkey PRIMARY KEY (interviewer_id)  [2682]
-- FK: interviewer_created_by_fkey / _updated_by_fkey → pp."user"(user_id)
-- SEQUENCE pp.interviewer_id_seq
```

### `pp.home_verification` (live-schema.sql:1009-1026)
```sql
CREATE TABLE pp.home_verification (
    verification_id numeric(12,0) DEFAULT nextval('pp.verification_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    date_of_verification date,
    remarks character varying(200),
    status character varying(10),
    verified_by character varying(100),
    rejection_reason_id numeric(4,0),
    verification_type character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    doc_name character varying(100),
    doc_type character varying(50),
    CONSTRAINT home_verification_status_check CHECK (status IN ('PENDING','SCHEDULED','REJECTED','ACCEPTED')),
    CONSTRAINT home_verification_verification_type_check CHECK (verification_type IN ('PHYSICAL','VIRTUAL'))
);
-- PK: home_verification_pkey PRIMARY KEY (verification_id)  [2657-2658]
-- FK: home_verification_applicant_id_fkey → pp.applicant_primary_info(applicant_id)
-- FK: home_verification_rejection_reason_id_fkey → pp.rejection_reasons(rej_reason_id)
-- FK: home_verification_created_by_fkey / _updated_by_fkey → pp."user"(user_id)
```
**Quirk:** the model's INSERT always passes `rejection_reason_id = null` (interviewModel.js:1007) — there is no UI/logic path in this controller that ever sets a rejection reason on submit; it's a column reserved for a later workflow.

### `pp.student_master` (live-schema.sql:1766-1800, relevant columns only)
```sql
CREATE TABLE pp.student_master (
    student_id numeric(14,0) DEFAULT nextval('pp.student_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    enr_id numeric(11,0),
    student_name character varying(100),
    father_name character varying(100),
    father_occupation character varying(100),
    mother_name character varying(100),
    mother_occupation character varying(100),
    gender character(1),
    contact_no1 character varying(12),
    contact_no2 character varying(12),
    current_institute_dise_code character varying(15),
    previous_institute_dise_code character varying(15),
    home_address character varying(200),
    active_yn character varying(10) DEFAULT 'ACTIVE',
    ... -- (batch_id, sim_name, emails, photo_link, recharge_status, sponsor, teacher_*, user_id, audit cols)
    CONSTRAINT student_master_gender_check CHECK (gender IN ('M','F','O'))
);
-- PK: student_master_pkey PRIMARY KEY (student_id)
-- UNIQUE: student_master_applicant_id_key UNIQUE (applicant_id)   [2906]
-- UNIQUE: student_master_enr_id_key UNIQUE (enr_id)                [2914]
-- FK: student_master_applicant_id_fkey → pp.applicant_primary_info(applicant_id)
```
**enr_id is `numeric(11,0)`** but the Node code builds it as a **string** `${nmmsYear}${paddedSeq}` (e.g. `"20250001"`) and lets `pg`'s implicit param coercion cast it to numeric on INSERT — Java/JDBC must do an explicit `BigDecimal`/`Long` construction from the same concatenation, not pass a raw string bind (behavior differs across JDBC drivers).

### `pp.jurisdiction` (live-schema.sql:1177-1186)
```sql
CREATE TABLE pp.jurisdiction (
    juris_code numeric(12,0) NOT NULL,
    juris_name character varying(100),
    juris_type character varying(100),
    parent_juris numeric(12,0),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);
-- PK: jurisdiction_pkey PRIMARY KEY (juris_code)
-- FK: jurisdiction_juris_type_fkey → pp.jurisdiction_type(juris_type)
-- FK: jurisdiction_parent_juris_fkey → pp.jurisdiction(juris_code)  (self-referential tree)
```
`juris_type` values used by this module (case-insensitive compares in SQL): `'state'`, `'division'`, `'education district'`, `'block'`.

### `pp.pp_exam_centre` (live-schema.sql:1295-1320, relevant columns)
```sql
CREATE TABLE pp.pp_exam_centre (
    pp_exam_centre_id numeric(10,0) DEFAULT nextval('pp.pp_exam_centre_seq'::regclass) NOT NULL,
    pp_exam_centre_code character varying(20),
    pp_exam_centre_name character varying(200) NOT NULL,
    ... -- address/village/pincode/contacts/capacity
    active_yn character(1) DEFAULT 'Y' NOT NULL,
    latitude numeric(15,2), longitude numeric(15,2),
    google_map_link text GENERATED ALWAYS AS (...) STORED,
    CONSTRAINT pp_exam_centre_active_yn_check CHECK (active_yn IN ('Y','N'))
);
-- PK: pp_exam_centre_pkey PRIMARY KEY (pp_exam_centre_id)
-- UNIQUE: pp_exam_centre_pp_exam_centre_code_key UNIQUE (pp_exam_centre_code)
```
`getExamCenters` returns only `pp_exam_centre_id, pp_exam_centre_name` — **no filter on `active_yn`** (inactive centers still listed).

### `pp.exam_results` (live-schema.sql:877-887)
```sql
CREATE TABLE pp.exam_results (
    applicant_id numeric(14,0),
    pp_exam_score numeric(3,0),
    pp_exam_cleared character(1),
    interview_required_yn character(1),
    CONSTRAINT exam_results_interview_required_yn_check CHECK (interview_required_yn IN ('Y','N')),
    CONSTRAINT exam_results_pp_exam_cleared_check CHECK (pp_exam_cleared IN ('Y','N'))
);
```
**No PK, no FK declared** on this table in the dump (bare table) — joins in this module treat `applicant_id` as effectively 1:1 per applicant but the schema doesn't enforce it.

### `pp.applicant_exam` (live-schema.sql:84-91)
```sql
CREATE TABLE pp.applicant_exam (
    applicant_id numeric(14,0) NOT NULL,
    exam_id numeric(14,0) NOT NULL,
    pp_hall_ticket_no character varying(20)
);
-- PK: pk_applicant_exam PRIMARY KEY (applicant_id, exam_id)
-- FK: applicant_exam_applicant_id_fkey → pp.applicant_primary_info(applicant_id)
-- FK: applicant_exam_exam_id_fkey → pp.examination(exam_id)
```

### `pp.examination` (live-schema.sql:907-921, relevant)
```sql
CREATE TABLE pp.examination (
    exam_id numeric(14,0) DEFAULT nextval('pp.examination_seq'::regclass) NOT NULL,
    exam_name character varying(100) NOT NULL,
    exam_date date NOT NULL,
    exam_start_time time NOT NULL,
    exam_end_time time NOT NULL,
    pp_exam_centre_id numeric(10,0),
    frozen_yn character(1) DEFAULT 'N',
    exam_year character varying(10),
    CONSTRAINT examination_frozen_yn_check CHECK (frozen_yn IN ('Y','N'))
);
-- PK: examination_pkey PRIMARY KEY (exam_id)
-- FK: examination_pp_exam_centre_id_fkey → pp.pp_exam_centre(pp_exam_centre_id)
```

### `pp.shortlist_batch` / `pp.shortlist_batch_jurisdiction` (used only in `getBlocksByDistrict`'s frozen-block flag)
```sql
CREATE TABLE pp.shortlist_batch (
    shortlist_batch_id numeric(6,0) DEFAULT nextval('pp.shortlist_batch_id_seq'::regclass) NOT NULL,
    shortlist_batch_name character varying(100) NOT NULL,
    ...
    frozen_yn character(1) DEFAULT 'N',
    shortlisted_year numeric(4,0) NOT NULL,
    CONSTRAINT shortlist_batch_frozen_yn_check CHECK (frozen_yn IN ('Y','N'))
);
-- PK: shortlist_batch_pkey PRIMARY KEY (shortlist_batch_id)

CREATE TABLE pp.shortlist_batch_jurisdiction (
    shortlist_batch_id numeric(6,0) NOT NULL,
    juris_code numeric(12,0) NOT NULL
);
-- PK: shortlist_batch_jurisdiction_pkey PRIMARY KEY (shortlist_batch_id, juris_code)
-- FK both columns → shortlist_batch / jurisdiction respectively (ON DELETE CASCADE)
```

### `pp.applicant_primary_info` / `pp.applicant_secondary_info` (only columns this module touches)
`applicant_primary_info`: PK `applicant_id`; `nmms_reg_number` UNIQUE NOT NULL; columns used: `applicant_id, nmms_year, nmms_reg_number, student_name, contact_no1, contact_no2, gmat_score, sat_score, app_state, district, nmms_block, current_institute_dise_code, previous_institute_dise_code, father_name, mother_name, gender, home_address`.
`applicant_secondary_info`: PK `applicant_id` (FK to primary, `ON DELETE CASCADE`); all fields nullable except a handful with `NOT NULL DEFAULT 0` (`num_two_wheelers`, `num_four_wheelers`, `irrigation_land`); used columns: `village, father_occupation, mother_occupation, father_education, mother_education, household_size, own_house, smart_phone_home, internet_facility_home, career_goals, subjects_of_interest, transportation_mode, distance_to_school, num_two_wheelers, num_four_wheelers, irrigation_land, neighbor_name, neighbor_phone, favorite_teacher_name, favorite_teacher_phone`.

## 6. Response shapes & status codes

| Endpoint | 200 body | Error bodies |
|---|---|---|
| `GET /exam-centers` | raw array `[{pp_exam_centre_id, pp_exam_centre_name}, ...]` | `500 {message:"Internal server error while fetching exam centers."}` |
| `GET /states` | raw array `[{juris_code, juris_name}, ...]` | `500 {message:"...fetching states."}` |
| `GET /divisions` | raw array | `400 {message:"Missing stateName query parameter."}`; `500 {message:"...fetching divisions."}` |
| `GET /districts` | raw array | `400 {message:"Missing divisionName parameter."}`; `500 {message:"...fetching districts."}` |
| `GET /blocks` | raw array incl. `is_frozen_block: boolean` | `400 {message:"Missing one or more required parameters: stateName, divisionName, or districtName."}`; `500 {message:"...fetching blocks."}` |
| `GET /interviewers` | raw array `[{interviewer_id, interviewer_name}]` | `500 {message:"...fetching interviewers."}` |
| `GET /students/:interviewerName?nmmsYear=` | raw array `[{student_name, applicant_id, interview_round}]` | `400 {message:"Missing interviewerName in parameters or nmmsYear in query."}`; `500 {message:"...fetching students for interviewer."}` |
| `GET /unassigned-students?centerName&nmmsYear` | raw array `[{applicant_id, student_name, pp_exam_score}]` | `400 {message:"Missing centerName or nmmsYear query parameter."}`; `500 {message:"...fetching unassigned students."}` |
| `GET /unassigned-students-by-block?stateName&districtName&blockName&nmmsYear` | raw array (same shape) | `400 {message:"Missing required query parameters."}`; `500 {message:"...fetching unassigned students by block."}` |
| `GET /reassignable-students?centerName&nmmsYear` | raw array `[{applicant_id, student_name, institute_name, pp_exam_score, pp_exam_centre_name, interview_round, current_interviewer, current_interviewer_id}]` | `400 {message:"Missing centerName or nmmsYear query parameter."}`; `500 {message:"...fetching reassignable students."}` |
| `GET /reassignable-students-by-block?...` | raw array (same shape minus `pp_exam_centre_name`) | **No explicit 400 validation** in controller (`getReassignableStudentsByBlock` skips the missing-params check present on the sibling endpoint — passes `undefined` straight to the model, which will bind `undefined`→SQL null and simply return `[]`); `500 {message:"Internal server error."}` |
| `POST /assign-students` | `200 {message:"Assignment process completed.", results:[{applicantId, status:"Assigned"|"Skipped", interviewRound?, reason?}, ...]}` | `400 {message:"Missing applicantIds, interviewerId, or nmmsYear in request body."}`; `500 {message:"Internal server error while assigning students."}` |
| `POST /reassign-students` | `200 {message:"Reassignment process completed.", results:[{applicantId, status:"RESCHEDULED"|"CANCELLED"|"Skipped", interviewRound?, reason?}, ...]}` | `400 {message:"Missing applicantIds, newInterviewerId, or nmmsYear in request body."}`; `500 {message:"Internal server error while reassigning students."}` |
| `GET /students-for-verification?nmmsYear=` | raw array `[{student_name, applicant_id}]` | `400 {message:"Missing or invalid nmmsYear. Received: <value>"}` (also rejects the literal strings `"undefined"`/`"null"`); `500 {message:"Failed to fetch students for verification."}` |
| `POST /submit-home-verification` (multipart) | `200 {message:"Home verification submitted successfully."` (+ `" Student Enrolled as: <enr_id>"` appended if accepted)`, data:{...home_verification row, enr_id}}` | `400 {message:"Missing required fields including nmmsYear."}`; `400 {message:"File upload failed: <err>"}` (multer); `500 {message: error.message \|\| "Internal server error."}` |
| `POST /submit-interview` (multipart) | `200 {message:"Interview details submitted successfully."` (+ `" Enrollment ID: <enr_id>"` if accepted)`, data:{...student_interview row, enr_id}}` | `400 {message:"Missing applicantId, remarks, interview file, or nmmsYear."}`; `400 {message:"File upload failed: <err>"}` (multer); `500 {error:true, message: error.message \|\| "Internal server error."}` (**note: this one 500 uniquely includes `error:true`** — no other endpoint in this module does) |
| `POST /download-assignment-report` | binary `application/pdf` stream, `Content-Disposition: attachment; filename="Interview-Assignment<id>_<ts>.pdf"` | `400 {error:"Missing required parameters: interviewerId, nmmsYear, or applicantIds list is empty/invalid."}`; `404 {error:"No student data found for the selected criteria."}`; `500 {error:"Failed to generate PDF report."}` (or `500 {error:"PDF generation stream failed."}` mid-stream, only if `!res.headersSent`) |

**IDs as numbers vs strings:** Node's `pg` driver returns `numeric` columns as **JS strings** by default (no custom type parser configured in this codebase's `config/db.js` — confirmed by absence of `pg.types.setTypeParser` calls in the files read). So `applicant_id`, `interviewer_id`, `interview_round` (actually `integer` — returns as JS number), `pp_exam_score` (`numeric(3,0)` → string), `juris_code` (→ string) all come back as strings except genuine `integer`/`boolean` columns (`interview_round` is `integer` → number; `is_frozen_block` is a SQL boolean → JS boolean). Match Node's per-column behavior in Java: numeric(*) columns serialize as String, `integer` columns as Number, per RESUME convention #3.

## 7. File-generating endpoint — `downloadAssignmentReport` (PDF, not XLSX)

**Library:** `pdfkit` (`PDFDocument`), **not** a spreadsheet library — despite the task brief's "(XLSX/PDF?)" question, this endpoint is 100% PDF.

**Request body:** `{ interviewerId, nmmsYear, applicantIds: number[] }`. Validation: 400 if any missing or `applicantIds` empty.

**Disk write (real side effect, not stateless):** writes a **permanent copy to disk** at `GENERATED_FILES_ROOT = path.join(process.env.FILE_STORAGE_PATH || 'public', 'generated-eval-data')`, filename `Interview-Assignment<cleanInterviewerId>_<Date.now()>.pdf` where `cleanInterviewerId = interviewerId.toString().replace(/[^a-zA-Z0-9-]/g,'')`. Directory created recursively if missing. The PDF is **piped to both** the local file (`fs.createWriteStream`) **and** the HTTP response simultaneously (`doc.pipe(writeStream); doc.pipe(res)`). This is a genuine disk-write side effect (unlike the shortlist module's `download-data` which the prior work note says was "disk-write dropped as stateless") — **Java must decide**: either replicate the disk write (needs equivalent storage path/config) or intentionally drop it and document the divergence; given the PDF has no re-download endpoint in this router, dropping it is plausible but is a product decision, not purely technical.

**Model call:** `InterviewModel.getAssignmentReportData(interviewerId, nmmsYear, applicantIds)` (interviewModel.js:1049-1226) — **two queries + in-memory merge**:

1. **Profile query** — built with `pg-format`'s `format()` (NOT parameterized placeholders; uses `%s`/`%L` literal-safe interpolation):
   ```sql
   SELECT
       API.applicant_id, API.nmms_reg_number,
       API.student_name AS "Student Name",
       API.contact_no1 AS "Contact No 1", API.contact_no2 AS "Contact No 2",
       CUR_INST.institute_name AS "Current School Name",
       PREV_INST.institute_name AS "Previous School Name",
       API.gmat_score, API.sat_score, E.pp_exam_score,
       SJ.juris_name AS "State Name", DJ.juris_name AS "District Name", BJ.juris_name AS "Block Name",
       S.village, S.father_occupation, S.mother_occupation, S.father_education, S.mother_education,
       S.household_size, S.own_house, S.smart_phone_home, S.internet_facility_home,
       S.career_goals, S.subjects_of_interest, S.transportation_mode, S.distance_to_school,
       S.num_two_wheelers, S.num_four_wheelers, S.irrigation_land,
       S.neighbor_name, S.neighbor_phone, S.favorite_teacher_name, S.favorite_teacher_phone
   FROM pp.applicant_primary_info API
   LEFT JOIN pp.applicant_secondary_info S ON S.applicant_id = API.applicant_id
   LEFT JOIN pp.exam_results E ON E.applicant_id = API.applicant_id
   LEFT JOIN pp.institute CUR_INST ON API.current_institute_dise_code = CUR_INST.dise_code
   LEFT JOIN pp.institute PREV_INST ON API.previous_institute_dise_code = PREV_INST.dise_code
   LEFT JOIN pp.jurisdiction SJ ON API.app_state = SJ.juris_code
   LEFT JOIN pp.jurisdiction DJ ON API.district = DJ.juris_code
   LEFT JOIN pp.jurisdiction BJ ON API.nmms_block = BJ.juris_code
   WHERE API.nmms_year = %s AND API.applicant_id IN (%L)
   ORDER BY API.student_name ASC;
   ```
   `%s` = `parseInt(nmmsYear, 10)` interpolated raw (NOT quoted — safe since it's a validated integer); `%L` = `applicantIds.map(String)` (pg-format's literal-list escaping, safe from injection). **This uses string-built SQL, not bind params** — Java port should use bind params (`IN (:ids)`) instead; the pg-format usage here is a Node idiom for dynamic `IN (...)` lists, not a security requirement to replicate literally.
   If zero profile rows → returns `[]` (controller then returns `404`).

2. **Interview-history query** (also `pg-format`, only `%L` for the ID list):
   ```sql
   SELECT
       S.applicant_id, I.interviewer_name,
       S.interview_round AS "Interview Round",
       S.interview_date AS "Interview Date",
       S.interview_time AS "Interview Time",
       S.interview_mode AS "Interview Mode",
       S.status AS "Assignment Status",
       S.life_goals_and_zeal AS "Life Goals and Zeal",
       S.commitment_to_learning AS "Commitment to Learning",
       S.integrity AS "Integrity",
       S.communication_skills AS "Communication Skills",
       S.interview_result AS "Interview Result",
       I.interviewer_name AS "Assigned Interviewer Name"
   FROM pp.student_interview S
   JOIN pp.interviewer I ON I.interviewer_id = S.interviewer_id
   WHERE S.applicant_id IN (%L)
   ORDER BY S.applicant_id ASC, S.interview_round DESC;
   ```
   **`JOIN` (not LEFT JOIN) to `pp.interviewer`** — any `student_interview` row with a NULL `interviewer_id` (e.g. after a cancellation sets `interviewer_id = NULL`) is **silently excluded** from the report's interview history. This means a cancelled-and-unassigned round vanishes from the PDF entirely rather than showing as "Cancelled" — a real quirk to decide whether to preserve.

3. **In-memory merge/categorize** per student: group all interview rows by `applicant_id`; for each row (already ordered by round DESC), classify: if `interview_result` is non-null, not `'PENDING'`, and `status` not in `{'CANCELLED','SKIPPED'}` → push to `completedRounds[]`; else if no `pendingAssignment` set yet → set it as the (single) `pendingAssignment`. Because rows are round-DESC, `pendingAssignment` ends up being the **highest-round** row that doesn't qualify as completed (first one encountered in the loop). Returns per-student `{ ...profileRow, "Pending Assignment": pendingAssignment|null, "Completed Rounds": completedRounds[] }`.

**PDF layout** (drawn manually with pdfkit primitives, `Times-Roman`/`Times-Bold` fonts, A4, margins `{top:100,bottom:30,left:30,right:30}`):
- Custom header (`drawReportHeader`) redrawn on **every page** via `doc.on('pageAdded', ...)`: RCF logo (left) + PP logo (right) loaded from `server/public/assets/rcf_logo-removebg-preview.png` / `logo.png` (skipped silently if files missing via `fs.existsSync`), centered title block "RAJALAKSHMI CHILDREN FOUNDATION" / "PRATIBHA POSHAK EXAMINATION - <nmmsYear>" / address / contact, separator line.
- One page per student (`doc.addPage()` for `index > 0`, so the first student shares the initial page). Sections: title `Student Interview Report: <name>`, "Primary Applicant & Profile Details" (29 label/value pairs — school names, jurisdiction, scores, contacts, parents' occupation/education, household facts, career interests, transport, vehicles, irrigation land, neighbor/teacher contacts, assigned interviewer), then conditionally "Current Assignment Details" (round/status/interviewer) if `pendingAssignment` truthy, then "Completed Interview Results (N Round(s))" iterating `completedRounds` (each: Result header, interviewer/date/mode/status, then "--- Scores ---" with the 4 numeric rubric scores), else a gray "No current assignment or completed interview records found." line if neither present.
- `cleanText()` strips control/non-printable chars via `/[^\x20-\x7E\xA0-\xFFĀ-￿]/g` before any text render (defends against pdfkit crashing on stray bytes). Per-student render wrapped in `try/catch` (`doc.save()`/`doc.restore()` in `finally`) so one bad student's data doesn't abort the whole PDF — errors are only `console.error`'d.
- `formatDateForPdf` uses `toLocaleDateString('en-IN', {year:'numeric', month:'short', day:'numeric'})`, falling back to the raw string via `cleanText` on any `Date` parse error.

**Java equivalent:** needs a PDF library (e.g. Apache PDFBox or OpenPDF/iText) — this is a **new library dependency** for the Java side (nothing in Phase 0-2 required PDF generation; XLSX (Apache POI) was already added for shortlist's `download-data` and bulk-upload). No XLSX involved in this specific endpoint.

## 8. Transactions

| Function | Transactional? | Notes |
|---|---|---|
| `assignStudents` | **Yes** — `client.query("BEGIN")` … `COMMIT`/`ROLLBACK`, `finally client.release()` | Loop of up to 4 sequential statements per applicant, all in one transaction spanning the whole batch (not per-applicant sub-transactions) — a thrown error on applicant N rolls back applicants 1..N-1's writes too, even though earlier iterations already pushed "Assigned" results into the (never-returned, because the throw propagates) results array. |
| `reassignStudents` | **Yes** — same shape | Same whole-batch-atomic semantics. |
| `submitInterviewDetails` | **Yes** — same shape | Also does filesystem rename (`fs.rename` before DB writes) and `fs.unlink` cleanup **on the catch path** (`if (finalTargetPath && fsExistsSync(finalTargetPath)) await fs.unlink(finalTargetPath)`) — file-move happens outside the DB transaction boundary conceptually but is manually rolled back on any subsequent DB error. |
| `submitHomeVerification` | **Yes** — same shape, same manual file-rollback-on-catch pattern | |
| `getAssignmentReportData` | No (two plain `pool.query` calls, not on a transaction client) — read-only, consistency between the two queries not guaranteed under concurrent writes (acceptable for a read-only report). | |
| All other `get*` functions | No — single `pool.query()`, autocommit. | |

## 9. Quirks & complexity warnings (file:line references)

1. **Duplicate `assignStudents` definition** (interviewModel.js:294-428 and :430-564) — byte-identical, second wins in the object literal (JS semantics), first is completely dead. Port only one; do not accidentally port both as overloads.
2. **`getUnassignedStudents` vs `getUnassignedStudentsByBlock` are NOT equivalent** (interviewModel.js:177-233 vs :235-292): the by-center version requires the applicant to have an exam-centre-linked `applicant_exam`/`examination` row and filters the `LatestInterview` CTE by `nmms_year` up front; the by-block version has no such exam/centre join requirement and computes `LatestInterview` across **all years** before filtering nmms_year in the outer query. A student assigned in a different year's interview could theoretically affect the by-block "latest interview" computation in a way the by-center query wouldn't. Preserve both queries distinctly — do not "simplify" into one shared query.
3. **`getReassignableStudentsByBlock` uses inner `JOIN`s to jurisdiction** while `getReassignableStudents` (by-center) uses `pp_exam_centre` — again asymmetric join strategies between the "by center" and "by block" sibling pairs across all four unassigned/reassignable variants; each pair independently chose different join types (LEFT vs INNER) for jurisdiction tables. Verify against real data whether NULLs in `app_state`/`district`/`nmms_block` should exclude a student (by-block reassignable: yes, INNER excludes) or not (by-block unassigned: no, LEFT includes with NULL jurisdiction name comparison always false anyway — practically same net effect, but worth a determinism test).
4. **`getReassignableStudentsByBlock` controller has no 400 validation** (interviewController.js:448-463) — sibling `getReassignableStudents` (centre-based, :495-507) validates `centerName`/`nmmsYear` are present; the by-block controller function destructures 4 params and passes them straight to the model with **no null-check**, silently returning `[]` on missing params instead of 400. Decide whether to fix or preserve.
5. **`assignStudents`'s "already assigned" duplicate check is global-across-rounds`, not "current round only"** (interviewModel.js: query at "2. Check for duplicate assignment" — `WHERE applicant_id = $1 AND interviewer_id = $2` with no round filter) — an applicant who was assigned to interviewer X in round 1 can never be assigned to X again in round 2/3 even if legitimately eligible for reassignment to the same person; this is almost certainly intentional business logic (interviewer diversity across rounds) but must be preserved exactly, including in golden tests.
6. **CANCELLED-record "fix" reuses the row and its original `interview_round`** (scenario C, interviewModel.js:336-360 / :472-496) — this is the ONLY assign-path branch that does an UPDATE instead of INSERT; it silently reuses the row's PK (`interview_id`) and the reported `interviewRound` is the **pre-existing** round of the cancelled record, not `nextRound`. If `nextRound` was computed as something else in the same code path (it isn't reached — the branch `continue`s before nextRound-driven insert), no conflict, but a maintainer must not "unify" this branch with the INSERT branch without preserving the row-reuse semantic.
7. **`reassignStudents` cancellation UPDATE has no LIMIT/round-scoping** (interviewModel.js:686-696) — if data integrity is ever violated (e.g. two SCHEDULED/RESCHEDULED rows for one applicant exist due to a bug elsewhere), the cancellation silently cancels **all** matching rows in one UPDATE but the JS code only reads `rows[0]` for the API response — the true blast radius of the cancellation could be understated to the caller.
8. **`getAssignmentReportData`'s interview-history query INNER JOINs `pp.interviewer`** (interviewModel.js:1166-1167) — rows where `interviewer_id IS NULL` (post-cancellation state) are dropped from the report entirely; a cancelled round is invisible in the generated PDF rather than shown as "Cancelled — no interviewer." Decide and document if Java should preserve this exclusion or fix it to a LEFT JOIN (a real behavior decision, not just a technical port).
9. **`downloadAssignmentReport` writes a permanent file to disk** (interviewController.js:136-144, 172-174) in addition to streaming the response — genuinely stateful, unlike shortlist's `download-data` XLSX endpoint which a prior migration decision (see git log `acb42d3`) made stateless by dropping the disk write. Needs an explicit decision for Java (replicate storage path + config, or intentionally drop and note the divergence).
10. **`pg-format` (`%s`/`%L`) SQL building in `getAssignmentReportData`** (interviewModel.js:1061-1126, 1146-1174) — string-interpolated SQL rather than parameterized; safe here because `%L` escapes list literals and `%s` only receives a `parseInt`-validated year, but Java must use `IN (:ids)` bind-var lists instead (Spring `JdbcClient` supports `List<>` binding for `IN` clauses) — do not literally port string-building.
11. **`enr_id` (numeric(11,0)) built as a concatenated string** `${nmmsYear}${paddedSeq}` in both `submitInterviewDetails` (interviewModel.js:839-860) and `submitHomeVerification` (interviewModel.js:947-973) — identical enrollment-ID-generation logic duplicated verbatim in both functions (`SELECT MAX(CAST(SUBSTRING(enr_id::TEXT, 5) AS INTEGER)) ... WHERE enr_id::TEXT LIKE $1 || '%'`, then zero-pad to 4 digits). Extract to one shared helper in Java; both call sites must produce byte-identical IDs given the same inputs (this is effectively a shared sequence-per-year scheme, racy under concurrent submissions since it's a MAX+1 read-then-write with no `SELECT ... FOR UPDATE` — same transaction isolation caveat as NMMS/shortlist modules; consider `SELECT FOR UPDATE` or an advisory lock in the Java port if concurrent submits are expected).
12. **`ACCEPTED`/`HOME VERIFICATION REQUIRED` → `SELECTED` remapping** only in `submitInterviewDetails` (interviewController comment "FIX: Map 'ACCEPTED' to 'SELECTED'", interviewModel.js:818-834) to satisfy `chk_interview_result`'s allowed values (`SELECTED`,`REJECTED`,`ANOTHER INTERVIEW REQUIRED`) — the incoming `interviewResult` field from the frontend can apparently be `'ACCEPTED'` or the literal phrase `'HOME VERIFICATION REQUIRED'`, both mapped to `'SELECTED'` before the CHECK-constrained column write; `submitHomeVerification`'s own `status` field uses a **different vocabulary** (`PENDING/SCHEDULED/REJECTED/ACCEPTED` per `home_verification_status_check`) that does NOT get remapped — two different result vocabularies across the two submit endpoints, easy to conflate when porting DTOs.
13. **File rename before DB write, manual rollback on catch** in both submit endpoints (interviewModel.js:809-815, 913-916 and 980-991, 1041) — `fs.rename()` happens mid-transaction (after BEGIN, before the UPDATE/INSERT), and on any subsequent error the catch block does `fs.unlink` to undo the rename; if the process crashes between rename and the catch's unlink, the renamed file is orphaned with no corresponding DB row — an inherent (Node-side) small window of inconsistency between filesystem and DB that a Java port should ideally close by doing DB writes first and file move last, or accept and document the same window.
14. **Two independently-defined `NO_INTERVIEWER_ID = "NO_ONE"` constants** (interviewController.js:6, interviewModel.js:7) — same string, not shared; keep them in sync if the sentinel ever changes.
15. **`getInterviewers` and `getExamCenters` do not filter on `active_status`/`active_yn`** — inactive interviewers/centres remain selectable in dropdowns; verify with product whether this is intended before "fixing" in Java.
16. **`nmms_year` type inconsistency across query sites**: `applicant_primary_info.nmms_year` is `numeric(4,0)`; controllers pass `req.query.nmmsYear` (a string from the URL) or `req.body.nmmsYear` directly as the bind param — `pg` coerces the string to numeric automatically on comparison (`api.nmms_year = $2` with a string param binds fine since Postgres casts numeric = text-parseable-value). In Java/JdbcClient, per RESUME convention #2, cast the **parameter** (`:nmmsYear::numeric`) rather than the column, and ensure the DTO accepts a String or Integer transparently the same way Express does.
