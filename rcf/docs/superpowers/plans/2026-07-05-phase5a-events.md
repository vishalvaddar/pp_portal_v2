# Phase 5a — Events (12 endpoints, FINAL module)

## Goal

Port the Node `eventRoutes.js` (`app.use("/api", eventRoutes)`) — 3 event-type CRUD endpoints, 5 event-master
CRUD endpoints (with photo/report multipart upload + static file serving), and 4 Sammelan-attendance endpoints
— to Spring Boot, byte-compatible on the wire with the frozen Node API + React client + Postgres `pp` schema.
New module `com.rcf.imas.modules.events`. This is the **final module** of the Node→Spring Boot migration.

## Architecture

- `com.rcf.imas.modules.events.persistence.EventsReadRepository` — plain `JdbcClient`, hand-written SQL, a
  local `genericRow` row-mapper (same convention as `CoordinatorReadRepository.genericRow` — package-private
  static, reused by `EventsWriteRepository`). All read-only queries: event-type list, jurisdiction cascade
  (state/division/education-district/block), `getAllEvents`, `getEventById` + photos/reports, Sammelan event
  dropdown, Sammelan student list, event-by-title lookup.
- `com.rcf.imas.modules.events.persistence.EventsWriteRepository` — event-type create/update (autocommit,
  single statement, Node parity §6); `createEvent`/`updateEvent`/`deleteEvent`/`submitAttendance` are each
  `@Transactional`, one connection per call (Node parity: each of these opens its own `pool.connect()` +
  `BEGIN`/`COMMIT`/`ROLLBACK`).
- `com.rcf.imas.modules.events.service.EventFileStorageService` — reproduces `uploadEventFiles`'s multer
  `diskStorage.filename` callback (photo/report filename generation, `photos/`+`reports/` subdirs under
  `imas.event-storage-path`).
