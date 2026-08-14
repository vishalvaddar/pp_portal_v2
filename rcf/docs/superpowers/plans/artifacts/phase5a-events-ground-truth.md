# EVENTS Module — Ground Truth (for Plan 5a)

Captured from a full read of the Node source, top to bottom, live code only (no dead/commented predecessor blocks were found in these files — they are short and clean). Base mount: `app.use("/api", eventRoutes)` (`server/index.js:300`).

Files read:
- `server/routes/eventRoutes.js` (75 lines)
- `server/controllers/eventController.js` (312 lines)
- `server/middleware/eventMiddleware.js` (213 lines)
- `server/models/eventModel.js` (505 lines)
- `server/index.js` (storage/static setup, lines 1-32, 130-165, 260-304)
- Frozen client (skim): `client/src/pages/Admin/Events/*.js`, `client/src/pages/Admin/Reports/SammelanReports.js`

**Dead code found (flag, do not port):** `eventModel.js` exports four functions never called by any live controller: `getMarkedSammelanStudents` (line 374), `removeSammelanAttendance` (line 402), `getExistingEventStudents` (line 441), `editSammelanAttendanceSync` (line 463). These look like an abandoned "edit attendance" implementation superseded by `submitAttendance` + `saveSammelanAttendance`. Do not port unless a currently-unmapped client call is found later.

## §0 Scoping note — file storage & static serving

**Env var:** `EVENT_STORAGE_PATH` (optional). Two separate places compute the same directory independently and must agree:
- `server/index.js:18`: `const EVENT_BASE_DIR = process.env.EVENT_STORAGE_PATH || path.join(__dirname, "uploads", "events");`
- `server/middleware/eventMiddleware.js:9-11`: `const BASE_EVENT_DIR = process.env.EVENT_STORAGE_PATH ? path.resolve(process.env.EVENT_STORAGE_PATH) : path.join(__dirname, "..", "uploads", "events");`

Both resolve to `<server>/uploads/events` by default (index.js is one level up from middleware, so `__dirname` differs but the join target is identical). Subdirs: `photos/` and `reports/`. Both created recursively at startup if missing (`index.js:22-32`, `index.js:159-164`, `eventMiddleware.js:17-19,31`).

**Static mounts** (`index.js:147-155`):
```js
app.use("/uploads/events/photos", express.static(EVENT_PHOTOS_DIR));
app.use("/uploads/events/reports", express.static(EVENT_REPORTS_DIR));
```
So a photo saved as `<EVENT_PHOTOS_DIR>/foo-1.jpg` is served at `GET /uploads/events/photos/foo-1.jpg`. The Java port MUST serve identical URL prefixes and preserve the stored filename convention (see §4) because the client reconstructs URLs from just the basename of the DB-stored `file_path` (see §7).

**Filename generation** (`eventMiddleware.js:48-72`, multer `filename` callback):
1. `eventTitle` is read from `req.body.eventTitle` (falls back to literal `"event"` if absent — note this is `eventTitle`, camelCase, NOT `event_title`; see §4 landmine).
2. Cleaned: `eventTitle.replace(/[^a-z0-9]/gi, '_').toLowerCase()`.
3. Extension: `path.extname(file.originalname).toLowerCase()`.
4. Photos: a per-request counter `req.photoIndex` (starts at 1, increments per file in the same request) produces `<cleanname>-<n><ext>`, e.g. `sammelan_2026-1.jpg`.
5. Reports: always `<cleanname>-report<ext>` (no counter — a second report upload in the same collection overwrites the same filename on disk, though DB rows are separately managed, see §4).

No collision handling across requests/events: two different events with titles that clean to the same string will overwrite each other's files on disk. Flag as a quirk to preserve or fix — ask product before "fixing" in the port.

## §1 Endpoint inventory

Mounted under `/api`. **No route in this module uses any `authenticate`/auth middleware** — confirmed by grepping `server/index.js` and `eventRoutes.js` for `authenticate`/`verifyToken`/`requireAuth`: zero matches. All 12 routes are open to any caller who can reach the API (relies purely on `req.user?.user_id` being present when some other global middleware happens to populate it — see §7).

| # | Method | Path | Middleware chain | Controller fn(s) | Purpose | Auth |
|---|--------|------|-------------------|-------------------|---------|------|
| 1 | POST | `/event-types` | — | `createEventType` | Create a lookup event type | none |
| 2 | PUT | `/event-type/:id` | `validateEventId` | `updateEventType` | Rename an event type | none |
| 3 | GET | `/event-types` | — | `getEventTypes` | List event types (dropdown) | none |
| 4 | POST | `/events` | `uploadEventFiles → sanitizeEventNumbers → validateEventBody` | `createEvent` **then** `updateEvent` (both run) | Create event + optional photos | none |
| 5 | GET | `/events` | — | `getAllEvents` | List all events (dashboard) | none |
| 6 | GET | `/events/:id` | `validateEventId` | `getEventById` | Event detail + photos + reports | none |
| 7 | PUT | `/events/:id` | `validateEventId → uploadEventFiles → sanitizeEventNumbers → validateEventBody` | `updateEvent` | Update event, photos, reports, Sammelan count sync | none |
| 8 | DELETE | `/events/:id` | `validateEventId` | `deleteEvent` | Cascade-delete event + students + photos + reports | none |
| 9 | GET | `/attendance/sammelan-list` | — | `getSammelanEvents` | Dropdown of events whose type is `'Sammelan'` | none |
| 10 | GET | `/attendance/jurisdictions` | — | `getJurisdictionData` | Cascading state/division/district/block picker | none |
| 11 | POST | `/attendance/students-list` | — | `fetchStudentAttendanceList` | Paginated student list for marking attendance | none |
| 12 | POST | `/attendance/save` | `uploadEventFiles` | `submitAttendance` | Persist attendance + counts + optional photos/report | none |

## §2 Exact SQL

### Event type (`eventModel.js`)

**createEventType** (line 7-15):
```sql
INSERT INTO pp.event_type (event_type_name)
VALUES ($1)
RETURNING *
```

**updateEventType** (line 17-26):
```sql
UPDATE pp.event_type
SET event_type_name = $1
WHERE event_type_id = $2
RETURNING *
```

