# Phase 5b Ground Truth — TAB-INVENTORY module

## §0 Scoping note

- **Base mount:** `app.use("/api", tabInventoryRoutes)` — `server/index.js:322`. No path prefix (routes define full `/tabs...` paths themselves).
- **Files read (full, to bottom):**
  - `server/routes/tabInventoryRoutes.js` (25 lines, no dead code)
  - `server/controllers/tabInventoryController.js` (167 lines, no dead code — every exported fn is wired to a route)
  - `server/models/tabInventoryModel.js` (605 lines; trailing blank lines 599-605 are just whitespace, no code)
  - Client skim: no dedicated Tab/Inventory page found via search of `client/src/pages/**`; treat client contract as governed entirely by this doc's response shapes (no additional client-side assumptions to reconcile).
- **No commented-out predecessor code** in any of the three files — this is a clean, single-version module (unlike other ported modules with dead code above the live exports).
- **Auth middleware: NONE.** `tabInventoryRoutes.js` never imports or applies `authenticate` (or any middleware) on any of the 14 routes. Contrast with `studentRoutes.js`, `coordinatorRoutes.js`, `applicantRoutes.js` which do use `authMiddleware.js`. This is either an intentional internal-only module or a gap — **flag for product/security sign-off before the Java port**, since Spring Security will need an explicit decision (open vs. secured) rather than silently inheriting "no auth."
- Route mount order in `server/index.js`: `tabInventoryRoutes` is mounted at line 322, after `searchRoutes` (line 320) and before `teacherStudentRoutes` (line 324-327). Nothing else double-mounts `/api/tabs*`.

## §1 Endpoint inventory table

| # | Method | Path | Controller fn | Model fn | Purpose | Auth |
|---|--------|------|----------------|----------|---------|------|
| 1 | GET | `/tabs/stats` | `getTabStats` | `getTabStats` | Dashboard counts (total/status breakdown/active assignments) | none |
| 2 | GET | `/tabs/eligible-students` | `getEligibleStudents` | `getEligibleStudents` | Active students with no open tab assignment (for the assign-tab dropdown) | none |
| 3 | GET | `/tabs/brands` | `getAllBrands` | `getAllBrands` | List brand/model catalog | none |
| 4 | GET | `/tabs/users` | `getAllUsers` | `getAllUsers` | Staff not currently holding a tab (for official-issue dropdown) | none |
| 5 | GET | `/tabs/cohorts` | `getAllCohorts` | `getAllCohorts` | List cohorts (for movement-report filters) | none |
| 6 | GET | `/tabs/movement-report` | `getTabMovementReport` | `getTabMovementReport` | Cross-cohort tab handover report, optional `fromCohort`/`toCohort` query filters | none |
| 7 | GET | `/tabs` | `getAllTabs` | `getAllTabs` | Full inventory listing with latest holder enrichment | none |
| 8 | POST | `/tabs` | `createTab` | `createTab` | Create one tablet | none |
| 9 | POST | `/tabs/bulk` | `bulkCreateTabs` | `bulkCreateTabs` | Excel-driven bulk upsert of tablets + assignment history | none |
| 10 | POST | `/tabs/brands` | `createBrand` | `createBrand` | Create/upsert a brand+model | none |
| 11 | GET | `/tabs/:tabId` | `getTabById` | `getTabById` | Single tablet raw row | none |
| 12 | PUT | `/tabs/:tabId/status` | `changeTabStatus` | `changeTabStatus` | Status transition + assignment side-effects | none |
| 13 | DELETE | `/tabs/:tabId` | `deleteTab` | `deleteTab` | Hard-delete a tablet (cascades issue rows manually) | none |
| 14 | GET | `/tabs/:tabId/history` | `getTabHistory` | `getTabHistory` | Full assignment history (student + staff) for one tab | none |

Route registration order in `tabInventoryRoutes.js` matters: **all 6 static GETs (1-6) and the 3 collection routes (7-10) are registered before the 4 dynamic `/:tabId` routes (11-14)**, per the file's own comments (`// 1. Static Routes (MUST BE FIRST)` line 5, `// 3. Dynamic Routes (MUST BE LAST)` line 19). In Express this ordering is what stops `/tabs/stats` etc. from being swallowed by `/tabs/:tabId`. **Java/Spring MVC path-variable matching does not have this first-match-wins ambiguity the same way** (it prefers exact/static matches over `{tabId}` automatically), but the ground truth still requires the Java route table to expose exactly these 6 static paths distinctly from `/tabs/{tabId}` — do not accidentally collapse `stats`/`eligible-students`/`brands`/`users`/`cohorts`/`movement-report` into path-variable territory.

## §2 Exact SQL

### 2.1 getAllBrands (model L15-19)
```sql
SELECT brand_id, brand_name, model_name FROM pp.tab_brand ORDER BY brand_name, model_name
```

### 2.2 createBrand (model L21-40)
```sql
INSERT INTO pp.tab_brand (brand_name, model_name, created_by, updated_by)
VALUES ($1, $2, $3, $3)
ON CONFLICT (brand_name, model_name)
DO UPDATE SET
  updated_at = CURRENT_TIMESTAMP,
  updated_by = $3
RETURNING *
```
Params: `[clean(brand_name), clean(model_name), created_by]` — `$3` reused for both `created_by` (insert branch) and `updated_by` (both branches). `clean()` maps `""`/`undefined` → `null`.

### 2.3 createTab (model L43-71)
```sql
INSERT INTO pp.tab_inventory (
  serial_number,
  imei,
  inventory_id,
  brand_id,
  tab_purchase_date,
  remarks,
  created_by,
  updated_by
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $7)
RETURNING tab_id;
```
Params: `[clean(serial_number), clean(imei), clean(inventory_id), brand_id, formatDate(tab_purchase_date), clean(remarks), created_by]`. `status` is **not** supplied — relies on the table `DEFAULT 'IN_OFFICE'`.