- `com.rcf.imas.modules.events.service.EventUploadValidation` — reproduces multer's `fileFilter` (MIME
  allowlist) + size limit (5MB) + max-count (4 photos / 1 report) checks as request-level validation (Spring's
  `MultipartResolver` has already fully parsed the request by the time the controller runs, so there is no
  streaming per-file callback to hook errors into the way Node's multer has).
- `com.rcf.imas.modules.events.service.EventValidation` — `validateEventId`, `validateEventBody`,
  `sanitizeNumeric` (the `sanitizeEventNumbers` per-field coercion), ported as static helpers.
- `com.rcf.imas.modules.events.config.EventStaticResourceConfig` — a `WebMvcConfigurer` bean that serves
  `imas.event-storage-path/photos/**` at `/uploads/events/photos/**` and `.../reports/**` at
  `/uploads/events/reports/**`, matching Node's `express.static` mounts (`index.js:147-155`) byte-for-byte on
  URL shape.
- `com.rcf.imas.modules.events.web.EventsController` — all 12 endpoints, class-level
  `@PreAuthorize("hasRole('ADMIN')")` (Locked Decision 1 — Node has **zero** auth on this router; every other
  zero-auth Node mount in this migration has been hardened to ADMIN the same way, e.g.
  `SelectionReportsController`, `BulkUploadController`).
- `com.rcf.imas.platform.security.SecurityConfig` gets two new **GET-only** `permitAll()` matchers for the two
  static-file URL prefixes (the frozen client's `<img src>`/download links hit these directly, unauthenticated,
  and must keep working — Locked Decision 1's exception).
- `com.rcf.imas.platform.error.ApiException` gets one new generic factory, `ApiException.of(int status)`, so
  controllers can build the `{success:false, msg:"..."}` envelope shape used by routes 9-12 (existing factories
  only produce single-key `{error:...}` / `{message:...}` bodies).
- Plain `JdbcClient` + hand-written SQL throughout — no JPA/Hibernate (user's global convention).
- `pp.event_students` already exists via `V2__event_students.sql` (added ahead of this plan) — composite PK
  `(event_id, student_id)`, so the `ON CONFLICT (event_id, student_id)` clause used by the live
  `saveSammelanAttendance` query is valid and ported verbatim.

## Tech Stack

Java 21, Spring Boot 3.3.5, Maven, Spring `JdbcClient`, Spring Web `MultipartFile` (`spring.servlet.multipart.*`
already configured at 50MB in `application.yml` — this module additionally self-enforces Node's tighter 5MB
per-file limit), JUnit 5 + Spring `MockMvc` (`multipart(...)` builder, `MockMultipartFile`) + embedded Postgres
(`PgIntegrationTest`), `@TempDir` + `@DynamicPropertySource` for the file-storage path (pattern established in
`TrackingStudentsAndDocumentIT`), Flyway `V1__baseline.sql` + `V2__event_students.sql`.

---

## Firm Decisions (locked — do not re-litigate)

| # | Decision |
|---|---|
| 1 | **Auth: class-level `@PreAuthorize("hasRole('ADMIN')")`** on `EventsController`. Node has zero auth on all 12 routes. **Exception:** `GET /uploads/events/photos/**` and `GET /uploads/events/reports/**` (static file serving) are `permitAll()` in `SecurityConfig` — the frozen client reads photos/reports directly via `<img src>`/download links with no Authorization header. |
| 2 | **`pp.event_students` exists** (`V2__event_students.sql`, already applied): `event_id integer`, `student_id numeric(14,0)`, PK `(event_id, student_id)`, FKs to `event_master`/`student_master`. All four Node references to this table are ported: `deleteEvent`'s cleanup DELETE, `getSammelanStudentList`'s `LEFT JOIN ... is_marked`, `saveSammelanAttendance`'s `INSERT ... ON CONFLICT (event_id, student_id) DO NOTHING` (verbatim — **no DELETE precedes it**, see Disagreements #2), and `updateEvent`'s Sammelan count-resync `JOIN`. |
| 3 | **`createEvent`→`updateEvent` route chain: only `createEvent` runs.** `POST /events` in Node lists `createEvent, updateEvent` as chained handlers, but `createEvent` (`eventController.js:50-100`) always terminates the response (`res.status(201)...` or `res.status(500)...`) and never calls `next()`, so `updateEvent` is dead code on that route. Java implements ONE `POST /events` handler that does exactly what `createEvent` does: insert `event_master` + any `photos` files (`req.files?.photos` loop). It does **not** look at a `reports` file at all — if one is sent, multer/Spring will have already accepted and buffered it, but no `pp.event_reports` row is ever written and (per this plan) no file is persisted to the reports directory either, since we only invoke `EventFileStorageService.storeReport` from `updateEvent`/`submitAttendance`. This *slightly* improves on Node (Node still writes the orphaned file to disk; Java doesn't write it at all) — flagged as an intentional, wire-invisible deviation in Deferred. |
| 4 | **File storage + static serving.** `imas.event-storage-path` (default `${EVENT_STORAGE_PATH:./uploads/events}`) with `photos/` and `reports/` subdirs, created at startup. Filenames are generated **verbatim** to Node's multer `filename` callback (`eventMiddleware.js:48-72`): source field is `eventTitle` (**camelCase**, NOT the `event_title` the rest of the body validation uses), falling back to the literal `"event"` if absent; cleaned via `[^a-zA-Z0-9]` → `_`, lower-cased; photos get `<clean>-<n><ext>` (`n` = 1-based per-request counter, max 4); reports get `<clean>-report<ext>` (no counter). `pp.event_photos.file_name`/`pp.event_reports.file_name` store the **original** uploaded filename on create (`file.originalname`, `eventController.js:89`) but the **server-generated** filename on update/attendance-save (`file.filename`, `eventController.js:158,298`) — ported literally, per-endpoint, not unified. Static serving: `WebMvcConfigurer.addResourceHandlers` maps `/uploads/events/photos/**` → `file:<photosDir>/` and `/uploads/events/reports/**` → `file:<reportsDir>/`, matching `index.js:147-155`'s `express.static` mounts. |
| 5 | **Photo-delete IDOR hardening.** Node's `updateEvent` photo-delete (`DELETE FROM pp.event_photos WHERE photo_id = ANY($1::int[])`, `eventController.js:122`) has **no `event_id` scoping** — any caller can delete any event's photo by ID. Java scopes the delete with `AND event_id = :eventId` (a wire-safe hardening, matching the project's established pattern of quietly closing IDOR holes during the port — e.g. the 4e-3 IDOR fix). The frozen client (`EventEditPage.js`-style forms) always issues `photos_to_delete` scoped to the event currently being edited, so this scoping does not break any legitimate client flow. |
| 6 | **Transactions:** `createEvent`, `updateEvent`, `deleteEvent`, `submitAttendance` are each `@Transactional` `@Repository` methods (one connection per call, matching Node's per-handler `pool.connect()`+`BEGIN`/`COMMIT`/`ROLLBACK`). `createEventType`/`updateEventType`/all read methods are plain autocommit (Node parity — `pool.query`, no transaction). |
| 7 | **Middleware → Java validation**, exact error envelopes: `validateEventId` → 400 `{message:"Invalid event ID"}`; `validateEventBody` → 400 `{message:"Valid event_type_id is required"}` / `{message:"Event title must be at least 3 characters"}` / `{message:"Start and end dates are required"}` / `{message:"End date must be after start date"}`; `uploadEventFiles` (size/MIME/count) → 400 `{message:"Too many files! Max 4 photos and 1 report allowed."}` / `{message:"File too large"}` / `{message:"Photos must be JPG, PNG, or WEBP"}` / `{message:"Reports must be PDF or Word documents"}`; `sanitizeEventNumbers` → empty/absent numeric field becomes `null`, a numeric string becomes its `Number()`-canonical form (drops leading zeros — LANDMINE, ground truth §3), a non-numeric string passes through unchanged. |
| 8 | **`genericRow` conventions** (same as `CoordinatorReadRepository`): `numeric(x,0)` → `String` (`event_district`, `event_block`, `created_by`, `updated_by`, `uploaded_by`, `generated_by`, `student_id`); plain `integer` → native JSON number (`event_id`, `photo_id`, `report_id`, `event_type_id`, `cohort_number`, `boys_attended`, `girls_attended`, `parents_attended`); `date` → `"yyyy-MM-dd"` (`event_start_date`/`event_end_date`, aliased `start_date`/`end_date` in `getAllEvents`); `timestamp` → ISO-Z string (`created_at`/`updated_at`/`uploaded_at`/`generated_at`). `boys_attended`/`girls_attended`/`parents_attended` are `integer DEFAULT 0` columns (NOT `numeric`), so node-pg's `0 || 0` pattern is genuinely safe there (ground truth §7.10, no bug) — Java passes these through as native `Integer`, no string-coercion trap. |
| 9 | **Preserved quirks (do NOT fix), each flagged in code comments:** `POST /events` never persists a `reports` file (Firm Decision 3); `updateEvent`'s report-replace leaks the old file on disk (`deleteOldReport` deletes the DB row but never unlinks the file — ported literally, DB-correct/disk-leaked); `submitAttendance` never calls `deleteOldReport` at all, so repeated attendance-save calls with a report file accumulate multiple `SAMMELAN_REPORT` rows (client shows `reports[0]` via `ORDER BY generated_at DESC`, so only the newest is visible, but old rows/files pile up forever); the `eventTitle`(camelCase)-vs-`event_title` filename-source landmine (Firm Decision 4); `getSammelanEvents`'s literal-string `'Sammelan'` filter (case-sensitive, no `ILIKE`); `updateEvent`'s Sammelan-sync branch trusts the **request body**'s `event_type_name` string, not a server-side lookup by `event_type_id`; `getAllEvents`'s `cover_photo` subquery has no `ORDER BY`, so "first photo" is Postgres's undefined pick, not deterministic; `getJurisdictionData`'s missing/unrecognized `type` query param returns 200 `{success:true}` with no `data` key at all (no 400, no validation on the enum) — ported literally. |

## Disagreements between the task brief / ground-truth doc and the LIVE Node source (for you to adjudicate)

1. **`submitAttendance` does NOT do "DELETE + INSERT".** The task brief's Locked Decision 2 states
   `/attendance/save` does "DELETE + INSERT ... ON CONFLICT (event_id, student_id) DO NOTHING — verbatim". The
   LIVE source (`eventModel.js:429-437`, `saveSammelanAttendance`) is:
   ```sql
   INSERT INTO pp.event_students (event_id, student_id)
   SELECT $1, unnest($2::int[])
   ON CONFLICT (event_id, student_id) DO NOTHING
   RETURNING student_id;
   ```
   — a single `INSERT ... ON CONFLICT DO NOTHING`, no preceding `DELETE`. This means marking a student
   "absent" after previously marking them "present" (i.e. removing them from a subsequent `studentIds` array)
   does **NOT** remove their existing `event_students` row — attendance can only accumulate, never shrink,
   through this endpoint (the dead `removeSammelanAttendance`/`editSammelanAttendanceSync` functions that
   *would* delete are never called — ground truth §7.11, "dead model exports"). The ground-truth doc itself
   (§2, §3) also only documents the plain `INSERT ... ON CONFLICT DO NOTHING`, matching the live source, not
   the task brief's "DELETE + INSERT" framing. **This plan ports the verbatim live-source behavior (INSERT-only,
   no DELETE)** since two independent reads of the actual code (this plan's + the ground-truth doc's) agree,
   and the task brief's phrasing appears to be a paraphrase error. Flagging for explicit sign-off since it's a
   real behavioral difference (idempotent-add-only vs. add-and-remove) that a product owner may care about.
2. **`eventController.js:58`'s `boys_attended = 0` destructure default is dead code on `POST /events` in
   practice**, which slightly corrects ground-truth §7.14's framing. Ground truth §7.14 says a client that
   *omits* `boys_attended` entirely gets DB `0` while a client sending `boys_attended: ""` gets DB `NULL`. But
   `sanitizeEventNumbers` (middleware, always runs *before* `createEvent` on this route per `eventRoutes.js:26-33`)
   sets `req.body.boys_attended = null` whenever the field is **either** `""` **or** `undefined`
   (`eventMiddleware.js:205`, `=== "" || === undefined`) — so by the time `createEvent`'s own
   `const {boys_attended = 0} = req.body` destructure runs, `req.body.boys_attended` is always already `null`
   in both cases (never actually `undefined`), and a JS destructuring default only fires on `undefined`, not
   `null`. **In practice, `boys_attended`/`girls_attended`/`parents_attended` are always inserted as `NULL` on
   `POST /events` unless the client sends an actual non-empty numeric value — the "omitted → 0" case ground
   truth §7.14 describes does not occur.** This plan's `createEvent` write path reflects the verified behavior
   (no coercion to `0`); `updateEvent`'s separate `boys_attended || 0` (a plain JS OR, evaluated *after* the
   same sanitize step) **does** coerce `null`→`0` there, which is unaffected by this correction and is ported
   as `0`-on-null in `updateEvent` per Firm Decision 8/ground truth's own read of that line. Flagging so this
   distinction is not silently "fixed" by a future maintainer who re-reads only the ground-truth doc's §7.14
   prose without re-tracing the middleware order.

## Deferred / Flagged (do not build now, noted for later)

- **Dead `createEvent`→`updateEvent` chain / orphaned report-on-create** (Firm Decision 3) — `POST /events`
  cannot accept a report upload at all; the client must do an immediate follow-up `PUT /events/:id` to attach
  one. Recommend flagging to product as a probable UX bug worth fixing in a later pass (not this port).
- **`updateEvent`'s report-replace leaks the old file on disk** (Firm Decision 9) — `deleteOldReport` only
  removes the DB row; the physical file under `reportsDir` is never deleted. Same for `submitAttendance`'s
  report rows, which additionally never even delete the *old row* (pure accumulation). A disk-cleanup job is a
  separate follow-up, not part of this port.
- **`eventTitle`(camelCase) filename-source landmine** (Firm Decision 4) — unless the frozen client is
  confirmed (via live traffic capture, not available in this repo snapshot) to send a form field literally
  named `eventTitle` in addition to `event_title`, every uploaded file across every event will be named
  `event-1.jpg`, `event-2.jpg`, `event-report.pdf`, etc., colliding across every event that doesn't supply that
  field. Ported literally (byte-compatible with the bug); recommend product confirm/fix by reading `event_title`
  instead in a later pass.
- **`getBlocksByMultiDistricts`'s `is_frozen_block` flag** couples this module to
  `pp.shortlist_batch`/`pp.shortlist_batch_jurisdiction` (Shortlisting module); ported as-is (column is
  returned) since removing it could break an untraced client dependency, per ground truth §7.7.
- **`Locked Decision 3`'s minor Java-vs-Node improvement**: Java's `POST /events` writes zero bytes to disk for
  an accompanying `reports` file (it simply never reads that part of the multipart request into
  `EventFileStorageService`), whereas Node's multer always buffers the report to `REPORTS_DIR` even though no
  DB row is ever created. Wire-invisible (the response body is identical either way); flagged as a deliberate,
  harmless deviation.
- **Fractional/negative event IDs**: Node's `validateEventId` (`Number(req.params.id)`) technically accepts a
  fractional string like `"3.5"` as a "valid" id (not `NaN`, not falsy) and passes it through to a
  parameterized query, which Postgres would then either fail to match (integer column) or error on depending on
  cast context. Java's `EventValidation.validateEventId` rejects any non-integer string as 400 `"Invalid event
  ID"` — a stricter, wire-safe simplification of an edge case with no realistic client trigger (path params are
  always plain integers from the frontend's route builder). Flagged, not expected to matter.

---

## Task 1 — Event-type CRUD + jurisdiction cascade + controller/security skeleton

Endpoints: `POST /event-types`, `PUT /event-type/:id`, `GET /event-types`, `GET /attendance/jurisdictions`.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventValidation.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/platform/error/ApiException.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java`
- Modify: `imas-backend/src/main/resources/application.yml`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/events/EventTypesAndJurisdictionsIT.java`

Seed id range for this task's IT: `970100`-`970199` (juris_code), `970101` (user_id).

- [ ] **1.1** Write the failing IT for all four Task-1 endpoints.

  `imas-backend/src/test/java/com/rcf/imas/modules/events/EventTypesAndJurisdictionsIT.java`:
  ```java
  package com.rcf.imas.modules.events;

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
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class EventTypesAndJurisdictionsIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String admin;
      Integer eventTypeId;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970101,'evAdmin970','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          admin = jwt.issueFinalToken("970101", "evAdmin970", "ADMIN");

          // Jurisdiction hierarchy: state -> division -> education district -> block
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970110,'Karnataka970','STATE',NULL)").update();
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970111,'Bangalore Div970','DIVISION',970110)").update();
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970112,'Bangalore North970','EDUCATION DISTRICT',970111)").update();
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970113,'Yelahanka970','BLOCK',970112)").update();

          // Frozen shortlist batch that references the block, to exercise is_frozen_block=true
          jdbc.sql("INSERT INTO pp.shortlist_batch(shortlist_batch_id, shortlist_batch_name, frozen_yn, shortlisted_year) VALUES (970101,'SB970','Y',2025)").update();
          jdbc.sql("INSERT INTO pp.shortlist_batch_jurisdiction(shortlist_batch_id, juris_code) VALUES (970101,970113)").update();
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = 970101").update();
          jdbc.sql("DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = 970101").update();
          jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code BETWEEN 970110 AND 970113").update();
          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'Sammelan970'").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970101").update();
      }

      @Test
      void createEventTypeThenListThenUpdate() throws Exception {
          String createBody = "{\"event_type_name\":\"Sammelan970\"}";
          String createResp = mvc.perform(post("/api/event-types").contentType("application/json").content(createBody)
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.event_type_name").value("Sammelan970"))
              .andReturn().getResponse().getContentAsString();
          Integer id = com.jayway.jsonpath.JsonPath.read(createResp, "$.event_type_id");

          mvc.perform(get("/api/event-types").header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$[?(@.event_type_name=='Sammelan970')]", hasSize(1)));

          mvc.perform(put("/api/event-type/" + id).contentType("application/json")
                  .content("{\"event_type_name\":\"Sammelan970-Renamed\"}")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.event_type_name").value("Sammelan970-Renamed"));

          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_id = :id").param("id", id).update();
      }

      @Test
      void createEventTypeMissingNameIs400() throws Exception {
          mvc.perform(post("/api/event-types").contentType("application/json").content("{}")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Event type name is required"));
      }

      @Test
      void updateEventTypeInvalidIdIs400() throws Exception {
          mvc.perform(put("/api/event-type/abc").contentType("application/json").content("{\"event_type_name\":\"x\"}")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Invalid event ID"));
      }

      @Test
      void jurisdictionsState() throws Exception {
          mvc.perform(get("/api/attendance/jurisdictions").param("type", "state")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data[?(@.juris_name=='Karnataka970')]", hasSize(1)));
      }

      @Test
      void jurisdictionsDivision() throws Exception {
          mvc.perform(get("/api/attendance/jurisdictions").param("type", "division")
                  .param("stateName", "Karnataka970")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[?(@.juris_name=='Bangalore Div970')]", hasSize(1)));
      }

      @Test
      void jurisdictionsDistrict() throws Exception {
          mvc.perform(get("/api/attendance/jurisdictions").param("type", "district")
                  .param("divisionNames", "Bangalore Div970")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[?(@.juris_name=='Bangalore North970')]", hasSize(1)));
      }

      @Test
      void jurisdictionsBlockWithFrozenFlag() throws Exception {
          mvc.perform(get("/api/attendance/jurisdictions").param("type", "block")
                  .param("stateName", "Karnataka970")
                  .param("divisionNames", "Bangalore Div970")
                  .param("districtNames", "Bangalore North970")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[0].juris_name").value("Yelahanka970"))
              .andExpect(jsonPath("$.data[0].is_frozen_block").value(true));
      }

      @Test
      void jurisdictionsUnknownTypeOmitsDataKey() throws Exception {
          mvc.perform(get("/api/attendance/jurisdictions").param("type", "bogus")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data").doesNotExist());
      }

      @Test
      void withoutAdminTokenIsForbidden() throws Exception {
          mvc.perform(get("/api/event-types")).andExpect(status().isUnauthorized());
      }
  }
  ```

- [ ] **1.2** Run and confirm it FAILS to compile / 404s (no controller yet):
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventTypesAndJurisdictionsIT`

- [ ] **1.3** Implement `ApiException`'s new generic factory.

  `imas-backend/src/main/java/com/rcf/imas/platform/error/ApiException.java` — add a second constructor and
  factory (keep everything else in the file unchanged):
  ```java
  package com.rcf.imas.platform.error;

  import java.util.LinkedHashMap;
  import java.util.Map;

  /** Carries the exact legacy JSON body key ("error" or "message") per endpoint contract. */
  public class ApiException extends RuntimeException {

      private final int status;
      private final Map<String, Object> body = new LinkedHashMap<>();

      private ApiException(int status, String key, String text) {
          super(text);
          this.status = status;
          this.body.put(key, text);
      }

      /** No default key -- caller builds the body entirely via .with(), for envelopes like
       *  {success:false, msg:"..."} (events module's "msg"-keyed routes, ground truth §5 rows 9-12) that
       *  don't fit the single-key error()/message() shape. */
      private ApiException(int status) {
          super((String) null);
          this.status = status;
      }

      public static ApiException error(int status, String text)   { return new ApiException(status, "error", text); }
      public static ApiException message(int status, String text) { return new ApiException(status, "message", text); }
      public static ApiException of(int status) { return new ApiException(status); }

      public ApiException with(String key, Object value) { body.put(key, value); return this; }

      public int status() { return status; }
      public Map<String, Object> body() { return body; }
  }
  ```

- [ ] **1.4** Implement `EventValidation`.

  `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventValidation.java`:
  ```java
  package com.rcf.imas.modules.events.service;

  import com.rcf.imas.platform.error.ApiException;

  import java.time.LocalDate;
  import java.time.format.DateTimeParseException;

  /** Static ports of eventMiddleware.js's validateEventId, validateEventBody, sanitizeEventNumbers. */
  public final class EventValidation {

      private EventValidation() {}

      /** validateEventId (eventMiddleware.js:175-186): Number(req.params.id); (!id || isNaN(id)) -> 400
       *  {message:"Invalid event ID"}. Java rejects non-integer strings too (see plan Deferred: Node's
       *  Number() would technically accept "3.5" as non-NaN/non-zero and let it flow through to a query;
       *  Java treats that as invalid up front -- a stricter, wire-safe simplification of a no-op edge case). */
      public static long validateEventId(String idParam) {
          long id;
          try {
              id = Long.parseLong(idParam);
          } catch (NumberFormatException | NullPointerException e) {
              throw ApiException.message(400, "Invalid event ID");
          }
          if (id == 0) throw ApiException.message(400, "Invalid event ID");
          return id;
      }

      /** validateEventBody (eventMiddleware.js:135-168). Node's date-order check does `new Date(start) >
       *  new Date(end)` on plain YYYY-MM-DD strings (UTC-midnight parse, timezone-agnostic for this
       *  comparison, ground truth §7.13) -- Java uses LocalDate comparison, equivalent for this exact check. */
      public static void validateEventBody(String eventTypeId, String eventTitle,
                                            String eventStartDate, String eventEndDate) {
          if (eventTypeId == null || eventTypeId.isBlank() || !isNumeric(eventTypeId)) {
              throw ApiException.message(400, "Valid event_type_id is required");
          }
          if (eventTitle == null || eventTitle.trim().length() < 3) {
              throw ApiException.message(400, "Event title must be at least 3 characters");
          }
          if (eventStartDate == null || eventStartDate.isBlank()
                  || eventEndDate == null || eventEndDate.isBlank()) {
              throw ApiException.message(400, "Start and end dates are required");
          }
          try {
              LocalDate start = LocalDate.parse(eventStartDate);
              LocalDate end = LocalDate.parse(eventEndDate);
              if (start.isAfter(end)) {
                  throw ApiException.message(400, "End date must be after start date");
              }
          } catch (DateTimeParseException e) {
              throw ApiException.message(400, "End date must be after start date");
          }
      }

      /** sanitizeEventNumbers (eventMiddleware.js:193-213): "" or absent -> null; a numeric string becomes
       *  its Number()-canonical form (LANDMINE: drops leading zeros, ground truth §3 LANDMINE #2); a
       *  non-numeric string passes through unchanged (Node's `!isNaN(field)` guard only rewrites numerics). */
      public static String sanitizeNumeric(String raw) {
          if (raw == null || raw.isEmpty()) return null;
          if (!isNumeric(raw)) return raw;
          double d = Double.parseDouble(raw);
          if (d == Math.rint(d) && !Double.isInfinite(d)) {
              return String.valueOf((long) d);
          }
          return String.valueOf(d);
      }

      static boolean isNumeric(String s) {
          try {
              Double.parseDouble(s);
              return true;
          } catch (NumberFormatException e) {
              return false;
          }
      }
  }
  ```

- [ ] **1.5** Implement `EventsReadRepository` (genericRow + event-type list + jurisdiction cascade).

  `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsReadRepository.java`:
  ```java
  package com.rcf.imas.modules.events.persistence;

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
  public class EventsReadRepository {

      private static final DateTimeFormatter TS =
              DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
      private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

      private final JdbcClient jdbc;

      public EventsReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

      /** Same convention as CoordinatorReadRepository.genericRow (LOCKED CONVENTIONS #3, ground truth §8):
       *  numeric(x,0) -> String (event_district, event_block, created_by, updated_by, uploaded_by,
       *  generated_by, student_id); integer columns pass through natively (event_id, photo_id, report_id,
       *  event_type_id, cohort_number, boys/girls/parents_attended); date -> "yyyy-MM-dd"; timestamp ->
       *  ISO-Z. Package-private static so EventsWriteRepository reuses it for RETURNING * rows. */
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

      /** getEventTypes (eventModel.js:28-36). */
      public List<Map<String, Object>> eventTypes() {
          return jdbc.sql("""
                  SELECT event_type_id, event_type_name
                  FROM pp.event_type
                  ORDER BY event_type_name ASC
                  """).query((rs, i) -> genericRow(rs)).list();
      }

      /** getStates (eventModel.js:232-236). */
      public List<Map<String, Object>> states() {
          return jdbc.sql("SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'")
                  .query((rs, i) -> genericRow(rs)).list();
      }

      /** getDivisionsByState (eventModel.js:238-247). */
      public List<Map<String, Object>> divisionsByState(String stateName) {
          return jdbc.sql("""
                  SELECT juris_code, juris_name FROM pp.jurisdiction
                  WHERE parent_juris IN (
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE LOWER(TRIM(juris_name)) = LOWER(TRIM(:state)) AND LOWER(juris_type) = 'state'
                  ) AND LOWER(juris_type) = 'division'
                  """).param("state", stateName).query((rs, i) -> genericRow(rs)).list();
      }

      /** getDistrictsByDivisions (eventModel.js:249-265). Caller lower/trims divisionNames (JS-side in
       *  Node; Java does the same before binding, see EventsController). */
      public List<Map<String, Object>> districtsByDivisions(String[] lowerDivisionNames) {
          return jdbc.sql("""
                  SELECT juris_code, juris_name FROM pp.jurisdiction
                  WHERE parent_juris IN (
                    SELECT juris_code FROM pp.jurisdiction
                    WHERE LOWER(TRIM(juris_name)) = ANY(:divisions)
                    AND LOWER(juris_type) = 'division'
                  ) AND LOWER(juris_type) = 'education district'
                  """).param("divisions", lowerDivisionNames).query((rs, i) -> genericRow(rs)).list();
      }

      /** getBlocksByMultiDistricts (eventModel.js:267-314). $1=stateName RAW (SQL does LOWER(TRIM())),
       *  $2/$3=already-lowered/trimmed arrays (JS-side in Node, Java mirrors in EventsController). */
      public List<Map<String, Object>> blocksByMultiDistricts(String stateName, String[] lowerDivisionNames,
                                                                String[] lowerDistrictNames) {
          return jdbc.sql("""
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
                      WHERE LOWER(TRIM(d.juris_name)) = ANY(:districts)
                        AND LOWER(d.juris_type) = 'education district'
                        AND d.parent_juris IN (
                          SELECT div.juris_code FROM pp.jurisdiction div
                          WHERE LOWER(TRIM(div.juris_name)) = ANY(:divisions)
                            AND LOWER(div.juris_type) = 'division'
                            AND div.parent_juris IN (
                              SELECT s.juris_code FROM pp.jurisdiction s
                              WHERE LOWER(TRIM(s.juris_name)) = LOWER(TRIM(:state))
                                AND LOWER(s.juris_type) = 'state'
                            )
                        )
                    )
                  """)
                  .param("state", stateName).param("divisions", lowerDivisionNames)
                  .param("districts", lowerDistrictNames)
                  .query((rs, i) -> genericRow(rs)).list();
      }
  }
  ```

- [ ] **1.6** Implement `EventsWriteRepository` (event-type create/update only, for this task).

  `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsWriteRepository.java`:
  ```java
  package com.rcf.imas.modules.events.persistence;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  import java.util.Map;
  import java.util.Optional;

  @Repository
  public class EventsWriteRepository {

      private final JdbcClient jdbc;

      public EventsWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

      /** createEventType (eventModel.js:7-15). Autocommit, single statement (ground truth §6). */
      public Map<String, Object> createEventType(String name) {
          return jdbc.sql("INSERT INTO pp.event_type (event_type_name) VALUES (:name) RETURNING *")
                  .param("name", name)
                  .query((rs, i) -> EventsReadRepository.genericRow(rs)).single();
      }

      /** updateEventType (eventModel.js:17-26). Returns null (no row) if id doesn't match -- Node's
       *  `rows[0]` on an empty result is `undefined`; Java's controller maps this to an empty 200 body. */
      public Optional<Map<String, Object>> updateEventType(long id, String name) {
          return jdbc.sql("UPDATE pp.event_type SET event_type_name = :name WHERE event_type_id = :id RETURNING *")
                  .param("name", name).param("id", id)
                  .query((rs, i) -> EventsReadRepository.genericRow(rs)).optional();
      }
  }
  ```

- [ ] **1.7** Implement `EventsController` (Task-1 endpoints only).

  `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`:
  ```java
  package com.rcf.imas.modules.events.web;

  import com.rcf.imas.modules.events.persistence.EventsReadRepository;
  import com.rcf.imas.modules.events.persistence.EventsWriteRepository;
  import com.rcf.imas.modules.events.service.EventValidation;
  import com.rcf.imas.platform.error.ApiException;
  import org.springframework.http.HttpStatus;
  import org.springframework.security.access.prepost.PreAuthorize;
  import org.springframework.web.bind.annotation.*;

  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  @RestController
  @RequestMapping("/api")
  @PreAuthorize("hasRole('ADMIN')")   // Node: eventRoutes.js has ZERO auth middleware on all 12 routes (Locked Decision 1)
  public class EventsController {

      private final EventsReadRepository reads;
      private final EventsWriteRepository writes;

      public EventsController(EventsReadRepository reads, EventsWriteRepository writes) {
          this.reads = reads;
          this.writes = writes;
      }

      /* ===================== EVENT TYPE ===================== */

      /** createEventType (eventController.js:9-24). */
      @PostMapping("/event-types")
      public org.springframework.http.ResponseEntity<Map<String, Object>> createEventType(
              @RequestBody(required = false) Map<String, Object> body) {
          Object name = body == null ? null : body.get("event_type_name");
          if (name == null || String.valueOf(name).isBlank()) {
              throw ApiException.message(400, "Event type name is required");
          }
          try {
              Map<String, Object> row = writes.createEventType(String.valueOf(name));
              return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(row);
          } catch (Exception e) {
              throw ApiException.message(500, "Failed to create event type");
          }
      }

      /** updateEventType (eventController.js:26-35). */
      @PutMapping("/event-type/{id}")
      public Map<String, Object> updateEventType(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
          long eventTypeId = EventValidation.validateEventId(id);
          Object name = body == null ? null : body.get("event_type_name");
          try {
              return writes.updateEventType(eventTypeId, name == null ? null : String.valueOf(name))
                      .map(row -> row)
                      .orElseGet(LinkedHashMap::new);
          } catch (Exception e) {
              throw ApiException.message(500, "Failed to update event type");
          }
      }

      /** getEventTypes (eventController.js:37-44). */
      @GetMapping("/event-types")
      public List<Map<String, Object>> getEventTypes() {
          try {
              return reads.eventTypes();
          } catch (Exception e) {
              throw ApiException.message(500, "Failed to fetch event types");
          }
      }

      /* ===================== JURISDICTIONS ===================== */

      /** getJurisdictionData (eventController.js:209-219). No `else` branch for an unrecognized `type` --
       *  `data` stays unset and the response omits the "data" key entirely (200, not 400) -- ported literally. */
      @GetMapping("/attendance/jurisdictions")
      public Map<String, Object> jurisdictionData(@RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String stateName,
                                                    @RequestParam(required = false) List<String> divisionNames,
                                                    @RequestParam(required = false) List<String> districtNames) {
          try {
              Map<String, Object> out = new LinkedHashMap<>();
              out.put("success", true);
              List<Map<String, Object>> data = switch (type == null ? "" : type) {
                  case "state" -> reads.states();
                  case "division" -> reads.divisionsByState(stateName);
                  case "district" -> reads.districtsByDivisions(lowerTrim(districtNames == null ? divisionNames : divisionNames));
                  case "block" -> reads.blocksByMultiDistricts(stateName, lowerTrim(divisionNames), lowerTrim(districtNames));
                  default -> null;
              };
              if (data != null) out.put("data", data);
              return out;
          } catch (Exception e) {
              throw ApiException.of(500).with("success", false).with("msg", e.getMessage());
          }
      }

      /** Node lower/trims divisionNames/districtNames arrays in JS before binding (eventModel.js:251-253,
       *  273-279) -- a single non-array query value is treated as a 1-element array (Spring's List<String>
       *  binding already normalizes single-vs-multi query params to a List, so no extra branch is needed here). */
      private static String[] lowerTrim(List<String> values) {
          if (values == null) return new String[0];
          return values.stream().map(v -> v == null ? "" : v.trim().toLowerCase()).toArray(String[]::new);
      }
  }
  ```

  **Note for 1.7 implementer:** the `"district"` case above intentionally reads `divisionNames` (matching
  `getDistrictsByDivisions(divisionNames)`'s live signature, `eventController.js:215`) -- `districtNames` is
  NOT an input to that branch; only `divisionNames` filters which districts come back. Do not swap these.
  Fix the placeholder ternary before shipping Task 1 (`districtNames == null ? divisionNames : divisionNames`
  above is a copy-paste artifact of drafting this plan -- it must simply read `lowerTrim(divisionNames)`).

- [ ] **1.8** Add the `imas.event-storage-path` property.

  `imas-backend/src/main/resources/application.yml` — add under the existing `imas:` block (after `file-storage-path`):
  ```yaml
  imas:
    jwt:
      secret: ${JWT_SECRET:}
      expires-in: ${JWT_EXPIRES_IN:1d}
      pre-auth-expires-in: ${PRE_AUTH_JWT_EXPIRES_IN:15m}
    file-storage-path: ${FILE_STORAGE_PATH:./data}
    event-storage-path: ${EVENT_STORAGE_PATH:./uploads/events}
  ```

- [ ] **1.9** Add the static-file `permitAll()` matchers to `SecurityConfig`.

  `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java` — insert a new matcher line
  right after the existing coordinator-health-check matcher (full resulting `filterChain` method shown for
  clarity; only the `.authorizeHttpRequests(...)` block changes):
  ```java
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
      http.csrf(csrf -> csrf.disable())
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(auth -> auth
              .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/authorize-role").permitAll()
              .requestMatchers(HttpMethod.GET, "/api/exams/hallticket/**").permitAll()
              .requestMatchers(HttpMethod.GET, "/api/student", "/api/student/").permitAll()
              .requestMatchers(HttpMethod.GET, "/api/coordinator", "/api/coordinator/").permitAll()
              // Public event photo/report static serving -- index.js:147-155's express.static mounts, GET
              // only. Paired with EventStaticResourceConfig (Plan 5a Task 3). Locked Decision 1's exception
              // to the events module's class-level @PreAuthorize("hasRole('ADMIN')").
              .requestMatchers(HttpMethod.GET, "/uploads/events/photos/**", "/uploads/events/reports/**").permitAll()
              .requestMatchers("/actuator/health").permitAll()
              .anyRequest().authenticated())
          .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
              res.setStatus(401);
              res.setContentType("application/json");
              res.getWriter().write("{\"error\":\"No token provided\",\"code\":\"TOKEN_MISSING\"}");
          }))
          .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
      return http.build();
  }
  ```

- [ ] **1.10** Fix the `"district"` case in `EventsController.jurisdictionData` per the 1.7 note (change
  `lowerTrim(districtNames == null ? divisionNames : divisionNames)` to `lowerTrim(divisionNames)`), then run:
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventTypesAndJurisdictionsIT`
  Expect PASS (all 9 test methods).