**getEventTypes** (line 28-36):
```sql
SELECT event_type_id, event_type_name
FROM pp.event_type
ORDER BY event_type_name ASC
```

**getEventTypeByName** (line 38-46) — defined but never called by any live controller:
```sql
SELECT *
FROM pp.event_type
WHERE event_type_name = $1
```

### Event master (`eventModel.js`)

**createEvent(client, values)** (line 52-76), run inside the caller's transaction, 15 positional params:
```sql
INSERT INTO pp.event_master (
  event_type_id, event_title, event_description, event_start_date, event_end_date,
  event_district, event_block, event_location, pincode, cohort_number,
  boys_attended, girls_attended, parents_attended, created_by, updated_by
)
VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
RETURNING event_id
```

**updateEvent(client, values)** (line 79-101), 15 positional params (order differs from create — no `event_description`... wait it does have all master columns except it ends with `updated_by`, `WHERE event_id`):
```sql
UPDATE pp.event_master
SET
  event_type_id = $1, event_title = $2, event_description = $3,
  event_start_date = $4, event_end_date = $5, event_district = $6,
  event_block = $7, event_location = $8, pincode = $9, cohort_number = $10,
  boys_attended = $11, girls_attended = $12, parents_attended = $13,
  updated_by = $14, updated_at = CURRENT_TIMESTAMP
WHERE event_id = $15
```

**deleteEvent(eventId)** (line 104-126) — self-contained transaction (own `pool.connect()`/BEGIN/COMMIT, not the shared `client`):
```sql
DELETE FROM pp.event_students WHERE event_id = $1;
DELETE FROM pp.event_photos WHERE event_id = $1;
DELETE FROM pp.event_reports WHERE event_id = $1;
DELETE FROM pp.event_master WHERE event_id = $1;
```
Run in this exact order, each a separate statement/round-trip. **LANDMINE: `pp.event_students` does not exist in `V1__baseline.sql`** — see §3/§7.

**getAllEvents()** (line 177-203):
```sql
SELECT
  m.event_id, m.event_title, m.event_description,
  m.event_start_date AS start_date, m.event_end_date AS end_date,
  m.event_location, m.cohort_number,
  m.boys_attended, m.girls_attended, m.parents_attended,
  t.event_type_name AS event_type,
  (
    SELECT p.file_path FROM pp.event_photos p
    WHERE p.event_id = m.event_id LIMIT 1
  ) AS cover_photo
FROM pp.event_master m
JOIN pp.event_type t ON t.event_type_id = m.event_type_id
ORDER BY m.event_start_date DESC
```
Note: `cover_photo` subquery has no `ORDER BY` inside it, so "first photo" is whatever Postgres returns first (undefined order in practice, usually insertion order) — not deterministic. Port literally; do not silently add an ORDER BY unless asked.

**getEventById(eventId)** (line 205-216):
```sql
SELECT m.*, t.event_type_name
FROM pp.event_master m
JOIN pp.event_type t ON t.event_type_id = m.event_type_id
WHERE m.event_id = $1
```
`m.*` — every `event_master` column is returned, including `event_district`/`event_block` as raw numeric jurisdiction codes (not names) and `created_at`/`updated_at`/`created_by`/`updated_by`.

### Event photos / reports

**insertPhoto(db, values)** (line 132-138):
```sql
INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
VALUES ($1, $2, $3, $4)
```

**getEventPhotos(eventId)** (line 140-148):
```sql
SELECT photo_id, file_path, file_name
FROM pp.event_photos
WHERE event_id = $1
```

**insertEventReport(db, values)** (line 154-160):
```sql
INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
VALUES ($1, $2, $3, $4, $5)
```

**getEventReports(eventId)** (line 162-171):
```sql
SELECT *
FROM pp.event_reports
WHERE event_id = $1
ORDER BY generated_at DESC
```

**deleteOldReport(client, eventId)** (line 416-427) — called only from `updateEvent` controller when a new report file arrives:
```sql
SELECT file_path FROM pp.event_reports WHERE event_id = $1 AND report_type = 'SAMMELAN_REPORT';
DELETE FROM pp.event_reports WHERE event_id = $1 AND report_type = 'SAMMELAN_REPORT';
```
Fetches paths first (so caller *could* unlink from disk), then deletes rows. **The controller (`eventController.js:162`) never uses the returned `rows` to unlink the old file from disk** — old report files are orphaned on disk when replaced. Flag as a quirk; port literally (DB rows correct, disk file leaked) unless asked to fix.

### Attendance / jurisdiction (`eventModel.js`)

**getSammelanEvents()** (line 221-230):
```sql
SELECT em.event_id, em.event_title
FROM pp.event_master em
JOIN pp.event_type et ON et.event_type_id = em.event_type_id
WHERE et.event_type_name = 'Sammelan'
```
Hard-coded string literal `'Sammelan'` — case-sensitive exact match, no `ILIKE`/trim. If the event-type row is ever named `'sammelan'` or `' Sammelan'` it silently disappears from this list.

**getStates()** (line 232-236):
```sql
SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'
```

**getDivisionsByState(stateName)** (line 238-247):
```sql
SELECT juris_code, juris_name FROM pp.jurisdiction
WHERE parent_juris IN (
  SELECT juris_code FROM pp.jurisdiction
  WHERE LOWER(TRIM(juris_name)) = LOWER(TRIM($1)) AND LOWER(juris_type) = 'state'
) AND LOWER(juris_type) = 'division'
```

**getDistrictsByDivisions(divisionNames)** (line 249-265) — Node lower-cases/trims the array in JS before binding:
```sql
SELECT juris_code, juris_name FROM pp.jurisdiction
WHERE parent_juris IN (
  SELECT juris_code FROM pp.jurisdiction
  WHERE LOWER(TRIM(juris_name)) = ANY($1)
  AND LOWER(juris_type) = 'division'
) AND LOWER(juris_type) = 'education district'
```
Note: filters on `juris_type = 'education district'`, NOT `'district'`. This is the jurisdiction-hierarchy quirk shared with other modules (see phase4* docs) — `district` in the applicant table maps to `juris_type = 'education district'` rows.