### 2.4 changeTabStatus (model L75-125) — runs inside a transaction, see §6
```sql
-- Step 1 (only if status IN RETURNED/DAMAGED/LOST):
UPDATE pp.student_issue SET return_date = $1 WHERE tab_id = $2 AND return_date IS NULL
UPDATE pp.official_issue SET return_date = $1 WHERE tab_id = $2 AND return_date IS NULL

-- Step 2a (only if status = 'ASSIGNED' AND assignment_type = 'STUDENT' AND student_id present):
INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
VALUES ($1, $2, $3, NULL, $4)
ON CONFLICT (tab_id, student_id)
DO UPDATE SET return_date = NULL, assignment_date = $3

-- Step 2b (only if status = 'ASSIGNED' AND assignment_type = 'OFFICIAL' AND official_user_id present):
INSERT INTO pp.official_issue (tab_id, user_id, assignment_date, return_date, remark, created_by)
VALUES ($1, $2, $3, NULL, $4, $5)
ON CONFLICT (tab_id, user_id)
DO UPDATE SET return_date = NULL, assignment_date = $3

-- Step 3 (always):
UPDATE pp.tab_inventory SET status = $1, remarks = COALESCE($2, remarks), updated_at = CURRENT_TIMESTAMP WHERE tab_id = $3
```
`status` here is **taken verbatim from `req.body.status`** with no server-side whitelist/enum check in JS — the only guard is the Postgres `tab_inventory_status_check` CHECK constraint (§3), which will reject any value outside `IN_OFFICE|ASSIGNED|RETURNED|DAMAGED|LOST` and bubble up as a thrown error → caught → `400 {success:false, message: error.message}` (raw Postgres error text leaks to the client). **This is the injection/validation surface to whitelist explicitly in the Java port** (an enum + `@Pattern`/allow-list before hitting SQL, not just relying on the DB constraint for a clean error message).

### 2.5 getAllUsers (model L128-141)
```sql
SELECT user_id, user_name
FROM pp."user" u
WHERE locked_yn = 'N'
AND NOT EXISTS (
    SELECT 1 FROM pp.official_issue oi
    WHERE oi.user_id = u.user_id AND oi.return_date IS NULL
)
ORDER BY user_name ASC
```
`locked_yn` is nullable with **no default** (`character(1)`, no `DEFAULT`) — rows with `locked_yn IS NULL` fail `locked_yn = 'N'` (three-valued logic) and are silently excluded. Only rows explicitly `'N'` are returned.

### 2.6 deleteTab (model L143-170) — transaction
```sql
DELETE FROM pp.student_issue WHERE tab_id = $1
DELETE FROM pp.official_issue WHERE tab_id = $1
DELETE FROM pp.tab_inventory WHERE tab_id = $1 RETURNING tab_id
```

### 2.7 getTabById (model L172-178)
```sql
SELECT * FROM pp.tab_inventory WHERE tab_id = $1
```

### 2.8 getAllTabs (model L180-241)
```sql
WITH latest_student_assignment AS (
  SELECT
    si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, sm.enr_id, b.batch_name, c.cohort_name,
    ROW_NUMBER() OVER(PARTITION BY si.tab_id ORDER BY si.assignment_date DESC, si.created_at DESC) as rn
  FROM pp.student_issue si
  JOIN pp.student_master sm ON si.student_id = sm.student_id
  LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
  LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
),
latest_official_assignment AS (
  SELECT
    oi.tab_id, oi.user_id, oi.assignment_date, oi.return_date, u.user_name as staff_name,
    ROW_NUMBER() OVER(PARTITION BY oi.tab_id ORDER BY oi.assignment_date DESC, oi.created_at DESC) as rn
  FROM pp.official_issue oi
  JOIN pp."user" u ON oi.user_id = u.user_id
)
SELECT
  t.tab_id, t.serial_number, t.imei, t.inventory_id, tb.brand_name, tb.model_name AS model,
  t.tab_purchase_date, t.status, t.remarks, t.updated_at,

  CASE
    WHEN t.status = 'IN_OFFICE' THEN NULL
    ELSE COALESCE(sa.student_name, oa.staff_name)
  END AS assigned_to,

  CASE
    WHEN t.status = 'IN_OFFICE' THEN NULL
    ELSE sa.enr_id
  END AS enr_id,

  CASE
    WHEN t.status = 'IN_OFFICE' THEN NULL
    ELSE sa.student_name
  END AS student_name,

  CASE
    WHEN t.status = 'IN_OFFICE' THEN NULL
    ELSE oa.staff_name
  END AS staff_name,

  CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.cohort_name END as cohort_name,
  CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.batch_name END as batch_name,

  CASE
    WHEN t.status = 'IN_OFFICE' THEN NULL
    WHEN sa.student_id IS NOT NULL AND (sa.assignment_date >= COALESCE(oa.assignment_date, '1970-01-01')) THEN 'STUDENT'
    WHEN oa.user_id IS NOT NULL THEN 'OFFICIAL'
    ELSE NULL
  END AS assignment_category
FROM pp.tab_inventory t
LEFT JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
LEFT JOIN latest_student_assignment sa ON t.tab_id = sa.tab_id AND sa.rn = 1
LEFT JOIN latest_official_assignment oa ON t.tab_id = oa.tab_id AND oa.rn = 1
ORDER BY t.created_at DESC;
```
Note `latest_student_assignment` uses **inner** `JOIN pp.batch`/`pp.cohort` inside the CTE only for the batch/cohort-derived columns (`batch_name`, `cohort_name`) but this CTE itself is `LEFT JOIN`ed onto the outer query, and the CTE's internal `batch`/`cohort` joins are `LEFT JOIN` too — so a student with `batch_id IS NULL` or an orphan `cohort_number` still surfaces (with null batch/cohort), it's only students with **no `student_issue` row at all** who are absent from the CTE (fine, `LEFT JOIN latest_student_assignment` covers that at the outer level).

### 2.9 getTabHistory (model L243-259)
```sql
SELECT
  assignment_date, return_date, sm.student_name as name, sm.enr_id, 'Student' as category, NULL as staff_remark
FROM pp.student_issue si
JOIN pp.student_master sm ON si.student_id = sm.student_id
WHERE si.tab_id = $1
UNION ALL
SELECT
  assignment_date, return_date, u.user_name as name, NULL as enr_id, 'Staff' as category, remark as staff_remark
FROM pp.official_issue oi
JOIN pp."user" u ON oi.user_id = u.user_id
WHERE oi.tab_id = $1
ORDER BY assignment_date DESC
```