- [ ] **1.11** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/events imas-backend/src/main/java/com/rcf/imas/platform/error/ApiException.java imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java imas-backend/src/main/resources/application.yml imas-backend/src/test/java/com/rcf/imas/modules/events/EventTypesAndJurisdictionsIT.java
  git commit -m "$(cat <<'EOF'
  feat(events): event-type CRUD + jurisdiction cascade (Phase 5a Task 1/5)

  New com.rcf.imas.modules.events module skeleton: event-type create/update/list,
  the state->division->education-district->block jurisdiction cascade (incl. the
  is_frozen_block shortlist-batch coupling), class-level ADMIN auth (Node had zero
  auth on this router), and the static-file permitAll() matchers SecurityConfig
  will need once file storage lands in Task 3.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2 — Events read (list + detail)

Endpoints: `GET /events` (`getAllEvents`), `GET /events/:id` (`getEventById` + photos + reports).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsReadRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsReadIT.java`

Seed id range: `970200`-`970299` (event_id, photo_id, report_id all come from their own sequences and are not
manually assigned; jurisdiction/cohort/user ids use `970200`-`970299`).

Note: neither `getAllEvents` nor `getEventById` joins `pp.event_students` (ground truth §2 confirms both
queries verbatim) -- the attendee counts they return (`boys_attended`/`girls_attended`/`parents_attended`) are
whatever is currently stored on `pp.event_master`, kept in sync separately by `updateEvent`/`submitAttendance`
(Tasks 4/5). This task does NOT need `pp.event_students` seed rows.

- [ ] **2.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsReadIT.java`:
  ```java
  package com.rcf.imas.modules.events;

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
  class EventsReadIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String admin;
      Integer eventTypeId;
      Integer eventId;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970201,'evAdmin972','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          admin = jwt.issueFinalToken("970201", "evAdmin972", "ADMIN");

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970201, 'Cohort970')").update();

          eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType970') RETURNING event_type_id")
                  .query(Integer.class).single();

          eventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_description, event_start_date,
                      event_end_date, event_location, cohort_number, boys_attended, girls_attended, parents_attended,
                      created_by, updated_by)
                  VALUES (:t,'Sammelan Event 970','desc970',DATE '2026-01-10',DATE '2026-01-11','Hall970',970201,5,7,3,970201,970201)
                  RETURNING event_id
                  """).param("t", eventTypeId).query(Integer.class).single();

          jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name, uploaded_by) VALUES (:e,'/tmp/p1.jpg','p1.jpg',970201)")
                  .param("e", eventId).update();
          jdbc.sql("INSERT INTO pp.event_reports(event_id, report_type, file_path, file_name, generated_by) VALUES (:e,'SAMMELAN_REPORT','/tmp/r1.pdf','r1.pdf',970201)")
                  .param("e", eventId).update();
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970201)").update();
          jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970201)").update();
          jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970201").update();
          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'EvType970'").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970201").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970201").update();
      }

      @Test
      void getAllEventsIncludesCoverPhotoAndCounts() throws Exception {
          mvc.perform(get("/api/events").header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].event_title").value("Sammelan Event 970"))
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].cover_photo").value("/tmp/p1.jpg"))
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].boys_attended").value(5))
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].girls_attended").value(7))
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].event_type").value("EvType970"))
              .andExpect(jsonPath("$[?(@.event_id==" + eventId + ")].start_date").value("2026-01-10"));
      }

      @Test
      void getEventByIdIncludesPhotosAndReports() throws Exception {
          mvc.perform(get("/api/events/" + eventId).header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.event_title").value("Sammelan Event 970"))
              .andExpect(jsonPath("$.event_type_name").value("EvType970"))
              .andExpect(jsonPath("$.created_by").value("970201"))
              .andExpect(jsonPath("$.photos", hasSize(1)))
              .andExpect(jsonPath("$.photos[0].file_name").value("p1.jpg"))
              .andExpect(jsonPath("$.reports", hasSize(1)))
              .andExpect(jsonPath("$.reports[0].report_type").value("SAMMELAN_REPORT"));
      }

      @Test
      void getEventByIdNotFoundIs404() throws Exception {
          mvc.perform(get("/api/events/999999999").header("Authorization", "Bearer " + admin))
              .andExpect(status().isNotFound())
              .andExpect(jsonPath("$.message").value("Not found"));
      }

      @Test
      void getEventByIdInvalidIdIs400() throws Exception {
          mvc.perform(get("/api/events/abc").header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Invalid event ID"));
      }
  }
  ```

