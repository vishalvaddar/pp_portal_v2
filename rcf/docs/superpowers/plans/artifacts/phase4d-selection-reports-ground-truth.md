# SELECTION-REPORTS Module — Ground Truth (for Plan 4d)

Captured from a full read of the Node source. Base mount: `app.use("/api/selection-reports", selectionReportRoutes)` (server/index.js). Files (all fully live, nothing commented out): `server/routes/selectionReportRoutes.js` (34 lines), `server/controllers/selectionReportsController.js` (522 lines), `server/models/selectionReportModel.js` (207 lines). No further `require`s beyond `../config/db` (pg pool), `pdfkit-table`, `path`, `fs`. Frontend: `client/src/pages/Admin/Reports/SelectionReports.js` (NMMS/Turnout/Selection/Selects tabs) and `client/src/pages/Admin/Reports/SammelanReports.js`.

## 1. Endpoint Inventory (11 routes under `/api/selection-reports`)

| # | Method | Path | Controller fn | Purpose |
|---|--------|------|----------------|---------|
| 1 | GET | `/init` | `getInitialData` | Distinct academic years for the year dropdown |
| 2 | GET | `/nmms-data` | `getNMMSData` | Applicant counts by district or block, `?year&type` |
| 3 | POST | `/download-pdf` | `downloadNMMSPDF` | NMMS PDF report, archived to disk + streamed |
| 4 | GET | `/turnout-data` | `getTurnOutData` | Called vs appeared counts (PP-Test turnout), `?year&type` |
| 5 | POST | `/download-turnout-pdf` | `downloadTurnOutPDF` | Turnout PDF, archived to disk + streamed |
| 6 | GET | `/selection-data` | `getSelectionData` | Appeared vs selected counts, `?year&type` |
| 7 | POST | `/download-selection-pdf` | `downloadSelectionPDF` | Selection-success PDF, archived to disk + streamed |
| 8 | GET | `/selects-data` | `getSelectsData` | Gender-wise selected-student counts, `?year&type` |
| 9 | POST | `/download-selects-pdf` | `downloadSelectsPDF` | Gender-wise PDF, **streamed only, no disk archive** |
| 10 | GET | `/cohorts` | `getCohorts` | Cohort dropdown list (Sammelan UI) |
| 11 | GET | `/sammelan-data` | `getSammelanData` | Sammelan event attendance, `?cohort&fromDate&toDate` |
| 12 | POST | `/download-sammelan` | `downloadSammelanPDF` | Sammelan attendance PDF, landscape, **streamed only, no disk archive** |

(Prompt estimated ~12 routes; actual count is 12 including the router comment split "Existing" vs "NEW" — matches.)

All GET/data endpoints are read-only single `pool.query()` calls (no transactions anywhere in this module — see §6). All POST/download endpoints take **client-computed** `reportPayload` in the body; the server does **not** re-query the DB for PDF generation, it only renders whatever JSON the frontend already fetched/aggregated (see §5 quirk).

`type` query param: only `type === 'district'` is checked explicitly; any other value (including `undefined`, `null`, typos) falls through to the **block** branch in every `get*Report` model method. There is no whitelist/enum validation server-side.

## 2. Exact SQL (verbatim, `selectionReportModel.js`)

**getAcademicYears** (line 5):
```sql
SELECT DISTINCT academic_year FROM pp.system_config ORDER BY academic_year DESC
```
No phase filter — collapses duplicate `academic_year` rows across `system_config` phases via `DISTINCT`. Order is lexicographic string DESC (fine given the `chk_academic_year_format` CHECK `^[0-9]{4}-[0-9]{2,4}$`, e.g. `"2025-26"`).