**getBlocksByMultiDistricts(stateName, divisionNames, districtNames)** (line 267-314), 3 params, JS-side lower/trim of arrays:
```sql
SELECT j.juris_code, j.juris_name,
  CASE WHEN j.juris_code IN (
    SELECT sbj.juris_code FROM pp.shortlist_batch_jurisdiction AS sbj
    JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
    WHERE sb.frozen_yn = 'Y'
  ) THEN TRUE ELSE FALSE END AS is_frozen_block
FROM pp.jurisdiction AS j
WHERE LOWER(j.juris_type) = 'block'
  AND j.parent_juris IN (
    SELECT d.juris_code FROM pp.jurisdiction d
    WHERE LOWER(TRIM(d.juris_name)) = ANY($3)
      AND LOWER(d.juris_type) = 'education district'
      AND d.parent_juris IN (
        SELECT div.juris_code FROM pp.jurisdiction div
        WHERE LOWER(TRIM(div.juris_name)) = ANY($2)
          AND LOWER(div.juris_type) = 'division'
          AND div.parent_juris IN (
            SELECT s.juris_code FROM pp.jurisdiction s
            WHERE LOWER(TRIM(s.juris_name)) = LOWER(TRIM($1))
              AND LOWER(s.juris_type) = 'state'
          )
      )
  )
```
`$1 = stateName` (raw, not lowered by JS — the SQL itself does `LOWER(TRIM($1))`), `$2 = lowerDivisions[]`, `$3 = lowerDistricts[]`. `is_frozen_block` is computed against `pp.shortlist_batch_jurisdiction`/`pp.shortlist_batch` — cross-module coupling with the shortlisting feature (see phase2c doc); this flag is returned but its consumption by the client for the events attendance flow was not found in `EventDetailsPage.js` — likely vestigial/copy-pasted from a shortlisting jurisdiction picker.

**getSammelanStudentList(filters)** (line 316-365), called from `fetchStudentAttendanceList`, 8 params:
```sql
SELECT DISTINCT
    sm.student_id,
    sm.student_name,
    bl.juris_name AS block_name,
    d.juris_name AS district_name,
    (es.student_id IS NOT NULL) AS is_marked
FROM pp.student_master sm
JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
LEFT JOIN pp.event_students es ON sm.student_id = es.student_id AND es.event_id = $1
LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
LEFT JOIN pp.jurisdiction s ON a.app_state = s.juris_code
LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
WHERE sm.active_yn = 'ACTIVE'
  AND b.cohort_number = $2
  AND ($3::text IS NULL OR s.juris_name = $3)
  AND ($4::text[] IS NULL OR d.juris_name = ANY($4))
  AND ($5::text[] IS NULL OR bl.juris_name = ANY($5))
  AND ($6::text IS NULL OR sm.student_name ILIKE '%' || $6 || '%')
ORDER BY sm.student_name
LIMIT $7 OFFSET $8;
```
Params: `$1=eventId, $2=cohortNumber, $3=stateName, $4=districtNames[], $5=blockNames[], $6=searchName (always null — controller never sets it), $7=limit(15), $8=offset`. **LANDMINE: joins `pp.event_students` which does not exist in `V1__baseline.sql`** (see §3/§7) — this query cannot run against the current baseline schema as-is.

### Attendance controller-inline SQL (`eventController.js`, not in the model file)

**fetchStudentAttendanceList** — event lookup by title (line 226-229):
```sql
SELECT event_id, cohort_number FROM pp.event_master WHERE event_title = $1
```
Note `event_title` has a UNIQUE constraint in the DDL (`event_master_event_title_key`), so this is safe as a single-row lookup, but any duplicate-title edge case (pre-constraint legacy data) would silently pick an arbitrary row — not a realistic concern given the constraint.

**submitAttendance** — gender counts (line 270-274):
```sql
SELECT gender, COUNT(*) as count
FROM pp.student_master
WHERE student_id = ANY($1::int[])
GROUP BY gender
```

**submitAttendance** — master sync (line 285-292):
```sql
UPDATE pp.event_master
SET boys_attended = $1, girls_attended = $2, parents_attended = $3,
    updated_by = $4, updated_at = CURRENT_TIMESTAMP
WHERE event_id = $5
```

**updateEvent controller** — Sammelan auto-count sync, run only `if (event_type_name === 'Sammelan')` (line 138-152):
```sql
SELECT
    COUNT(*) FILTER (WHERE UPPER(gender) IN ('M','MALE')) as boys,
    COUNT(*) FILTER (WHERE UPPER(gender) IN ('F','FEMALE')) as girls
FROM pp.student_master sm
JOIN pp.event_students es ON sm.student_id = es.student_id
WHERE es.event_id = $1
```
then:
```sql
UPDATE pp.event_master SET boys_attended = $1, girls_attended = $2 WHERE event_id = $3
```
Second `pp.event_students` reference that will fail against the baseline schema (see §3/§7). Also note this UPDATE does **not** set `updated_by`/`updated_at`, unlike every other master UPDATE in this module — inconsistent audit trail, port literally.

**updateEvent controller** — photo delete, only if `photos_to_delete` present (line 119-124):
```sql
DELETE FROM pp.event_photos WHERE photo_id = ANY($1::int[])
```
`$1` = `JSON.parse(photos_to_delete)` — a JS array parsed from a JSON string in the multipart body (see §4). **No `event_id` filter** — any photo_id, from any event, can be deleted via this endpoint if the caller knows the id. Cross-event deletion is possible; flag as an authorization/scoping gap to preserve-or-fix (ask product).

## §3 Table DDL (from `imas-backend/src/main/resources/db/migration/V1__baseline.sql`)

### `pp.event_master` (line 383-402), PK `event_id` (line 1337-1338), UNIQUE `event_title` (line 1334-1335)
```sql
CREATE SEQUENCE pp.event_master_event_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE pp.event_master (
    event_id integer DEFAULT nextval('pp.event_master_event_id_seq'::regclass) NOT NULL,
    event_type_id integer,
    event_title character varying(150),
    event_start_date date NOT NULL,
    event_end_date date,
    event_district numeric(12,0),
    event_block numeric(12,0),
    event_location character varying(150),
    pincode character varying(12),
    cohort_number integer,
    boys_attended integer DEFAULT 0,
    girls_attended integer DEFAULT 0,
    parents_attended integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by numeric(8,0),
    updated_by numeric(8,0),
    event_description character varying(255)
);
```
FKs (line 1693-1706): `event_block → pp.jurisdiction(juris_code)`, `cohort_number → pp.cohort(cohort_number)`, `created_by → pp."user"(user_id)`, `event_district → pp.jurisdiction(juris_code)`, `updated_by → pp."user"(user_id)`. Plus `fk_event_type: event_type_id → pp.event_type(event_type_id)` (line 1732-1733).