### 2.10 bulkCreateTabs (model L261-503) — transaction, two passes, see §4 for full narrative
Pre-scan (PASS 1) queries, run per-row inside the loop:
```sql
-- seed current holders for all serials in the batch (once, before the loop)
SELECT ti.serial_number, sm.enr_id, sm.student_id
FROM pp.tab_inventory ti
LEFT JOIN pp.student_issue si ON si.tab_id = ti.tab_id AND si.return_date IS NULL
LEFT JOIN pp.student_master sm ON sm.student_id = si.student_id
WHERE ti.serial_number = ANY($1)

-- per-row, only if dev.inventory_id present
SELECT serial_number FROM pp.tab_inventory WHERE inventory_id = $1

-- per-row, only if dev.imei present
SELECT serial_number FROM pp.tab_inventory WHERE imei = $1

-- per-row, only if dev.enr_id present
SELECT student_id FROM pp.student_master WHERE enr_id = $1
```
Apply (PASS 2) queries, run per-row:
```sql
SELECT tab_id FROM pp.tab_inventory WHERE serial_number = $1

-- if tab not found (new tablet):
INSERT INTO pp.tab_brand (brand_name, model_name, created_by)
VALUES ($1, $2, $3)
ON CONFLICT (brand_name, model_name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
RETURNING brand_id

INSERT INTO pp.tab_inventory (serial_number, imei, inventory_id, brand_id, status, remarks, created_by)
VALUES ($1, $2, $3, $4, $5, $6, $7)
RETURNING tab_id

-- if tab found (existing tablet):
UPDATE pp.tab_inventory SET status = $1, remarks = $2, updated_at = CURRENT_TIMESTAMP WHERE tab_id = $3

-- if enr_id present:
SELECT student_id FROM pp.student_master WHERE enr_id = $1

-- if normalizedStatus == 'ASSIGNED':
UPDATE pp.student_issue
   SET return_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP
 WHERE tab_id = $1 AND return_date IS NULL AND student_id != $2

INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
VALUES ($1, $2, $3, NULL, $4)
ON CONFLICT (tab_id, student_id)
DO UPDATE SET assignment_date = EXCLUDED.assignment_date, return_date = NULL, updated_at = CURRENT_TIMESTAMP

-- if normalizedStatus IN ('RETURNED','DAMAGED','LOST') AND enr_id present:
INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
VALUES ($1, $2, $3, $4, $5)
ON CONFLICT (tab_id, student_id)
DO UPDATE SET
  assignment_date = COALESCE(pp.student_issue.assignment_date, EXCLUDED.assignment_date),
  return_date = EXCLUDED.return_date,
  updated_at = CURRENT_TIMESTAMP

-- if normalizedStatus IN ('RETURNED','DAMAGED','LOST') AND NO enr_id (i.e. blank student column, generic return):
UPDATE pp.student_issue SET return_date = $1, updated_at = CURRENT_TIMESTAMP WHERE tab_id = $2 AND return_date IS NULL
```
All params are bound (`$1..$n`), no string-interpolated SQL text anywhere in this file — the "dynamic" part is entirely in **which query branch runs**, driven by `normalizedStatus` (server-computed after typo-mapping, model L271-276/403-408) and presence/absence of `enr_id`/`assignment_type`, not raw SQL string concatenation. The one place raw request values feed directly into a WHERE-comparable column without an enum gate is `changeTabStatus`'s `status` (see §2.4) and `bulkCreateTabs`'s `normalizedStatus` written straight into the `tab_inventory.status`/`INSERT ... VALUES` — both rely on the DB CHECK constraint as the actual gate, not app code. **Java port must add an explicit enum/whitelist check before either write**, both for a clean 400 message and defense-in-depth.

### 2.11 getEligibleStudents (model L505-517)
```sql
SELECT s.student_id, s.applicant_id, s.student_name, s.enr_id
FROM pp.student_master s
WHERE s.active_yn = 'ACTIVE'
AND NOT EXISTS (
    SELECT 1 FROM pp.student_issue si
    WHERE si.student_id = s.student_id AND si.return_date IS NULL
)
```

### 2.12 getTabStats (model L518-534)
```sql
SELECT
  COUNT(*) as total,
  COUNT(*) FILTER (WHERE status = 'IN_OFFICE') as in_office,
  COUNT(*) FILTER (WHERE status = 'DAMAGED') as damaged,
  COUNT(*) FILTER (WHERE status = 'LOST') as lost,
  COUNT(*) FILTER (WHERE status = 'RETURNED') as returned_awaiting,
  (SELECT COUNT(*) FROM pp.student_issue WHERE return_date IS NULL) as student_assigned,
  (SELECT COUNT(*) FROM pp.official_issue WHERE return_date IS NULL) as official_assigned
FROM pp.tab_inventory;
```
All `COUNT(...)` results are Postgres `bigint` → node-pg returns them as **strings** (e.g. `"total": "42"`), not JS numbers. The Java port must decide explicitly whether to serialize these as numeric JSON (breaking wire-compat with the frozen client) or as strings (matching current behavior) — **cross-check against how the React client parses `/tabs/stats`** before deciding; default to string-preserving to stay byte-compatible unless the client already does `Number(...)` on read.

### 2.13 getAllCohorts (model L536-540)
```sql
SELECT cohort_number, cohort_name FROM pp.cohort ORDER BY cohort_name ASC
```

### 2.14 getTabMovementReport (model L541-581) — **dynamic SQL (string-built, parameterized)**
Base (always run):
```sql
WITH sequential_issues AS (
  SELECT
    si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, c.cohort_name,
    LEAD(sm.student_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_holder,
    LEAD(c.cohort_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_cohort,
    LEAD(si.assignment_date) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as transfer_date
  FROM pp.student_issue si
  JOIN pp.student_master sm ON si.student_id = sm.student_id
  JOIN pp.batch b ON sm.batch_id = b.batch_id
  JOIN pp.cohort c ON b.cohort_number = c.cohort_number
)
SELECT
  t.serial_number, t.inventory_id, tb.brand_name, tb.model_name as model,
  si.student_name AS previous_holder, si.cohort_name AS from_cohort,
  si.next_holder AS new_holder, si.next_cohort AS to_cohort, si.transfer_date AS moved_at
FROM sequential_issues si
JOIN pp.tab_inventory t ON si.tab_id = t.tab_id
JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
WHERE si.next_cohort IS NOT NULL
```
Then conditionally appended (string concatenation of SQL **text**, but values are still bound as `$1`/`$2` placeholders — **not** raw interpolation of user data, just conditional clause assembly):
```sql
 AND si.cohort_name = $1   -- appended only if fromCohort present and != 'ALL'
 AND si.next_cohort = $2   -- appended only if toCohort present and != 'ALL' (index shifts to $1 if fromCohort absent)
```
Trailing, always appended:
```sql
 ORDER BY si.transfer_date DESC;
```
This is the one query in the module that builds SQL text conditionally (`query +=`), so **flag it structurally** even though the actual values are parameterized (`$1`/`$2`, no injection via values) — the risk in a Java port is purely about correctly reproducing the *conditional clause presence* and *parameter index shifting*, not SQL injection. `fromCohort`/`toCohort` come from `req.query` as raw strings compared against `si.cohort_name`/`si.next_cohort` (both derived from `pp.cohort.cohort_name`, a free-text `varchar(100)`) — exact string match, case-sensitive, no normalization.

