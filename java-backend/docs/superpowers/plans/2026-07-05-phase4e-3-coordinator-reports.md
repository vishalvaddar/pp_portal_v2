# Phase 4e-3 — Coordinator Reports (endpoints #23-29)

## Goal

Port the 7 read-only report endpoints of the Node `coordinatorRoutes.js` (#23 `/reports/attendance`,
#24 `/reports/absentees`, #25 `/reports/teacher-load`, #26 `/reports/teacher-performance`,
#27 `/reports/coordinator-teachers`, #28 `/reports/batch-class-details`, #29 `/reports/teacher-class-details`)
to Spring Boot, byte-compatible on the wire with the frozen Node API + React client + Postgres `pp` schema.
Builds on module `com.rcf.imas.modules.coordinator` (4e-1 delivered `CoordinatorController` /
`CoordinatorReadRepository` + the shared `genericRow` row-mapper; 4e-2 delivered `AttendanceController` /
`AttendanceReadRepository` / `AttendanceWriteRepository` as sibling classes in the same package). This slice
adds report-specific classes rather than bloating the existing files further. All 7 endpoints are pure reads —
no writes, no transactions, no schema risk. They are the heaviest raw SQL in the module (multi-CTE queries with
`FILTER`, `ARRAY_AGG`, `generate_series`) and the JS-side response reshaping (nested nested Maps) must be
reproduced key-for-key in Java.

## Architecture

- New `CoordinatorReportsController` (`com.rcf.imas.modules.coordinator.web`) mounted at
  `/api/coordinator/reports`, own class-level `@PreAuthorize("isAuthenticated()")` (see Firm Decision 1 —
  this is a deliberate hardening over Node's non-verifying `requireAuth`).
- New `CoordinatorReportsRepository` (`com.rcf.imas.modules.coordinator.persistence`), reusing the
  package-private static `CoordinatorReadRepository.genericRow` row-mapper (LOCKED CONVENTIONS #3 — same
  package, no duplication). Every CTE is a `jdbc.sql(...)` call with named params, no JPA/Hibernate.
- New `CoordinatorReportsService` (`com.rcf.imas.modules.coordinator.service`) — stateless, holds the JS-side
  reshaping logic (`conductedStructured`, `studentMap` → `students`, absentees grouping, teacher-performance
  subject merge) that Node does in the controller body. Kept out of the web layer so the controller methods stay
  thin, and out of the repository so SQL vs. shaping are separately testable.
- Plain `JdbcClient` + hand-written SQL throughout — no JPA/Hibernate (user's global convention).

## Tech Stack

Java 21, Spring Boot 3.3.5, Maven, Spring `JdbcClient`, JUnit 5 + Spring `MockMvc` + embedded Postgres
(`io.zonky.test.db.postgres.embedded.EmbeddedPostgres` via `PgIntegrationTest`), Flyway baseline
`imas-backend/src/main/resources/db/migration/V1__baseline.sql`.

---

## Firm Decisions (locked — do not re-litigate)

| # | Decision |
|---|---|
| 1 | **Auth: `@PreAuthorize("isAuthenticated()")`** on `CoordinatorReportsController` (real, JWT-verified). Node's live `reportsController.js:5-9` `requireAuth` only checks that an `Authorization` header is *present* — it never calls `jwt.verify`, so any garbage bearer token (even `Authorization: x`) passes for all 7 `/reports/*` routes. This closes that gap. **Deliberate hardening, not a silent behavior change** — flagged in Deferred/Flagged below. |
| 2 | **§4.6 attendance-matrix `LEFT JOIN pp.inactive_students ins` ported VERBATIM, no dedup.** `pp.inactive_students` has no PK/unique constraint (append-only log — a student marked inactive twice produces 2 rows). Node's `batch_students` CTE joins against it with no `MAX(inactive_date)`/`DISTINCT` collapse, so a student with 2+ inactive rows fans out through `student_sessions` and inflates `attended` counts for that student. This is a **latent Node bug, preserved on purpose for wire parity** — NOT fixed in the Java port. See Deferred/Flagged. |
| 3 | **§4.7 absentees CTE ported VERBATIM**, including: (a) `scheduled_count` counts *timetabled slots* joined via `generate_series`, not actual `pp.class_session` rows — a timetabled class the teacher never ran still counts as "scheduled" and inflates `missed_count`; (b) `(SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)` assumes `subject_code` uniqueness (no DB constraint enforces it — only `subject_id` is the real PK); (c) the day-name match `trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt,'DAY')))` uses `trim(upper())` on BOTH sides (defensive against `to_char`'s locale-dependent fixed-width padding). None of these are "fixed" in Java — ported exactly. See Deferred/Flagged. |
| 4 | **#29 `getTeacherClassDetails` filterColumn is a closed 2-way switch, never string-interpolated from the request value.** Node picks the SQL column name (`t.teacher_id` vs `t.teacher_name`) via `isNumeric ? "t.teacher_id" : "t.teacher_name"` — the column NAME comes from a fixed internal branch, not from `teacherId` itself, so it isn't injectable as written. Java reproduces this as a Java `enum`/switch choosing between two **hardcoded, fully-formed SQL strings** (one filtering on `t.teacher_id = :val::integer`, one on `t.teacher_name = :val`); the request `teacherId` value is ALWAYS passed as a bound named parameter, never concatenated into the SQL text. |
| 5 | **`reportId` format strings reproduced VERBATIM** from the live source: <br>• #23 attendance → `"ATT-" + batchId + "-" + fromDate + "-" + toDate` (`reportsController.js:140`) <br>• #24 absentees → `"ABS-" + batch_id + "-" + fromDate + "-" + toDate` (`reportsController.js:215`) <br>• #26 teacher-performance → `"TP-" + teacherId + "-" + fromDate + "-" + toDate` (`reportsController.js:358`) <br>#25, #27, #28, #29 have no `reportId` field in their live responses — do not add one. |
| 6 | **JS response-shaping reproduced key-for-key in `CoordinatorReportsService`:** <br>• #23 → `{reportId, cohort_name, batch_name, subjects, students}` where `subjects` = `{subject_code: [{teacher_name, conducted}]}` (built from the `conducted` query, `conducted` parsed as int) and `students` = `List<Map>` from `studentMap.values()`, each `{id, name, subjects: {subject_code: {teacher_name: attended_int}}}`, insertion order = `LinkedHashMap` (Node object key insertion order, driven by row order `ORDER BY ss.student_name`). <br>• #24 → `{reportId, students}` where each student = `{id, name, missedClasses: [{subject, count, dates}], totalMissed}`, `dates` = the `missed_dates` SQL array with `NULL` entries filtered out (Node's `.filter(Boolean)`), grouping order follows `ORDER BY missed_count DESC`. <br>• #25 → `{teacherClassCounts: [...]}` — bare passthrough rows, no reshaping. <br>• #26 → `{reportId, subjects: [{subject, scheduled, conducted, completion}]}`, `completion = conducted>0&&scheduled>0 ? round1dp(conducted/scheduled*100) : 0`, subject key set = union of scheduled ∪ conducted subject codes (Node builds `subjectsMap` from `scheduled` rows first, then folds in `conducted` rows, creating a `{scheduled:0,conducted:X}` entry for any subject only in `conducted`). <br>• #27 → bare array of rows, no reshaping. <br>• #28/#29 → `{success:true, count, classes:[...]}` — bare passthrough rows (`classes` = the raw SQL rows) plus a computed `count = classes.size()`. |
| 7 | **Error envelopes reproduced per-endpoint exactly (not uniform):** #23 → `{error:"Server error generating attendance report"}` 500. #24 → `{error:"batch_id, fromDate, and toDate required"}` 400 (missing params) / `{error:"Server error generating absentees report"}` 500. #25 → `{message:"Internal server error"}` 500 (uses `message`, NOT `error` — matches attendance-module convention, not the rest of coordinator). #26 → `{error:"teacherId, fromDate, and toDate required"}` 400 / `{error:"Server error generating teacher performance"}` 500. #27 → `{error: <exception message>}` 500 (echoes `err.message`, not a static string), `{error:"Coordinator ID is required"}` 400 if unresolved. #28/#29 → `{error:"Internal Server Error"}` 500 (capital-S/E, verbatim string). |
| 8 | **#27 coordinator-teachers scoping: `principal.userId()` from the JWT, NOT a client-supplied query param.** See Disagreements below — the live `teacherController.js:4` reads `coordinatorId = req.params.coordinatorId \|\| req.query.user_id`, and the mounted route (`coordinatorRoutes.js:545`) has no `:coordinatorId` path segment, so in practice `coordinatorId` comes from client-controlled `?user_id=`, letting any authenticated coordinator query any other coordinator's teacher list. The Java port scopes via `@AuthenticationPrincipal JwtService.FinalToken principal` → `principal.userId()` instead, closing this authorization gap — consistent with Firm Decision 1's "close, don't preserve, the auth gaps" direction and the project's established pattern (`CoordinatorController.cohorts`/`batches` already use `principal.userId()`, never a client param). |

## Disagreements between the task brief / ground-truth doc and the LIVE Node source (for you to adjudicate)

1. **#27 `coordinator-teachers` scoping source.** The task brief's "LOCKED DECISIONS #8" and the ground-truth
   doc's row #27 both assert scoping is "via the coordinator's user_id from JWT principal (NOT a client
   param)". Reading the LIVE source (`server/controllers/coordinator/teacherController.js:1-58`,
   `getCoordinatorTeachers`) shows the opposite: `const coordinatorId = req.params.coordinatorId ||
   req.query.user_id;` — and the mounted route (`server/routes/coordinatorRoutes.js:545`,
   `router.get("/reports/coordinator-teachers", requireAuth, getCoordinatorTeachers);`) has no
   `:coordinatorId` path param, so `req.params.coordinatorId` is always `undefined` here and the effective
   value is always `req.query.user_id` — a plain, client-controlled query string. Today's Node behavior is
   "any authenticated bearer token (per Firm Decision 1, not even a verified one) can pass
   `?user_id=<anyone>` and read that coordinator's teacher roster." This plan follows the task brief's locked
   decision (principal-scoped, ignore any client-supplied `user_id`), since it's explicitly locked and matches
   the module's existing pattern elsewhere — **flagging the brief/ground-truth vs. live-source mismatch for
   you to confirm**, since this is a genuine behavior change (a client that was relying on cross-coordinator
   lookups via `?user_id=` will get its own roster instead after this port).
   **VERIFIED WIRE-SAFE:** the frozen client (`client/src/pages/Coordinator/BatchReports.js:158`) sends
   `params: { user_id: userId, ... }` where `userId = auth?.user?.user_id` (`:86-87`) — i.e. the logged-in
   coordinator's OWN id, the same identity as the JWT they present. So `principal.userId()` yields the
   identical value for every legitimate frozen-client request; the port is byte-identical on the wire for real
   traffic and only differs for a hand-crafted `?user_id=<someone else>` (the IDOR we're closing). This is the
   same wire-safe-hardening shape as 4e-1's whitelist/created_by decisions — recommended to keep as-is.

## Deferred / Flagged (do not build now, noted for later)

- **Attendance-matrix `inactive_students` fan-out (Firm Decision 2).** Preserved Node behavior: a student with
  2+ `pp.inactive_students` rows double-counts (or worse) in the #23 attendance matrix. Fixing this (e.g.
  `MAX(inactive_date)` dedup in the `batch_students` CTE) is a one-line change if/when a product decision says
  to diverge from Node's wire output — not done here.
- **Absentees timetabled-slot-vs-session + `subject_code LIMIT 1` (Firm Decision 3).** `scheduled_count`
  overcounts relative to actual conducted sessions, and the subject lookup silently picks an arbitrary
  `subject_id` if `subject_code` is ever duplicated. Both preserved for parity; flag for a future data-quality
  pass (e.g. add a real UNIQUE constraint on `subject_code`, or switch `scheduled_count` to join against
  `pp.class_session` instead of the timetable).
- **`/reports/*` auth hardening (Firm Decision 1).** Node's `requireAuth` never verifies the JWT for any of
  these 7 routes today. The Java port's `@PreAuthorize("isAuthenticated()")` is real JWT verification — this
  is an intentional widening of what "authenticated" means for this URL prefix, consistent with how every
  other coordinator sub-plan (4e-1, 4e-2) already enforces real auth. No further action needed, just recorded
  here as the authoritative note for anyone diffing Node vs. Java security posture.
- **#27 scoping-source behavior change.** See Disagreements #1 above — needs your sign-off, implemented here
  per the task brief's locked decision.

---

## Task 1 — Controller skeleton + `/reports/teacher-load` (#25) + `/reports/coordinator-teachers` (#27)

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorReportsLoadAndTeachersIT.java`

Seed id range for this task's IT: `965701`-`965799`.

- [ ] **1.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorReportsLoadAndTeachersIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

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
  class CoordinatorReportsLoadAndTeachersIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965701,'coordUser965701','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965701,'Reports Cohort 965701')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

          // Assigned batch (coordinator 965701 IS assigned) and an unassigned batch, to prove scoping on #27.
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965701,'Assigned Batch 965701',965701)").update();
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965702,'Unassigned Batch 965702',965701)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965701,965701)").update();

          jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965701,'MATH','Mathematics')").update();
          jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

          // Two teachers: one taught a session in the ASSIGNED batch (must appear in #27), one only in the
          // unassigned batch (must NOT appear).
          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name, contact_no) VALUES (965701,'Assigned Teacher 965701','9000000001')").update();
          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name, contact_no) VALUES (965702,'Unassigned Teacher 965702','9000000002')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965701,'Assigned Classroom 965701',965701,965701)").update();
          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965702,'Unassigned Classroom 965702',965701,965702)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965701,965701)").update();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965702,965702)").update();

          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (965701,965701,'2026-06-01','09:00:00','10:00:00',965701)
                  """).update();
          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (965702,965702,'2026-06-01','09:00:00','10:00:00',965702)
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

          coordToken = jwt.issueFinalToken("965701", "coordUser965701", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965701").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (965701,965702)").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965701").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965701").update();
      }

      @Test
      void teacherLoadReturnsCountsGroupedByTeacherCohortClassroomSubject() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-load")
                  .param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.teacherClassCounts", hasSize(2)))
             .andExpect(jsonPath("$.teacherClassCounts[?(@.teacher=='Assigned Teacher 965701')].total_classes_taken").value(hasSize(1)))
             .andExpect(jsonPath("$.teacherClassCounts[?(@.teacher=='Assigned Teacher 965701')].total_classes_taken[0]").value(1));
      }

      @Test
      void teacherLoadNoDateFilterReturnsAllRows() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-load")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.teacherClassCounts", hasSize(2)));
      }

      @Test
      void coordinatorTeachersScopedToPrincipalNotClientParam() throws Exception {
          // Even if a malicious client passes ?user_id= for a DIFFERENT coordinator, the principal's own
          // batches govern -- proves Firm Decision 8 / Disagreements #1.
          mvc.perform(get("/api/coordinator/reports/coordinator-teachers")
                  .param("user_id", "999999")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$", hasSize(1)))
             .andExpect(jsonPath("$[0].teacher_id").value(965701))
             .andExpect(jsonPath("$[0].teacher_name").value("Assigned Teacher 965701"))
             .andExpect(jsonPath("$[0].subject_name").value("Mathematics"))
             .andExpect(jsonPath("$[0].batch_name").value("Assigned Batch 965701"));
      }
  }
  ```

- [ ] **1.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorReportsLoadAndTeachersIT` —
  expect **FAIL** (`CoordinatorReportsController`/`CoordinatorReportsRepository` do not exist; no route mapped
  at `/api/coordinator/reports/*`).

- [ ] **1.3** Implement `CoordinatorReportsRepository`.

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java`:
  ```java
  package com.rcf.imas.modules.coordinator.persistence;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  import java.util.List;
  import java.util.Map;

  import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

  /**
   * Backs the 7 report endpoints (#23-29, ground truth phase4e-coordinator-ground-truth.md). Every method is
   * a single read-only SQL statement (or the two-query pair #26 needs) -- no writes, no @Transactional.
   * Reuses CoordinatorReadRepository.genericRow (LOCKED CONVENTIONS #3): numeric(x,0) -> String,
   * integer/bigint COUNT(...) -> String via BIGINT branch, plain integer columns pass through natively.
   */
  @Repository
  public class CoordinatorReportsRepository {

      private final JdbcClient jdbc;

      public CoordinatorReportsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

      /** reportsController.js getTeacherLoad -- bare rows, {teacherClassCounts} wrapping is the controller's job. */
      public List<Map<String, Object>> teacherLoad(String fromDate, String toDate) {
          String sql = """
                  SELECT
                      t.teacher_name AS teacher,
                      b.cohort_number AS cohort,
                      c.classroom_name AS classroom,
                      s.subject_code AS subject,
                      COUNT(DISTINCT cs.session_id) AS total_classes_taken
                  FROM pp.class_session cs
                  JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                  JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                  JOIN pp.batch b ON cb.batch_id = b.batch_id
                  JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                  JOIN pp.subject s ON c.subject_id = s.subject_id
                  """
                  + (fromDate != null && toDate != null ? " WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date " : "")
                  + """
                  GROUP BY t.teacher_name, b.cohort_number, c.classroom_name, s.subject_code
                  ORDER BY t.teacher_name, b.cohort_number, c.classroom_name
                  """;
          var spec = jdbc.sql(sql);
          if (fromDate != null && toDate != null) {
              spec = spec.param("fromDate", fromDate).param("toDate", toDate);
          }
          return spec.query((rs, i) -> genericRow(rs)).list();
      }

      /** teacherController.js getCoordinatorTeachers -- scoped by principal userId (Firm Decision 8,
       *  Disagreements #1: live Node scopes by a client-supplied query param instead, closed here). */
      public List<Map<String, Object>> coordinatorTeachers(String userId) {
          return jdbc.sql("""
                  SELECT DISTINCT
                      t.teacher_id,
                      t.teacher_name,
                      t.contact_no,
                      s.subject_name,
                      b.batch_name
                  FROM pp.batch_coordinator_batches bcb
                  JOIN pp.batch b ON bcb.batch_id = b.batch_id
                  JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                  JOIN pp.classroom cls ON cb.classroom_id = cls.classroom_id
                  JOIN pp.class_session cs ON cs.classroom_id = cls.classroom_id
                  JOIN pp.teacher t ON t.teacher_id = cs.teacher_id
                  LEFT JOIN pp.subject s ON cls.subject_id = s.subject_id
                  WHERE bcb.user_id = :userId::numeric
                  ORDER BY t.teacher_name
                  """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
      }
  }
  ```

- [ ] **1.4** Implement `CoordinatorReportsController`.

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java`:
  ```java
  package com.rcf.imas.modules.coordinator.web;

  import com.rcf.imas.modules.coordinator.persistence.CoordinatorReportsRepository;
  import com.rcf.imas.platform.error.ApiException;
  import com.rcf.imas.platform.security.JwtService;
  import org.springframework.security.access.prepost.PreAuthorize;
  import org.springframework.security.core.annotation.AuthenticationPrincipal;
  import org.springframework.web.bind.annotation.*;

  import java.util.List;
  import java.util.Map;

  /**
   * Firm Decision 1: real, JWT-verified @PreAuthorize("isAuthenticated()") on all 7 routes -- Node's live
   * reportsController.js requireAuth (line 5-9) only checks an Authorization header is PRESENT, never calls
   * jwt.verify. This is a deliberate hardening, closing that gap (ground truth doc §8.5).
   */
  @RestController
  @RequestMapping("/api/coordinator/reports")
  @PreAuthorize("isAuthenticated()")
  public class CoordinatorReportsController {

      private final CoordinatorReportsRepository reports;

      public CoordinatorReportsController(CoordinatorReportsRepository reports) {
          this.reports = reports;
      }

      /** getTeacherLoad -- {teacherClassCounts:[...]}, {message} error envelope (matches attendance module,
       *  NOT the {error} convention used elsewhere in coordinator -- reportsController.js:273-280). */
      @GetMapping("/teacher-load")
      public Map<String, Object> teacherLoad(@RequestParam(required = false) String fromDate,
                                               @RequestParam(required = false) String toDate) {
          try {
              return Map.of("teacherClassCounts", reports.teacherLoad(fromDate, toDate));
          } catch (Exception e) {
              throw ApiException.message(500, "Internal server error");
          }
      }

      /** getCoordinatorTeachers -- bare array, scoped by JWT principal (Firm Decision 8). */
      @GetMapping("/coordinator-teachers")
      public List<Map<String, Object>> coordinatorTeachers(@AuthenticationPrincipal JwtService.FinalToken principal) {
          try {
              return reports.coordinatorTeachers(principal.userId());
          } catch (Exception e) {
              throw ApiException.error(500, e.getMessage());
          }
      }
  }
  ```

  Check `com.rcf.imas.platform.error.ApiException` has both `ApiException.error(status, msg)` (`{error:msg}`)
  and `ApiException.message(status, msg)` (`{message:msg}`) static factories before writing this step (both
  are already used across 4e-1/4e-2 per `CoordinatorController`/`AttendanceController` — if the exact method
  names differ, match the existing convention rather than inventing a new one).

- [ ] **1.5** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorReportsLoadAndTeachersIT` —
  expect **PASS**.

- [ ] **1.6** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorReportsLoadAndTeachersIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): reports controller skeleton + teacher-load, coordinator-teachers

  Ports endpoints #25 (teacher-load) and #27 (coordinator-teachers) of the Node
  coordinator reports surface under new /api/coordinator/reports/* routes, with
  real JWT-verified auth (closing Node's non-verifying requireAuth gap) and
  #27 scoped by JWT principal instead of a client-supplied user_id param.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2 — `/reports/attendance` (#23) — attendance matrix

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAttendanceReportIT.java`

Seed id range for this task's IT: `965801`-`965899`.

- [ ] **2.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAttendanceReportIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

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
  class CoordinatorAttendanceReportIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965801,'coordUser965801','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965801,'Attendance Report Cohort')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965801,'Attendance Report Batch',965801)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965801,965801)").update();

          jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965801,'MATH','Mathematics')").update();
          jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965801,'Report Teacher 965801')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965801,'Attendance Report Classroom',965801,965801)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965801,965801)").update();

          // Student A: stays ACTIVE the whole range -- present in both sessions.
          jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965801,'Active Student 965801',965801,'F','ACTIVE')").update();
          // Student B: goes inactive mid-range (2026-06-10) -- only counted for sessions BEFORE that date.
          jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965802,'Mid Range Inactive Student 965802',965801,'F','INACTIVE')").update();
          jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) VALUES (965802,'Left program','2026-06-10')").update();

          // Session 1 (before inactivation): both students attend.
          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (965801,965801,'2026-06-05','09:00:00','10:00:00',965801)
                  """).update();
          // Session 2 (after inactivation): only student A is eligible (student B excluded by the
          // `s.session_date < bs.inactive_date` join condition in student_sessions).
          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (965802,965801,'2026-06-15','09:00:00','10:00:00',965801)
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965801,965801,'PRESENT')").update();
          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965801,965802,'PRESENT')").update();
          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965802,965801,'PRESENT')").update();

          coordToken = jwt.issueFinalToken("965801", "coordUser965801", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (965801,965802)").update();
          jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (965801,965802)").update();
          jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 965802").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (965801,965802)").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965801").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965801").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965801").update();
          jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965801").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965801").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965801").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965801").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965801").update();
      }

      @Test
      void attendanceReportShapeAndCounts() throws Exception {
          mvc.perform(get("/api/coordinator/reports/attendance")
                  .param("batchId", "965801").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.reportId").value("ATT-965801-2026-06-01-2026-06-30"))
             .andExpect(jsonPath("$.batch_name").value("Attendance Report Batch"))
             .andExpect(jsonPath("$.cohort_name").value("Attendance Report Cohort"))
             .andExpect(jsonPath("$.subjects.MATH[0].teacher_name").value("Report Teacher 965801"))
             .andExpect(jsonPath("$.subjects.MATH[0].conducted").value(2))
             // Student A (965801): attended both sessions -> attended=2 under MATH/Report Teacher.
             .andExpect(jsonPath("$.students[?(@.id=='965801')].subjects.MATH['Report Teacher 965801']").value(java.util.List.of(2)))
             // Student B (965802): only eligible for the pre-inactivation session -> attended=1.
             .andExpect(jsonPath("$.students[?(@.id=='965802')].subjects.MATH['Report Teacher 965801']").value(java.util.List.of(1)));
      }
  }
  ```

- [ ] **2.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorAttendanceReportIT` — expect
  **FAIL** (no `/reports/attendance` route, no repository/service methods).

- [ ] **2.3** Add the two SQL methods to `CoordinatorReportsRepository` (append inside the existing class from
  Task 1, after `coordinatorTeachers`).

  ```java
      public record AttendanceBatchInfo(String batchName, String cohortName) {}

      /** getAttendanceReport step 1 -- batch/cohort names. Empty rows -> caller substitutes "" (Node's `?.`
       *  optional-chaining default), so this returns Optional-style null-safe access via the list being empty. */
      public List<Map<String, Object>> attendanceBatchInfo(String batchId) {
          return jdbc.sql("""
                  SELECT b.batch_name, c.cohort_name
                  FROM pp.batch b
                  JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                  WHERE b.batch_id = :batchId::integer
                  """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
      }

      /** getAttendanceReport step 2 -- conducted counts per subject_code x teacher_name, ported verbatim. */
      public List<Map<String, Object>> attendanceConducted(String batchId, String fromDate, String toDate) {
          return jdbc.sql("""
                  WITH batch_classrooms AS (
                      SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = :batchId::integer
                  )
                  SELECT
                      subj.subject_code,
                      t.teacher_name,
                      COUNT(DISTINCT cs.session_id) AS conducted
                  FROM pp.class_session cs
                  JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
                  JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                  JOIN pp.subject subj ON subj.subject_id = c.subject_id
                  LEFT JOIN pp.teacher t ON t.teacher_id = cs.teacher_id
                  WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                  GROUP BY subj.subject_code, t.teacher_name
                  """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }

      /** getAttendanceReport step 3 -- student attendance matrix. Firm Decision 2: the LEFT JOIN
       *  pp.inactive_students has NO dedup, ported verbatim (append-only table, no unique constraint --
       *  a double-inactivated student fans out here exactly as it does in Node). */
      public List<Map<String, Object>> attendanceByStudent(String batchId, String fromDate, String toDate) {
          return jdbc.sql("""
                  WITH batch_classrooms AS (
                      SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = :batchId::integer
                  ),
                  batch_students AS (
                      SELECT sm.student_id, sm.student_name, ins.inactive_date
                      FROM pp.student_master sm
                      LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id
                      WHERE sm.batch_id = :batchId::integer
                        AND (ins.student_id IS NULL OR ins.inactive_date > :fromDate::date)
                  ),
                  sessions AS (
                      SELECT
                          cs.session_id,
                          cs.teacher_id,
                          subj.subject_code,
                          cs.session_date
                      FROM pp.class_session cs
                      JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
                      JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                      JOIN pp.subject subj ON subj.subject_id = c.subject_id
                      WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                  ),
                  student_sessions AS (
                      SELECT
                          bs.student_id,
                          bs.student_name,
                          bs.inactive_date,
                          s.session_id,
                          s.subject_code,
                          s.teacher_id,
                          s.session_date
                      FROM sessions s
                      JOIN batch_students bs
                          ON (bs.inactive_date IS NULL OR s.session_date < bs.inactive_date)
                  )
                  SELECT
                      ss.student_id,
                      ss.student_name,
                      ss.subject_code,
                      t.teacher_name,
                      COUNT(DISTINCT ss.session_id) FILTER (
                          WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
                      ) AS attended
                  FROM student_sessions ss
                  LEFT JOIN pp.student_attendance sa
                      ON sa.session_id = ss.session_id
                      AND sa.student_id = ss.student_id
                  LEFT JOIN pp.teacher t ON t.teacher_id = ss.teacher_id
                  GROUP BY ss.student_id, ss.student_name, ss.subject_code, t.teacher_name
                  ORDER BY ss.student_name
                  """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }
  ```

- [ ] **2.4** Create `CoordinatorReportsService` with the JS-shaping logic for #23.

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java`:
  ```java
  package com.rcf.imas.modules.coordinator.service;

  import com.rcf.imas.modules.coordinator.persistence.CoordinatorReportsRepository;
  import org.springframework.stereotype.Service;

  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * Reproduces the JS-side response reshaping from reportsController.js (nested Maps built in the controller
   * body, not SQL) -- kept in a service so it's unit-testable independent of MockMvc/DB and so the controller
   * methods stay thin.
   */
  @Service
  public class CoordinatorReportsService {

      private final CoordinatorReportsRepository reports;

      public CoordinatorReportsService(CoordinatorReportsRepository reports) {
          this.reports = reports;
      }

      /** getAttendanceReport -- {reportId, cohort_name, batch_name, subjects, students}. */
      public Map<String, Object> attendanceReport(String batchId, String fromDate, String toDate) {
          List<Map<String, Object>> info = reports.attendanceBatchInfo(batchId);
          String batchName = info.isEmpty() || info.get(0).get("batch_name") == null ? "" : String.valueOf(info.get(0).get("batch_name"));
          String cohortName = info.isEmpty() || info.get(0).get("cohort_name") == null ? "" : String.valueOf(info.get(0).get("cohort_name"));

          Map<String, Object> conductedStructured = new LinkedHashMap<>();
          for (Map<String, Object> r : reports.attendanceConducted(batchId, fromDate, toDate)) {
              String subjectCode = String.valueOf(r.get("subject_code"));
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> bucket = (List<Map<String, Object>>) conductedStructured
                      .computeIfAbsent(subjectCode, k -> new java.util.ArrayList<Map<String, Object>>());
              bucket.add(Map.of(
                      "teacher_name", r.get("teacher_name"),
                      "conducted", parseIntOrZero(r.get("conducted"))));
          }

          Map<String, Map<String, Object>> studentMap = new LinkedHashMap<>();
          for (Map<String, Object> r : reports.attendanceByStudent(batchId, fromDate, toDate)) {
              String studentId = String.valueOf(r.get("student_id"));
              Map<String, Object> student = studentMap.computeIfAbsent(studentId, k -> {
                  Map<String, Object> s = new LinkedHashMap<>();
                  s.put("id", studentId);
                  s.put("name", r.get("student_name"));
                  s.put("subjects", new LinkedHashMap<String, Object>());
                  return s;
              });
              @SuppressWarnings("unchecked")
              Map<String, Object> subjects = (Map<String, Object>) student.get("subjects");
              String subjectCode = String.valueOf(r.get("subject_code"));
              @SuppressWarnings("unchecked")
              Map<String, Object> teacherAttended = (Map<String, Object>) subjects
                      .computeIfAbsent(subjectCode, k -> new LinkedHashMap<String, Object>());
              teacherAttended.put(String.valueOf(r.get("teacher_name")), parseIntOrZero(r.get("attended")));
          }

          Map<String, Object> response = new LinkedHashMap<>();
          response.put("reportId", "ATT-" + batchId + "-" + fromDate + "-" + toDate);
          response.put("cohort_name", cohortName);
          response.put("batch_name", batchName);
          response.put("subjects", conductedStructured);
          response.put("students", List.copyOf(studentMap.values()));
          return response;
      }

      /** Node: `parseInt(r.conducted, 10)` / `parseInt(r.attended || 0, 10)`. genericRow's BIGINT branch
       *  already turns COUNT(...) into a String -- watch a real "0" String, do NOT let it fall through to a
       *  null-coalesce fallback (ground truth §5 numeric-id note). */
      private static int parseIntOrZero(Object value) {
          if (value == null) return 0;
          return Integer.parseInt(String.valueOf(value));
      }
  }
  ```

- [ ] **2.5** Wire `/reports/attendance` into `CoordinatorReportsController` (add import
  `com.rcf.imas.modules.coordinator.service.CoordinatorReportsService`, inject it via the constructor alongside
  `CoordinatorReportsRepository`, add this method):

  ```java
      @GetMapping("/attendance")
      public Map<String, Object> attendanceReport(@RequestParam String batchId,
                                                     @RequestParam String fromDate,
                                                     @RequestParam String toDate) {
          try {
              return service.attendanceReport(batchId, fromDate, toDate);
          } catch (Exception e) {
              throw ApiException.error(500, "Server error generating attendance report");
          }
      }
  ```

  Update the constructor:
  ```java
      private final CoordinatorReportsRepository reports;
      private final com.rcf.imas.modules.coordinator.service.CoordinatorReportsService service;

      public CoordinatorReportsController(CoordinatorReportsRepository reports,
                                            com.rcf.imas.modules.coordinator.service.CoordinatorReportsService service) {
          this.reports = reports;
          this.service = service;
      }
  ```

- [ ] **2.6** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorAttendanceReportIT` — expect
  **PASS**.

- [ ] **2.7** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAttendanceReportIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): reports attendance matrix (#23)

  Ports getAttendanceReport's 3-query CTE + JS response nesting verbatim,
  including the un-deduped inactive_students LEFT JOIN fan-out (preserved
  Node behavior, ground truth §4.6/§8 Firm Decision 2).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 3 — `/reports/absentees` (#24)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAbsenteesReportIT.java`

Seed id range for this task's IT: `965901`-`965999`.

- [ ] **3.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAbsenteesReportIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

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
  class CoordinatorAbsenteesReportIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965901,'coordUser965901','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965901,'Absentees Cohort')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965901,'Absentees Batch',965901)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965901,965901)").update();

          jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (965901,'SCI','Science')").update();
          jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965901,'Absentees Teacher')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (965901,'Absentees Classroom',965901,965901)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965901,965901)").update();

          // Timetable: MONDAY slot for this classroom. 2026-06-08 and 2026-06-15 are both Mondays.
          jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) VALUES (965901,965901,'MONDAY','09:00:00','10:00:00')").update();
          jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) VALUES (965901,'Absentee Student 965901',965901,'F','ACTIVE')").update();
          jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

          // Session + attendance only for the FIRST Monday (2026-06-08, PRESENT). The second scheduled Monday
          // (2026-06-15) has no session/attendance row at all -> counts as a scheduled-but-missed slot.
          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (965901,965901,'2026-06-08','09:00:00','10:00:00',965901)
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901,965901,'PRESENT')").update();

          coordToken = jwt.issueFinalToken("965901", "coordUser965901", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965901").update();
          jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 965901").update();
          jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 965901").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 965901").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965901").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965901").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965901").update();
          jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 965901").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965901").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965901").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965901").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965901").update();
      }

      @Test
      void absenteesReportShapeAndMissedCount() throws Exception {
          // Range covers exactly the two Mondays: 2026-06-08 (attended) and 2026-06-15 (missed, no session ever ran).
          mvc.perform(get("/api/coordinator/reports/absentees")
                  .param("batch_id", "965901").param("fromDate", "2026-06-08").param("toDate", "2026-06-15")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.reportId").value("ABS-965901-2026-06-08-2026-06-15"))
             .andExpect(jsonPath("$.students", hasSize(1)))
             .andExpect(jsonPath("$.students[0].id").value("965901"))
             .andExpect(jsonPath("$.students[0].name").value("Absentee Student 965901"))
             .andExpect(jsonPath("$.students[0].totalMissed").value(1))
             .andExpect(jsonPath("$.students[0].missedClasses", hasSize(1)))
             .andExpect(jsonPath("$.students[0].missedClasses[0].subject").value("SCI"))
             .andExpect(jsonPath("$.students[0].missedClasses[0].count").value(1));
      }

      @Test
      void absenteesMissingRequiredParamsReturns400() throws Exception {
          mvc.perform(get("/api/coordinator/reports/absentees")
                  .param("batch_id", "965901")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isBadRequest())
             .andExpect(jsonPath("$.error").value("batch_id, fromDate, and toDate required"));
      }
  }
  ```

- [ ] **3.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorAbsenteesReportIT` — expect
  **FAIL**.

- [ ] **3.3** Add the absentees SQL method to `CoordinatorReportsRepository` (append after
  `attendanceByStudent`):

  ```java
      /** getAbsenteesReport -- generate_series + timetable day-name match. Firm Decision 3: ported verbatim,
       *  including scheduled_count counting timetabled slots (not actual class_session rows) and the
       *  subject_code LIMIT-1 lookup (assumes subject_code uniqueness, unenforced by schema). */
      public List<Map<String, Object>> absentees(String batchId, String fromDate, String toDate) {
          return jdbc.sql("""
                  WITH dates AS (
                      SELECT generate_series(:fromDate::date, :toDate::date, interval '1 day')::date AS dt
                  ),
                  batch_classrooms AS (
                      SELECT cb.classroom_id FROM pp.classroom_batch cb WHERE cb.batch_id = :batchId::integer
                  ),
                  scheduled AS (
                      SELECT c.classroom_id, s.subject_code, d.dt
                      FROM pp.classroom c
                      JOIN batch_classrooms bc ON bc.classroom_id = c.classroom_id
                      JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                      JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                      JOIN pp.subject s ON s.subject_id = c.subject_id
                  ),
                  attended AS (
                      SELECT sa.student_id, c.subject_id, cs.session_date AS date, sa.status
                      FROM pp.student_attendance sa
                      JOIN pp.class_session cs ON cs.session_id = sa.session_id
                      JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                      WHERE cs.session_date BETWEEN :fromDate::date AND :toDate::date
                  ),
                  compare AS (
                      SELECT bs.student_id, bs.student_name, sch.subject_code,
                             COUNT(*) AS scheduled_count,
                             COUNT(att.*) FILTER (WHERE att.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_count,
                             ARRAY_AGG(CASE WHEN att.status = 'ABSENT' THEN att.date END)
                               FILTER (WHERE att.status = 'ABSENT') AS absent_dates
                      FROM (SELECT sm.student_id, sm.student_name FROM pp.student_master sm WHERE sm.batch_id = :batchId::integer) bs
                      JOIN scheduled sch ON TRUE
                      LEFT JOIN attended att
                        ON att.student_id = bs.student_id
                        AND att.subject_id = (SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)
                        AND att.date = sch.dt
                      GROUP BY bs.student_id, bs.student_name, sch.subject_code
                  )
                  SELECT student_id, student_name, subject_code AS subject, scheduled_count, attended_count,
                         (scheduled_count - attended_count) AS missed_count,
                         COALESCE(absent_dates, '{}') AS missed_dates
                  FROM compare
                  WHERE (scheduled_count - attended_count) > 0
                  ORDER BY missed_count DESC
                  """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }
  ```

  Note: `missed_dates` is a Postgres `date[]`. `genericRow`'s `default -> val = rs.getObject(i)` branch will
  hand back a `java.sql.Array` for this column, NOT a `List`. Add a dedicated array-unwrap in the SQL projection
  instead of relying on genericRow for this one column: change the final `SELECT` to cast
  `COALESCE(absent_dates,'{}')::text[]` is still an array type at the JDBC level, so unwrap it explicitly in
  the service (`java.sql.Array.getArray()` -> `Object[]` -> map each `java.sql.Date` to its `yyyy-MM-dd`
  string, dropping `null` entries) rather than trying to make `genericRow` array-aware for one caller.

- [ ] **3.4** Add the absentees shaping method to `CoordinatorReportsService` (append after
  `parseIntOrZero`):

  ```java
      /** getAbsenteesReport -- {reportId, students:[{id,name,missedClasses,totalMissed}]}. */
      public Map<String, Object> absenteesReport(String batchId, String fromDate, String toDate) {
          Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
          for (Map<String, Object> r : reports.absentees(batchId, fromDate, toDate)) {
              String studentId = String.valueOf(r.get("student_id"));
              Map<String, Object> student = grouped.computeIfAbsent(studentId, k -> {
                  Map<String, Object> s = new LinkedHashMap<>();
                  s.put("id", studentId);
                  s.put("name", r.get("student_name"));
                  s.put("missedClasses", new java.util.ArrayList<Map<String, Object>>());
                  s.put("totalMissed", 0);
                  return s;
              });
              int missedCount = parseIntOrZero(r.get("missed_count"));
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> missedClasses = (List<Map<String, Object>>) student.get("missedClasses");
              missedClasses.add(Map.of(
                      "subject", r.get("subject"),
                      "count", missedCount,
                      "dates", unwrapDateArray(r.get("missed_dates"))));
              student.put("totalMissed", (Integer) student.get("totalMissed") + missedCount);
          }

          Map<String, Object> response = new LinkedHashMap<>();
          response.put("reportId", "ABS-" + batchId + "-" + fromDate + "-" + toDate);
          response.put("students", List.copyOf(grouped.values()));
          return response;
      }

      /** missed_dates is a Postgres date[] -- genericRow hands back a raw java.sql.Array for this column.
       *  Node's `.filter(Boolean)` drops the NULL placeholder entries the CASE/ARRAY_AGG can produce; do the
       *  same here. */
      private static List<String> unwrapDateArray(Object arrayObj) {
          if (arrayObj == null) return List.of();
          try {
              java.sql.Array sqlArray = (java.sql.Array) arrayObj;
              Object[] raw = (Object[]) sqlArray.getArray();
              List<String> out = new java.util.ArrayList<>();
              for (Object o : raw) {
                  if (o == null) continue;
                  out.add(o instanceof java.sql.Date d ? d.toLocalDate().toString() : String.valueOf(o));
              }
              return out;
          } catch (java.sql.SQLException e) {
              throw new RuntimeException(e);
          }
      }
  ```

- [ ] **3.5** Wire `/reports/absentees` into `CoordinatorReportsController` (append after the `/attendance`
  method):

  ```java
      @GetMapping("/absentees")
      public Map<String, Object> absenteesReport(@RequestParam(name = "batch_id", required = false) String batchId,
                                                    @RequestParam(required = false) String fromDate,
                                                    @RequestParam(required = false) String toDate) {
          if (batchId == null || fromDate == null || toDate == null) {
              throw ApiException.error(400, "batch_id, fromDate, and toDate required");
          }
          try {
              return service.absenteesReport(batchId, fromDate, toDate);
          } catch (Exception e) {
              throw ApiException.error(500, "Server error generating absentees report");
          }
      }
  ```

- [ ] **3.6** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorAbsenteesReportIT` — expect
  **PASS**.

- [ ] **3.7** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorAbsenteesReportIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): reports absentees (#24)

  Ports getAbsenteesReport's generate_series + timetable day-name-match CTE
  verbatim, including the timetabled-slot-vs-session and subject_code LIMIT-1
  quirks (ground truth §4.7/§8 Firm Decision 3), plus the missed_count>0
  filter and missed_dates array unwrap.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 4 — `/reports/teacher-performance` (#26), `/reports/batch-class-details` (#28),
`/reports/teacher-class-details` (#29)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTeacherPerformanceAndClassDetailsIT.java`

Seed id range for this task's IT: `966001`-`966099`.

- [ ] **4.1** Write the failing IT.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTeacherPerformanceAndClassDetailsIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

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
  class CoordinatorTeacherPerformanceAndClassDetailsIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966001,'coordUser966001','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966001,'Perf Cohort')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966001,'Perf Batch',966001)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966001,966001)").update();

          jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966001,'ENG','English')").update();
          jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966001,'Perf Teacher 966001')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

          // classroom.teacher_id = the "default" teacher (used by teacher-performance's scheduled query and
          // batch-class-details' classroom-batch join).
          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966001,'Perf Classroom',966001,966001)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966001,966001)").update();

          jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) VALUES (966001,966001,'MONDAY','09:00:00','10:00:00')").update();
          jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

          // One session actually run (session-level teacher_id = 966001, same as classroom's default), on the
          // one scheduled Monday in range (2026-06-08) -- scheduled=1, conducted=1, completion=100.0.
          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (966001,966001,'2026-06-08','09:00:00','10:00:00',966001)
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

          coordToken = jwt.issueFinalToken("966001", "coordUser966001", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 966001").update();
          jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = 966001").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 966001").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966001").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966001").update();
          jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966001").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 966001").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966001").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966001").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966001").update();
      }

      @Test
      void teacherPerformanceShapeAndCompletion() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-performance")
                  .param("teacherId", "966001").param("fromDate", "2026-06-08").param("toDate", "2026-06-08")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.reportId").value("TP-966001-2026-06-08-2026-06-08"))
             .andExpect(jsonPath("$.subjects", hasSize(1)))
             .andExpect(jsonPath("$.subjects[0].subject").value("ENG"))
             .andExpect(jsonPath("$.subjects[0].scheduled").value(1))
             .andExpect(jsonPath("$.subjects[0].conducted").value(1))
             .andExpect(jsonPath("$.subjects[0].completion").value(100.0));
      }

      @Test
      void teacherPerformanceMissingParamsReturns400() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-performance")
                  .param("teacherId", "966001")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isBadRequest())
             .andExpect(jsonPath("$.error").value("teacherId, fromDate, and toDate required"));
      }

      @Test
      void batchClassDetailsShapeAndAttendanceMarkedFlag() throws Exception {
          mvc.perform(get("/api/coordinator/reports/batch-class-details")
                  .param("batchId", "966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.success").value(true))
             .andExpect(jsonPath("$.count").value(1))
             .andExpect(jsonPath("$.classes", hasSize(1)))
             .andExpect(jsonPath("$.classes[0].session_id").value(966001))
             .andExpect(jsonPath("$.classes[0].teacher_name").value("Perf Teacher 966001"))
             .andExpect(jsonPath("$.classes[0].cohort_name").value("Perf Cohort"))
             .andExpect(jsonPath("$.classes[0].classroom_name").value("Perf Classroom"))
             .andExpect(jsonPath("$.classes[0].attendance_marked").value(false));
      }

      @Test
      void teacherClassDetailsNumericIdFilterUsesTeacherIdColumn() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-class-details")
                  .param("teacherId", "966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.success").value(true))
             .andExpect(jsonPath("$.count").value(1))
             .andExpect(jsonPath("$.classes[0].teacher_name").value("Perf Teacher 966001"))
             .andExpect(jsonPath("$.classes[0].batch_name").value("Perf Batch"));
      }

      @Test
      void teacherClassDetailsNonNumericFilterUsesTeacherNameColumn() throws Exception {
          mvc.perform(get("/api/coordinator/reports/teacher-class-details")
                  .param("teacherId", "Perf Teacher 966001").param("fromDate", "2026-06-01").param("toDate", "2026-06-30")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.success").value(true))
             .andExpect(jsonPath("$.count").value(1))
             .andExpect(jsonPath("$.classes[0].classroom_name").value("Perf Classroom"));
      }
  }
  ```

- [ ] **4.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTeacherPerformanceAndClassDetailsIT`
  — expect **FAIL**.

- [ ] **4.3** Add the remaining SQL methods to `CoordinatorReportsRepository` (append after `absentees`):

  ```java
      /** getTeacherPerformance step 1 -- scheduled slots via generate_series + timetable day-name match,
       *  filtered by classroom.teacher_id (the DEFAULT/classroom-level teacher, not the session-level one --
       *  ported exactly as reportsController.js:326-330 has it). */
      public List<Map<String, Object>> teacherPerformanceScheduled(String teacherId, String fromDate, String toDate) {
          return jdbc.sql("""
                  WITH dates AS (SELECT generate_series(:fromDate::date, :toDate::date, interval '1 day')::date AS dt),
                  scheduled AS (
                      SELECT s.subject_code, d.dt FROM pp.classroom c
                      JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                      JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                      JOIN pp.subject s ON s.subject_id = c.subject_id
                      WHERE c.teacher_id = :teacherId::integer
                  )
                  SELECT subject_code AS subject, COUNT(*) AS scheduled FROM scheduled GROUP BY subject_code
                  """).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }

      /** getTeacherPerformance step 2 -- conducted sessions, also filtered by classroom.teacher_id (NOT
       *  cs.teacher_id -- ported exactly as reportsController.js:339 has it). */
      public List<Map<String, Object>> teacherPerformanceConducted(String teacherId, String fromDate, String toDate) {
          return jdbc.sql("""
                  SELECT subj.subject_code AS subject, COUNT(DISTINCT cs.session_id) AS conducted
                  FROM pp.class_session cs
                  JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                  JOIN pp.subject subj ON subj.subject_id = c.subject_id
                  WHERE c.teacher_id = :teacherId::integer AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                  GROUP BY subj.subject_code
                  """).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }

      /** getBatchClassDetails -- session list for a batch w/ attendance_marked EXISTS-flag. */
      public List<Map<String, Object>> batchClassDetails(String batchId, String fromDate, String toDate) {
          return jdbc.sql("""
                  SELECT
                      cs.session_id,
                      cs.session_date AS date,
                      t.teacher_name,
                      co.cohort_name,
                      c.classroom_name,
                      EXISTS (
                          SELECT 1
                          FROM pp.student_attendance sa
                          JOIN pp.student_master sm ON sa.student_id = sm.student_id
                          WHERE sa.session_id = cs.session_id
                            AND sm.batch_id = :batchId::integer
                      ) AS attendance_marked
                  FROM pp.class_session cs
                  JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                  JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                  JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                  JOIN pp.batch b ON cb.batch_id = b.batch_id
                  JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                  WHERE b.batch_id = :batchId::integer
                    AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                  ORDER BY cs.session_date DESC
                  """).param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }

      /** getTeacherClassDetails -- Firm Decision 4: closed 2-way switch on filterColumn, never string-
       *  interpolating the request VALUE (only the column name, chosen from two hardcoded literals, differs). */
      public List<Map<String, Object>> teacherClassDetails(String teacherId, String fromDate, String toDate) {
          boolean numeric = teacherId != null && teacherId.matches("\\d+");
          String sql = numeric
                  ? """
                      SELECT DISTINCT ON (cs.session_id)
                             cs.session_date AS date, t.teacher_name, co.cohort_name,
                             b.batch_name, c.classroom_name
                      FROM pp.class_session cs
                      JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                      JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                      JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                      JOIN pp.batch b ON cb.batch_id = b.batch_id
                      JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                      WHERE t.teacher_id = :teacherId::integer
                        AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                      ORDER BY cs.session_id, cs.session_date DESC
                      """
                  : """
                      SELECT DISTINCT ON (cs.session_id)
                             cs.session_date AS date, t.teacher_name, co.cohort_name,
                             b.batch_name, c.classroom_name
                      FROM pp.class_session cs
                      JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                      JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                      JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                      JOIN pp.batch b ON cb.batch_id = b.batch_id
                      JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                      WHERE t.teacher_name = :teacherId
                        AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                      ORDER BY cs.session_id, cs.session_date DESC
                      """;
          return jdbc.sql(sql).param("teacherId", teacherId).param("fromDate", fromDate).param("toDate", toDate)
                  .query((rs, i) -> genericRow(rs)).list();
      }
  ```

- [ ] **4.4** Add the teacher-performance shaping method to `CoordinatorReportsService` (append after
  `unwrapDateArray`):

  ```java
      /** getTeacherPerformance -- {reportId, subjects:[{subject,scheduled,conducted,completion}]}. Subject
       *  key set = union of scheduled ∪ conducted subject codes (Node builds subjectsMap from `scheduled`
       *  first, then folds `conducted` in, creating a fresh {scheduled:0,...} entry for conducted-only subjects). */
      public Map<String, Object> teacherPerformanceReport(String teacherId, String fromDate, String toDate) {
          Map<String, int[]> subjectsMap = new LinkedHashMap<>(); // [0]=scheduled, [1]=conducted
          for (Map<String, Object> r : reports.teacherPerformanceScheduled(teacherId, fromDate, toDate)) {
              subjectsMap.put(String.valueOf(r.get("subject")), new int[]{parseIntOrZero(r.get("scheduled")), 0});
          }
          for (Map<String, Object> r : reports.teacherPerformanceConducted(teacherId, fromDate, toDate)) {
              String subject = String.valueOf(r.get("subject"));
              int conducted = parseIntOrZero(r.get("conducted"));
              subjectsMap.computeIfAbsent(subject, k -> new int[]{0, 0})[1] = conducted;
          }

          List<Map<String, Object>> subjects = new java.util.ArrayList<>();
          for (Map.Entry<String, int[]> e : subjectsMap.entrySet()) {
              int scheduled = e.getValue()[0];
              int conducted = e.getValue()[1];
              double completion = scheduled > 0
                      ? Math.round((conducted / (double) scheduled) * 100 * 10) / 10.0
                      : 0.0;
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("subject", e.getKey());
              row.put("scheduled", scheduled);
              row.put("conducted", conducted);
              row.put("completion", completion);
              subjects.add(row);
          }

          Map<String, Object> response = new LinkedHashMap<>();
          response.put("reportId", "TP-" + teacherId + "-" + fromDate + "-" + toDate);
          response.put("subjects", subjects);
          return response;
      }
  ```

  `batchClassDetails`/`teacherClassDetails` need no service-layer shaping — the controller wraps the raw rows
  directly in `{success, count, classes}`.

- [ ] **4.5** Wire the three remaining routes into `CoordinatorReportsController` (append after
  `/absentees`):

  ```java
      @GetMapping("/teacher-performance")
      public Map<String, Object> teacherPerformance(@RequestParam(required = false) String teacherId,
                                                        @RequestParam(required = false) String fromDate,
                                                        @RequestParam(required = false) String toDate) {
          if (teacherId == null || fromDate == null || toDate == null) {
              throw ApiException.error(400, "teacherId, fromDate, and toDate required");
          }
          try {
              return service.teacherPerformanceReport(teacherId, fromDate, toDate);
          } catch (Exception e) {
              throw ApiException.error(500, "Server error generating teacher performance");
          }
      }

      @GetMapping("/batch-class-details")
      public Map<String, Object> batchClassDetails(@RequestParam String batchId,
                                                       @RequestParam String fromDate,
                                                       @RequestParam String toDate) {
          try {
              List<Map<String, Object>> classes = reports.batchClassDetails(batchId, fromDate, toDate);
              return Map.of("success", true, "count", classes.size(), "classes", classes);
          } catch (Exception e) {
              throw ApiException.error(500, "Internal Server Error");
          }
      }

      @GetMapping("/teacher-class-details")
      public Map<String, Object> teacherClassDetails(@RequestParam String teacherId,
                                                         @RequestParam String fromDate,
                                                         @RequestParam String toDate) {
          try {
              List<Map<String, Object>> classes = reports.teacherClassDetails(teacherId, fromDate, toDate);
              return Map.of("success", true, "count", classes.size(), "classes", classes);
          } catch (Exception e) {
              throw ApiException.error(500, "Internal Server Error");
          }
      }
  ```

- [ ] **4.6** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTeacherPerformanceAndClassDetailsIT`
  — expect **PASS**.

- [ ] **4.7** Run the full coordinator module test suite to confirm no regression:
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=com.rcf.imas.modules.coordinator.**` — expect **PASS**.

- [ ] **4.8** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorReportsRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorReportsService.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorReportsController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTeacherPerformanceAndClassDetailsIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): reports teacher-performance, batch/teacher class details (#26,#28,#29)

  Completes the 4e-3 reports slice. #29's filterColumn is a closed 2-way
  switch between two hardcoded SQL strings (never string-interpolating the
  request value), per Firm Decision 4. All 7 report endpoints (#23-29) now
  live under /api/coordinator/reports/* with real JWT-verified auth.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Self-review checklist (verify before calling this plan done)

- [ ] #23-29 each map to exactly one task (Task 1: #25,#27; Task 2: #23; Task 3: #24; Task 4: #26,#28,#29).
- [ ] Every CTE (attendance matrix, absentees, teacher-performance-scheduled) is copied verbatim from the live
      Node source — no added `DISTINCT`, no `MAX(inactive_date)` dedup, no session-based rewrite of
      `scheduled_count`.
- [ ] `conductedStructured`/`studentMap`/absentees-grouping/`subjectsMap` nesting reproduces Node's exact keys
      (`id`, `name`, `subjects`, `missedClasses`, `totalMissed`, `scheduled`, `conducted`, `completion`).
- [ ] #29's `filterColumn` choice is a Java `if`/ternary selecting between two fully-formed SQL string
      literals — `teacherId` itself is always a bound `:teacherId` param, never concatenated.
- [ ] `reportId` formats match exactly: `ATT-<batchId>-<from>-<to>`, `ABS-<batch_id>-<from>-<to>`,
      `TP-<teacherId>-<from>-<to>`.
- [ ] `genericRow` reused everywhere (no duplicate row-mapper).
- [ ] `@PreAuthorize("isAuthenticated()")` present on `CoordinatorReportsController` (class-level), covering
      all 7 routes.
- [ ] No placeholder code — every step above is complete, compilable Java.