**LANDMINE #1: `event_start_date` is `NOT NULL` in the DDL, but `createEvent` (`eventController.js:56,66-82`) inserts `event_start_date` straight from `req.body.event_start_date` with no explicit validation beyond `validateEventBody` requiring it non-empty** — consistent, this is fine, but note the middleware validation (`eventMiddleware.js:155-159`) is the only thing standing between a null insert and a DB constraint violation; the Java port must replicate that 400 pre-check or a raw `NOT NULL` DB error will leak through as a 500.

**LANDMINE #2: `pincode` in the DDL is `character varying(12)`, but `sanitizeEventNumbers` (`eventMiddleware.js:196`) coerces `pincode` to a JS `Number`** before it reaches the INSERT. A numeric pincode like `"560001"` becomes JS number `560001`, which node-pg will still bind fine as a varchar parameter (implicit stringification), but a pincode with a leading zero or non-numeric char (some Indian PIN codes theoretically don't, but the column is varchar for a reason — maybe alphanumeric international mail codes) would be coerced to `NaN`→ actually `sanitizeEventNumbers` only overwrites when `!isNaN(field)`, so non-numeric pincodes pass through as strings unchanged, but purely-numeric ones become JS numbers and lose leading zeros if any existed. Low risk in practice (Indian PINs don't have leading zeros) but flag as an int-vs-varchar type mismatch to replicate exactly (Number coercion, not string) in Java.

### `pp.event_photos` (line 404-418), PK `photo_id` (line 1340-1341)
```sql
CREATE SEQUENCE pp.event_photos_photo_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE pp.event_photos (
    photo_id integer DEFAULT nextval('pp.event_photos_photo_id_seq'::regclass) NOT NULL,
    event_id integer,
    file_path text NOT NULL,
    file_name character varying(100),
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    uploaded_by numeric(8,0)
);
```
FKs (line 1708-1712): `event_id → pp.event_master(event_id) ON DELETE CASCADE`, `uploaded_by → pp."user"(user_id)`. Note: `ON DELETE CASCADE` on `event_id` means the model's manual `DELETE FROM pp.event_photos WHERE event_id = $1` in `deleteEvent` (line 113) is actually redundant with the DB-level cascade — deleting `event_master` alone would already cascade-delete photos and reports. Harmless but worth noting; port literally (explicit deletes first, then master) since order doesn't matter given the cascade.

### `pp.event_reports` (line 420-435), PK `report_id` (line 1343-1344)
```sql
CREATE SEQUENCE pp.event_reports_report_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE pp.event_reports (
    report_id integer DEFAULT nextval('pp.event_reports_report_id_seq'::regclass) NOT NULL,
    event_id integer,
    report_type character varying(50),
    file_path text NOT NULL,
    file_name character varying(150),
    generated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    generated_by numeric(8,0)
);
```
FKs (line 1714-1718): `event_id → pp.event_master(event_id) ON DELETE CASCADE`, `generated_by → pp."user"(user_id)`.

### `pp.event_type` (line 437-450), PK `event_type_id` (line 1349-1350), UNIQUE `event_type_name` (line 1346-1347)
```sql
CREATE TABLE pp.event_type (
    event_type_id integer NOT NULL,
    event_type_name character varying(100) NOT NULL
);
CREATE SEQUENCE pp.event_type_event_type_id_seq AS integer START WITH 1 INCREMENT BY 1 ...;
ALTER SEQUENCE pp.event_type_event_type_id_seq OWNED BY pp.event_type.event_type_id;
ALTER TABLE ONLY pp.event_type ALTER COLUMN event_type_id SET DEFAULT nextval('pp.event_type_event_type_id_seq'::regclass);
```

### **LANDMINE #3 — CRITICAL: `pp.event_students` does not exist anywhere in `V1__baseline.sql`.**
Verified with a full-file grep for `event_students` — zero matches, and no other migration file exists in `imas-backend/src/main/resources/db/migration/` (only `V1__baseline.sql`). Yet the Node code references this table in **four places**:
- `eventModel.js:110` — `deleteEvent`: `DELETE FROM pp.event_students WHERE event_id = $1`
- `eventModel.js:337` — `getSammelanStudentList`: `LEFT JOIN pp.event_students es ON sm.student_id = es.student_id AND es.event_id = $1`
- `eventModel.js:429-437` — `saveSammelanAttendance`: `INSERT INTO pp.event_students (event_id, student_id) SELECT $1, unnest($2::int[]) ON CONFLICT (event_id, student_id) DO NOTHING RETURNING student_id`
- `eventController.js:143` — `updateEvent`'s Sammelan sync: `JOIN pp.event_students es ON sm.student_id = es.student_id`

`saveSammelanAttendance`'s `ON CONFLICT (event_id, student_id)` clause implies the (missing) table has a composite unique constraint/PK on `(event_id, student_id)`. Since it's referenced by `pp.event_master(event_id)` conceptually and `pp.student_master(student_id)`, the Java port must **add this table via a new Flyway migration** before porting any Events/Attendance code — it cannot be ported against baseline as-is. Suggested minimal DDL to reconstruct from usage (confirm with product/DBA before applying — this is inferred, not verbatim from any source):
```sql
CREATE TABLE pp.event_students (
    event_id integer NOT NULL REFERENCES pp.event_master(event_id) ON DELETE CASCADE,
    student_id numeric(14,0) NOT NULL REFERENCES pp.student_master(student_id),
    PRIMARY KEY (event_id, student_id)
);
```