- [ ] **2.2** Run, confirm FAIL (`getAllEvents`/`getEventById` don't exist yet):
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsReadIT`

- [ ] **2.3** Add the read methods to `EventsReadRepository` (insert into the existing class, after `states()`
  or anywhere convenient inside the class body):
  ```java
  /** getAllEvents (eventModel.js:177-203). cover_photo subquery has NO ORDER BY -- "first photo" is
   *  Postgres's undefined pick, not deterministic (ground truth §2 note) -- ported literally, do not add
   *  an ORDER BY. */
  public List<Map<String, Object>> allEvents() {
      return jdbc.sql("""
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
              """).query((rs, i) -> genericRow(rs)).list();
  }

  /** getEventById (eventModel.js:205-216). m.* returns every event_master column verbatim, including
   *  event_district/event_block as raw numeric jurisdiction codes (not names). */
  public Optional<Map<String, Object>> eventById(long eventId) {
      return jdbc.sql("""
              SELECT m.*, t.event_type_name
              FROM pp.event_master m
              JOIN pp.event_type t ON t.event_type_id = m.event_type_id
              WHERE m.event_id = :id
              """).param("id", eventId).query((rs, i) -> genericRow(rs)).optional();
  }

  /** getEventPhotos (eventModel.js:140-148). */
  public List<Map<String, Object>> eventPhotos(long eventId) {
      return jdbc.sql("SELECT photo_id, file_path, file_name FROM pp.event_photos WHERE event_id = :id")
              .param("id", eventId).query((rs, i) -> genericRow(rs)).list();
  }

  /** getEventReports (eventModel.js:162-171). */
  public List<Map<String, Object>> eventReports(long eventId) {
      return jdbc.sql("SELECT * FROM pp.event_reports WHERE event_id = :id ORDER BY generated_at DESC")
              .param("id", eventId).query((rs, i) -> genericRow(rs)).list();
  }
  ```

- [ ] **2.4** Add the two endpoints to `EventsController` (insert after the jurisdictions endpoint):
  ```java
  /* ===================== EVENTS ===================== */

  /** getAllEvents (eventController.js:183-188). */
  @GetMapping("/events")
  public List<Map<String, Object>> getAllEvents() {
      try {
          return reads.allEvents();
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("message", "Fetch failed");
      }
  }

  /** getEventById (eventController.js:190-199). */
  @GetMapping("/events/{id}")
  public Map<String, Object> getEventById(@PathVariable String id) {
      long eventId = EventValidation.validateEventId(id);
      try {
          Map<String, Object> event = reads.eventById(eventId)
                  .orElseThrow(() -> ApiException.message(404, "Not found"));
          Map<String, Object> out = new LinkedHashMap<>(event);
          out.put("photos", reads.eventPhotos(eventId));
          out.put("reports", reads.eventReports(eventId));
          return out;
      } catch (ApiException e) {
          throw e;
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("message", "Fetch failed");
      }
  }
  ```
  Add `import java.util.Optional;` to `EventsController.java` if not already present (it isn't needed directly
  here since `.orElseThrow` is called on the repository's `Optional` return, but double-check imports compile).

- [ ] **2.5** Run, expect PASS:
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsReadIT`

- [ ] **2.6** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/events imas-backend/src/test/java/com/rcf/imas/modules/events/EventsReadIT.java
  git commit -m "$(cat <<'EOF'
  feat(events): list + detail read endpoints (Phase 5a Task 2/5)

  GET /events (getAllEvents, incl. the non-deterministic cover_photo subquery
  ported literally) and GET /events/:id (getEventById + nested photos/reports).
  Neither joins pp.event_students -- attendee counts come straight off
  event_master, kept in sync by updateEvent/submitAttendance (Tasks 4-5).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 3 — Events create + delete (multipart file storage, `@Transactional`)

Endpoints: `POST /events` (`createEvent`-only behavior, Firm Decision 3), `DELETE /events/:id` (`deleteEvent`).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventFileStorageService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventUploadValidation.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/events/config/EventStaticResourceConfig.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsCreateDeleteIT.java`

Seed id range: `970300`-`970399`.

- [ ] **3.1** Write the failing IT (uses `@TempDir` + `@DynamicPropertySource` for `imas.event-storage-path`,
  per the `TrackingStudentsAndDocumentIT` pattern).

  `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsCreateDeleteIT.java`:
  ```java
  package com.rcf.imas.modules.events;

  import com.rcf.imas.PgIntegrationTest;
  import com.rcf.imas.platform.security.JwtService;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.mock.web.MockMultipartFile;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;

  import static org.junit.jupiter.api.Assertions.*;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class EventsCreateDeleteIT extends PgIntegrationTest {

      @TempDir static Path storageRoot;

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry registry) {
          registry.add("imas.event-storage-path", () -> storageRoot.toString());
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String admin;
      Integer eventTypeId;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970301,'evAdmin973','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          admin = jwt.issueFinalToken("970301", "evAdmin973", "ADMIN");

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970301, 'Cohort973')").update();
          eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType973') RETURNING event_type_id")
                  .query(Integer.class).single();
      }

      @AfterEach
      void tearDown() throws Exception { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.event_students WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
          jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
          jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970301)").update();
          jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970301").update();
          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name = 'EvType973'").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970301").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970301").update();
      }

      @Test
      void createEventWithPhotosPersistsMasterAndPhotosAndFiles() throws Exception {
          MockMultipartFile photo1 = new MockMultipartFile("photos", "IMG_001.jpg", "image/jpeg", "fake-jpg-bytes".getBytes());
          MockMultipartFile photo2 = new MockMultipartFile("photos", "IMG_002.png", "image/png", "fake-png-bytes".getBytes());

          String resp = mvc.perform(multipart("/api/events").file(photo1).file(photo2)
                  .param("eventTitle", "Sammelan 970 Launch")
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_title", "Sammelan 970 Launch")
                  .param("event_start_date", "2026-02-01")
                  .param("event_end_date", "2026-02-02")
                  .param("cohort_number", "970301")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.message").value("Event created"))
              .andReturn().getResponse().getContentAsString();

          Integer eventId = com.jayway.jsonpath.JsonPath.read(resp, "$.event_id");

          Integer photoCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE event_id = :id")
                  .param("id", eventId).query(Integer.class).single();
          assertEquals(2, photoCount);

          List<String> fileNames = jdbc.sql("SELECT file_name FROM pp.event_photos WHERE event_id = :id ORDER BY file_name")
                  .param("id", eventId).query(String.class).list();
          // createEvent stores the ORIGINAL uploaded filename (eventController.js:89), not the server-generated one
          assertEquals(List.of("IMG_001.jpg", "IMG_002.png"), fileNames);

          // server-generated disk filenames follow <clean(eventTitle)>-<n><ext>
          assertTrue(Files.exists(storageRoot.resolve("photos").resolve("sammelan_970_launch-1.jpg")));
          assertTrue(Files.exists(storageRoot.resolve("photos").resolve("sammelan_970_launch-2.png")));

          // served publicly, no auth header
          mvc.perform(get("/uploads/events/photos/sammelan_970_launch-1.jpg"))
              .andExpect(status().isOk());

          jdbc.sql("DELETE FROM pp.event_photos WHERE event_id = :id").param("id", eventId).update();
          jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
      }

      @Test
      void createEventReportFileIsIgnoredNoDbRowWritten() throws Exception {
          MockMultipartFile report = new MockMultipartFile("reports", "report.pdf", "application/pdf", "fake-pdf".getBytes());

          String resp = mvc.perform(multipart("/api/events").file(report)
                  .param("eventTitle", "No Report On Create 970")
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_title", "No Report On Create 970")
                  .param("event_start_date", "2026-03-01")
                  .param("event_end_date", "2026-03-02")
                  .param("cohort_number", "970301")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isCreated())
              .andReturn().getResponse().getContentAsString();

          Integer eventId = com.jayway.jsonpath.JsonPath.read(resp, "$.event_id");
          Integer reportCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id")
                  .param("id", eventId).query(Integer.class).single();
          assertEquals(0, reportCount); // Firm Decision 3: createEvent never persists reports

          jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
      }

      @Test
      void createEventMissingTitleIs400() throws Exception {
          mvc.perform(multipart("/api/events")
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_start_date", "2026-02-01").param("event_end_date", "2026-02-02")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Event title must be at least 3 characters"));
      }

      @Test
      void createEventBadPhotoMimeIs400() throws Exception {
          MockMultipartFile badPhoto = new MockMultipartFile("photos", "note.txt", "text/plain", "hi".getBytes());
          mvc.perform(multipart("/api/events").file(badPhoto)
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_title", "Bad Photo Event 970")
                  .param("event_start_date", "2026-02-01").param("event_end_date", "2026-02-02")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Photos must be JPG, PNG, or WEBP"));
      }

      @Test
      void deleteEventCascadesStudentsPhotosReportsMaster() throws Exception {
          Integer eventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                  VALUES (:t,'To Delete 970',DATE '2026-04-01',DATE '2026-04-02',970301) RETURNING event_id
                  """).param("t", eventTypeId).query(Integer.class).single();
          jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/x.jpg','x.jpg')")
                  .param("e", eventId).update();
          jdbc.sql("INSERT INTO pp.event_reports(event_id, report_type, file_path, file_name) VALUES (:e,'SAMMELAN_REPORT','/tmp/x.pdf','x.pdf')")
                  .param("e", eventId).update();

          mvc.perform(delete("/api/events/" + eventId).header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.message").value("Deleted successfully"));

          Integer remainingMaster = jdbc.sql("SELECT COUNT(*) FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
          Integer remainingPhotos = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
          Integer remainingReports = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
          assertEquals(0, remainingMaster);
          assertEquals(0, remainingPhotos);
          assertEquals(0, remainingReports);
      }

      @Test
      void deleteEventInvalidIdIs400() throws Exception {
          mvc.perform(delete("/api/events/abc").header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Invalid event ID"));
      }
  }
  ```

- [ ] **3.2** Run, confirm FAIL: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsCreateDeleteIT`

- [ ] **3.3** Implement `EventUploadValidation`.

  `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventUploadValidation.java`:
  ```java
  package com.rcf.imas.modules.events.service;

  import com.rcf.imas.platform.error.ApiException;
  import org.springframework.web.multipart.MultipartFile;

  import java.util.Set;

  /** uploadEventFiles' fileFilter (eventMiddleware.js:82-101) + size limit (5MB, line 112) + fields maxCount
   *  (4 photos / 1 report, lines 113-116 -> LIMIT_UNEXPECTED_FILE, lines 119-123). Spring's MultipartResolver
   *  has already fully parsed the request by controller time, so there's no streaming per-file error
   *  callback to hook into -- this is request-level validation covering the same three failure modes. */
  public final class EventUploadValidation {

      private EventUploadValidation() {}

      private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/jpg", "image/webp");
      private static final Set<String> DOC_TYPES = Set.of(
              "application/pdf", "application/msword",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
      private static final long MAX_SIZE = 5L * 1024 * 1024;

      public static void validate(MultipartFile[] photos, MultipartFile[] reports) {
          int photoCount = photos == null ? 0 : (int) java.util.Arrays.stream(photos).filter(f -> !f.isEmpty()).count();
          int reportCount = reports == null ? 0 : (int) java.util.Arrays.stream(reports).filter(f -> !f.isEmpty()).count();
          if (photoCount > 4 || reportCount > 1) {
              throw ApiException.message(400, "Too many files! Max 4 photos and 1 report allowed.");
          }
          if (photos != null) {
              for (MultipartFile f : photos) {
                  if (f == null || f.isEmpty()) continue;
                  if (f.getSize() > MAX_SIZE) throw ApiException.message(400, "File too large");
                  if (!PHOTO_TYPES.contains(f.getContentType())) {
                      throw ApiException.message(400, "Photos must be JPG, PNG, or WEBP");
                  }
              }
          }
          if (reports != null) {
              for (MultipartFile f : reports) {
                  if (f == null || f.isEmpty()) continue;
                  if (f.getSize() > MAX_SIZE) throw ApiException.message(400, "File too large");
                  if (!DOC_TYPES.contains(f.getContentType())) {
                      throw ApiException.message(400, "Reports must be PDF or Word documents");
                  }
              }
          }
      }
  }
  ```

- [ ] **3.4** Implement `EventFileStorageService`.

  `imas-backend/src/main/java/com/rcf/imas/modules/events/service/EventFileStorageService.java`:
  ```java
  package com.rcf.imas.modules.events.service;

  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.stereotype.Service;
  import org.springframework.web.multipart.MultipartFile;

  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.nio.file.Paths;

  /**
   * Reproduces uploadEventFiles' multer diskStorage.filename callback (eventMiddleware.js:37-73) VERBATIM,
   * including the eventTitle(camelCase)-vs-event_title(snake_case) filename-source landmine (ground truth
   * §4, §7.3, plan Firm Decision 4 / Deferred): the filename source is a request param literally named
   * "eventTitle", falling back to the literal "event" if absent -- NOT the "event_title" field the rest of
   * this module's validation uses. Ported as-is; flagged, not fixed.
   */
  @Service
  public class EventFileStorageService {

      private final Path photosDir;
      private final Path reportsDir;

      public EventFileStorageService(@Value("${imas.event-storage-path}") String basePath) {
          Path base = Paths.get(basePath);
          this.photosDir = base.resolve("photos");
          this.reportsDir = base.resolve("reports");
          try {
              Files.createDirectories(photosDir);
              Files.createDirectories(reportsDir);
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
      }

      public record StoredFile(String diskPath, String storedFilename, String originalFilename) {}

      static String cleanName(String eventTitleField) {
          String t = (eventTitleField == null || eventTitleField.isBlank()) ? "event" : eventTitleField;
          return t.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
      }

      static String extensionOf(String originalFilename) {
          if (originalFilename == null) return "";
          int dot = originalFilename.lastIndexOf('.');
          return dot >= 0 ? originalFilename.substring(dot).toLowerCase() : "";
      }

      /** Photos: <cleanName>-<n><ext>, n = 1-based per-request counter (eventMiddleware.js:56-66, max 4). */
      public StoredFile storePhoto(MultipartFile file, String eventTitleField, int index) {
          String filename = cleanName(eventTitleField) + "-" + index + extensionOf(file.getOriginalFilename());
          Path target = photosDir.resolve(filename);
          try {
              file.transferTo(target);
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
          return new StoredFile(target.toString(), filename, file.getOriginalFilename());
      }

      /** Reports: <cleanName>-report<ext>, no counter -- a second report in the same request overwrites the
       *  first ON DISK (eventMiddleware.js:67-70); DB rows are managed separately per endpoint. */
      public StoredFile storeReport(MultipartFile file, String eventTitleField) {
          String filename = cleanName(eventTitleField) + "-report" + extensionOf(file.getOriginalFilename());
          Path target = reportsDir.resolve(filename);
          try {
              file.transferTo(target);
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
          return new StoredFile(target.toString(), filename, file.getOriginalFilename());
      }

      public Path photosDir() { return photosDir; }
      public Path reportsDir() { return reportsDir; }
  }
  ```

- [ ] **3.5** Implement `EventStaticResourceConfig`.

  `imas-backend/src/main/java/com/rcf/imas/modules/events/config/EventStaticResourceConfig.java`:
  ```java
  package com.rcf.imas.modules.events.config;

  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
  import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

  import java.nio.file.Path;
  import java.nio.file.Paths;

  /** Static-serves pp.event_photos/pp.event_reports files at the SAME URL prefixes Node used
   *  (index.js:147-155: app.use("/uploads/events/photos", express.static(EVENT_PHOTOS_DIR)) / "/reports").
   *  GET-only in practice -- paired with the permitAll() matchers added to SecurityConfig in Task 1. */
  @Configuration
  public class EventStaticResourceConfig implements WebMvcConfigurer {

      private final String eventStoragePath;

      public EventStaticResourceConfig(@Value("${imas.event-storage-path}") String eventStoragePath) {
          this.eventStoragePath = eventStoragePath;
      }

      @Override
      public void addResourceHandlers(ResourceHandlerRegistry registry) {
          Path base = Paths.get(eventStoragePath).toAbsolutePath().normalize();
          registry.addResourceHandler("/uploads/events/photos/**")
                  .addResourceLocations("file:" + base.resolve("photos") + "/");
          registry.addResourceHandler("/uploads/events/reports/**")
                  .addResourceLocations("file:" + base.resolve("reports") + "/");
      }
  }
  ```

- [ ] **3.6** Add `createEvent` and `deleteEvent` to `EventsWriteRepository` (insert into the existing class):
  ```java
  /** createEvent (eventModel.js:52-76 + eventController.js:50-100), fused into one @Transactional method
   *  (Firm Decision 6). Only the POST-/events behavior: master INSERT + photos loop. Never touches reports
   *  (Firm Decision 3 -- the dead createEvent->updateEvent chain means Node's live POST /events never
   *  persists a report either). boys/girls/parents_attended are inserted exactly as sanitized (nullable,
   *  no `|| 0` coercion here -- see plan Disagreements #2, this differs from updateEvent). */
  @org.springframework.transaction.annotation.Transactional
  public int createEvent(Integer eventTypeId, String eventTitle, String eventDescription,
                          String eventStartDate, String eventEndDate,
                          String eventDistrict, String eventBlock, String eventLocation,
                          String pincode, String cohortNumber,
                          String boysAttended, String girlsAttended, String parentsAttended,
                          Long userId,
                          java.util.List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> photos) {
      int eventId = jdbc.sql("""
              INSERT INTO pp.event_master (
                event_type_id, event_title, event_description, event_start_date, event_end_date,
                event_district, event_block, event_location, pincode, cohort_number,
                boys_attended, girls_attended, parents_attended, created_by, updated_by
              ) VALUES (
                :eventTypeId::integer, :eventTitle, :eventDescription, :eventStartDate::date, :eventEndDate::date,
                :eventDistrict::numeric, :eventBlock::numeric, :eventLocation, :pincode, :cohortNumber::integer,
                :boysAttended::integer, :girlsAttended::integer, :parentsAttended::integer, :userId::numeric, :userId::numeric
              )
              RETURNING event_id
              """)
              .param("eventTypeId", eventTypeId).param("eventTitle", eventTitle)
              .param("eventDescription", eventDescription)
              .param("eventStartDate", eventStartDate).param("eventEndDate", eventEndDate)
              .param("eventDistrict", eventDistrict).param("eventBlock", eventBlock)
              .param("eventLocation", eventLocation).param("pincode", pincode)
              .param("cohortNumber", cohortNumber)
              .param("boysAttended", boysAttended).param("girlsAttended", girlsAttended)
              .param("parentsAttended", parentsAttended).param("userId", userId)
              .query(Integer.class).single();

      for (var p : photos) {
          jdbc.sql("""
                  INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                  VALUES (:eventId, :path, :name, :userId::numeric)
                  """)
                  .param("eventId", eventId).param("path", p.diskPath())
                  .param("name", p.originalFilename())   // createEvent stores the ORIGINAL filename (eventController.js:89)
                  .param("userId", userId)
                  .update();
      }
      return eventId;
  }

  /** deleteEvent (eventModel.js:104-126). Node runs this as its OWN self-contained transaction inside the
   *  model function (unusual vs. every other multi-statement write, which BEGINs/COMMITs in the controller
   *  -- ground truth §6); Java just makes the whole method @Transactional, same net effect. Order: students,
   *  photos, reports, master (photos/reports ON DELETE CASCADE from event_master makes the explicit deletes
   *  technically redundant, ground truth §3, but ported literally/in-order regardless). */
  @org.springframework.transaction.annotation.Transactional
  public void deleteEvent(long eventId) {
      jdbc.sql("DELETE FROM pp.event_students WHERE event_id = :id").param("id", eventId).update();
      jdbc.sql("DELETE FROM pp.event_photos WHERE event_id = :id").param("id", eventId).update();
      jdbc.sql("DELETE FROM pp.event_reports WHERE event_id = :id").param("id", eventId).update();
      jdbc.sql("DELETE FROM pp.event_master WHERE event_id = :id").param("id", eventId).update();
  }
  ```
  Add `import org.springframework.transaction.annotation.Transactional;` and use the short `@Transactional`
  form instead of the fully-qualified one above once wired in (both compile; prefer the import for readability).

- [ ] **3.7** Add the two endpoints + shared multipart helper to `EventsController`. Add these imports:
  `org.springframework.web.multipart.MultipartFile`, `com.rcf.imas.modules.events.service.EventFileStorageService`,
  `com.rcf.imas.modules.events.service.EventUploadValidation`, `java.util.ArrayList`, `java.util.stream.Collectors`.
  Inject `EventFileStorageService` via the constructor (add a field + constructor param). Then add:
  ```java
  /* ===================== EVENTS: CREATE / DELETE ===================== */

  /** createEvent-only behavior for POST /events (Firm Decision 3 -- updateEvent never runs on this route
   *  in live Node, so Java doesn't implement it here at all). */
  @PostMapping(value = "/events", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public org.springframework.http.ResponseEntity<Map<String, Object>> createEvent(
          @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
          @RequestParam("event_type_id") String eventTypeId,
          @RequestParam("event_title") String eventTitle,
          @RequestParam(value = "event_description", required = false) String eventDescription,
          @RequestParam("event_start_date") String eventStartDate,
          @RequestParam("event_end_date") String eventEndDate,
          @RequestParam(value = "event_district", required = false) String eventDistrictRaw,
          @RequestParam(value = "event_block", required = false) String eventBlockRaw,
          @RequestParam(value = "event_location", required = false) String eventLocation,
          @RequestParam(value = "pincode", required = false) String pincodeRaw,
          @RequestParam(value = "cohort_number", required = false) String cohortNumberRaw,
          @RequestParam(value = "boys_attended", required = false) String boysAttendedRaw,
          @RequestParam(value = "girls_attended", required = false) String girlsAttendedRaw,
          @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
          @RequestParam(value = "user_id", required = false) String userIdBody,
          @RequestParam(value = "photos", required = false) MultipartFile[] photos,
          @RequestParam(value = "reports", required = false) MultipartFile[] reports,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
                  com.rcf.imas.platform.security.JwtService.FinalToken principal) {

      EventUploadValidation.validate(photos, reports);

      String eventDistrict = EventValidation.sanitizeNumeric(eventDistrictRaw);
      String eventBlock = EventValidation.sanitizeNumeric(eventBlockRaw);
      String pincode = EventValidation.sanitizeNumeric(pincodeRaw);
      String cohortNumber = EventValidation.sanitizeNumeric(cohortNumberRaw);
      String boysAttended = EventValidation.sanitizeNumeric(boysAttendedRaw);
      String girlsAttended = EventValidation.sanitizeNumeric(girlsAttendedRaw);
      String parentsAttended = EventValidation.sanitizeNumeric(parentsAttendedRaw);

      EventValidation.validateEventBody(eventTypeId, eventTitle, eventStartDate, eventEndDate);

      // req.user?.user_id || user_id || null (eventController.js:63) -- principal is real now that this
      // controller enforces ADMIN auth (Locked Decision 1), so it's the operative path in practice.
      Long userId = principal != null ? Long.valueOf(principal.userId())
              : (userIdBody != null && !userIdBody.isBlank() ? Long.valueOf(userIdBody) : null);

      try {
          List<EventFileStorageService.StoredFile> stored = new ArrayList<>();
          if (photos != null) {
              int idx = 0;
              for (MultipartFile f : photos) {
                  if (f == null || f.isEmpty()) continue;
                  idx++;
                  stored.add(fileStorage.storePhoto(f, eventTitleFilenameField, idx));
              }
          }

          int eventId = writes.createEvent(Integer.valueOf(eventTypeId), eventTitle, eventDescription,
                  eventStartDate, eventEndDate, eventDistrict, eventBlock, eventLocation, pincode, cohortNumber,
                  boysAttended, girlsAttended, parentsAttended, userId, stored);

          Map<String, Object> body = new LinkedHashMap<>();
          body.put("success", true);
          body.put("message", "Event created");
          body.put("event_id", eventId);
          return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(body);
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("message", "Failed to create event");
      }
  }

  /** deleteEvent (eventController.js:175-181). */
  @DeleteMapping("/events/{id}")
  public Map<String, Object> deleteEvent(@PathVariable String id) {
      long eventId = EventValidation.validateEventId(id);
      try {
          writes.deleteEvent(eventId);
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("success", true);
          body.put("message", "Deleted successfully");
          return body;
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("message", "Delete failed");
      }
  }
  ```
  Also update the constructor to accept and store `EventFileStorageService fileStorage` as a new field/param.

- [ ] **3.8** Run, expect PASS: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsCreateDeleteIT`

- [ ] **3.9** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/events imas-backend/src/test/java/com/rcf/imas/modules/events/EventsCreateDeleteIT.java
  git commit -m "$(cat <<'EOF'
  feat(events): create + delete with multipart photo storage (Phase 5a Task 3/5)

  POST /events (createEvent-only behavior -- Node's dead createEvent->updateEvent
  route chain means reports can never be attached on create; ported literally) and
  DELETE /events/:id (student/photo/report/master cascade). Adds the file-storage
  service (verbatim multer filename reproduction, incl. the eventTitle-camelCase
  landmine), upload validation (size/MIME/count), and the WebMvcConfigurer static
  resource handler paired with Task 1's SecurityConfig matchers.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 4 — Events update (multipart + `@Transactional` + IDOR-scoped photo delete)

Endpoint: `PUT /events/:id` (`updateEvent`).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsUpdateIT.java`

Seed id range: `970400`-`970499`.

- [ ] **4.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/events/EventsUpdateIT.java`:
  ```java
  package com.rcf.imas.modules.events;

  import com.rcf.imas.PgIntegrationTest;
  import com.rcf.imas.platform.security.JwtService;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.mock.web.MockMultipartFile;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

  import java.nio.file.Path;

  import static org.junit.jupiter.api.Assertions.*;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class EventsUpdateIT extends PgIntegrationTest {

      @TempDir static Path storageRoot;

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry registry) {
          registry.add("imas.event-storage-path", () -> storageRoot.toString());
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String admin;
      Integer eventTypeId;
      Integer sammelanTypeId;
      Integer eventId;
      Integer otherEventId;
      Integer keepPhotoId;
      Integer deletePhotoId;
      Integer otherEventPhotoId;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970401,'evAdmin974','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          admin = jwt.issueFinalToken("970401", "evAdmin974", "ADMIN");

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970401, 'Cohort974')").update();
          eventTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('EvType974') RETURNING event_type_id").query(Integer.class).single();
          sammelanTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Sammelan') RETURNING event_type_id").query(Integer.class).single();

          eventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                  VALUES (:t,'Update Target 974',DATE '2026-05-01',DATE '2026-05-02',970401) RETURNING event_id
                  """).param("t", sammelanTypeId).query(Integer.class).single();
          otherEventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                  VALUES (:t,'Other Event 974',DATE '2026-05-01',DATE '2026-05-02',970401) RETURNING event_id
                  """).param("t", eventTypeId).query(Integer.class).single();

          keepPhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/keep.jpg','keep.jpg') RETURNING photo_id")
                  .param("e", eventId).query(Integer.class).single();
          deletePhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/del.jpg','del.jpg') RETURNING photo_id")
                  .param("e", eventId).query(Integer.class).single();
          otherEventPhotoId = jdbc.sql("INSERT INTO pp.event_photos(event_id, file_path, file_name) VALUES (:e,'/tmp/other.jpg','other.jpg') RETURNING photo_id")
                  .param("e", otherEventId).query(Integer.class).single();

          // A student marked present on the sammelan event, for the count-sync assertion
          jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name) VALUES (970401,2026,97040100001,'Sync Student')").update();
          jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, active_yn) VALUES (970401,970401,'Sync Student','F','ACTIVE')").update();
          jdbc.sql("INSERT INTO pp.event_students(event_id, student_id) VALUES (:e,970401)").param("e", eventId).update();
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.event_students WHERE student_id = 970401").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 970401").update();
          jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id = 970401").update();
          jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970401)").update();
          jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970401)").update();
          jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970401").update();
          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name IN ('EvType974','Sammelan')").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970401").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970401").update();
      }

      @Test
      void updateEventMasterFieldsPhotoDeleteScopedAndSammelanSync() throws Exception {
          MockMultipartFile newPhoto = new MockMultipartFile("photos", "IMG_NEW.jpg", "image/jpeg", "bytes".getBytes());
          MockMultipartFile report = new MockMultipartFile("reports", "final.pdf", "application/pdf", "pdf-bytes".getBytes());

          mvc.perform(multipart("/api/events/" + eventId).file(newPhoto).file(report)
                  .param("eventTitle", "Updated Title 974")
                  .with(req -> { req.setMethod("PUT"); return req; })
                  .param("event_type_id", String.valueOf(sammelanTypeId))
                  .param("event_title", "Updated Title 974")
                  .param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-03")
                  .param("event_type_name", "Sammelan")
                  .param("cohort_number", "970401")
                  .param("photos_to_delete", "[" + deletePhotoId + "]")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.message").value("Updated successfully"));

          String title = jdbc.sql("SELECT event_title FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(String.class).single();
          assertEquals("Updated Title 974", title);

          // scoped delete removed only the targeted photo for THIS event
          Integer remaining = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id").param("id", deletePhotoId).query(Integer.class).single();
          assertEquals(0, remaining);
          Integer keptCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id").param("id", keepPhotoId).query(Integer.class).single();
          assertEquals(1, keptCount);

          // Sammelan count sync: 1 student, gender F -> girls_attended=1, boys_attended=0
          Integer boys = jdbc.sql("SELECT boys_attended FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
          Integer girls = jdbc.sql("SELECT girls_attended FROM pp.event_master WHERE event_id = :id").param("id", eventId).query(Integer.class).single();
          assertEquals(0, boys);
          assertEquals(1, girls);

          // new photo stored with the SERVER-GENERATED filename (unlike createEvent's original-filename choice)
          String newPhotoName = jdbc.sql("SELECT file_name FROM pp.event_photos WHERE event_id = :id AND file_name LIKE 'updated_title_974%'")
                  .param("id", eventId).query(String.class).single();
          assertTrue(newPhotoName.startsWith("updated_title_974-"));

          // report row written with hard-coded SAMMELAN_REPORT type
          String reportType = jdbc.sql("SELECT report_type FROM pp.event_reports WHERE event_id = :id").param("id", eventId).query(String.class).single();
          assertEquals("SAMMELAN_REPORT", reportType);
      }

      @Test
      void updateEventPhotoDeleteCannotTouchOtherEventsPhoto() throws Exception {
          mvc.perform(multipart("/api/events/" + eventId)
                  .with(req -> { req.setMethod("PUT"); return req; })
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_title", "Cross Event Attempt 974")
                  .param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-02")
                  .param("photos_to_delete", "[" + otherEventPhotoId + "]")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk());

          // otherEventPhotoId belongs to otherEventId, NOT eventId -- must survive (Locked Decision 5 hardening)
          Integer stillThere = jdbc.sql("SELECT COUNT(*) FROM pp.event_photos WHERE photo_id = :id")
                  .param("id", otherEventPhotoId).query(Integer.class).single();
          assertEquals(1, stillThere);
      }

      @Test
      void updateEventInvalidIdIs400() throws Exception {
          mvc.perform(multipart("/api/events/abc")
                  .with(req -> { req.setMethod("PUT"); return req; })
                  .param("event_type_id", String.valueOf(eventTypeId))
                  .param("event_title", "x").param("event_start_date", "2026-05-01").param("event_end_date", "2026-05-02")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("Invalid event ID"));
      }
  }
  ```

- [ ] **4.2** Run, confirm FAIL: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsUpdateIT`

- [ ] **4.3** Add `updateEvent` to `EventsWriteRepository`:
  ```java
  /** updateEvent (eventModel.js:79-101 + eventController.js:102-173), fused into one @Transactional
   *  method. Order: (1) scoped photo-delete (Locked Decision 5 -- Node has NO event_id scoping here,
   *  ground truth §7.5, an IDOR; Java adds `AND event_id = :eventId`), (2) full master UPDATE (boys/girls/
   *  parents_attended DO get `|| 0` semantics here -- pass 0 when the sanitized value is null, matching
   *  JS `boys_attended || 0`, unlike createEvent -- see plan Disagreements #2), (3) conditional Sammelan
   *  count-resync via pp.event_students (only when eventTypeName.equals("Sammelan"), does NOT set
   *  updated_by/updated_at -- ported literally, an inconsistent audit trail vs. every other master UPDATE
   *  in this module), (4) new photo inserts (file_name = SERVER-GENERATED name, unlike createEvent),
   *  (5) report replace: delete old SAMMELAN_REPORT row (deleteOldReport, DB-only -- old file orphaned on
   *  disk, ported literally) then insert the new one. */
  @Transactional
  public void updateEvent(long eventId, java.util.List<Integer> photosToDelete,
                           Integer eventTypeId, String eventTitle, String eventDescription,
                           String eventStartDate, String eventEndDate,
                           String eventDistrict, String eventBlock, String eventLocation,
                           String pincode, String cohortNumber,
                           String boysAttendedRaw, String girlsAttendedRaw, String parentsAttendedRaw,
                           String eventTypeName, Long userId,
                           java.util.List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> newPhotos,
                           com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile newReport) {

      if (photosToDelete != null && !photosToDelete.isEmpty()) {
          jdbc.sql("DELETE FROM pp.event_photos WHERE photo_id = ANY(:ids::int[]) AND event_id = :eventId")
                  .param("ids", photosToDelete.toArray(new Integer[0])).param("eventId", eventId).update();
      }

      int boys = boysAttendedRaw == null || boysAttendedRaw.isEmpty() ? 0 : Integer.parseInt(boysAttendedRaw);
      int girls = girlsAttendedRaw == null || girlsAttendedRaw.isEmpty() ? 0 : Integer.parseInt(girlsAttendedRaw);
      int parents = parentsAttendedRaw == null || parentsAttendedRaw.isEmpty() ? 0 : Integer.parseInt(parentsAttendedRaw);

      jdbc.sql("""
              UPDATE pp.event_master
              SET event_type_id = :eventTypeId::integer, event_title = :eventTitle, event_description = :eventDescription,
                  event_start_date = :eventStartDate::date, event_end_date = :eventEndDate::date,
                  event_district = :eventDistrict::numeric, event_block = :eventBlock::numeric,
                  event_location = :eventLocation, pincode = :pincode, cohort_number = :cohortNumber::integer,
                  boys_attended = :boys, girls_attended = :girls, parents_attended = :parents,
                  updated_by = :userId::numeric, updated_at = CURRENT_TIMESTAMP
              WHERE event_id = :eventId
              """)
              .param("eventTypeId", eventTypeId).param("eventTitle", eventTitle).param("eventDescription", eventDescription)
              .param("eventStartDate", eventStartDate).param("eventEndDate", eventEndDate)
              .param("eventDistrict", eventDistrict).param("eventBlock", eventBlock)
              .param("eventLocation", eventLocation).param("pincode", pincode).param("cohortNumber", cohortNumber)
              .param("boys", boys).param("girls", girls).param("parents", parents)
              .param("userId", userId).param("eventId", eventId)
              .update();

      if ("Sammelan".equals(eventTypeName)) {
          Map<String, Object> counts = jdbc.sql("""
                  SELECT
                      COUNT(*) FILTER (WHERE UPPER(gender) IN ('M','MALE')) as boys,
                      COUNT(*) FILTER (WHERE UPPER(gender) IN ('F','FEMALE')) as girls
                  FROM pp.student_master sm
                  JOIN pp.event_students es ON sm.student_id = es.student_id
                  WHERE es.event_id = :eventId
                  """).param("eventId", eventId).query((rs, i) -> EventsReadRepository.genericRow(rs)).single();
          long syncBoys = ((Number) counts.getOrDefault("boys", 0)).longValue();
          long syncGirls = ((Number) counts.getOrDefault("girls", 0)).longValue();
          jdbc.sql("UPDATE pp.event_master SET boys_attended = :boys, girls_attended = :girls WHERE event_id = :eventId")
                  .param("boys", syncBoys).param("girls", syncGirls).param("eventId", eventId).update();
      }

      for (var p : newPhotos) {
          jdbc.sql("""
                  INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                  VALUES (:eventId, :path, :name, :userId::numeric)
                  """)
                  .param("eventId", eventId).param("path", p.diskPath())
                  .param("name", p.storedFilename())   // updateEvent stores the SERVER-GENERATED filename (eventController.js:158)
                  .param("userId", userId)
                  .update();
      }

      if (newReport != null) {
          jdbc.sql("DELETE FROM pp.event_reports WHERE event_id = :eventId AND report_type = 'SAMMELAN_REPORT'")
                  .param("eventId", eventId).update();
          jdbc.sql("""
                  INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
                  VALUES (:eventId, 'SAMMELAN_REPORT', :path, :name, :userId::numeric)
                  """)
                  .param("eventId", eventId).param("path", newReport.diskPath())
                  .param("name", newReport.storedFilename()).param("userId", userId)
                  .update();
      }
  }
  ```
  `COUNT(*) FILTER (...)` in Postgres returns `bigint`, which `genericRow`'s `Types.BIGINT` branch turns into a
  `String` — cast with `Long.parseLong` (or `Number`, since the switch above emits a String for BIGINT columns,
  so read it as `Long.parseLong(String.valueOf(counts.get("boys")))`) if `((Number) ...)` doesn't compile; use
  whichever compiles cleanly against the actual `genericRow` output type for `BIGINT`.

- [ ] **4.4** Add the `PUT /events/{id}` endpoint to `EventsController` (mirrors the `POST /events` shape,
  adds `photos_to_delete`/`event_type_name`, drops `event_title` describe-only fields that aren't required
  on update per `validateEventBody`'s own field list — note `validateEventBody` runs on this route too, per
  the route chain, so the same required fields apply):
  ```java
  /** updateEvent (eventController.js:102-173), the PUT /events/:id handler. */
  @PutMapping(value = "/events/{id}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> updateEvent(
          @PathVariable String id,
          @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
          @RequestParam("event_type_id") String eventTypeId,
          @RequestParam("event_title") String eventTitle,
          @RequestParam(value = "event_description", required = false) String eventDescription,
          @RequestParam("event_start_date") String eventStartDate,
          @RequestParam("event_end_date") String eventEndDate,
          @RequestParam(value = "event_district", required = false) String eventDistrictRaw,
          @RequestParam(value = "event_block", required = false) String eventBlockRaw,
          @RequestParam(value = "event_location", required = false) String eventLocation,
          @RequestParam(value = "pincode", required = false) String pincodeRaw,
          @RequestParam(value = "cohort_number", required = false) String cohortNumberRaw,
          @RequestParam(value = "boys_attended", required = false) String boysAttendedRaw,
          @RequestParam(value = "girls_attended", required = false) String girlsAttendedRaw,
          @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
          @RequestParam(value = "event_type_name", required = false) String eventTypeName,
          @RequestParam(value = "photos_to_delete", required = false) String photosToDeleteJson,
          @RequestParam(value = "photos", required = false) MultipartFile[] photos,
          @RequestParam(value = "reports", required = false) MultipartFile[] reports,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
                  com.rcf.imas.platform.security.JwtService.FinalToken principal) {

      long eventId = EventValidation.validateEventId(id);
      EventUploadValidation.validate(photos, reports);

      String eventDistrict = EventValidation.sanitizeNumeric(eventDistrictRaw);
      String eventBlock = EventValidation.sanitizeNumeric(eventBlockRaw);
      String pincode = EventValidation.sanitizeNumeric(pincodeRaw);
      String cohortNumber = EventValidation.sanitizeNumeric(cohortNumberRaw);
      String boysAttended = EventValidation.sanitizeNumeric(boysAttendedRaw);
      String girlsAttended = EventValidation.sanitizeNumeric(girlsAttendedRaw);
      String parentsAttended = EventValidation.sanitizeNumeric(parentsAttendedRaw);

      EventValidation.validateEventBody(eventTypeId, eventTitle, eventStartDate, eventEndDate);

      Long userId = principal != null ? Long.valueOf(principal.userId()) : null;

      try {
          java.util.List<Integer> photosToDelete = new ArrayList<>();
          if (photosToDeleteJson != null && !photosToDeleteJson.isBlank()) {
              com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
              int[] ids = om.readValue(photosToDeleteJson, int[].class);
              for (int pid : ids) photosToDelete.add(pid);
          }

          List<EventFileStorageService.StoredFile> storedPhotos = new ArrayList<>();
          if (photos != null) {
              for (MultipartFile f : photos) {
                  if (f == null || f.isEmpty()) continue;
                  storedPhotos.add(fileStorage.storePhoto(f, eventTitleFilenameField, storedPhotos.size() + 1));
              }
          }
          EventFileStorageService.StoredFile storedReport = null;
          if (reports != null) {
              for (MultipartFile f : reports) {
                  if (f == null || f.isEmpty()) continue;
                  storedReport = fileStorage.storeReport(f, eventTitleFilenameField);
                  break; // only reports[0] is ever used (eventController.js:163)
              }
          }

          writes.updateEvent(eventId, photosToDelete, Integer.valueOf(eventTypeId), eventTitle, eventDescription,
                  eventStartDate, eventEndDate, eventDistrict, eventBlock, eventLocation, pincode, cohortNumber,
                  boysAttended, girlsAttended, parentsAttended, eventTypeName, userId, storedPhotos, storedReport);

          Map<String, Object> body = new LinkedHashMap<>();
          body.put("success", true);
          body.put("message", "Updated successfully");
          return body;
      } catch (ApiException e) {
          throw e;
      } catch (Exception e) {
          // updateEvent's Node catch block leaks the raw exception message to the client (eventController.js:171,
          // ground truth §5 row 7) -- ported literally, not sanitized.
          throw ApiException.of(500).with("success", false).with("message", e.getMessage());
      }
  }
  ```
  Add `com.fasterxml.jackson.databind.ObjectMapper` (already a transitive Spring Boot dependency, used the same
  way elsewhere in the codebase for ad hoc JSON parsing — confirm an existing import pattern in another module
  before adding a duplicate dependency; none is needed, it's already on the classpath via `spring-boot-starter-web`).

- [ ] **4.5** Run, expect PASS: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=EventsUpdateIT`

- [ ] **4.6** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/events imas-backend/src/test/java/com/rcf/imas/modules/events/EventsUpdateIT.java
  git commit -m "$(cat <<'EOF'
  feat(events): update with scoped photo-delete + Sammelan count sync (Phase 5a Task 4/5)

  PUT /events/:id: full master overwrite, new-photo inserts (server-generated
  filename, unlike create's original-filename), report replace (DB row swap,
  disk file orphaned -- ported literally), and the Sammelan-only boys/girls
  recount via pp.event_students. Photo-delete-by-id is scoped to event_id
  (IDOR hardening -- Node has none) while still honoring the client's normal
  same-event delete flow.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 5 — Sammelan attendance

Endpoints: `GET /attendance/sammelan-list` (`getSammelanEvents`), `POST /attendance/students-list`
(`fetchStudentAttendanceList`), `POST /attendance/save` (`submitAttendance`).

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsReadRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/persistence/EventsWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/events/web/EventsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/events/SammelanAttendanceIT.java`

Seed id range: `970500`-`970599`.

- [ ] **5.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/events/SammelanAttendanceIT.java`:
  ```java
  package com.rcf.imas.modules.events;

  import com.rcf.imas.PgIntegrationTest;
  import com.rcf.imas.platform.security.JwtService;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.mock.web.MockMultipartFile;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import java.nio.file.Path;

  import static org.hamcrest.Matchers.hasSize;
  import static org.junit.jupiter.api.Assertions.*;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class SammelanAttendanceIT extends PgIntegrationTest {

      @TempDir static Path storageRoot;

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry registry) {
          registry.add("imas.event-storage-path", () -> storageRoot.toString());
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String admin;
      Integer sammelanTypeId;
      Integer otherTypeId;
      Integer sammelanEventId;
      Integer nonSammelanEventId;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970501,'evAdmin975','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          admin = jwt.issueFinalToken("970501", "evAdmin975", "ADMIN");

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (970501, 'Cohort975')").update();
          sammelanTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Sammelan975') RETURNING event_type_id").query(Integer.class).single();
          otherTypeId = jdbc.sql("INSERT INTO pp.event_type(event_type_name) VALUES ('Workshop975') RETURNING event_type_id").query(Integer.class).single();

          sammelanEventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                  VALUES (:t,'Sammelan Attendance 975',DATE '2026-06-01',DATE '2026-06-02',970501) RETURNING event_id
                  """).param("t", sammelanTypeId).query(Integer.class).single();
          nonSammelanEventId = jdbc.sql("""
                  INSERT INTO pp.event_master (event_type_id, event_title, event_start_date, event_end_date, cohort_number)
                  VALUES (:t,'Workshop Not Sammelan 975',DATE '2026-06-01',DATE '2026-06-02',970501) RETURNING event_id
                  """).param("t", otherTypeId).query(Integer.class).single();

          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970510,'StateX975','STATE',NULL)").update();
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970511,'DistrictX975','EDUCATION DISTRICT',970510)").update();
          jdbc.sql("INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES (970512,'BlockX975','BLOCK',970511)").update();

          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (970501,'Batch975',970501)").update();

          // Student A: present already (event_students row exists) -> is_marked=true
          jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block) VALUES (970501,2026,97050100001,'Amy Attend975',970510,970511,970512)").update();
          jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, batch_id, active_yn) VALUES (970501,970501,'Amy Attend975','F',970501,'ACTIVE')").update();
          jdbc.sql("INSERT INTO pp.event_students(event_id, student_id) VALUES (:e,970501)").param("e", sammelanEventId).update();

          // Student B: not yet marked -> is_marked=false
          jdbc.sql("INSERT INTO pp.applicant_primary_info(applicant_id, nmms_year, nmms_reg_number, student_name, app_state, district, nmms_block) VALUES (970502,2026,97050200001,'Bob Attend975',970510,970511,970512)").update();
          jdbc.sql("INSERT INTO pp.student_master(student_id, applicant_id, student_name, gender, batch_id, active_yn) VALUES (970502,970502,'Bob Attend975','M',970501,'ACTIVE')").update();
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.event_students WHERE student_id IN (970501,970502)").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (970501,970502)").update();
          jdbc.sql("DELETE FROM pp.applicant_primary_info WHERE applicant_id IN (970501,970502)").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 970501").update();
          jdbc.sql("DELETE FROM pp.jurisdiction WHERE juris_code IN (970510,970511,970512)").update();
          jdbc.sql("DELETE FROM pp.event_photos WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970501)").update();
          jdbc.sql("DELETE FROM pp.event_reports WHERE event_id IN (SELECT event_id FROM pp.event_master WHERE cohort_number = 970501)").update();
          jdbc.sql("DELETE FROM pp.event_master WHERE cohort_number = 970501").update();
          jdbc.sql("DELETE FROM pp.event_type WHERE event_type_name IN ('Sammelan975','Workshop975')").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 970501").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970501").update();
      }

      @Test
      void sammelanListOnlyReturnsSammelanTypeEvents() throws Exception {
          mvc.perform(get("/api/attendance/sammelan-list").header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data[?(@.event_title=='Sammelan Attendance 975')]", hasSize(1)))
              .andExpect(jsonPath("$.data[?(@.event_title=='Workshop Not Sammelan 975')]", hasSize(0)));
      }

      @Test
      void studentsListMarksExistingAttendeeAndOmitsUnmarked() throws Exception {
          String body = "{\"eventTitle\":\"Sammelan Attendance 975\",\"stateName\":\"StateX975\",\"page\":1}";
          mvc.perform(post("/api/attendance/students-list").contentType("application/json").content(body)
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data[?(@.student_name=='Amy Attend975')].is_marked").value(true))
              .andExpect(jsonPath("$.data[?(@.student_name=='Bob Attend975')].is_marked").value(false));
      }

      @Test
      void studentsListUnknownEventTitleIs404() throws Exception {
          String body = "{\"eventTitle\":\"Does Not Exist 975\"}";
          mvc.perform(post("/api/attendance/students-list").contentType("application/json").content(body)
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isNotFound())
              .andExpect(jsonPath("$.msg").value("Event not found"));
      }

      @Test
      void submitAttendancePersistsEventStudentsAndCountsAndIsIdempotent() throws Exception {
          String studentIdsJson = "[970501,970502]";

          mvc.perform(multipart("/api/attendance/save")
                  .param("eventId", String.valueOf(sammelanEventId))
                  .param("studentIds", studentIdsJson)
                  .param("parents_attended", "4")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.msg").value("Attendance updated successfully!"));

          Integer linkedCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_students WHERE event_id = :id")
                  .param("id", sammelanEventId).query(Integer.class).single();
          assertEquals(2, linkedCount); // student A already linked (seed) + student B newly linked

          Integer boys = jdbc.sql("SELECT boys_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
          Integer girls = jdbc.sql("SELECT girls_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
          Integer parents = jdbc.sql("SELECT parents_attended FROM pp.event_master WHERE event_id = :id").param("id", sammelanEventId).query(Integer.class).single();
          assertEquals(1, boys);   // Bob = M
          assertEquals(1, girls);  // Amy = F
          assertEquals(4, parents);

          // idempotent replay: ON CONFLICT DO NOTHING, still exactly 2 rows, no DELETE ever happens
          // (Disagreements #1 -- this endpoint can only ADD attendees, never remove them)
          mvc.perform(multipart("/api/attendance/save")
                  .param("eventId", String.valueOf(sammelanEventId))
                  .param("studentIds", "[970501]")   // dropping 970502 from the list does NOT remove it
                  .param("parents_attended", "4")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk());

          Integer stillLinked = jdbc.sql("SELECT COUNT(*) FROM pp.event_students WHERE event_id = :id")
                  .param("id", sammelanEventId).query(Integer.class).single();
          assertEquals(2, stillLinked); // 970502 NOT removed -- verbatim INSERT-only port (Disagreements #1)
      }

      @Test
      void submitAttendanceWithReportPersistsRow() throws Exception {
          MockMultipartFile report = new MockMultipartFile("reports", "attendance-report.pdf", "application/pdf", "pdf-bytes".getBytes());
          mvc.perform(multipart("/api/attendance/save").file(report)
                  .param("eventId", String.valueOf(sammelanEventId))
                  .param("studentIds", "[970501]")
                  .param("parents_attended", "1")
                  .header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk());

          Integer reportCount = jdbc.sql("SELECT COUNT(*) FROM pp.event_reports WHERE event_id = :id AND report_type = 'SAMMELAN_REPORT'")
                  .param("id", sammelanEventId).query(Integer.class).single();
          assertEquals(1, reportCount);
      }
  }
  ```

- [ ] **5.2** Run, confirm FAIL: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=SammelanAttendanceIT`

- [ ] **5.3** Add the read methods to `EventsReadRepository`:
  ```java
  /** getSammelanEvents (eventModel.js:221-230). Literal string match on 'Sammelan', case-sensitive, no
   *  ILIKE/trim (ground truth §2, §7.8) -- ported literally, do not relax. */
  public List<Map<String, Object>> sammelanEvents() {
      return jdbc.sql("""
              SELECT em.event_id, em.event_title
              FROM pp.event_master em
              JOIN pp.event_type et ON et.event_type_id = em.event_type_id
              WHERE et.event_type_name = 'Sammelan'
              """).query((rs, i) -> genericRow(rs)).list();
  }

  /** Event lookup by title, inline SQL in the controller in Node (eventController.js:226-229). event_title
   *  has a UNIQUE constraint (event_master_event_title_key) so this is safe as a single-row lookup. */
  public Optional<Map<String, Object>> eventByTitle(String eventTitle) {
      return jdbc.sql("SELECT event_id, cohort_number FROM pp.event_master WHERE event_title = :title")
              .param("title", eventTitle).query((rs, i) -> genericRow(rs)).optional();
  }

  /** getSammelanStudentList (eventModel.js:316-365). $6=searchName is always null in practice (the live
   *  controller never sets it, ground truth §2) -- still wired here for completeness/future use. */
  public List<Map<String, Object>> sammelanStudentList(long eventId, Integer cohortNumber, String stateName,
                                                          String[] districtNames, String[] blockNames,
                                                          String searchName, int limit, int offset) {
      return jdbc.sql("""
              SELECT DISTINCT
                  sm.student_id,
                  sm.student_name,
                  bl.juris_name AS block_name,
                  d.juris_name AS district_name,
                  (es.student_id IS NOT NULL) AS is_marked
              FROM pp.student_master sm
              JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
              LEFT JOIN pp.event_students es ON sm.student_id = es.student_id AND es.event_id = :eventId
              LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
              LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
              LEFT JOIN pp.jurisdiction s ON a.app_state = s.juris_code
              LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
              WHERE sm.active_yn = 'ACTIVE'
                AND b.cohort_number = :cohortNumber
                AND (:stateName::text IS NULL OR s.juris_name = :stateName)
                AND (:districtNames::text[] IS NULL OR d.juris_name = ANY(:districtNames))
                AND (:blockNames::text[] IS NULL OR bl.juris_name = ANY(:blockNames))
                AND (:searchName::text IS NULL OR sm.student_name ILIKE '%' || :searchName || '%')
              ORDER BY sm.student_name
              LIMIT :limit OFFSET :offset
              """)
              .param("eventId", eventId).param("cohortNumber", cohortNumber).param("stateName", stateName)
              .param("districtNames", (districtNames == null || districtNames.length == 0) ? null : districtNames)
              .param("blockNames", (blockNames == null || blockNames.length == 0) ? null : blockNames)
              .param("searchName", searchName).param("limit", limit).param("offset", offset)
              .query((rs, i) -> genericRow(rs)).list();
  }
  ```
  The `:districtNames::text[] IS NULL` null-array-cast pattern requires binding an actual SQL `NULL` (not an
  empty array) when the filter should be skipped — passing a Java `null` through `JdbcClient.param` for an
  array-typed named parameter binds a SQL `NULL` correctly with this driver (same pattern already used
  elsewhere in the codebase, e.g. `ShortlistReadRepository`); confirm this resolves during 5.5 and switch to
  an explicit `.paramSource(...)` / `SqlParameterValue` with `Types.ARRAY` if the driver instead throws on a
  bare `null` for an array parameter.

- [ ] **5.4** Add `submitAttendance` to `EventsWriteRepository`:
  ```java
  /** submitAttendance (eventController.js:256-312). saveSammelanAttendance (eventModel.js:429-437) is a
   *  SINGLE `INSERT ... ON CONFLICT (event_id, student_id) DO NOTHING` -- NO preceding DELETE (see plan
   *  Disagreements #1: this endpoint can only ADD attendees, never remove them; verified against both the
   *  live model source and the ground-truth doc, which agree with each other and disagree with the task
   *  brief's "DELETE + INSERT" framing). student_id is numeric(14,0) but this query casts to ::int[]
   *  VERBATIM from Node (eventModel.js:432, eventController.js:273) -- a real overflow risk for any
   *  student_id > 2^31-1, tolerated in practice because ids come from a small sequential sequence; ported
   *  literally, not widened to bigint[]/numeric[]. Does NOT call deleteOldReport before inserting a new
   *  report (ground truth §4/§9 -- report rows accumulate across repeated calls, unlike updateEvent). */
  @Transactional
  public void submitAttendance(long eventId, java.util.List<Integer> studentIds, int parentsAttended,
                                Long userId,
                                java.util.List<com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile> photos,
                                com.rcf.imas.modules.events.service.EventFileStorageService.StoredFile report) {

      Integer[] idsArray = studentIds.toArray(new Integer[0]);

      jdbc.sql("""
              INSERT INTO pp.event_students (event_id, student_id)
              SELECT :eventId::integer, unnest(:studentIds::int[])
              ON CONFLICT (event_id, student_id) DO NOTHING
              """).param("eventId", eventId).param("studentIds", idsArray).update();

      List<Map<String, Object>> genderRows = jdbc.sql("""
              SELECT gender, COUNT(*) as count FROM pp.student_master WHERE student_id = ANY(:ids::int[]) GROUP BY gender
              """).param("ids", idsArray).query((rs, i) -> EventsReadRepository.genericRow(rs)).list();

      int boys = 0, girls = 0;
      for (Map<String, Object> row : genderRows) {
          String g = row.get("gender") == null ? null : String.valueOf(row.get("gender")).toUpperCase();
          int count = Integer.parseInt(String.valueOf(row.get("count")));
          if ("MALE".equals(g) || "M".equals(g)) boys = count;
          if ("FEMALE".equals(g) || "F".equals(g)) girls = count;
      }

      jdbc.sql("""
              UPDATE pp.event_master
              SET boys_attended = :boys, girls_attended = :girls, parents_attended = :parents,
                  updated_by = :userId::numeric, updated_at = CURRENT_TIMESTAMP
              WHERE event_id = :eventId
              """).param("boys", boys).param("girls", girls).param("parents", parentsAttended)
              .param("userId", userId).param("eventId", eventId).update();

      for (var p : photos) {
          jdbc.sql("""
                  INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
                  VALUES (:eventId, :path, :name, :userId::numeric)
                  """)
                  .param("eventId", eventId).param("path", p.diskPath())
                  .param("name", p.storedFilename())   // uses file.filename like updateEvent, not originalname (eventController.js:298)
                  .param("userId", userId)
                  .update();
      }
      if (report != null) {
          jdbc.sql("""
                  INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
                  VALUES (:eventId, 'SAMMELAN_REPORT', :path, :name, :userId::numeric)
                  """).param("eventId", eventId).param("path", report.diskPath())
                  .param("name", report.storedFilename()).param("userId", userId).update();
      }
  }
  ```

- [ ] **5.5** Add the three endpoints to `EventsController`. Add imports:
  `com.fasterxml.jackson.databind.ObjectMapper`, `java.util.stream.Collectors`. Then:
  ```java
  /* ===================== ATTENDANCE ===================== */

  /** getSammelanEvents (eventController.js:202-207). */
  @GetMapping("/attendance/sammelan-list")
  public Map<String, Object> sammelanList() {
      try {
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("success", true);
          out.put("data", reads.sammelanEvents());
          return out;
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("msg", e.getMessage());
      }
  }

  @SuppressWarnings("unchecked")
  /** fetchStudentAttendanceList (eventController.js:221-254). */
  @PostMapping("/attendance/students-list")
  public Map<String, Object> studentsList(@RequestBody Map<String, Object> body) {
      String eventTitle = (String) body.get("eventTitle");
      Map<String, Object> event = reads.eventByTitle(eventTitle).orElse(null);
      if (event == null) {
          throw ApiException.of(404).with("success", false).with("msg", "Event not found");
      }
      try {
          long eventId = Long.parseLong(String.valueOf(event.get("event_id")));
          Integer cohortNumber = (Integer) event.get("cohort_number");
          String stateName = (String) body.get("stateName");
          List<String> districtNames = (List<String>) body.get("districtNames");
          List<String> blockNames = (List<String>) body.get("blockNames");
          Object pageObj = body.get("page");
          int page = pageObj == null ? 1 : ((Number) pageObj).intValue();
          int limit = 15;
          int offset = (page - 1) * limit;

          List<Map<String, Object>> students = reads.sammelanStudentList(eventId, cohortNumber, stateName,
                  (districtNames == null || districtNames.isEmpty()) ? null : districtNames.toArray(new String[0]),
                  (blockNames == null || blockNames.isEmpty()) ? null : blockNames.toArray(new String[0]),
                  null, limit, offset);

          Map<String, Object> out = new LinkedHashMap<>();
          out.put("success", true);
          out.put("data", students);
          return out;
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("msg", "Internal Server Error");
      }
  }

  /** submitAttendance (eventController.js:256-312). */
  @PostMapping(value = "/attendance/save", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> submitAttendance(
          @RequestParam(value = "eventTitle", required = false) String eventTitleFilenameField,
          @RequestParam("eventId") String eventIdRaw,
          @RequestParam("studentIds") String studentIdsRaw,
          @RequestParam(value = "parents_attended", required = false) String parentsAttendedRaw,
          @RequestParam(value = "user_id", required = false) String userIdBody,
          @RequestParam(value = "photos", required = false) MultipartFile[] photos,
          @RequestParam(value = "reports", required = false) MultipartFile[] reports,
          @org.springframework.security.core.annotation.AuthenticationPrincipal
                  com.rcf.imas.platform.security.JwtService.FinalToken principal) {

      EventUploadValidation.validate(photos, reports);
      try {
          long eventId = Long.parseLong(eventIdRaw);
          ObjectMapper om = new ObjectMapper();
          int[] parsedIds = om.readValue(studentIdsRaw, int[].class);
          List<Integer> studentIds = new ArrayList<>();
          for (int sid : parsedIds) studentIds.add(sid);

          int parentsAttended = (parentsAttendedRaw == null || parentsAttendedRaw.isBlank())
                  ? 0 : Integer.parseInt(parentsAttendedRaw);

          Long userId = principal != null ? Long.valueOf(principal.userId())
                  : (userIdBody != null && !userIdBody.isBlank() ? Long.valueOf(userIdBody) : null);

          List<EventFileStorageService.StoredFile> storedPhotos = new ArrayList<>();
          if (photos != null) {
              for (MultipartFile f : photos) {
                  if (f == null || f.isEmpty()) continue;
                  storedPhotos.add(fileStorage.storePhoto(f, eventTitleFilenameField, storedPhotos.size() + 1));
              }
          }
          EventFileStorageService.StoredFile storedReport = null;
          if (reports != null) {
              for (MultipartFile f : reports) {
                  if (f == null || f.isEmpty()) continue;
                  storedReport = fileStorage.storeReport(f, eventTitleFilenameField);
                  break;
              }
          }

          writes.submitAttendance(eventId, studentIds, parentsAttended, userId, storedPhotos, storedReport);

          Map<String, Object> body = new LinkedHashMap<>();
          body.put("success", true);
          body.put("msg", "Attendance updated successfully!");
          return body;
      } catch (Exception e) {
          throw ApiException.of(500).with("success", false).with("msg", "Server Error: " + e.getMessage());
      }
  }
  ```

- [ ] **5.6** Run, expect PASS: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=SammelanAttendanceIT`

- [ ] **5.7** Run the full events package to confirm nothing regressed across the 5 tasks:
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=com.rcf.imas.modules.events.**`

- [ ] **5.8** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/events imas-backend/src/test/java/com/rcf/imas/modules/events/SammelanAttendanceIT.java
  git commit -m "$(cat <<'EOF'
  feat(events): Sammelan attendance list, student search, and save (Phase 5a Task 5/5, FINAL module)

  GET /attendance/sammelan-list (literal 'Sammelan' type filter), POST
  /attendance/students-list (is_marked via LEFT JOIN pp.event_students), and
  POST /attendance/save (submitAttendance: INSERT-only ON CONFLICT DO NOTHING
  attendance link -- ported verbatim, this endpoint can only add attendees,
  never remove them, see plan Disagreements #1 -- gender-count sync, and
  optional photo/report upload with no old-report cleanup, unlike updateEvent).

  This completes the Events module and the Node->Spring Boot migration.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Self-review checklist (verify before treating this plan as ready to execute)

- [x] All 12 endpoints mapped to a task: 1→{1,2,3}, 2→{5,6}, 3→{4,7}, 4→{9,10,11,12}, 5→{8}. *(Task numbers in
      this list refer to ground-truth §1's endpoint numbering, not this plan's task numbers.)*
- [x] SQL ported verbatim throughout, including `pp.event_students`' `ON CONFLICT (event_id, student_id) DO
      NOTHING` (Task 5) and the `LEFT JOIN pp.event_students ... is_marked` (Task 5).
- [x] File storage + static serving + Node's exact filename-generation logic reproduced (Task 3), including the
      `eventTitle`(camelCase) landmine (flagged, not fixed).
- [x] `createEvent`-only-on-`POST /events` reproduced (Task 3); `updateEvent` never runs on that route.
- [x] Photo-delete scoped by `event_id` in `updateEvent` (Task 4) — IDOR hardening.
- [x] All 4 multi-statement writers (`createEvent`, `updateEvent`, `deleteEvent`, `submitAttendance`) are
      `@Transactional`.
- [x] No placeholders — every task shows complete SQL, complete Java, complete seed data, and concrete
      assertions.
- [x] Two disagreements between the task brief/ground-truth doc and the live Node source are called out
      explicitly for adjudication (see "Disagreements" section above) rather than silently resolved.