Also note: `sequential_issues` CTE uses **inner** `JOIN pp.batch`/`JOIN pp.cohort` (not LEFT) — any `student_issue` row whose student has `batch_id IS NULL`, or whose batch's `cohort_number` doesn't resolve, is **silently dropped** from the movement report entirely (not just nulled). This differs from `getAllTabs`'s CTE which uses LEFT JOIN for the same relationship — an inconsistency to preserve faithfully (not "fix") in the port, since the Node behavior is the frozen contract.

## §3 Table DDL (authoritative: `V1__baseline.sql`, `imas-backend/src/main/resources/db/migration/`)

### pp.tab_inventory (V1__baseline.sql:1108-1122)
```sql
CREATE SEQUENCE pp.tab_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE pp.tab_inventory (
    tab_id numeric(20,0) DEFAULT nextval('pp.tab_id_seq'::regclass) NOT NULL,
    serial_number character varying(50) NOT NULL,
    brand_id integer NOT NULL,
    inventory_id character varying(40),
    imei character varying(40),
    tab_purchase_date date,
    status character varying(10) DEFAULT 'IN_OFFICE'::character varying,
    remarks text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    CONSTRAINT tab_inventory_status_check CHECK (((status)::text = ANY ((ARRAY['IN_OFFICE'::character varying, 'ASSIGNED'::character varying, 'RETURNED'::character varying, 'DAMAGED'::character varying, 'LOST'::character varying])::text[])))
);
-- tab_inventory_pkey PRIMARY KEY (tab_id)                     [line 1484-1485]
-- tab_inventory_serial_number_key UNIQUE (serial_number)      [line 1487-1488]
-- tab_inventory_imei_key UNIQUE (imei)                        [line 1478-1479]
-- tab_inventory_inventory_id_key UNIQUE (inventory_id)        [line 1481-1482]
-- tab_inventory_brand_fkey FOREIGN KEY (brand_id) REFERENCES pp.tab_brand(brand_id)   [1939-1940]
-- tab_inventory_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id) [1942-1943]
-- tab_inventory_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id) [1945-1946]
```
`status varchar(10)` — all 5 enum values (`IN_OFFICE`=9, `ASSIGNED`=8, `RETURNED`=8, `DAMAGED`=7, `LOST`=4 chars) fit within 10 chars; no truncation risk from the CHECK-constrained values themselves, but any post-typo-map value that is NOT one of the 5 canonical strings will fail the CHECK regardless of length.

### pp.tab_brand (V1__baseline.sql:1081-1099, 1289-1290, 1615-1619)
```sql
CREATE TABLE pp.tab_brand (
    brand_id integer NOT NULL,
    brand_name character varying(15) NOT NULL,
    model_name character varying(15) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);
CREATE SEQUENCE pp.tab_brand_brand_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE pp.tab_brand_brand_id_seq OWNED BY pp.tab_brand.brand_id;
ALTER TABLE ONLY pp.tab_brand ALTER COLUMN brand_id SET DEFAULT nextval('pp.tab_brand_brand_id_seq'::regclass);
-- tab_brand_pkey PRIMARY KEY (brand_id)                    [1475-1476]
-- brand_model_unique UNIQUE (brand_name, model_name)       [1289-1290]  <- matches the ON CONFLICT target used by createBrand and bulkCreateTabs. VALID.
-- brand_created_by_fkey FOREIGN KEY (created_by) REFERENCES pp."user"(user_id)  [1615-1616]
-- brand_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES pp."user"(user_id)  [1618-1619]
```
`brand_name varchar(15)` / `model_name varchar(15)` — **tight limits**. Any brand/model string over 15 chars (plausible for real tablet brand/model names, e.g. "Samsung Galaxy Tab A9+" is 22 chars) will raise a Postgres `value too long for type character varying(15)` error, caught generically and surfaced as a raw 500/409 message. Not a schema mismatch, but a real-world data-entry landmine to flag for the port (consider whether Java should pre-validate length for a friendlier 400).

### pp.student_issue (V1__baseline.sql:949-958, 1445-1446, 1888-1898)
```sql
CREATE TABLE pp.student_issue (
    tab_id integer NOT NULL,
    student_id numeric(14,0) NOT NULL,
    assignment_date date NOT NULL,
    return_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);
-- student_issue_pkey PRIMARY KEY (tab_id, student_id)   [1445-1446]  <- matches ON CONFLICT (tab_id, student_id). VALID.
-- student_issue_student_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id) [1891-1892]
-- student_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id)               [1894-1895]
-- student_issue_created_by_fkey / updated_by_fkey -> pp."user"(user_id)                          [1888-1889, 1897-1898]
```
**Note:** no `remark`/`remarks` column on `student_issue` (unlike `official_issue` which has `remark text`) — `getTabHistory`'s `NULL as staff_remark` for the student branch is correct/required, not an oversight; there is no student-side remark to select.

### pp.official_issue (V1__baseline.sql:657-667, 1385-1386, 1795-1805)
```sql
CREATE TABLE pp.official_issue (
    tab_id integer NOT NULL,
    user_id numeric(8,0) NOT NULL,
    assignment_date date NOT NULL,
    return_date date,
    remark text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0)
);
-- official_issue_pkey PRIMARY KEY (tab_id, user_id)    [1385-1386]  <- matches ON CONFLICT (tab_id, user_id). VALID.
-- official_issue_tab_fkey FOREIGN KEY (tab_id) REFERENCES pp.tab_inventory(tab_id)  [1798-1799]
-- official_issue_user_fkey FOREIGN KEY (user_id) REFERENCES pp."user"(user_id)      [1804-1805]
-- official_issue_created_by_fkey / updated_by_fkey -> pp."user"(user_id)             [1795-1796, 1801-1802]
```