### Read-only tables touched (for context, not owned by this module)
- `pp.jurisdiction` (line 628-637): `juris_code numeric(12,0) PK-like, juris_name varchar(100), juris_type varchar(100), parent_juris numeric(12,0)`.
- `pp.student_master` (line 983-1017): `student_id numeric(14,0)`, `applicant_id numeric(14,0)`, `student_name varchar(100)`, `gender character(1) CHECK IN ('M','F','O')`, `batch_id integer`, `active_yn varchar(10) DEFAULT 'ACTIVE' CHECK IN ('ACTIVE','INACTIVE')`.
- `pp.applicant_primary_info` (line 43-71): `applicant_id numeric(14,0)`, `app_state numeric(12,0)`, `district numeric(12,0)`, `nmms_block numeric(12,0)`.
- `pp.batch` (line 182-193): `batch_id integer`, `cohort_number integer`.
- `pp.shortlist_batch` (line 747-758) / `pp.shortlist_batch_jurisdiction` (line 760-763): used only by `getBlocksByMultiDistricts`'s `is_frozen_block` computed flag.
- `pp.cohort` (line 257+): referenced by `event_master.cohort_number` FK.

## §4 File-upload + multipart detail

### `uploadEventFiles` (`eventMiddleware.js:108-129`)
Wraps a **freshly-constructed** `multer({...}).fields([...])` on every request (not a module-level singleton — functionally equivalent but re-parses config each call; no behavioral difference, just note it if a Java equivalent normally uses a singleton `MultipartResolver`).

- Storage: `multer.diskStorage`, `destination` branches on `file.fieldname`: `"photos"` → `PHOTOS_DIR`, `"reports"` → `REPORTS_DIR`, anything else → `cb(new Error("Invalid field"))`.
- Fields accepted: `{ name: "photos", maxCount: 4 }`, `{ name: "reports", maxCount: 1 }`. Any other field name in a multipart file part is rejected by multer as `LIMIT_UNEXPECTED_FILE` (caught, returns 400).
- Size limit: `5 * 1024 * 1024` bytes (5 MB) per file, from `limits: { fileSize: 5*1024*1024 }`.
- MIME filter (`fileFilter`, line 82-101): `photos` → must be one of `image/jpeg, image/png, image/jpg, image/webp` else `cb(new Error("Photos must be JPG, PNG, or WEBP"), false)`. `reports` → must be one of `application/pdf, application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document` else `cb(new Error("Reports must be PDF or Word documents"), false)`. Any other fieldname: silently `cb(null, false)` (file dropped, no error — but this branch is unreachable in practice since `.fields()` already restricts to `photos`/`reports`).
- Filename pattern: `<cleaned-event-title>-<n><ext>` for photos (n = 1..4, per-request counter), `<cleaned-event-title>-report<ext>` for reports (always suffix `-report`, no counter). `cleaned-event-title` is derived from `req.body.eventTitle` — **camelCase `eventTitle`, NOT the snake_case `event_title` that the JSON/body-validation code (`validateEventBody`) checks**. Confirmed against the client: `EventForm.js` sends both `event_title` (the actual DB field, in the FormData body) — multer's `filename` callback fires while parsing the multipart stream and only `req.body` fields that arrived **before** the file part in the multipart body are populated at that point (multer/busboy parses fields in stream order); if the client doesn't explicitly send an `eventTitle` (camelCase) field, `eventTitle` is `undefined` and the fallback literal `"event"` is used for ALL photo/report filenames on that request. **This means unless the client is confirmed to send a separate `eventTitle` field, every create/update with photos may silently produce files named `event-1.jpg`, `event-2.jpg`, etc., colliding across events.** Verify against the live client's FormData keys before porting — the exploration agent did not find an explicit `eventTitle` (camelCase) append in `EventForm.js`/`EventEditPage.js`, only `event_title`. **Flag as a likely latent bug to replicate as-is (byte-compatible) unless product confirms it should be fixed.**
- Error handling: `MulterError` with code `LIMIT_UNEXPECTED_FILE` → 400 `{ message: "Too many files! Max 4 photos and 1 report allowed." }`; any other Multer/generic error → 400 `{ message: err.message }`.

### The createEvent → updateEvent chain (`eventRoutes.js:26-33`)
```js
router.post("/events", uploadEventFiles, sanitizeEventNumbers, validateEventBody,
  eventController.createEvent, eventController.updateEvent);
```
Express allows multiple handler functions on one route; each receives `(req, res, next)` and only runs if the previous one calls `next()`. **`createEvent` (`eventController.js:50-100`) never calls `next()`** — it always terminates the request with `res.status(201).json(...)` (success) or `res.status(500).json(...)` (error, inside its own catch). Because of this, **`updateEvent` is dead code on the POST `/events` route — it is registered as the second handler but can never execute**, since `createEvent` always sends a response and Express stops the chain once a response is sent (calling `next()` after a response is undefined behavior and `createEvent` doesn't do it anyway). This is very likely leftover/copy-paste from when the route may have chained differently, or a misunderstanding of Express handler chaining by the original author. **Net behavior of POST `/events`: only `createEvent` ever runs.** `createEvent` handles `event_master` INSERT + `photos` (not `reports` — createEvent's file loop only checks `req.files?.photos`, never `req.files?.reports`) in one transaction. **Report upload is impossible on initial create** — a `reports` file sent alongside a POST `/events` is accepted by multer (written to disk under `REPORTS_DIR`) but never inserted into `pp.event_reports`, leaving an orphaned file on disk with no DB reference. Port this exactly: Java's create-event endpoint should persist photos only, ignore/orphan any report file the same way (or flag to product as a bug to fix — recommend flagging, since orphaning uploaded files is user-hostile, but the ground truth must state current behavior first).