**getNMMSReport(year, type)** — district (lines 12-17):
```sql
SELECT d.juris_name AS label, COUNT(a.applicant_id) AS applicant_count
FROM pp.applicant_primary_info a
JOIN pp.jurisdiction d ON a.district = d.juris_code
WHERE a.nmms_year = $1
GROUP BY d.juris_name ORDER BY d.juris_name;
```
block (lines 21-31):
```sql
SELECT
    d.juris_name AS district_name,
    b.juris_name AS label,
    COUNT(a.applicant_id) AS applicant_count
FROM pp.applicant_primary_info a
JOIN pp.jurisdiction d ON a.district = d.juris_code
JOIN pp.jurisdiction b ON a.nmms_block = b.juris_code
WHERE a.nmms_year = $1
GROUP BY d.juris_name, b.juris_name
ORDER BY d.juris_name, b.juris_name;
```

**getTurnOutReport(year, type)** — district (lines 40-65):
```sql
SELECT
    j.juris_name AS label,
    COUNT(DISTINCT s.applicant_id) AS called_count,
    COUNT(DISTINCT CASE
        WHEN a.pp_exam_appeared_yn = 'Y'
        THEN s.applicant_id
    END) AS appeared_count,
    ROUND(
        COUNT(DISTINCT CASE
            WHEN a.pp_exam_appeared_yn = 'Y'
            THEN s.applicant_id
        END) * 100.0
        / NULLIF(COUNT(DISTINCT s.applicant_id), 0),
        2
    ) AS turnout_percentage
FROM pp.applicant_shortlist_info s
JOIN pp.applicant_primary_info ap ON ap.applicant_id = s.applicant_id
LEFT JOIN pp.applicant_exam_attendance a ON a.applicant_id = s.applicant_id
JOIN pp.jurisdiction j ON ap.district = j.juris_code
WHERE ap.nmms_year = $1
GROUP BY ap.district, j.juris_name
ORDER BY j.juris_name;
```
block (lines 69-97) — same shape with `d`/`b` split by `ap.district`/`ap.nmms_block`, `GROUP BY ap.district, ap.nmms_block, d.juris_name, b.juris_name`.

**Called-count semantics quirk**: `called_count` = `COUNT(DISTINCT s.applicant_id)` from `applicant_shortlist_info` **with no filter on `shortlisted_yn`**. Every row in the shortlist table for that district/year is treated as "called", including any `shortlisted_yn = 'N'` rows. Port this literally unless product wants it fixed — flag for confirmation.

**getSelectionReport(year, type)** — district (lines 103-113):
```sql
SELECT j.juris_name AS label,
    COUNT(DISTINCT a.applicant_id) AS appeared_count,
    COUNT(DISTINCT sm.applicant_id) AS selected_count,
    ROUND(COUNT(DISTINCT sm.applicant_id) * 100.0 / NULLIF(COUNT(DISTINCT a.applicant_id), 0), 2) AS selection_percentage
FROM pp.applicant_exam_attendance a
JOIN pp.applicant_primary_info ap ON ap.applicant_id = a.applicant_id
JOIN pp.jurisdiction j ON ap.district = j.juris_code
LEFT JOIN pp.student_master sm ON sm.applicant_id = a.applicant_id
WHERE a.pp_exam_appeared_yn = 'Y' AND ap.nmms_year = $1
GROUP BY ap.district, j.juris_name ORDER BY j.juris_name;
```
block (lines 117-129) — same with `d`/`b` split, `GROUP BY ap.district, ap.nmms_block, d.juris_name, b.juris_name`.

**getSelectsReport(year, type)** — district (lines 136-147):
```sql
SELECT
    d.juris_name AS label,
    ap.gender,
    COUNT(sm.applicant_id) AS student_count
FROM pp.applicant_primary_info ap
JOIN pp.jurisdiction d ON ap.district = d.juris_code
LEFT JOIN pp.student_master sm ON sm.applicant_id = ap.applicant_id
WHERE ap.nmms_year = $1
GROUP BY ap.district, d.juris_name, ap.gender
ORDER BY d.juris_name, ap.gender;
```
block (lines 151-164) — adds `b.juris_name AS label` (block) with `district_name` = `d.juris_name`, `GROUP BY ap.district, d.juris_name, ap.nmms_block, b.juris_name, ap.gender`. Returns **one row per (location, gender)** — the frontend pivots M/F rows into `{boys_sel, girls_sel}` per location client-side (see §5).