### Related tables referenced (joins only, not owned by this module)
- `pp.student_master` (V1__baseline.sql:983-1017) — `student_id numeric(14,0)`, `enr_id numeric(11,0)`, `student_name varchar(100)`, `batch_id integer`, `active_yn varchar(10) DEFAULT 'ACTIVE'` with CHECK `ACTIVE|INACTIVE`. All columns referenced by tabInventory (`student_id`, `applicant_id`, `student_name`, `enr_id`, `active_yn`, `batch_id`) exist and match types used.
- `pp."user"` (V1__baseline.sql:1221-1238) — `user_id numeric(8,0)`, `user_name varchar(100)`, `locked_yn character(1)` (nullable, **no default**, CHECK `Y|N`). All referenced columns exist.
- `pp.batch` (V1__baseline.sql:182-193) — `batch_id integer`, `batch_name varchar(100)`, `cohort_number integer`. Matches.
- `pp.cohort` (V1__baseline.sql:257-271) — `cohort_number integer`, `cohort_name varchar(100)`. Matches.

### Cross-check verdict
**No schema-vs-code mismatches found.** Every table and column referenced by `tabInventoryModel.js` exists in `V1__baseline.sql` with compatible types, and every `ON CONFLICT` target used (`(brand_name, model_name)`, `(tab_id, student_id)`, `(tab_id, user_id)`) has a matching unique/primary-key constraint in the schema. This module is schema-clean, unlike other ported modules that hit broken `ON CONFLICT`/missing-column landmines — the real risks here are behavioral/validation (see §2.4, §2.14, §7), not schema drift.

## §4 Bulk + status-change + history detail

### bulkCreateTabs — two-pass design (model L261-503)
1. **PASS 1 (pre-scan, no writes except read-only SELECTs, all inside the same open transaction that gets rolled back if anything fails):**
   - Normalizes every row's `serial_number` (`trim().toUpperCase()`), and `status` via `STATUS_TYPO_MAP` (fixes common misspellings: `ASIGNED/ASSIGEND/ASSIGED→ASSIGNED`, `RETUREND/RETRUNED→RETURNED`, `DAMGED/DAMMAGED→DAMAGED`, `IN_OFICE/INOFFICE→IN_OFFICE`) then `.toUpperCase().trim().replace(/\s+/g,"_")`.
   - Seeds a `tabHolderMap` (serial_number → `{enrId, studentId}` or `null`) from a single batched query using `serial_number = ANY($1)`, reflecting who currently, actively (`return_date IS NULL`) holds each tab per the DB — **before** any row in this upload is processed.
   - For every device row: checks `inventory_id` conflict (different serial owns that inventory_id in DB → error, skip row), `imei` conflict (same logic), and `enr_id` existence (must resolve to a `student_master` row).
   - If `normalizedStatus === 'ASSIGNED'`: cross-checks against `tabHolderMap` — if the tab is currently/provisionally held by a **different** enr_id, records an error (`allErrors.push(...)`) and continues to the next row (this row is skipped, not fatal to the whole pass) — but if it IS a conflict-free assign, updates `tabHolderMap[serialNumber]` to the new holder so **later rows in the same upload** see the updated holder (sequential simulation within one pass).
   - If `normalizedStatus` is `RETURNED|DAMAGED|LOST|IN_OFFICE`: frees `tabHolderMap[serialNumber] = null` for subsequent rows.
   - **If `allErrors.length > 0` after scanning ALL rows: `ROLLBACK`, return `{success:false, errors: allErrors}`** — collects every error across the whole file in one pass rather than failing fast on row 1 (a deliberate, user-friendly behavior — the Java port must replicate "collect-all-errors-then-reject" semantics, not fail-fast).
2. **PASS 2 (apply, only runs if PASS 1 found zero errors):** re-normalizes serial/status per row (recomputes `STATUS_TYPO_MAP` inline again — literally redeclared inside the loop, model L403-408, redundant with the PASS 1 declaration at L271-276 but functionally identical) and for each row:
   - Looks up `tab_id` by `serial_number`. If absent: upserts (`ON CONFLICT DO UPDATE ... updated_at`) into `tab_brand` using `dev.brand_name || "Unknown"` / `dev.model_name || "Unknown"` as JS fallback (**not SQL `COALESCE`** — a falsy-but-present brand_name like `""` also triggers the `"Unknown"` fallback via `||`), then inserts a new `tab_inventory` row with the row's `normalizedStatus` as the initial `status`. If present: just `UPDATE`s `status`/`remarks`/`updated_at` on the existing row.
   - If `enr_id` present: resolves `student_id`, then branches on `normalizedStatus`:
     - `ASSIGNED`: first closes out (sets `return_date = CURRENT_DATE`) any **other** student's still-open `student_issue` row for this tab (`student_id != $2`) — this is the actual hand-over mechanic, guaranteed safe by the PASS 1 single-holder check — then upserts (`ON CONFLICT (tab_id, student_id) DO UPDATE`) the target student's own row to active (`return_date = NULL`).
     - `RETURNED|DAMAGED|LOST`: upserts the student's `student_issue` row with the provided/derived `return_date`, using `COALESCE(pp.student_issue.assignment_date, EXCLUDED.assignment_date)` to preserve an existing assignment_date if one exists.
   - If **no** `enr_id`: only acts when status is `RETURNED|DAMAGED|LOST` — blind `UPDATE ... WHERE tab_id=$1 AND return_date IS NULL` (closes whoever currently holds it, without needing to know who).
   - Returns `{success: true, count: devices.length}` (count is simply the input row count, **not** a count of rows actually changed).

### changeTabStatus — state machine (model L75-125)
Not a formally enumerated state machine in code — it's driven entirely by the incoming `status` string plus conditional side-effects:
- `status ∈ {RETURNED, DAMAGED, LOST}` → close any open `student_issue`/`official_issue` row for this tab (return_date = today or supplied `transaction_date`).
- `status === 'ASSIGNED'` → open (or re-open via upsert) exactly one of `student_issue`/`official_issue` depending on `assignment_type` (`'STUDENT'` or `'OFFICIAL'`) plus the corresponding id field. If `assignment_type` is neither, or the corresponding id is missing, **no assignment row is written at all** — only the final `tab_inventory.status` update runs, silently producing an inconsistent state (status=ASSIGNED but no active issue row). This is not validated/rejected anywhere in JS.
- `status === 'IN_OFFICE'` → no special-case branch; falls through to just the final inventory update (does NOT auto-close open issue rows — only `RETURNED/DAMAGED/LOST` do that). So setting status to `IN_OFFICE` directly, without going through `RETURNED` first, can leave a stale open `student_issue`/`official_issue` row while `tab_inventory.status = 'IN_OFFICE'`. **Preserve this exact behavior** (a real gap in the Node app, but the port must be byte-compatible, not "fixed," unless product explicitly signs off on a behavior change).
- Final step always runs: `UPDATE pp.tab_inventory SET status=$1, remarks=COALESCE($2,remarks), updated_at=NOW() WHERE tab_id=$3` — `remarks` is only overwritten if a non-null value is supplied (`COALESCE` keeps existing remarks on omit).
- **No dedicated tab-history/movement table exists.** "History" is reconstructed entirely from `student_issue` + `official_issue` rows (see `getTabHistory`, §2.9) — there is no separate audit-log insert anywhere in `changeTabStatus` or `bulkCreateTabs`. The only persisted trace of a status change is the mutated `student_issue`/`official_issue` rows plus the `tab_inventory.status`/`updated_at` overwrite (which loses the *previous* status — no history of status values themselves, only of assignment date ranges).