### `updateEvent` (`eventController.js:102-173`) — the one that actually handles reports
Runs on PUT `/events/:id` only (route line 46-53) — never runs on POST `/events` per above. Sequence inside one transaction:
1. If `photos_to_delete` present in body (JSON string), parse and `DELETE FROM pp.event_photos WHERE photo_id = ANY($1::int[])` — no `event_id` scoping (see §2 landmine).
2. `UPDATE pp.event_master SET ... WHERE event_id = $15` — full column overwrite from body (missing fields become `NULL`/`0` since `boys_attended || 0` etc. are the only defaulted ones — `event_type_id`, `event_title`, etc. have no fallback and will be written as `undefined`→`null` if absent from the PUT body; a partial PUT will null out unset master columns).
3. If `event_type_name === 'Sammelan'` (from body, i.e. client must pass the type NAME on update, not looked up from `event_type_id`): re-derive `boys_attended`/`girls_attended` from `pp.event_students` join, overwrite master row again (this second write only touches `boys_attended`/`girls_attended`, not `parents_attended`, and does not set `updated_by`/`updated_at`).
4. If `req.files?.photos`: insert each with `EventModel.insertPhoto(client, [id, file.path, file.filename, userId])` — **note: uses `file.filename` here (line 158), not `file.originalname` as `createEvent` does (line 89, `file.originalname`)**. This is an inconsistency: `createEvent` stores the ORIGINAL uploaded filename (e.g. `IMG_2043.jpg`) in `pp.event_photos.file_name`, while `updateEvent` stores the SERVER-GENERATED filename (e.g. `sammelan_2026-1.jpg`) in the same column for photos added via PUT. Port both behaviors literally, per-endpoint — do not unify unless asked.
5. If `req.files?.reports?.length > 0`: call `deleteOldReport` (removes any existing `SAMMELAN_REPORT` row for this event, orphaning the old file on disk per §2), then insert the new one with `EventModel.insertEventReport(client, [id, 'SAMMELAN_REPORT', report.path, report.filename, userId])` — hard-coded `report_type = 'SAMMELAN_REPORT'` regardless of the event's actual type.
6. COMMIT, respond `{ success: true, message: "Updated successfully" }`.

### `/attendance/save` (`submitAttendance`, `eventController.js:256-312`)
Also runs `uploadEventFiles` (route line 69-73), so it can receive `photos` (up to 4) and a `reports` file (up to 1, `SAMMELAN_REPORT` type) in the same multipart body as the attendance JSON fields. `studentIds` arrives as either a parsed array or (typically, since this is `multipart/form-data`) a **JSON string** that the controller `JSON.parse`s (line 261) — `typeof studentIds === 'string' ? JSON.parse(studentIds) : studentIds`. File handling logic is byte-identical to steps 4-5 of `updateEvent` above (same `file.filename` usage, same hard-coded `SAMMELAN_REPORT` type), just inlined rather than shared — **but `submitAttendance` does NOT call `deleteOldReport` before inserting a new report** (unlike `updateEvent`), so repeated attendance-save calls with a report file will accumulate multiple `SAMMELAN_REPORT` rows per event instead of replacing. This is a real behavioral difference between the two report-upload paths — port both literally.

### Columns storing paths
`pp.event_photos.file_path` / `pp.event_reports.file_path` store the **full server-side disk path** returned by multer's `file.path` (an absolute or relative-to-cwd path depending on how multer's diskStorage resolved `PHOTOS_DIR`/`REPORTS_DIR`, which are themselves computed via `path.join(__dirname, ...)` — effectively absolute at runtime). The client only ever uses the basename (see §7), so the Java port's exact path format (absolute vs relative) is not contract-critical for the frontend, but should be consistent for any future admin tooling that reads these paths directly.

## §5 Response shapes & status codes

