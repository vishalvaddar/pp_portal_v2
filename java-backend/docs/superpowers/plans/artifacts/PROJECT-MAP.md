# IMAS Migration — Project Map (where everything resides)

Index of the repo so any session can locate things fast. Regenerate after big structural changes.
Repo root: `C:\work\rcf`. Branch: `feat/springboot-migration-phase1`. Java: 21 (Temurin), Maven 3.9.9 — reachable only via git-bash shims in `~/bin` (see RESUME-migration.md). Tests: Zonky embedded-postgres (no Docker). **Full suite: 229 green** as of exams (3a) done.

## 1. Top-level layout

| Path | What |
|---|---|
| `imas-backend/` | The Spring Boot app being built (Maven). THE deliverable. |
| `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/` | Node/Express source being ported (parity target): `routes/`, `controllers/`, `models/`, `config/db.js`, `public/{assets,fonts}` |
| `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/src/` | Frozen React frontend (contract; `config/axiosConfig.js` = global JWT interceptor) |
| `docs/superpowers/specs/` | The design spec |
| `docs/superpowers/plans/` | Per-phase implementation plans |
| `docs/superpowers/plans/artifacts/` | Ground-truth docs, live schema, RESUME doc, this map |
| `scripts/parity/` | Phase-1 parity scripts |
| `imas_schema_05_07_2026_12_50_47.sql` | The raw production pg_dump (authoritative schema source) |
| `IMAS 2.0.pptx`, `*Presentation.pptx`, `Functional Requirements conv.html` | Product docs (untracked) |
| Memory (outside repo) | `C:\Users\shrid\.claude\projects\C--work-rcf\memory\` — `imas-migration-status.md` is the live state |

## 2. Backend platform layer — `imas-backend/src/main/java/com/rcf/imas/`

| File | Responsibility |
|---|---|
| `ImasApplication.java` | Spring Boot entry point |
| `platform/config/JacksonConfig.java` | Global snake_case JSON |
| `platform/error/ApiException.java` | `.error(status,msg)`→`{error}`, `.message(...)`→`{message}`, `.with(k,v)` |
| `platform/error/GlobalExceptionHandler.java` | Maps ApiException; re-throws AccessDenied (→403 not 500); generic 500 `{error:"Internal Server Error"}` |
| `platform/security/SecurityConfig.java` | Stateless JWT chain; permits `/api/auth/*`, `/api/exams/hallticket/**`, `/actuator/health`; else authenticated. `@EnableMethodSecurity` |
| `platform/security/JwtService.java` / `JwtProperties.java` / `JwtAuthFilter.java` | Node-compatible HS256 tokens (auth0 java-jwt); `issueFinalToken`, `FinalToken.userId()` |

## 3. Backend modules — `.../modules/<name>/{web,service,persistence}/` (DONE)

Each module: `web/*Controller` (package-private class, public handlers, `@PreAuthorize("hasRole('ADMIN')")`), `persistence/*Repository` (JdbcClient + a local static `genericRow` mapper), optional `service/*`. Writes that span statements live in a dedicated `@Repository @Transactional` bean.

| Module | Base path(s) | Key Java files | Ported from (Node) | Tests | Phase |
|---|---|---|---|---|---|
| `identity` | `/api/auth`, `/api` (users/roles) | AuthController, UserRoleAdminController, AuthService, UserAdminService, LoginAuditLogger, IdentityRepository | loginController, authorizeRoleController, userRolesController | AuthFlowIT, UserRoleAdminIT | 0–1 |
| `masterdata` | `/api` (jurisdiction), `/api/system-config` | JurisdictionController, SystemConfigController, +Repositories | jurisdictionController, districtRoutes, jurisNameRoutes, institutesRoutes, systemConfigController | JurisdictionIT, SystemConfigIT | 0–1 |
| `admission` | `/api/applicants`, `/api/bulk-upload` | ApplicantController, BulkUploadController, ApplicantService, BulkUploadService, ApplicantFormatter, ApplicantRepository, JurisdictionLookupRepository, BulkInsertRepository | applicantController, bulkUploadController | ApplicantRead/Create/Update/DeleteIT, BulkUploadIT, ApplicantFormatterTest | 2a |
| `merge` | `/api/merge` | MergeController, MergeReadRepository, MergeWriteRepository, MergeService, MergeMatching, CsvSupport | mergeController/mergeModel | MergeRead/Preview/Upload/Write/DownloadDeleteIT, MergeMatchingTest | 2b |
| `shortlist` | `/api/shortlist/generate`, `/api/shortlist-info` | GenerateShortlistController, ShortlistInfoController, ShortlistService, ShortlistRead/WriteRepository, XlsxSupport | generateShortlistController, shortlistInfoController | ShortlistGenerate(Read)/InfoRead/Freeze/Mutate/DownloadIT | 2c |
| `results` | `/api/results` | ResultsController, ResultsReadRepository, ResultsXlsxSupport | resultandrankingController/Model | ResultsCascadeAndFilterOptions/Search/DownloadIT | 3d |
| `evaluation` | `/api/custom-list`+`/api/evaluation` (dual), `/api/evaluation-dashboard` | CustomListController, EvaluationController, EvaluationDashboardController, Evaluation(Read/Write)Repository, DashboardReadRepository, CustomListXlsx/Pdf/ValueMapper, StudentExcelSupport | customListController, evaluationController, evaluationDashboardController | CustomListReads/Write/Export, Evaluation, EvaluationDashboardIT | 3b |
| `exams` | `/api/exams` | ExamsController, ExamsReadRepository, ExamsWriteRepository, ExamCallingListXlsxSupport, HallTicketPdfSupport, HallTicketZipSupport | examControllers/examModels | ExamCentres/Jurisdiction/Listing/CreateAssign/StudentList/HallTicketIT | 3a |

Shared test base: `src/test/.../PgIntegrationTest.java` (JVM-wide embedded PG). `FlywayBaselineIT.java`, platform tests (Jackson/GlobalExceptionHandler/JwtService/SecurityConfig).

## 4. Remaining Node source → planned phase (NOT yet migrated)

| Node route / controller | Base path | Target module | Phase | Notes |
|---|---|---|---|---|
| interviewRoutes / interviewController / interviewModel | `/api/interview` | `interview` | **3c (plan drafted, unexecuted)** | Hardest: assign/reassign 4-branch algorithm + report PDF |
| studentRoutes | `/api/student` | academics | 4 | controller = verify (nmms/studentSearch) |
| studentSearchRoutes / studentSearchController | `/api` | academics | 4 | |
| classroomRoutes / classroomController | `/api/classrooms` | academics | 4 | |
| batchRoutes / batchController | `/api/batches` | academics | 4 | |
| activeTimeTableRoutes / activeTimeTableController | `/api/activetimetable` | academics | 4 | (timeTableRoutes mount is commented out in index.js) |
| trackingRoutes / trackingController | `/api/tracking` | academics | 4 | |
| coordinatorRoutes | `/api/coordinator` | academics | 4 | controller = verify |
| searchRoutes / searchController | `/api` | academics | 4 | |
| selectionReportRoutes / selectionReportsController | `/api/selection-reports` | academics | 4 | |
| eventRoutes / eventController | `/api` | events | 5 | |
| tabInventoryRoutes / tabInventoryController | `/api` | inventory | 5 | |
| — | — | — | 6 | Decommission Node |

**Dead/unmounted in Node (skip unless asked):** `reports.js`, `teacherStudentRoutes.js`, `timeTableRoutes.js` (commented out), `nmmsController.js` (verify usage).

## 5. Docs index — `docs/superpowers/`

- **Spec:** `specs/2026-07-04-imas-springboot-migration-design.md` (+ .pdf)
- **Plans** (`plans/`): `phase0-1-foundation-identity-masterdata`, `phase2a-applicants-bulk-upload`, `phase2b-nmms-merge`, `phase2c-shortlisting`, `phase3d-results-ranking`, `phase3b-evaluation`, `phase3a-exams`, `phase3c-interview` (drafted, unreviewed/unexecuted)
- **Ground truth** (`plans/artifacts/`): `phase2b/2c/3a/3b/3c/3d-*-ground-truth.md`, `live-schema.sql` (the authoritative pg_dump), `phase1-fetch-audit.md`, `phase1-cutover-notes.md`, `RESUME-migration.md` (toolchain + 11 locked conventions + resume procedure), `PROJECT-MAP.md` (this file)

## 6. Config / schema / resources

- `imas-backend/pom.xml` — deps: spring-boot-starter-{web,jdbc,security,validation}, postgresql, flyway, auth0 java-jwt, commons-csv, **poi-ooxml** (XLSX), **openpdf:2.0.3** (PDF), embedded-postgres (test)
- `imas-backend/src/main/resources/application.yml` — datasource, flyway (baseline from pg_dump), jwt, 50MB multipart, graceful shutdown
- `imas-backend/src/main/resources/db/migration/V1__baseline.sql` — Flyway baseline (derived from the live pg_dump; `pp` schema)
- `imas-backend/src/main/resources/exam-assets/` — hall-ticket PDF assets: 2 logos, signature, stamp, `NotoSansKannada-Regular.ttf`
- `logback-spring.xml`
- CI: `.github/workflows/backend-java.yml`

## 7. Locked conventions (full list in RESUME-migration.md §"LOCKED conventions")
Plain JdbcClient+SQL (no JPA); cast numeric PARAMS (`:x::numeric`); numeric/bigint ids→String (except used-blocks→number, dashboard counts→int); snake_case JSON; per-endpoint `{error}` vs `{message}`; controllers package-private + public handlers; ADMIN on everything except `/api/auth/*` and `/api/exams/hallticket/**`; transactions in dedicated @Repository; per-IT `@AfterEach` clean children-before-parents; JWT Node-compatible.