**getCohorts** (line 170):
```sql
SELECT cohort_name FROM pp.cohort ORDER BY cohort_number ASC;
```

**getSammelanData(cohort, fromDate, toDate)** (lines 177-197):
```sql
SELECT
    c.cohort_name,
    em.event_title AS label,
    d.juris_name AS district_name,
    b.juris_name AS block_name,
    em.event_location,
    em.event_start_date AS from_date,
    em.event_end_date AS to_date,
    COALESCE(em.boys_attended, 0) AS boys_sel,
    COALESCE(em.girls_attended, 0) AS girls_sel
FROM pp.cohort c
JOIN pp.event_master em ON em.cohort_number = c.cohort_number
JOIN pp.event_type et ON et.event_type_id = em.event_type_id
LEFT JOIN pp.jurisdiction d ON em.event_district = d.juris_code
LEFT JOIN pp.jurisdiction b ON em.event_block = b.juris_code
WHERE et.event_type_name = 'Sammelan'
    AND em.event_start_date <= $2
    AND em.event_end_date >= $3
    AND c.cohort_name = $1
ORDER BY em.event_start_date;
```
Call site: `pool.query(query, [cohort, toDate, fromDate])` — **`$2` binds to `toDate`, `$3` binds to `fromDate`** (an explicit swap, per the source comment "Note: $2 is toDate, $3 is fromDate to match your logic"). This is a date-range **overlap** filter: any event whose `[event_start_date, event_end_date]` window intersects `[fromDate, toDate]`. `event_type_name = 'Sammelan'` is a **hard-coded literal string filter**, not parameterized/configurable. Reproduce the overlap semantics exactly (don't "fix" the param order — it is correct as an overlap test, just confusingly named).

## 3. Table DDL (from live-schema.sql, all pre-existing — no new tables needed)

```sql
CREATE TABLE pp.system_config (
    system_config_id integer DEFAULT nextval('pp.system_config_id_seq'::regclass) NOT NULL,
    academic_year character varying(9) NOT NULL,
    phase character varying(50) NOT NULL,
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_academic_year_format CHECK (((academic_year)::text ~ '^[0-9]{4}-[0-9]{2,4}$'::text))
);

CREATE TABLE pp.applicant_primary_info (
    applicant_id numeric(14,0) DEFAULT nextval('pp.applicant_id_seq'::regclass) NOT NULL,
    nmms_year numeric(4,0),
    nmms_reg_number numeric(11,0) NOT NULL,
    app_state numeric(12,0), district numeric(12,0), nmms_block numeric(12,0),
    student_name varchar(100), father_name varchar(100), mother_name varchar(100),
    gmat_score numeric(2,0), sat_score numeric(2,0), gender character(1),
    medium varchar(50), aadhaar varchar(12), dob date, home_address varchar(200),
    family_income_total numeric(7,0), contact_no1 varchar(12), contact_no2 varchar(12),
    current_institute_dise_code varchar(15), previous_institute_dise_code varchar(15),
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0),
    students_sats_id numeric(11,0),
    CONSTRAINT applicant_primary_info_gender_check CHECK (gender = ANY (ARRAY['M','F','O']))
);

CREATE TABLE pp.applicant_shortlist_info (
    shortlist_info_id numeric(14,0) DEFAULT nextval('pp.shortlist_info_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0),
    shortlisted_yn character(1),
    shortlist_batch_id numeric(6,0),
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0),
    CONSTRAINT applicant_shortlist_info_shortlisted_yn_check CHECK (shortlisted_yn = ANY (ARRAY['Y','N']))
);

CREATE TABLE pp.applicant_exam_attendance (
    applicant_id numeric(14,0),
    pp_exam_appeared_yn character(1),
    CONSTRAINT applicant_exam_attendance_pp_exam_appeared_yn_check CHECK (pp_exam_appeared_yn = ANY (ARRAY['Y','N']))
);
-- Note: no PK/FK on this table; no columns are NOT NULL.

CREATE TABLE pp.student_master (
    student_id numeric(14,0) DEFAULT nextval('pp.student_id_seq'::regclass) NOT NULL,
    applicant_id numeric(14,0), enr_id numeric(11,0),
    student_name varchar(100), father_name varchar(100), father_occupation varchar(100),
    mother_name varchar(100), mother_occupation varchar(100), gender character(1),
    batch_id integer, sim_name varchar(10), student_email varchar(150),
    student_email_password varchar(100), parent_email varchar(150), photo_link text,
    home_address varchar(200), contact_no1 varchar(12), contact_no2 varchar(12),
    current_institute_dise_code varchar(15), previous_institute_dise_code varchar(15),
    active_yn varchar(10) DEFAULT 'ACTIVE', recharge_status varchar(20), sponsor varchar(100),
    teacher_name varchar(100), teacher_mobile_number varchar(12),
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0),
    user_id numeric,
    CONSTRAINT student_master_gender_check CHECK (gender = ANY (ARRAY['M','F','O']))
);

CREATE TABLE pp.jurisdiction (
    juris_code numeric(12,0) NOT NULL,
    juris_name varchar(100), juris_type varchar(100), parent_juris numeric(12,0),
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0)
);

CREATE TABLE pp.cohort (
    cohort_number integer DEFAULT nextval('pp.cohort_seq'::regclass) NOT NULL,
    cohort_name varchar(100), start_date date, end_date date, description text,
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0),
    status varchar(20), current_grade integer,
    CONSTRAINT cohort_current_grade_check CHECK (current_grade = ANY (ARRAY[9,10,11,12])),
    CONSTRAINT cohort_status_check CHECK (status = ANY (ARRAY['ACTIVE','COMPLETED']))
);

CREATE TABLE pp.event_master (
    event_id integer DEFAULT nextval('pp.event_master_event_id_seq'::regclass) NOT NULL,
    event_type_id integer, event_title varchar(150),
    event_start_date date NOT NULL, event_end_date date,
    event_district numeric(12,0), event_block numeric(12,0), event_location varchar(150),
    pincode varchar(12), cohort_number integer,
    boys_attended integer DEFAULT 0, girls_attended integer DEFAULT 0, parents_attended integer DEFAULT 0,
    created_at timestamp, updated_at timestamp, created_by numeric(8,0), updated_by numeric(8,0),
    event_description varchar(255)
);

CREATE TABLE pp.event_type (
    event_type_id integer NOT NULL,
    event_type_name varchar(100) NOT NULL
);
```

## 4. Response Shapes & Status Codes

| Endpoint | 200 body | Error body |
|---|---|---|
| `GET /init` | `{ years: [{academic_year}, ...] }` | `500 {error}` |
| `GET /nmms-data` | district: `[{label, applicant_count}]`; block: `[{district_name, label, applicant_count}]` | `500 {error}` |
| `GET /turnout-data` | `[{label, called_count, appeared_count, turnout_percentage}]` (+`district_name` for block) | `500 {error}` |
| `GET /selection-data` | `[{label, appeared_count, selected_count, selection_percentage}]` (+`district_name`) | `500 {error}` |
| `GET /selects-data` | `[{label, gender, student_count}]` (+`district_name`) — **not pivoted server-side** | `500 {error}` |
| `GET /cohorts` | `[{cohort_name}]` | `500 {error}` |
| `GET /sammelan-data` | `[{cohort_name, label, district_name, block_name, event_location, from_date, to_date, boys_sel, girls_sel}]` | `400 {error:"Missing required parameters"}` (any of cohort/fromDate/toDate absent); `500 {error}` |
| `POST /download-pdf`, `/download-turnout-pdf`, `/download-selection-pdf` | `200`, `Content-Type: application/pdf`, `Content-Disposition: attachment`, raw PDF bytes | `500 "Error generating PDF"` / `"Error generating Turn-Out PDF"` (only if `!res.headersSent`) |
| `POST /download-selects-pdf` | same as above | `500 "Error generating Selects PDF"` (only if `!res.headersSent`) |
| `POST /download-sammelan` | same as above | `500 <e.message>` (raw error message, not JSON) |

All numeric aggregate columns (`applicant_count`, `called_count`, etc.) come back from `pg` as **strings** for `COUNT`/`bigint`-typed results — the PDF code does `(b.count || 0).toString()`, tolerant of either. Java/JDBC mapping should keep this in mind (BigDecimal/Long vs String) when serializing JSON to match Node's driver behavior (node-postgres returns bigint counts as JS strings).

## 5. File-Generating Endpoints In Detail

**Library:** `pdfkit-table` (wraps `pdfkit` with a `doc.table()` helper) — `package.json` pins `"pdfkit": "^0.17.1"`, `"pdfkit-table": "^0.1.99"`. **No ExcelJS/XLSX anywhere in this module** — despite the "reports = XLSX/PDF" prior assumption, every export here is PDF only. Java equivalent: **OpenPDF** (already available, per RESUME-migration.md — no new dependency needed). No POI/XLSX needed for this module.

**Critical architecture point:** the download endpoints do **not** query the database. The frontend (`SelectionReports.js` `handleDownload`) already has `data` from the paired GET endpoint, locally re-groups/re-labels it into `groupedData`, renders Chart.js charts client-side, captures each chart as a base64 JPEG (`chart.toBase64Image("image/jpeg", 0.6)`), and POSTs the whole thing back as `reportPayload: [{ districtName, chartImage, blocks: [...] }]`. The Java port must decide: (a) keep this contract (Java also just renders whatever JSON body arrives, chart image included) — simplest 1:1 port; or (b) redesign to compute server-side (loses the client chart image, needs a server chart renderer). **Recommend (a)** for parity; flag if product wants native server-side charting later.

**Shared header (`drawReportHeader`, controller lines 52-94)** used by all 5 PDF generators:
- Two logos: `PATH_TO_RCF_LOGO = server/public/assets/rcf_logo-removebg-preview.png`, `PATH_TO_PP_LOGO = server/public/assets/logo.png`, each drawn only `if (fs.existsSync(...))`, 50x50pt, top-left/top-right at margin 30.
- Title `"RAJALAKSHMI CHILDREN FOUNDATION"` (Times-Bold, 18pt first page / 12pt subsequent).
- Subtitle `"PRATIBHA POSHAK - ${nmmsYear}"` (or `cohort` string for Sammelan) (16pt/10pt).
- Hard-coded address: `"Kayaka Kranti Towers, CTS No. 4824C/23+24, Ayodhya Nagar, Near Kolhapur Circle, Belagavi 590016"` (Times-Roman 8pt/7pt).
- Hard-coded phone: `"Contact No. +91 9444900755, +91 9606930208"`.
- Horizontal rule under the header, `doc.y` reset to `lineY + 15`.
- A4 portrait, `margin: 30`, except Sammelan which is **A4 landscape** (`layout: 'landscape'`).

**5a. `downloadNMMSPDF` (POST /download-pdf)** — controller lines 97-172:
- Filename: `` `NMMS_${type==='block'?'Block_Report':'District_Report'}_${year}.pdf` `` — **no timestamp**, so concurrent/repeat calls for the same year+type overwrite the same archived file on disk (race condition on the archive copy only; the HTTP response itself is unaffected).
- **Disk write:** `GENERATED_FILES_ROOT = ${FILE_STORAGE_PATH || './storage'}/generated-report-data` (dir auto-created via `fs.mkdirSync(..., {recursive:true})` at **module load time**, i.e. as a side effect of `require`-ing the controller). `doc.pipe(writeStream)` AND `doc.pipe(res)` — dual-piped, both written from the same stream.
- Loop over `reportPayload` (one page per array item = one district, or one page per district-group in block mode): `doc.addPage()` after the first.
- Title: `"NMMS Report (by Block)"` / `"NMMS Report (by District)"`, Helvetica-Bold 14pt, `#2c3e50`.
- If block type: subheading `` `District: ${item.districtName.toUpperCase()}` `` underlined.
- Optional chart image: strips `data:image/\w+;base64,` prefix, draws white rect `[40, y, 500, 280]` then image at `[45, y+5]` sized `480x220`; cursor set to `y+290`.
- Table (via `doc.table`): headers `["District Name"|"Block Name" (property `label`, width 300), "Applicant Count" (property `count`, width 180)]`; rows built manually as `[b.label, (b.applicant_count||0).toString()]` — table width 480, x=40, header style Helvetica-Bold 10pt `#475569`, row style Helvetica 10pt `#1e293b`.

**5b. `downloadTurnOutPDF` (POST /download-turnout-pdf)** — lines 174-243:
- Filename: `` `NMMS_${type==='block'?'Block_TurnOut':'District_TurnOut'}_${year}_${Date.now()}.pdf` `` (has timestamp, unlike 5a).
- Disk archive: yes (same pattern).
- Title `"Test Turn-Out Report (by Block/District)"` + italic subtitle `"(PP-Test appeared students as a percentage of called students)"`.
- Chart rect `[40,y,500,260]`, image `480x220`, cursor `y+290`.
- Table columns: `District/Block (label, w200)`, `Called (called, w80)`, `Appeared (appeared, w80)`, `Turn-Out % (percentage, w100)`; rows use `b.called_count`, `b.appeared_count`, `` `${b.turnout_percentage||0}%` ``.

**5c. `downloadSelectionPDF` (POST /download-selection-pdf)** — lines 260-340:
- Filename: `` `NMMS_${type==='block'?'Block_Selection':'District_Selection'}_${year}_${Date.now()}.pdf` ``.
- Disk archive: yes.
- Title `"Selection Success Report"` + italic subtitle `"(Percentage of appeared students successfully selected)"`.
- Chart rect `[40,y,500,260]`.
- Table columns: `District/Block (label, w200)`, `Appeared (app, w90)`, `Selected (sel, w90)`, `Success % (pct, w100)`; rows use `b.appeared_count`, `b.selected_count`, `` `${b.selection_percentage||0}%` ``.

**5d. `downloadSelectsPDF` (POST /download-selects-pdf)** — lines 354-427:
- Filename computed (`` `NMMS_${type==='block'?'Block_Selects':'District_Selects'}_${year}.pdf` ``, no timestamp) but **used only for `Content-Disposition`** — **no `fs.createWriteStream`/archive copy at all**, unlike 5a-5c. Inconsistent with the other three; confirm intentional before porting (likely just an oversight, but preserve unless told to fix).
- Title `"Selects Report (by Block/District)"` + italic subtitle `"(Gender-wise selection details)"`.
- Chart rect `[40,y,500,260]`, cursor `y+280` (slightly different offset than 5a-5c's `y+290`).
- Table columns: `Location Name (label, w220)`, `Boys Selected (boys, w130)`, `Girls Selected (girls, w130)`; rows use `b.boys_sel`, `b.girls_sel` — these fields only exist because the **frontend** pivoted the raw `{label, gender, student_count}` rows into `{boys_sel, girls_sel}` per location (`groupedData` reducer in `SelectionReports.js` lines 138-156) before building `reportPayload`. The server never does this pivot; a Java port that tries to recompute this from the raw `/selects-data` SQL must replicate the frontend's grouping-by-`label` + gender-split logic, not just re-run the SQL.

**5e. `downloadSammelanPDF` (POST /download-sammelan)** — lines 459-522:
- **A4 landscape**, no year param (uses `cohort` string in place of `nmmsYear` in the shared header, and in the filename).
- Filename: `` `Sammelan_Report_${cohort}.pdf` `` — **`cohort` is interpolated into the `Content-Disposition` header unsanitized** (no encoding/escaping of quotes or CRLF). Low-severity header-injection surface if a cohort name ever contains `"` or control characters; flag for Java (use a proper `ContentDisposition` builder / sanitize the filename).
- **No disk archive** (streamed to `res` only).
- Single title block only on first "item" implicitly — actually `drawReportHeader` is called **once outside the loop** (line 470), not per-page, so all districts/blocks for the Sammelan cohort render onto a continuous flow without a repeated header (pdfkit auto-paginates the table when content overflows, but the header is not redrawn — differs from the other 4 PDFs which redraw the header on every page/item).
- Per-item optional chart image at fixed `[45, doc.y]`, `700x250`, cursor `+= 270`.
- Table columns (9, no widths specified — pdfkit-table auto-sizes): `Event Title, District, Block, Location, Start Date, End Date, Boys, Girls, Total`. Rows: `b.label, b.district_name, b.block_name, b.event_location, formatDate(b.from_date), formatDate(b.to_date), b.boys_sel, b.girls_sel, (boys_sel+girls_sel)`.
- **Date formatting:** `formatDate = d => d ? new Date(d).toLocaleDateString('en-GB') : '--'` → `dd/mm/yyyy`. `from_date`/`to_date` arrive as JS `Date` objects (from `pg` mapping Postgres `date` columns) already shifted to local server time before `toLocaleDateString` runs — reproduce as `dd/MM/yyyy` formatting of the SQL `date` value with `'--'` for null, no timezone conversion needed in Java since `LocalDate` has no time-of-day component.
- Table width 780, x=30, header Helvetica-Bold 10pt (no explicit color, defaults black), row Helvetica 9pt.

## 6. Transactions

**None.** Every query in this module is a single `pool.query(...)` autocommit call — no `pool.connect()`, no `BEGIN`/`COMMIT`/`ROLLBACK` anywhere in `selectionReportModel.js`. This is purely a read/report module; nothing here writes to business tables (the only writes are PDF bytes to the filesystem archive, not DB writes). Java port: plain `JdbcClient` query calls, no `@Transactional` needed for the GET/data endpoints; the download endpoints likewise need no DB transaction (file I/O only, and per §5d/5e even that's inconsistent about whether it happens).

## 7. Quirks & Complexity (file:line references are to the Node source read above)

1. **No dynamic SQL / no injection risk.** Unlike the merge module (phase2b ground truth §3), every query here uses fixed table/column names with `$1`/`$2`/`$3` placeholders only. Nothing to whitelist for Java — straightforward parameterized `JdbcClient` translation.
2. **Inconsistent disk-archiving across the 5 download endpoints** — `download-pdf`/`download-turnout-pdf`/`download-selection-pdf` write an archive copy to `GENERATED_FILES_ROOT` (`selectionReportsController.js:107-108, 183-185, 268-269`); `download-selects-pdf` (`:362-364`) and `download-sammelan` (`:465-467`) do not. Decide once whether Java archives all 5 or none — recommend **none** (the archive copy is never read back anywhere in this codebase; grep confirms no route serves `generated-report-data/*` back to a client) unless product says otherwise.
3. **Filename collision risk** — `download-pdf` (5a) has no timestamp in its archived filename (`selectionReportsController.js:105`); repeated generation for the same `year`+`type` silently overwrites the previous archive file. The other archiving endpoints append `Date.now()`.
4. **`GENERATED_FILES_ROOT` created as a module-load side effect** (`selectionReportsController.js:10-15`, `fs.mkdirSync` outside any handler) — Java equivalent should be an app-startup/bean-init step, not per-request.
5. **Year-format normalization duplicated 4x**: `year && year.includes("-") ? year.split("-")[0] : year` appears verbatim in `getNMMSData`, `getTurnOutData`, `getSelectionData`, `getSelectsData` (not in Sammelan, which takes raw dates, or `/init`, which returns the raw `academic_year` strings for the dropdown). Extract to one shared helper in Java. Handles `"2025-26"` → `"2025"` but would also mangle a plain `"2025"` unaffected, and would take only the first segment of any multi-hyphen input (none expected given the DB CHECK constraint).
6. **`type` param has no validation/whitelist** — any value other than the literal string `"district"` is silently treated as `"block"` in all four report methods (`selectionReportModel.js` `if (type === 'district') {...} else {...}`). A missing/typo'd `type` degrades to block-mode queries requiring a non-null `nmms_block`/`event_block`, silently dropping rows where that FK is null (INNER JOINs, not LEFT). Java should decide whether to keep this permissive fallback or validate `type ∈ {district, block}` and 400 otherwise (recommend validating, since it's a clear improvement with zero behavior loss for legitimate callers).
7. **`called_count` (Turn-Out report) counts ALL `applicant_shortlist_info` rows for the year, not just `shortlisted_yn='Y'`** (`selectionReportModel.js:44, 74`) — likely intended to filter but doesn't. Flag explicitly for product/QA sign-off before porting; a "fix" here changes report numbers, so don't silently correct it without confirmation.
8. **Sammelan date-range param swap** (`selectionReportModel.js:200`) — `pool.query(query, [cohort, toDate, fromDate])` against `$1=cohort, $2 (bound to toDate) used in "event_start_date <= $2", $3 (bound to fromDate) used in "event_end_date >= $3"`. This is a correct overlap-range test but the variable naming makes it easy to "fix" incorrectly during a literal port — preserve the overlap semantics (`event_start_date <= toDate AND event_end_date >= fromDate`), just bind params by position/semantics rather than by copying the confusing names.
9. **Hard-coded `event_type_name = 'Sammelan'`** literal filter (`selectionReportModel.js:193`) — not parameterized; if event-type names ever change/localize this silently returns nothing. Fine to port as a literal for now.
10. **`getSelectsData` catch block doesn't log** (`selectionReportsController.js:349-351`, only `res.status(500).json(...)`, no `console.error`) — inconsistent with every sibling handler; cosmetic, note but not required to replicate.
11. **PDF generation trusts client-supplied `reportPayload` entirely** (§5) — no server-side recomputation/validation of `blocks[].applicant_count` etc. against the DB. This is a deliberate "renders what it's given" design; a faithful Java port must NOT re-derive numbers from SQL inside the download handlers, only format whatever JSON arrives. If this is considered a data-integrity risk (client could tamper with counts before download), flag as a product decision, not a bug to silently fix.
12. **Sammelan `cohort` unsanitized into `Content-Disposition` header** (`selectionReportsController.js:466`) — potential header-injection if cohort names ever contain quotes/CRLF; low severity since cohort names come from an admin-managed dropdown (`pp.cohort`), but Java should use a safe filename-encoding helper regardless.
13. **No broken/dead endpoints found.** All 12 routes wire to implemented, reachable controller functions; all controller functions call a real model function; no leftover/commented routes in `selectionReportRoutes.js`. Nothing to deprecate or flag as non-functional in this module (contrast with modules that had genuinely broken routes).
14. **No XLSX/ExcelJS anywhere** — the entire module is PDF-only via `pdfkit-table`. Confirms **no new Java dependency needed**; OpenPDF (already in the project per RESUME-migration.md) covers 100% of this module's file generation.
15. **`pg` numeric aggregates return as strings** — `COUNT()`/computed `ROUND()` values come back through `node-postgres` as JS strings for `bigint`/`numeric` types; downstream code does `(x || 0).toString()` (tolerant either way) and JSON-serializes them as-is to the frontend, which does `Number(...)` where needed (e.g. `SelectionReports.js:149-151`). Java/Jackson will naturally serialize `BigDecimal`/`Long` as JSON numbers, not strings — this is a **response-shape difference** from Node (`"42"` vs `42`) that the frontend's `Number(...)` calls already tolerate, but any strict-typed consumer/test comparing raw JSON against Node output should account for it.