| # | Endpoint | Success | Error bodies |
|---|----------|---------|---------------|
| 1 | POST `/event-types` | 201, event_type row `{event_type_id, event_type_name}` | 400 `{message:"Event type name is required"}` (missing name); 500 `{message:"Failed to create event type"}` |
| 2 | PUT `/event-type/:id` | 200, updated row `{event_type_id, event_type_name}` (or `undefined` body if id not found — `rows[0]` on empty result) | 500 `{message:"Failed to update event type"}` (validateEventId's 400 for bad id also applies) |
| 3 | GET `/event-types` | 200, array of `{event_type_id, event_type_name}` | 500 `{message:"Failed to fetch event types"}` |
| 4 | POST `/events` | 201 `{success:true, message:"Event created", event_id:<number>}` | 400 from `uploadEventFiles`/`validateEventBody` (`{message:"..."}` shape, see below); 500 `{success:false, message:"Failed to create event"}` |
| 5 | GET `/events` | 200, array of event summary rows (see §2 `getAllEvents` columns, aliased `start_date`/`end_date`/`event_type`/`cover_photo`) | 500 `{success:false, message:"Fetch failed"}` |
| 6 | GET `/events/:id` | 200, `{...event_master columns, event_type_name, photos:[{photo_id,file_path,file_name}], reports:[{report_id,event_id,report_type,file_path,file_name,generated_at,generated_by}]}` | 404 `{message:"Not found"}` if event missing; 500 `{success:false, message:"Fetch failed"}`; 400 from `validateEventId` |
| 7 | PUT `/events/:id` | 200 `{success:true, message:"Updated successfully"}` | 500 `{success:false, message: err.message}` (raw DB/JS error message leaked to client — e.g. a `JSON.parse` failure on malformed `photos_to_delete` bubbles up verbatim); 400 from `validateEventId`/`uploadEventFiles`/`validateEventBody` |
| 8 | DELETE `/events/:id` | 200 `{success:true, message:"Deleted successfully"}` | 500 `{success:false, message:"Delete failed"}`; 400 from `validateEventId` |
| 9 | GET `/attendance/sammelan-list` | 200 `{success:true, data:[{event_id, event_title}]}` | 500 `{success:false, msg: err.message}` (note: this route family uses `msg`, not `message`, unlike routes 1-8) |
| 10 | GET `/attendance/jurisdictions` | 200 `{success:true, data:[...]}` (shape depends on `type`; `data` is `undefined` — not an error — if `type` doesn't match any of `state/division/district/block`, since no `else` branch sets it, yet the code still returns 200 with `data: undefined` serialized as `data` key omitted... actually `res.status(200).json({success:true, data})` with `data === undefined` → JSON output `{"success":true}` with `data` key dropped by `JSON.stringify`) | 500 `{success:false, msg: err.message}` |
| 11 | POST `/attendance/students-list` | 200 `{success:true, data:[{student_id, student_name, block_name, district_name, is_marked}]}` | 404 `{success:false, msg:"Event not found"}` if `event_title` doesn't match any row; 500 `{success:false, msg:"Internal Server Error"}` (generic — actual error only logged server-side via `console.error`) |
| 12 | POST `/attendance/save` | 200 `{success:true, msg:"Attendance updated successfully!"}` | 500 `{success:false, msg:"Server Error: " + err.message}` (raw error appended); 400 from `uploadEventFiles` |

**Validation-middleware error bodies** (shared across routes 4, 7, 12 via `validateEventBody`/`uploadEventFiles`, and routes 2,4,6,7,8 via `validateEventId`):
- `uploadEventFiles` failure: 400 `{message:"Too many files! Max 4 photos and 1 report allowed."}` or 400 `{message: <multer/file-filter error text>}`.
- `validateEventBody` (`eventMiddleware.js:135-168`): 400 `{message:"Valid event_type_id is required"}` / `{message:"Event title must be at least 3 characters"}` / `{message:"Start and end dates are required"}` / `{message:"End date must be after start date"}`.
- `validateEventId` (`eventMiddleware.js:175-186`): 400 `{message:"Invalid event ID"}`.

**Numeric-id serialization note (node-pg defaults):** `event_id` (integer) and `photo_id`/`report_id` (integer) serialize as native JS numbers. `event_district`, `event_block` (`numeric(12,0)`), `created_by`/`updated_by`/`uploaded_by`/`generated_by` (`numeric(8,0)`), and `student_id` (`numeric(14,0)`) all come back from `pg` as **strings** (node-pg does not parse `numeric`/`decimal` to JS numbers by default, to avoid precision loss) — the Java port must serialize these same columns as JSON strings, not numbers, to stay byte-compatible with the frozen client, unless the client is confirmed to `Number()`-coerce them itself (the exploration agent found the client reads `s.student_id` directly for equality/array-membership checks against `presentStudentIds`, which are themselves unconverted — so as long as Java is internally consistent this may not break the client, but any endpoint returning a bare numeric `student_id` where the client compares with `===` against a string, or vice versa, would break attendance-marking `.includes()` checks. Recommend Java also emit these numeric(N,0) columns as JSON strings to be safe and byte-compatible).

## §6 Transactions

| Handler | Transaction? | Notes |
|---|---|---|
| `createEventType`, `updateEventType`, `getEventTypes`, `getEventTypeByName` | No (autocommit, `pool.query`) | Single-statement |
| `createEvent` | Yes — own `client.connect()`/BEGIN/COMMIT/ROLLBACK | INSERT master + loop-insert photos |
| `updateEvent` | Yes — own `client.connect()`/BEGIN/COMMIT/ROLLBACK | Photo-delete, master UPDATE, conditional Sammelan re-count UPDATE, photo inserts, report delete+insert — all one transaction |
| `deleteEvent` (model-level, `eventModel.js:104-126`) | Yes — its own `pool.connect()`/BEGIN/COMMIT/ROLLBACK, self-contained inside the model function (unusual — every other multi-statement write does BEGIN/COMMIT in the controller, this one does it in the model) | 4 sequential DELETEs |
| `getAllEvents`, `getEventById`, `getEventPhotos`, `getEventReports` | No | Read-only `pool.query` |
| `getSammelanEvents`, `getStates`, `getDivisionsByState`, `getDistrictsByDivisions`, `getBlocksByMultiDistricts`, `getSammelanStudentList` | No | Read-only `pool.query` |
| `fetchStudentAttendanceList` | No | Two sequential `pool.query` calls (event lookup, then student list) — no transaction, technically a TOCTOU gap if the event is deleted between the two calls, but low risk |
| `submitAttendance` | Yes — `client.connect()`/BEGIN/COMMIT/ROLLBACK | saveSammelanAttendance INSERT, gender-count SELECT, master UPDATE, photo inserts, report insert |

## §7 Quirks & complexity warnings (file:line)

1. **`pp.event_students` table is missing from `V1__baseline.sql` entirely** (`eventModel.js:110,337,429-437`; `eventController.js:143`) — the single biggest blocker. Must be added via a new Flyway migration before any Events/Attendance code can be ported and tested. See §3 for inferred DDL (needs DBA/product confirmation, not verbatim source).

2. **`createEvent`→`updateEvent` route chain is non-functional as apparently intended** (`eventRoutes.js:26-33`): `updateEvent` never runs on POST `/events` because `createEvent` always terminates the response and never calls `next()`. Net effect: POST `/events` == `createEvent` only. This also means **reports cannot be uploaded on event creation** (only on the immediately-following PUT `/events/:id`, if the client does one) — any `reports` file sent with the initial POST is written to disk by multer but never gets a DB row, i.e. **guaranteed orphaned file on every create-with-report attempt.** The Java port should almost certainly just implement one create endpoint (photos-only, matching actual behavior) — but flag this to product as a probable bug worth fixing, since it silently drops report uploads on create.

3. **Filename generation depends on a `req.body.eventTitle` (camelCase) field that the frontend forms may never actually send** (`eventMiddleware.js:50`) — client code sends `event_title` (snake_case, the real column) in FormData; if `eventTitle` isn't also sent, every uploaded file across every event falls back to the literal name `event`, causing filename collisions (`event-1.jpg` overwritten by the next event's `event-1.jpg`). This needs to be verified against actual multipart traffic (browser devtools/API capture) before the Java port decides whether to replicate the bug or read `event_title` instead — replicate first, then flag to product.

4. **`file_name` column semantics differ between `createEvent` (original uploaded filename) and `updateEvent`/`submitAttendance` (server-generated filename)** (`eventController.js:89` vs `:158,298`) — inconsistent by endpoint; port literally per-endpoint.

5. **`updateEvent`'s photo-delete step has no `event_id` scoping** (`eventController.js:122`): `DELETE FROM pp.event_photos WHERE photo_id = ANY($1::int[])` — any authenticated (or, since there's no auth at all, any) caller who knows a `photo_id` can delete a photo belonging to a different event by passing its id in `photos_to_delete` on an unrelated event's PUT. Combined with §7.9 (no auth), this is a real cross-tenant data-integrity gap. Port literally but flag to product.

6. **Old report files are never unlinked from disk on replacement** — `deleteOldReport` (`eventModel.js:416-427`) removes the DB row but the returned `file_path` rows are discarded by the caller (`eventController.js:162`), leaking the physical file. `submitAttendance` doesn't even delete the old DB row (§4) — it just inserts a new `SAMMELAN_REPORT` row, so `getEventReports`'s `ORDER BY generated_at DESC` `LIMIT` (client takes `reports[0]`) determines which one the UI shows, but old rows/files accumulate forever via that path.