### getTabHistory (model L243-259) — see §2.9 for SQL
Reconstructs history by `UNION ALL`-ing all `student_issue` rows and all `official_issue` rows for the tab, tagging each with `category` (`'Student'`/`'Staff'`), ordered by `assignment_date DESC`. Because there's no true audit table, this reflects **current row state** of `student_issue`/`official_issue` (including their `return_date`), not a true point-in-time change log — e.g. if `bulkCreateTabs`' upsert path overwrote an existing row's `assignment_date`/`return_date` via `ON CONFLICT DO UPDATE`, the prior values are gone, not preserved as a separate history entry.

## §5 Response shapes & status codes

All responses follow `{success: boolean, ...}`. Every handler wraps in `res.status(...).json(...)`.

| # | Endpoint | Success | Body (success) | Error cases |
|---|----------|---------|-----------------|-------------|
| 1 | GET /tabs/stats | 200 | `{success:true, data: {total, in_office, damaged, lost, returned_awaiting, student_assigned, official_assigned}}` (all bigint COUNT results — strings in node-pg) | 500 `{success:false, message:"Internal Server Error"}` (generic message, error NOT echoed — differs from every other handler in this file) |
| 2 | GET /tabs/eligible-students | 200 | `{success:true, data:[{student_id, applicant_id, student_name, enr_id}, ...]}` (`student_id`/`applicant_id`/`enr_id` are numeric → strings) | 500 `{success:false, message:error.message}` |
| 3 | GET /tabs/brands | 200 | `{success:true, data:[{brand_id, brand_name, model_name}, ...]}` (`brand_id` integer → JS number) | 500 `{success:false, message:error.message}` |
| 4 | GET /tabs/users | 200 | `{success:true, data:[{user_id, user_name}, ...]}` (`user_id` numeric → string) | 500 `{success:false, message:error.message}` |
| 5 | GET /tabs/cohorts | 200 | `{success:true, data:[{cohort_number, cohort_name}, ...]}` (`cohort_number` integer → number) | 500 `{success:false, message:error.message}` |
| 6 | GET /tabs/movement-report | 200 | `{success:true, data:[{serial_number, inventory_id, brand_name, model, previous_holder, from_cohort, new_holder, to_cohort, moved_at}, ...]}` | 500 `{success:false, message:error.message}` |
| 7 | GET /tabs | 200 | `{success:true, data:[<row from §2.8>, ...]}` (`tab_id` numeric(20,0) → string; `brand_id` not selected here) | 500 `{success:false, message:error.message}` |
| 8 | POST /tabs | 201 | `{success:true, message:"Tablet created", data:{tab_id}}` | 400 `{success:false, message:"Required fields missing."}` if `serial_number`/`brand_id`/`created_by` absent; 409 `{success:false, message:"Serial number already exists in inventory."}` on PG unique-violation `23505`; 500 `{success:false, message:error.message}` otherwise |
| 9 | POST /tabs/bulk | 201 | `{success:true, count: <number of input rows>}` | 400 `{success:false, message:"Excel is empty"}` if `devices` missing/empty; 400 `{success:false, errors:[...]}` if pre-scan found row errors (note: this branch omits `message`, only `errors` array — inconsistent key vs. other 400s); 400 `{success:false, message:error.message \|\| "An error occurred during bulk upload"}` on unexpected throw |
| 10 | POST /tabs/brands | 201 | `{success:true, data:{brand_id, brand_name, model_name, created_at, updated_at, created_by, updated_by}}` (`RETURNING *`) | 400 `{success:false, message:"brand_name, model_name, and created_by are required."}`; 409 `{success:false, message:"This Brand and Model combination already exists."}` on `23505` (**note:** since `createBrand`'s SQL is `ON CONFLICT ... DO UPDATE`, not `DO NOTHING`, a `(brand_name, model_name)` collision is actually absorbed by the upsert and does NOT raise `23505` — this 409 branch is effectively **dead code** for that specific conflict; a `23505` could still arise from some other future unique constraint, but under current schema this path is unreachable via normal use); 500 `{success:false, message:error.message}` otherwise |
| 11 | GET /tabs/:tabId | 200 | `{success:true, data:<full tab_inventory row>}` | 404 `{success:false, message:"Not found"}` if no row; 500 `{success:false, message:error.message}` |
| 12 | PUT /tabs/:tabId/status | 200 | `{success:true, message:"Status updated successfully"}` | 400 `{success:false, message:error.message}` for ANY thrown error (including CHECK-constraint violations, FK violations, etc. — all surfaced as raw Postgres error text at 400, not 500) |
| 13 | DELETE /tabs/:tabId | 200 | `{success:true, message:"Deleted", data:{tab_id}}` (from `RETURNING tab_id` — **note:** if `tabId` didn't exist, `rows[0]` is `undefined`, so `data: undefined` — response is still 200, not 404; deleting a non-existent tab "succeeds" silently) | 500 `{success:false, message:error.message}` |
| 14 | GET /tabs/:tabId/history | 200 | `{success:true, data:[<row from §2.9>, ...]}` (empty array, not 404, if tab has no history) | 500 `{success:false, message:error.message}` |

**Numeric-id serialization summary (node-pg defaults, no custom type parsers found in this codebase for these columns):**
- `numeric(*)` columns (`tab_id`, `student_id`, `user_id`, `applicant_id`, `enr_id`) → **JS string** in JSON.
- `integer` columns (`brand_id`, `cohort_number`) → **JS number**.
- `bigint`/`COUNT(*)` results (all of `getTabStats`) → **JS string**.
- `boolean`, `date`, `text`, `varchar` → standard JS types (`date` → `YYYY-MM-DD` string via node-pg's date parsing, but note `createTab`'s `formatDate()` helper does its own `new Date(val).toISOString().split("T")[0]` conversion on the way IN, not out — that's for INSERT param formatting only, unrelated to how dates come back OUT in SELECT results).

## §6 Transactions

| Handler | Uses `pool.connect()` + BEGIN/COMMIT/ROLLBACK? | Statements in the transaction |
|---|---|---|
| `changeTabStatus` | **Yes** (model L78-124) | Up to 4 statements: close student_issue, close official_issue (conditional), insert/upsert student_issue OR official_issue (conditional, exactly one), final tab_inventory status update (always) |
| `deleteTab` | **Yes** (model L144-169) | 3 statements: delete student_issue rows, delete official_issue rows, delete tab_inventory row (RETURNING tab_id) |
| `bulkCreateTabs` | **Yes** (model L262-502) | Variable — PASS 1 runs N×(0-3) read SELECTs; if clean, PASS 2 runs N×(2-5) writes. Single BEGIN...COMMIT wraps BOTH passes — a mid-file error in PASS 2 (e.g. a length-15 brand_name violation on row 50 of 100) rolls back **everything already applied in PASS 2**, including rows 1-49, even though PASS 1 gave them a clean bill of health (PASS 1 doesn't validate brand/model length, IMEI/inventory_id length, etc. — only conflict/existence checks) |
| `getAllBrands`, `createBrand`, `createTab`, `getAllTabs`, `getTabById`, `getTabHistory`, `getEligibleStudents`, `getAllUsers`, `getTabStats`, `getAllCohorts`, `getTabMovementReport` | No — single `pool.query()` call, autocommit | n/a |

**All 3 transactional handlers follow the identical pattern:** `client = await pool.connect()` → `try { BEGIN; ...; COMMIT; return ...; } catch(err) { ROLLBACK; throw err; } finally { client.release(); }`. The Java port should use a single `@Transactional` boundary per handler matching this exact scope (not per-statement).

## §7 Quirks & complexity warnings (file:line)

| # | Quirk | Location | Port implication |
|---|---|---|---|
| 1 | **No auth middleware on any of the 14 routes** | `routes/tabInventoryRoutes.js` (whole file) | Confirm with product whether Spring Security should leave this open or add auth — do not silently add auth (would be a behavior change) or silently leave open (may be an oversight) without a decision |
| 2 | **`status` written to DB with no app-level enum/whitelist check** — relies solely on Postgres CHECK constraint for both `changeTabStatus` (raw `req.body.status`) and effectively `bulkCreateTabs` (typo-mapped but still unbounded) | `controllers/tabInventoryController.js:50-59`; `models/tabInventoryModel.js:75-125` (esp. L90, L112-115), `L314-315/409-410` | Add an explicit Java enum (`TabStatus{IN_OFFICE,ASSIGNED,RETURNED,DAMAGED,LOST}`) validated before any SQL, but preserve current behavior of accepting ONLY the exact 5 values (case-sensitive) once past the port's typo-map equivalent for bulk |
| 3 | **`changeTabStatus` writes an `ASSIGNED` inventory status even when no assignment row was actually written** (missing/invalid `assignment_type` or missing id) — no validation rejects this combination | `models/tabInventoryModel.js:90-109` | Decide whether to preserve (byte-compatible) or fix (behavior change) — flag explicitly in the plan; recommend preserving unless product signs off |
| 4 | **`status='IN_OFFICE'` does NOT auto-close open issue rows** (only RETURNED/DAMAGED/LOST do) — can leave `tab_inventory.status='IN_OFFICE'` with a stale open `student_issue`/`official_issue` row | `models/tabInventoryModel.js:84-87` (the `if (["RETURNED","DAMAGED","LOST"].includes(status))` guard excludes IN_OFFICE) | Preserve as-is; document as known gap, not a bug to silently fix |
| 5 | **`deleteTab` returns 200 with `data: undefined`/null for a non-existent `tabId`** — no 404 | `controllers/tabInventoryController.js:61-68`, `models/tabInventoryModel.js:143-170` | Preserve exact status code/body — do NOT "improve" to 404 unless asked |
| 6 | **`getTabStats` is the only handler that swallows the real error message**, always returning generic `"Internal Server Error"` on 500 (every other handler in this file echoes `error.message`) | `controllers/tabInventoryController.js:116-124` | Preserve this one inconsistency exactly — a naive port would "fix" it to match the others and silently break wire-compat |
| 7 | **`createBrand`'s 409 branch (`error.code === '23505'`) is effectively dead code** given the SQL is `ON CONFLICT DO UPDATE` (upsert absorbs the exact conflict it's targeting) | `controllers/tabInventoryController.js:13-32`, `models/tabInventoryModel.js:21-40` | Port can keep the same dead branch for parity/future-proofing, or note it's unreachable under current schema/constraints — either is fine since it never fires |
| 8 | **`bulkCreateTabs`' 400-with-`errors[]` response omits the `message` key** that every other error response in this controller includes | `controllers/tabInventoryController.js:126-147`, specifically L137-138 | Preserve the exact key shape: `{success:false, errors:[...]}` with NO `message` for this specific branch |
| 9 | **`bulkCreateTabs` count is `devices.length` (input row count), not actual rows changed** | `models/tabInventoryModel.js:495-496` | Preserve — do not "fix" to a real changed-row count |
| 10 | **`tab_brand.brand_name`/`model_name` are `varchar(15)`** — real-world brand/model names commonly exceed this (e.g. "Samsung Galaxy Tab A9+" = 22 chars); any insert/update over 15 chars throws a raw Postgres length error, caught generically as 500 (single create) or added to the PASS-2-only failure surface for bulk (not caught by PASS 1's pre-scan, so it can abort a bulk transaction partway through, see §6) | `V1__baseline.sql:1081-1089` (`brand_name character varying(15)`, `model_name character varying(15)`) | Recommend adding this exact length check to Java validation (a friendlier 400 pre-check) — but note the Node app currently has NO such pre-check, so this is an *improvement opportunity* to flag for product/plan discussion, not a required parity item |
| 11 | **`locked_yn character(1)` has no DEFAULT and is nullable** — `getAllUsers`'s `WHERE locked_yn = 'N'` silently excludes users with `locked_yn IS NULL` (three-valued SQL logic), not just those explicitly locked (`'Y'`) | `V1__baseline.sql:1225` (no DEFAULT); `models/tabInventoryModel.js:128-141` (L133) | Preserve the exact `= 'N'` comparison (not `IS DISTINCT FROM 'Y'` or similar) in the Java/SQL translation |
| 12 | **`getAllTabs`'s CTEs use LEFT JOIN for batch/cohort**, but **`getTabMovementReport`'s CTE uses INNER JOIN** for the same `student_issue → student_master → batch → cohort` chain — inconsistent, and the movement report silently drops rows where a student has `batch_id IS NULL` or an unresolvable `cohort_number` | `models/tabInventoryModel.js:187-189` (LEFT) vs. `L550-552` (INNER) | Preserve both behaviors exactly as-is per query — do not harmonize |
| 13 | **`getTabMovementReport` builds SQL text conditionally** (`query += " AND ... = $N"`) based on `fromCohort`/`toCohort` presence, with parameter index shifting (`$1` becomes `$2` if both filters present, or stays `$1` if only one is) — values themselves are always bound placeholders, not string-interpolated, so this is NOT a SQL-injection risk, but the *conditional clause + index arithmetic* must be reproduced exactly (e.g. via `JdbcClient`'s named/positional param builder or a `StringBuilder` mirroring the same conditional structure) | `models/tabInventoryModel.js:541-579` | Java port: build the WHERE clause conditionally with the same two optional predicates, using `'ALL'` (not null/empty) as the Node-equivalent "no filter" sentinel value from the query string — reproduce that sentinel check (`fromCohort && fromCohort !== "ALL"`) exactly |
| 14 | **`bulkCreateTabs`' `STATUS_TYPO_MAP` and normalization logic is declared/duplicated in BOTH pass 1 (L271-276) and pass 2 (L403-408)** — functionally identical, just redundant code, not a behavioral risk, but note when porting to avoid drift between two Java copies of the same map | `models/tabInventoryModel.js:271-276` and `:403-408` | Extract to one shared constant/method in Java (safe simplification, not a behavior change) |
| 15 | **No dedicated tab-history/audit table** — `getTabHistory`/`getTabMovementReport` both reconstruct "history" from live `student_issue`/`official_issue` row state, which is itself subject to being overwritten by upserts (`ON CONFLICT DO UPDATE`) in `changeTabStatus`/`bulkCreateTabs` — so prior `assignment_date`/`return_date` values before an upsert are NOT retained anywhere | Whole module — no `tab_history`/`tab_movement` table in `V1__baseline.sql` | Confirms §1's endpoint list — there is no 15th "history table" to migrate; the Java port's "history" queries are just reads over `student_issue`/`official_issue`, same as Node |
| 16 | **Numeric id serialization**: `tab_id`, `student_id`, `user_id`, `applicant_id`, `enr_id` are all `numeric(*)` → strings in node-pg JSON; `brand_id`, `cohort_number` are `integer` → numbers; `COUNT(*)` results in `getTabStats` are bigint → strings | See §5 table | Java/Jackson must be configured to match this exactly per-field (e.g. `BigDecimal`/`String` for numeric columns vs. `Integer` for true integer columns) — a generic "everything as number" serialization will break wire-compat with the frozen React client |
| 17 | **Route ordering** — static routes (`/tabs/stats`, `/tabs/eligible-students`, `/tabs/brands`, `/tabs/users`, `/tabs/cohorts`, `/tabs/movement-report`) must remain distinguishable from `/tabs/{tabId}` | `routes/tabInventoryRoutes.js:5-23` | Spring MVC handles static-vs-variable precedence automatically, but keep the 6 static endpoints as literal `@GetMapping` paths, not accidentally folded into a `{tabId}` handler via a permissive path pattern |

## §8 Summary

- **Endpoint count: 14** (6 static GET, 3 collection POST/GET, 4 dynamic `/:tabId`, 1 collection GET `/tabs`).
- **Schema check: clean.** No table/column referenced by the Node code is missing from `V1__baseline.sql`; all three `ON CONFLICT` targets (`tab_brand(brand_name,model_name)`, `student_issue(tab_id,student_id)`, `official_issue(tab_id,user_id)`) have matching constraints. This module does NOT have the broken-`ON CONFLICT`/missing-column landmines seen in other ported modules.
- **Top risks/landmines for the implementation plan:**
  1. **No auth on any route** (§0, §7-1) — needs an explicit product decision before the Java port locks in a security posture; don't guess.
  2. **`status` values are validated only by the Postgres CHECK constraint**, not app code, in both `changeTabStatus` (§2.4) and `bulkCreateTabs` (§2.10/§4) — the Java port must add an explicit whitelist/enum both for defense-in-depth and for a clean 400 instead of a leaked Postgres error string; this is the primary "injection-adjacent" surface even though it's not classic SQL injection (all params are bound).
  3. **`bulkCreateTabs` is the highest-complexity handler**: two-pass (validate-all-then-apply-all) transaction, a `tabHolderMap` simulated across rows within PASS 1, typo-normalization duplicated in two places, and a single transaction boundary spanning both passes (§4, §6, §7-14) — recommend its own dedicated sub-task in the implementation plan, separate from the other 13 simpler CRUD-style endpoints.
  4. **Several intentionally-preserved inconsistencies** must NOT be "fixed" during the port: `getTabStats`' generic 500 message (§7-6), bulk's 400 response missing `message` key (§7-8), `deleteTab`'s 200-on-missing-row (§7-5), `getAllTabs` LEFT JOIN vs. `getTabMovementReport` INNER JOIN for the same relationship (§7-12), and `IN_OFFICE` not auto-closing open issue rows (§7-4) — each is a deliberate parity requirement, easy for an agent to "clean up" by mistake.
  5. **Numeric serialization fidelity** (`numeric`→string vs `integer`→number vs `bigint`→string) must be field-by-field matched in Jackson config to avoid silently breaking the frozen React client (§5, §7-16).
  6. Recommended plan split: (a) 6 static read-only GETs, (b) `getAllTabs`+`getTabById`+`getTabHistory`+`deleteTab` (the `/:tabId`-scoped reads/deletes), (c) `createTab`+`createBrand`+`getAllBrands` (simple writes), (d) `changeTabStatus` (transactional state-change), (e) `bulkCreateTabs` alone (highest complexity, two-pass transaction), (f) `getTabMovementReport` (dynamic-clause query) — six focused sub-tasks total.