7. **`getBlocksByMultiDistricts`'s `is_frozen_block` flag couples the Events module to the Shortlisting module's `pp.shortlist_batch`/`pp.shortlist_batch_jurisdiction` tables** (`eventModel.js:283-287`) — the client-side consumption of this flag in the attendance jurisdiction picker was not confirmed by the frontend skim; likely dead/vestigial but must port the column since removing it could break an untraced client dependency.

8. **`getSammelanEvents` filters on the literal string `'Sammelan'`** (`eventModel.js:226`), and `updateEvent`'s Sammelan-sync branch checks `event_type_name === 'Sammelan'` from the **request body** (`eventController.js:136`), not looked up server-side from `event_type_id` — the client must send the correct type name string for the auto-count-sync to fire; a case/spelling mismatch silently skips the sync with no error.

9. **Zero authentication on all 12 routes** (`eventRoutes.js` — no `authenticate` middleware anywhere, confirmed by grepping `index.js` for `authenticate`/`verifyToken`/`requireAuth`, zero matches touching this router). `req.user?.user_id` (`eventController.js:63,116,264`) will always be `undefined` in practice unless some other still-unfound global middleware populates `req.user`, meaning the `user_id`-from-body fallback (`|| user_id || null`) is the operative path in real usage — **the created_by/updated_by/uploaded_by/generated_by audit columns are effectively client-supplied and trustless.** Confirm with product whether the Java port should add real authentication here (recommend flagging strongly — this is a portability decision, not just a straight port, since adding auth changes the contract) or replicate the open-access behavior byte-for-byte for phase 5a and handle auth in a later hardening pass.

10. **`0`-as-falsy / numeric-string traps**: `boys_attended || 0` / `girls_attended || 0` / `parents_attended || 0` patterns appear in `updateEvent` (`eventController.js:131-132`) and `createEvent` (defaults at destructure, line 58). Since these are `integer` columns (not `numeric`), node-pg returns them as real JS numbers, so `0 || 0` correctly stays `0` — **no bug here**, unlike the classic node-pg `numeric`-as-string 0-as-truthy trap seen in other modules (see phase3d doc) — flagging only because the pattern looks identical to that trap at a glance; it isn't one in this module since all three columns are `integer DEFAULT 0`, not `numeric`.

11. **Dead model exports** (`eventModel.js:374,402,441,463`) — `getMarkedSammelanStudents`, `removeSammelanAttendance`, `getExistingEventStudents`, `editSammelanAttendanceSync` are never called by any live controller. Do not port unless a currently-unmapped client call surfaces later (re-check the frontend attendance-edit flow specifically before excluding).

12. **`getJurisdictionData`'s missing-`type`-branch behavior**: if `type` query param isn't one of `state|division|district|block`, `data` stays `undefined` and the handler still returns `200 {success:true}` (no `data` key at all after JSON serialization) rather than a 400 — no validation on the `type` enum (`eventController.js:209-219`).

13. **Date/timezone handling**: `event_start_date`/`event_end_date` are plain `date` columns (no time/timezone component in DDL); `validateEventBody`'s date-order check (`eventMiddleware.js:161`) uses JS `new Date(event_start_date) > new Date(event_end_date)` — for a plain `YYYY-MM-DD` string this parses as UTC midnight in Node, so comparison is safe/timezone-agnostic for this specific check, but the Java port should use `LocalDate` comparison (not `Instant`/`ZonedDateTime`) to match.

14. **`createEvent`'s `pool.connect()` transaction runs `sanitizeEventNumbers`-processed body values but re-destructures `boys_attended = 0, girls_attended = 0, parents_attended = 0` with its own defaults** (`eventController.js:58`) — redundant with `sanitizeEventNumbers` already nulling empty strings, but since these three are `integer NOT NULL DEFAULT 0`-ish columns (actually nullable with `DEFAULT 0`, so a JS `null` bound value would insert SQL `NULL`, not `0`) — the destructure default `= 0` only fires when the key is **absent from `req.body` entirely**, not when `sanitizeEventNumbers` set it to `null` (since destructuring defaults only apply to `undefined`, not `null`). So a client that explicitly sends `boys_attended: ""` will end up with DB `NULL` (via sanitize→null→bound as null), while a client that omits the field entirely gets DB `0`. Confirm this distinction is intentional; port literally.

## §8 Summary

- **Endpoint count: 12** (matches the task's route list exactly — 3 event-type CRUD, 5 event CRUD, 4 attendance/jurisdiction).
- **Biggest risks, ranked:**
  1. `pp.event_students` is entirely absent from the baseline schema (§3, §7.1) — blocks all attendance-marking and delete-event functionality until a new migration is written and confirmed against product/DBA.
  2. The `createEvent`→`updateEvent` chain is dead-code-by-accident (§4, §7.2) — the Java port must decide (and get product sign-off) whether to replicate the "reports silently dropped on create" bug or fix it, since this is user-visible behavior, not an invisible implementation detail.
  3. File-upload persistence is inconsistent across three near-duplicate code paths (`createEvent`, `updateEvent`, `submitAttendance`) that differ in: which `file_name` is stored (original vs generated), whether old reports are cleaned up first, and whether `photos`/`reports` are both handled — these must be ported as three distinct, deliberately-different implementations, not unified, unless product agrees to a cleanup.
  4. Zero authentication + no `event_id` scoping on the photo-delete path (§7.5, §7.9) is a real security/data-integrity gap that should be flagged to product before or during the port, separate from the byte-compatibility goal.
  5. The `eventTitle` (camelCase) vs `event_title` (snake_case) filename-source mismatch (§4, §7.3) needs live-traffic verification before deciding what the Java multipart filename logic should key off of.
- **Recommended sub-task split for the implementation plan:**
  a. Schema fix: add `pp.event_students` migration (blocks everything else).
  b. Event-type CRUD (3 endpoints) — trivial, no transactions, no file I/O.
  c. Event CRUD + file upload (create, get-all, get-by-id, update, delete) — the multipart/transaction-heavy core; needs the three-divergent-upload-paths behavior nailed down first via live-traffic capture.
  d. Attendance sub-routes (sammelan-list, jurisdictions, students-list, save) — depends on (a) for `pp.event_students`, and shares upload logic with (c)'s `submitAttendance`.
  e. A follow-up decision doc (not code) resolving the product questions raised in §7.2, §7.3, §7.5, §7.9 before or shortly after the initial byte-compatible port ships.
