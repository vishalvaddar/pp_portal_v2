# Phase 4e-2 — Coordinator Attendance (endpoints #15-22)

## Goal

Port the 8 attendance endpoints of the Node `coordinatorRoutes.js` (#15-22: session lookup, CSV preview,
CSV commit, undo, overlap check, bulk stub, manual fetch, sample-CSV download) to Spring Boot, byte-compatible
on the wire with the frozen Node API + React client + Postgres `pp` schema. Builds on module
`com.rcf.imas.modules.coordinator` (4e-1 already delivered `CoordinatorController` /
`CoordinatorReadRepository` / `CoordinatorWriteRepository` + the shared `genericRow` row-mapper). This slice
adds attendance-specific classes rather than bloating the existing 4e-1 files further.

## Architecture

- New `AttendanceController` (`com.rcf.imas.modules.coordinator.web`) mounted at `/api/coordinator`, same
  class as `CoordinatorController` but a separate file for cohesion; inherits nothing automatically, so it
  repeats the class-level `@PreAuthorize("isAuthenticated()")` (Node: every attendance route uses `authenticate`).
- New `AttendanceReadRepository` / `AttendanceWriteRepository` (`com.rcf.imas.modules.coordinator.persistence`),
  reusing the package-private static `CoordinatorReadRepository.genericRow` row-mapper (LOCKED CONVENTIONS #3 —
  same file, same package, no duplication).
- New `AttendanceSupport` (`com.rcf.imas.modules.coordinator.service`) — static, stateless time/duration
  helpers ported verbatim from the live Node regexes. No Spring bean; plain static methods, unit-tested without
  a Spring context.
- New `AttendanceCsvPreviewService` (`com.rcf.imas.modules.coordinator.service`) — in-memory CSV fuzzy-match
  algorithm (no DB write), calls `AttendanceReadRepository.attendanceStudentsByBatch`.
- `AttendanceWriteRepository.commitCsvAttendance` is `@Transactional`, threading ONE connection through both
  the session upsert and the N attendance-row upserts (fixes the Node atomicity bug, ground truth §8.2).
- Plain `JdbcClient` + hand-written SQL throughout — no JPA/Hibernate (user's global convention).

## Tech Stack

Java 21, Spring Boot 3.3.5, Maven, Spring `JdbcClient`, Apache Commons CSV (already a project dependency, see
`com.rcf.imas.modules.merge.service.CsvSupport`), JUnit 5 + Spring `MockMvc` + embedded Postgres
(`io.zonky.test.db.postgres.embedded.EmbeddedPostgres` via `PgIntegrationTest`), Flyway baseline
`imas-backend/src/main/resources/db/migration/V1__baseline.sql`.

---

## Firm Decisions (locked — do not re-litigate)

| # | Decision |
|---|---|
| 1 | **`ON CONFLICT` clauses are VALID and ported VERBATIM.** The ground-truth doc's §7.1/§8.1 claim of "no matching unique constraint → live 500 bug" is WRONG — its scan missed the `ALTER TABLE ... ADD CONSTRAINT` lines. Verified in `V1__baseline.sql`: `class_session_classroom_id_session_date_start_time_end_time_key UNIQUE (classroom_id, session_date, start_time, end_time)` (line 1293) and `student_attendance_session_id_student_id_key UNIQUE (session_id, student_id)` (line 1440) both exist. Both `INSERT ... ON CONFLICT` statements run natively in Postgres. Do NOT rewrite as SELECT-then-upsert, do NOT flag as broken, do NOT add new constraints — they're already there. |
| 2 | **`commitCSVAttendance` is `@Transactional`** in `AttendanceWriteRepository`: the getOrCreateSession logic (SELECT `teacher_id` from `pp.classroom`, then `INSERT ... ON CONFLICT` on `pp.class_session` RETURNING `session_id`) and the per-student `pp.student_attendance` upsert loop all run on ONE tx-bound connection. This fixes Node's real atomicity hole (ground truth §7.2/§8.2: Node's `getOrCreateSession` uses the module-level `pool`, not the caller's `client`, so the session row commits independently of the surrounding `BEGIN`/`COMMIT`/`ROLLBACK`). Success → `{session_id}` 200 (session_id as a JSON number, see #9). Any exception → `@Transactional` rolls back everything (session insert included) and the controller returns 500 `{message: <exception message>}`. |
| 3 | **Time helpers ported VERBATIM** as static Java methods in `AttendanceSupport`: `normalizeTimeToDB`, `timeToMinutes`, `parseDurationToMinutes`. Same regexes, same nbsp/narrow-no-break-space stripping, same `HH:mm`/`HH:mm:ss` passthrough, same AM/PM 12-hour conversion (PM+<12 → +12, AM+12 → 0), same "00:00:00" fallback. `parseDurationToMinutes` sums `(\d+)\s*hr`×60 + `(\d+)\s*min` + `(\d+)\s*sec`/60, `Math.round`. Unit-tested for AM/PM, "1 hr 25 min", nbsp, "null", blank, bare `HH:mm`. |
| 4 | **CSV preview (#16) is an in-memory fuzzy match, NO DB write.** Positional (array-of-arrays) CSV parse via Apache Commons CSV in no-header mode — Zoom export shape: row 0 = header, row 1 col D (index 3) = total meeting duration string, data rows from index 2, columns A=name(0), D=duration(3), E=time_joined(4), F=time_exited(5). `csvMap` keyed by `name.toLowerCase()`, keeping the LARGEST-duration row per key. For every student from `attendanceStudentsByBatch(batch_id)` (live source queries by `batch_id`, NOT by classroom — see Disagreements #2), substring-match `csvKey.contains(dbNameCleanLowercased)`, first match wins over `csvMap`'s insertion order (`LinkedHashMap`). `pct = duration/totalCSVDurationMins*100`; `status = pct>=75 ? PRESENT : pct>=40 ? "LATE JOINED" : "ABSENT"`. No CSV match → row with `duration_minutes=0`, `status="ABSENT"`. Non-ACTIVE students → `inactiveStudents[]` (excluded from `previewData`). Unmatched CSV rows → `unmatchedStudents[]`. `previewData` sorted by name (locale compare). Response `{previewData, unmatchedStudents, inactiveStudents}` 200. Java reads the `MultipartFile` stream directly — Node's write-to-disk-then-`unlinkSync` dance is skipped (wire-invisible simplification, and it also fixes Node's real leak: `fs.unlinkSync` only runs on the success path). 400 `{message:"No file uploaded"}` if no file; 400 `{message:"CSV missing data rows."}` if fewer than 2 CSV records. |
| 5 | **#20 `/attendance/bulk` is a no-op stub.** Returns `{message:"Bulk submission logic active"}` 200, reads and does nothing with the request body — exact string from live `attendanceController.js:727-729`. |
| 6 | **#22 `/attendance/csv/reference`:** the original `server/uploads/sample_attendance.csv` asset is absent from this repo snapshot. A representative Zoom-format sample is bundled as classpath resource `imas-backend/src/main/resources/attendance-assets/sample_attendance.csv` (header + summary row + 3 data rows matching the A/D/E/F shape) and streamed with `Content-Disposition: attachment; filename="sample_attendance.csv"`, `Content-Type: text/csv`. |
| 7 | **Auth:** `AttendanceController` carries its own class-level `@PreAuthorize("isAuthenticated()")` (mirrors `CoordinatorController`; Node's `authenticate` middleware applies to all attendance routes). |
| 8 | **Error envelopes:** every attendance endpoint uses `{message}` (NOT `{error}`) for 400s/500s — matches the live source exactly, this is genuinely inconsistent with the rest of the coordinator module (which uses `{error}`) but consistent within attendance itself. `getOrFindSession` returns `{session_id:null}` (still 200, not 404) when no session row matches. `/attendance/undo` → `{message:"Undo Successful"}`. `/attendance/check-overlap` → `{overlap:true/false}` ONLY (see Disagreements #1 — no `conflicts` key here). `fetchAttendance` (#21) → array of student rows with `db_status`. |
| 9 | **id serialization**, matching the 4e-1-established `genericRow` convention (already in `CoordinatorReadRepository`, reused as-is): `numeric(x,0)` columns → `String` (`student_id`, `enr_id`, `created_by`, `updated_by`); plain `integer` columns pass through natively as JSON numbers (`session_id`, `attendance_id`, `classroom_id`, `batch_id`, `teacher_id`). No new mapping code needed — `genericRow` already does this correctly for every column touched in this slice. |

## Disagreements between the task brief / ground-truth doc and the LIVE Node source (for you to adjudicate)

1. **`check-overlap` response shape.** The task brief (and a literal reading of the ground-truth doc's §5
   response table) says `check-overlap → {overlap:true/false, conflicts:[...]}`. The LIVE
   `attendanceController.js:703-716` (`exports.checkOverlap`) returns **only** `{overlap: r.rows.length > 0}`
   — there is no `conflicts` array anywhere in this handler. Cross-checking ground truth §5 more carefully:
   the `{overlap, conflicts}` row is documented as belonging to **timetable's** `checkConflict` /
   `createSlot`/`updateSlot` inline pre-check (a *different* module slice, phase 4e-4), not attendance's
   `checkOverlap`. This plan ports the attendance endpoint exactly as the live source has it: `{overlap:boolean}`
   only. **Flagging in case the task brief intended `conflicts` deliberately** — if so, that would be a new
   behavior not present in Node today and needs an explicit product decision before adding it.
2. **CSV preview db-student scope.** Ground truth §4.1's prose says "for every ACTIVE db student (query the
   coordinator/classroom's students)". The LIVE `previewCSVAttendance` (`attendanceController.js:474-614`)
   actually queries `SELECT student_id, student_name, enr_id, active_yn FROM pp.student_master WHERE batch_id = $1`
   using `req.body.batch_id` — scoped by **batch**, not by classroom, and not additionally scoped to the
   requesting coordinator. Ported per the live source (`attendanceStudentsByBatch`). No adjudication needed
   unless the ground truth's phrasing was meant as a correction rather than a paraphrase — flagging for
   awareness only.

## Deferred / Flagged (do not build now, noted for later)

- **`/attendance/bulk` stub** (#20) — real bulk-JSON-attendance-submit behavior is NOT implemented; Node
  itself never implemented it either. If the React client is later found to depend on real behavior, that's a
  separate follow-up ticket, not part of this slice.
- **Synthesized sample CSV** — `attendance-assets/sample_attendance.csv` is a representative template, not
  the original Node asset (which isn't in this repo snapshot). If the real file surfaces later, swap it in
  without changing the endpoint contract.
- **Disk-write skip in CSV preview** — Java parses the `MultipartFile` stream directly instead of Node's
  write-to-`server/uploads/`-then-`unlinkSync` dance. Wire-invisible; also avoids Node's real leak on early
  4xx / exception paths.
- **`getOrFindSession`-connection atomicity improvement** — see Firm Decision #2. A deliberate behavior
  improvement over Node, not a silent one; called out here and in the code comment on `commitCsvAttendance`.

---

## Task 1 — Time helpers + session lookup, overlap check, undo, bulk stub, sample CSV download

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceSupport.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceWriteRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java`
- Create: `imas-backend/src/main/resources/attendance-assets/sample_attendance.csv`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/service/AttendanceSupportTest.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceSessionOverlapUndoIT.java`

Seed id range for this task's IT: `965601`-`965699`.

- [ ] **1.1** Write the failing unit test for the time/duration helpers.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/service/AttendanceSupportTest.java`:
  ```java
  package com.rcf.imas.modules.coordinator.service;

  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class AttendanceSupportTest {

      @Test
      void normalizeTimeToDB_passesThroughHHmm() {
          assertThat(AttendanceSupport.normalizeTimeToDB("09:05")).isEqualTo("09:05:00");
      }

      @Test
      void normalizeTimeToDB_passesThroughHHmmss() {
          assertThat(AttendanceSupport.normalizeTimeToDB("09:05:30")).isEqualTo("09:05:30");
      }

      @Test
      void normalizeTimeToDB_convertsPM() {
          assertThat(AttendanceSupport.normalizeTimeToDB("2:15 PM")).isEqualTo("14:15:00");
      }

      @Test
      void normalizeTimeToDB_convertsPMAlreadyGreaterThan12IsUnchanged() {
          // PM and hrs already >= 12 must NOT add another 12 (guards the "hrs < 12" branch condition)
          assertThat(AttendanceSupport.normalizeTimeToDB("12:30 PM")).isEqualTo("12:30:00");
      }

      @Test
      void normalizeTimeToDB_convertsAM12ToZeroHundred() {
          assertThat(AttendanceSupport.normalizeTimeToDB("12:00 AM")).isEqualTo("00:00:00");
      }

      @Test
      void normalizeTimeToDB_convertsAMNormalHourUnchanged() {
          assertThat(AttendanceSupport.normalizeTimeToDB("9:05 AM")).isEqualTo("09:05:00");
      }

      @Test
      void normalizeTimeToDB_stripsNarrowNoBreakSpaceBeforeAmPm() {
          // Zoom/Excel exports commonly separate the time and AM/PM with U+202F (narrow no-break space)
          String raw = "9:05\u202fAM";
          assertThat(AttendanceSupport.normalizeTimeToDB(raw)).isEqualTo("09:05:00");
      }

      @Test
      void normalizeTimeToDB_stripsRegularNoBreakSpace() {
          String raw = "9:05\u00a0AM";
          assertThat(AttendanceSupport.normalizeTimeToDB(raw)).isEqualTo("09:05:00");
      }

      @Test
      void normalizeTimeToDB_nullReturnsFallback() {
          assertThat(AttendanceSupport.normalizeTimeToDB(null)).isEqualTo("00:00:00");
      }

      @Test
      void normalizeTimeToDB_blankReturnsFallback() {
          assertThat(AttendanceSupport.normalizeTimeToDB("   ")).isEqualTo("00:00:00");
      }

      @Test
      void normalizeTimeToDB_literalStringNullReturnsFallback() {
          assertThat(AttendanceSupport.normalizeTimeToDB("null")).isEqualTo("00:00:00");
      }

      @Test
      void normalizeTimeToDB_unparseableReturnsFallback() {
          assertThat(AttendanceSupport.normalizeTimeToDB("garbage")).isEqualTo("00:00:00");
      }

      @Test
      void timeToMinutes_convertsNormalizedTime() {
          assertThat(AttendanceSupport.timeToMinutes("2:15 PM")).isEqualTo(14 * 60 + 15);
      }

      @Test
      void timeToMinutes_midnightFallbackIsZero() {
          assertThat(AttendanceSupport.timeToMinutes(null)).isEqualTo(0);
          assertThat(AttendanceSupport.timeToMinutes("garbage")).isEqualTo(0);
      }

      @Test
      void parseDurationToMinutes_hoursAndMinutes() {
          assertThat(AttendanceSupport.parseDurationToMinutes("1 hr 25 min")).isEqualTo(85);
      }

      @Test
      void parseDurationToMinutes_minutesOnly() {
          assertThat(AttendanceSupport.parseDurationToMinutes("45 min")).isEqualTo(45);
      }

      @Test
      void parseDurationToMinutes_secondsRoundUp() {
          // 40 sec = 0.666... min, rounds to 1
          assertThat(AttendanceSupport.parseDurationToMinutes("40 sec")).isEqualTo(1);
      }

      @Test
      void parseDurationToMinutes_hoursMinutesSecondsCombined() {
          // 1 hr = 60, 10 min = 10, 30 sec = 0.5 -> 70.5 -> Math.round -> 71 (round-half-up, matches JS Math.round)
          assertThat(AttendanceSupport.parseDurationToMinutes("1 hr 10 min 30 sec")).isEqualTo(71);
      }

      @Test
      void parseDurationToMinutes_nullIsZero() {
          assertThat(AttendanceSupport.parseDurationToMinutes(null)).isEqualTo(0);
      }

      @Test
      void parseDurationToMinutes_literalStringNullIsZero() {
          assertThat(AttendanceSupport.parseDurationToMinutes("null")).isEqualTo(0);
      }

      @Test
      void parseDurationToMinutes_unrecognizedTextIsZero() {
          assertThat(AttendanceSupport.parseDurationToMinutes("N/A")).isEqualTo(0);
      }

      @Test
      void parseDurationToMinutes_caseInsensitive() {
          assertThat(AttendanceSupport.parseDurationToMinutes("1 HR 5 MIN")).isEqualTo(65);
      }
  }
  ```

- [ ] **1.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceSupportTest` — expect **FAIL**
  (compile error: `AttendanceSupport` does not exist yet).

- [ ] **1.3** Implement `AttendanceSupport`.

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceSupport.java`:
  ```java
  package com.rcf.imas.modules.coordinator.service;

  import java.util.regex.Matcher;
  import java.util.regex.Pattern;

  /**
   * Time/duration helpers ported VERBATIM from the live Node source
   * (server/controllers/coordinator/attendanceController.js:257-307, attendanceController.js:275-281). Every
   * regex, every branch condition, and the AM/PM 12-hour conversion logic must match Node's behavior exactly --
   * these functions are reused across getOrFindSession, previewCSVAttendance, and commitCSVAttendance. Static,
   * stateless -- not a Spring bean.
   */
  public final class AttendanceSupport {

      private static final Pattern HHMM_OR_HHMMSS = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$");
      private static final Pattern AMPM = Pattern.compile("(\\d+):(\\d+)\\s*(AM|PM)", Pattern.CASE_INSENSITIVE);
      private static final Pattern HR = Pattern.compile("(\\d+)\\s*hr");
      private static final Pattern MIN = Pattern.compile("(\\d+)\\s*min");
      private static final Pattern SEC = Pattern.compile("(\\d+)\\s*sec");

      private AttendanceSupport() {}

      /** normalizeTimeToDB (attendanceController.js:284-307). */
      public static String normalizeTimeToDB(String raw) {
          if (raw == null || raw.trim().isEmpty() || raw.trim().equalsIgnoreCase("null")) {
              return "00:00:00";
          }
          // Node: raw.replace(/\u202f|\u00a0/g, " ").trim() -- strip narrow-no-break-space / nbsp
          String s = raw.replace('\u202f', ' ').replace('\u00a0', ' ').trim();

          Matcher hhmm = HHMM_OR_HHMMSS.matcher(s);
          if (hhmm.matches()) {
              return s.length() == 5 ? s + ":00" : s;
          }

          Matcher ampm = AMPM.matcher(s);
          if (ampm.find()) {
              int hrs = Integer.parseInt(ampm.group(1));
              int mins = Integer.parseInt(ampm.group(2));
              String suffix = ampm.group(3).toUpperCase();
              if (suffix.equals("PM") && hrs < 12) hrs += 12;
              if (suffix.equals("AM") && hrs == 12) hrs = 0;
              return String.format("%02d:%02d:00", hrs, mins);
          }

          return "00:00:00";
      }

      /** timeToMinutes (attendanceController.js:275-281). */
      public static int timeToMinutes(String raw) {
          String timeStr = normalizeTimeToDB(raw);
          if (timeStr == null || timeStr.equals("00:00:00")) return 0;
          String[] parts = timeStr.split(":");
          int h = Integer.parseInt(parts[0]);
          int m = Integer.parseInt(parts[1]);
          return h * 60 + m;
      }

      /** parseDurationToMinutes (attendanceController.js:258-272). */
      public static int parseDurationToMinutes(String raw) {
          if (raw == null || raw.trim().equalsIgnoreCase("null")) return 0;
          String s = raw.toLowerCase();
          double totalMinutes = 0;

          Matcher hr = HR.matcher(s);
          if (hr.find()) totalMinutes += Integer.parseInt(hr.group(1)) * 60;

          Matcher min = MIN.matcher(s);
          if (min.find()) totalMinutes += Integer.parseInt(min.group(1));

          Matcher sec = SEC.matcher(s);
          if (sec.find()) totalMinutes += Integer.parseInt(sec.group(1)) / 60.0;

          return (int) Math.round(totalMinutes);
      }
  }
  ```

- [ ] **1.4** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceSupportTest` — expect **PASS**.

- [ ] **1.5** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceSupport.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/service/AttendanceSupportTest.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): attendance time/duration helpers ported verbatim from Node

  normalizeTimeToDB/timeToMinutes/parseDurationToMinutes reproduce the live
  Node regexes exactly (nbsp stripping, AM/PM 12-hour conversion, hr/min/sec
  duration parsing) -- needed by session lookup, CSV preview, and CSV commit.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

- [ ] **1.6** Write the failing IT for session lookup / overlap / undo / bulk stub / sample CSV download.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceSessionOverlapUndoIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

  import com.rcf.imas.PgIntegrationTest;
  import com.rcf.imas.platform.security.JwtService;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class AttendanceSessionOverlapUndoIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965601,'coordUser965601','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965601,'Attendance Cohort 965601')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965601,'Attendance Batch 965601',965601)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965601,965601)").update();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965601,'Attendance Teacher 965601')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965601,'Attendance Classroom 965601',965601)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965601,965601)").update();

          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                  VALUES (965601, 965601, DATE '2026-07-06', '09:00:00', '10:00:00')
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

          coordToken = jwt.issueFinalToken("965601", "coordUser965601", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965601").update();
          jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965601").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965601").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965601").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965601").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965601").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965601").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965601").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965601").update();
      }

      @Test
      void getOrFindSession_findsExistingSessionByHHmmStartTime() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/session")
                  .param("classroom_id", "965601").param("session_date", "2026-07-06").param("start_time", "09:00")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.session_id").value(965601))
             .andExpect(jsonPath("$.start_time").value("09:00:00"))
             .andExpect(jsonPath("$.end_time").value("10:00:00"));
      }

      @Test
      void getOrFindSession_noMatchReturns200WithNullSessionId() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/session")
                  .param("classroom_id", "965601").param("session_date", "2026-07-07").param("start_time", "09:00")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.session_id").doesNotExist())
             .andExpect(jsonPath("$.session_id").value((Object) null));
      }

      @Test
      void checkOverlap_trueWhenRangesOverlap() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/check-overlap")
                  .param("classroomId", "965601").param("date", "2026-07-06")
                  .param("startTime", "09:30:00").param("endTime", "10:30:00")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.overlap").value(true))
             .andExpect(jsonPath("$.conflicts").doesNotExist());
      }

      @Test
      void checkOverlap_falseWhenNoOverlap() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/check-overlap")
                  .param("classroomId", "965601").param("date", "2026-07-06")
                  .param("startTime", "11:00:00").param("endTime", "12:00:00")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.overlap").value(false));
      }

      @Test
      void undo_deletesSessionAndAttendanceRows() throws Exception {
          jdbc.sql("""
                  INSERT INTO pp.student_attendance(session_id, student_id, status)
                  VALUES (965601, 1, 'PRESENT')
                  """).update();

          mvc.perform(post("/api/coordinator/attendance/undo")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"session_id\":965601}"))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.message").value("Undo Successful"));

          Long sessions = jdbc.sql("SELECT COUNT(*) FROM pp.class_session WHERE session_id = 965601").query(Long.class).single();
          Long attendance = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = 965601").query(Long.class).single();
          assertThat(sessions).isZero();
          assertThat(attendance).isZero();
      }

      @Test
      void bulk_isANoOpStub() throws Exception {
          mvc.perform(post("/api/coordinator/attendance/bulk")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"anything\":\"ignored\"}"))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.message").value("Bulk submission logic active"));
      }

      @Test
      void sampleCsvReference_downloadsAttachment() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/csv/reference")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sample_attendance.csv")))
             .andExpect(content().contentType("text/csv"));
      }

      @Test
      void unauthenticatedIsRejected() throws Exception {
          mvc.perform(get("/api/coordinator/attendance/session")
                  .param("classroom_id", "965601").param("session_date", "2026-07-06").param("start_time", "09:00"))
             .andExpect(status().isUnauthorized());
      }
  }
  ```

- [ ] **1.7** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceSessionOverlapUndoIT` — expect
  **FAIL** (`AttendanceController`/`AttendanceReadRepository`/`AttendanceWriteRepository` do not exist; no
  route mapped).

- [ ] **1.8** Create the sample CSV classpath resource.

  `imas-backend/src/main/resources/attendance-assets/sample_attendance.csv`:
  ```csv
  Name,User Email,User Type,Duration (Total Meeting),Time Joined,Time Left
  ,,,1 hr 25 min,,
  Asha Rani,asha.rani@example.com,Participant,1 hr 20 min,09:00 AM,10:20 AM
  Kiran Kumar,kiran.kumar@example.com,Participant,45 min,09:15 AM,10:00 AM
  Meena S,meena.s@example.com,Participant,20 min,09:45 AM,10:05 AM
  ```

- [ ] **1.9** Implement `AttendanceReadRepository` (session lookup + overlap check for this task; more
  methods added in Tasks 2/4).

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java`:
  ```java
  package com.rcf.imas.modules.coordinator.persistence;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  import java.util.List;
  import java.util.Map;
  import java.util.Optional;

  import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

  @Repository
  public class AttendanceReadRepository {

      private final JdbcClient jdbc;

      public AttendanceReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

      /** attendanceController.getOrFindSession (live, attendanceController.js:311-334). Empty Optional means
       *  "no matching session" -- the controller maps that to {session_id:null}, 200 (Node parity, not 404). */
      public Optional<Map<String, Object>> getOrFindSession(String classroomId, String sessionDate, String normalizedStartTime) {
          return jdbc.sql("""
                  SELECT session_id, start_time::text AS start_time, end_time::text AS end_time, duration_minutes
                  FROM pp.class_session
                  WHERE classroom_id = :classroomId::integer
                    AND session_date = :sessionDate::date
                    AND to_char(start_time, 'HH24:MI:SS') = :startTime
                  LIMIT 1
                  """)
                  .param("classroomId", classroomId)
                  .param("sessionDate", sessionDate)
                  .param("startTime", normalizedStartTime)
                  .query((rs, i) -> genericRow(rs)).optional();
      }

      /** attendanceController.checkOverlap (live, attendanceController.js:703-716). LIVE SOURCE returns ONLY
       *  {overlap:boolean} -- no "conflicts" array (see plan's Disagreements #1). */
      public boolean checkOverlap(String classroomId, String date, String startTime, String endTime) {
          Integer count = jdbc.sql("""
                  SELECT COUNT(*)::int FROM pp.class_session
                  WHERE classroom_id = :classroomId::integer
                    AND session_date = :date::date
                    AND (start_time, end_time) OVERLAPS (:startTime::time, :endTime::time)
                  """)
                  .param("classroomId", classroomId).param("date", date)
                  .param("startTime", startTime).param("endTime", endTime)
                  .query(Integer.class).single();
          return count != null && count > 0;
      }
  }
  ```

- [ ] **1.10** Implement `AttendanceWriteRepository` (undo for this task; `commitCsvAttendance` added in
  Task 3).

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceWriteRepository.java`:
  ```java
  package com.rcf.imas.modules.coordinator.persistence;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  @Repository
  public class AttendanceWriteRepository {

      private final JdbcClient jdbc;

      public AttendanceWriteRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

      /** attendanceController.undoLastAttendanceCommit (live, attendanceController.js:689-699). Node runs
       *  these as two sequential autocommit statements (not wrapped in BEGIN/COMMIT); ported the same way --
       *  student_attendance also cascades on class_session delete (ON DELETE CASCADE, V1__baseline.sql:1868)
       *  so the first DELETE is technically redundant once the second runs, but kept for exact parity and to
       *  tolerate a session_id with no matching class_session row. */
      public void undoAttendance(String sessionId) {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = :sessionId::integer")
                  .param("sessionId", sessionId).update();
          jdbc.sql("DELETE FROM pp.class_session WHERE session_id = :sessionId::integer")
                  .param("sessionId", sessionId).update();
      }
  }
  ```

- [ ] **1.11** Implement `AttendanceController` (session/overlap/undo/bulk/sample-csv for this task; preview,
  commit, fetch added in Tasks 2-4).

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java`:
  ```java
  package com.rcf.imas.modules.coordinator.web;

  import com.rcf.imas.modules.coordinator.persistence.AttendanceReadRepository;
  import com.rcf.imas.modules.coordinator.persistence.AttendanceWriteRepository;
  import com.rcf.imas.modules.coordinator.service.AttendanceSupport;
  import com.rcf.imas.platform.error.ApiException;
  import org.springframework.core.io.ClassPathResource;
  import org.springframework.core.io.Resource;
  import org.springframework.http.HttpHeaders;
  import org.springframework.http.MediaType;
  import org.springframework.http.ResponseEntity;
  import org.springframework.security.access.prepost.PreAuthorize;
  import org.springframework.web.bind.annotation.*;

  import java.util.HashMap;
  import java.util.Map;

  @RestController
  @RequestMapping("/api/coordinator/attendance")
  @PreAuthorize("isAuthenticated()")   // Node: attendanceController routes all use `authenticate`
  public class AttendanceController {

      private final AttendanceReadRepository reads;
      private final AttendanceWriteRepository writes;

      public AttendanceController(AttendanceReadRepository reads, AttendanceWriteRepository writes) {
          this.reads = reads;
          this.writes = writes;
      }

      /** getOrFindSession -- {session_id:null} 200 (NOT 404) when nothing matches, Node parity. */
      @GetMapping("/session")
      public Map<String, Object> getOrFindSession(@RequestParam("classroom_id") String classroomId,
                                                    @RequestParam("session_date") String sessionDate,
                                                    @RequestParam("start_time") String startTime) {
          try {
              String normalized = AttendanceSupport.normalizeTimeToDB(startTime);
              return reads.getOrFindSession(classroomId, sessionDate, normalized)
                      .map(row -> row)
                      .orElseGet(() -> {
                          Map<String, Object> nullSession = new HashMap<>();
                          nullSession.put("session_id", null);
                          return nullSession;
                      });
          } catch (Exception e) {
              throw ApiException.message(500, e.getMessage());
          }
      }

      /** checkOverlap -- {overlap:boolean} ONLY, see plan Disagreements #1. */
      @GetMapping("/check-overlap")
      public Map<String, Object> checkOverlap(@RequestParam("classroomId") String classroomId,
                                                 @RequestParam("date") String date,
                                                 @RequestParam("startTime") String startTime,
                                                 @RequestParam("endTime") String endTime) {
          boolean overlap = reads.checkOverlap(classroomId, date, startTime, endTime);
          return Map.of("overlap", overlap);
      }

      /** undoLastAttendanceCommit. */
      @PostMapping("/undo")
      public Map<String, Object> undo(@RequestBody Map<String, Object> body) {
          try {
              writes.undoAttendance(String.valueOf(body.get("session_id")));
              return Map.of("message", "Undo Successful");
          } catch (Exception e) {
              throw ApiException.message(500, e.getMessage());
          }
      }

      /** submitBulkAttendance -- STUB, does nothing (Firm Decision 5 / ground truth §8.4). */
      @PostMapping("/bulk")
      public Map<String, Object> bulk(@RequestBody(required = false) Map<String, Object> ignoredBody) {
          return Map.of("message", "Bulk submission logic active");
      }

      /** downloadSampleCSV -- bundled classpath resource (Firm Decision 6, original Node asset absent). */
      @GetMapping("/csv/reference")
      public ResponseEntity<Resource> sampleCsv() {
          Resource resource = new ClassPathResource("attendance-assets/sample_attendance.csv");
          return ResponseEntity.ok()
                  .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample_attendance.csv\"")
                  .contentType(MediaType.parseMediaType("text/csv"))
                  .body(resource);
      }
  }
  ```

- [ ] **1.12** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceSessionOverlapUndoIT` —
  expect **PASS**.

- [ ] **1.13** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceWriteRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java imas-backend/src/main/resources/attendance-assets/sample_attendance.csv imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceSessionOverlapUndoIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): attendance session lookup, overlap check, undo, bulk stub, sample CSV

  Ports endpoints #15,18-20,22 of the Node coordinator attendance surface.
  check-overlap returns {overlap} only (no conflicts array -- that shape
  belongs to timetable's checkConflict, not this endpoint). bulk stays a
  no-op stub matching Node's own unimplemented behavior.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2 — CSV preview (#16)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceCsvPreviewService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvPreviewIT.java`

Seed id range: `965701`-`965799`.

- [ ] **2.1** Write the failing IT for CSV preview.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvPreviewIT.java`:
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
  import org.springframework.mock.web.MockMultipartFile;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class AttendanceCsvPreviewIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965701,'coordUser965701','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965701,'CSV Preview Cohort 965701')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965701,'CSV Preview Batch 965701',965701)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965701,965701)").update();

          // ACTIVE, matches CSV row "Asha Rani K" at 80% -> PRESENT
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                  VALUES (965711,'Asha Rani',101,965701,'F','ACTIVE')
                  """).update();
          // ACTIVE, matches CSV row "Kiran Kumar M" at 37.5% -> ABSENT (below 40)
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                  VALUES (965712,'Kiran Kumar',102,965701,'M','ACTIVE')
                  """).update();
          // ACTIVE, matches CSV row at 50% -> LATE JOINED
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                  VALUES (965713,'Meena S',103,965701,'F','ACTIVE')
                  """).update();
          // ACTIVE, no CSV match at all -> ABSENT, duration 0
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                  VALUES (965714,'Zoya Not In Csv',104,965701,'F','ACTIVE')
                  """).update();
          // INACTIVE, matches CSV row "Ravi Teja" -> inactiveStudents[], excluded from previewData
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, enr_id, batch_id, gender, active_yn)
                  VALUES (965715,'Ravi Teja',105,965701,'M','INACTIVE')
                  """).update();
          jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

          coordToken = jwt.issueFinalToken("965701", "coordUser965701", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965701").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965701").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965701").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965701").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965701").update();
      }

      /**
       * Total meeting duration (row index 1, col D) = 80 minutes ("1 hr 20 min").
       * Data rows (index >= 2), column A=name, D=duration, E=time_joined, F=time_exited:
       *  - "Asha Rani K"   80 min column D duplicated across 2 rows (64 min, then 80 min) -> keep LARGEST (80) -> 80/80=100% -> PRESENT
       *  - "Kiran Kumar M" 30 min -> 30/80=37.5% -> ABSENT (below 40)
       *  - "Meena S Extra" 40 min -> 40/80=50% -> LATE JOINED
       *  - "Ravi Teja X"   80 min -> matches INACTIVE student Ravi Teja -> inactiveStudents[]
       *  - "Unmatched Guy" 10 min -> no db student's name is a substring of this csv key -> unmatchedStudents[]
       * "Zoya Not In Csv" has no CSV row at all -> previewData ABSENT, duration 0.
       */
      private String csvContent() {
          return "Name,User Email,User Type,Duration (Total Meeting),Time Joined,Time Left\n"
               + ",,,1 hr 20 min,,\n"
               + "Asha Rani K,a@example.com,Participant,64 min,09:00 AM,10:04 AM\n"
               + "Asha Rani K,a@example.com,Participant,1 hr 20 min,09:00 AM,10:20 AM\n"
               + "Kiran Kumar M,k@example.com,Participant,30 min,09:05 AM,09:35 AM\n"
               + "Meena S Extra,m@example.com,Participant,40 min,09:10 AM,09:50 AM\n"
               + "Ravi Teja X,r@example.com,Participant,1 hr 20 min,09:00 AM,10:20 AM\n"
               + "Unmatched Guy,u@example.com,Participant,10 min,09:00 AM,09:10 AM\n";
      }

      private MockMultipartFile csv() {
          return new MockMultipartFile("file", "attendance.csv", "text/csv", csvContent().getBytes());
      }

      @Test
      void previewPartitionsAndScoresCorrectly() throws Exception {
          mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                  .file(csv())
                  .param("batch_id", "965701")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.previewData", org.hamcrest.Matchers.hasSize(4)))
             // previewData sorted by student_name (locale compare): Asha Rani, Kiran Kumar, Meena S, Zoya Not In Csv
             .andExpect(jsonPath("$.previewData[0].student_name").value("Asha Rani"))
             .andExpect(jsonPath("$.previewData[0].student_id").value("965711"))
             .andExpect(jsonPath("$.previewData[0].duration_minutes").value(80))
             .andExpect(jsonPath("$.previewData[0].status").value("PRESENT"))
             .andExpect(jsonPath("$.previewData[1].student_name").value("Kiran Kumar"))
             .andExpect(jsonPath("$.previewData[1].duration_minutes").value(30))
             .andExpect(jsonPath("$.previewData[1].status").value("ABSENT"))
             .andExpect(jsonPath("$.previewData[2].student_name").value("Meena S"))
             .andExpect(jsonPath("$.previewData[2].duration_minutes").value(40))
             .andExpect(jsonPath("$.previewData[2].status").value("LATE JOINED"))
             .andExpect(jsonPath("$.previewData[3].student_name").value("Zoya Not In Csv"))
             .andExpect(jsonPath("$.previewData[3].duration_minutes").value(0))
             .andExpect(jsonPath("$.previewData[3].status").value("ABSENT"))
             .andExpect(jsonPath("$.previewData[3].time_joined").value("N/A"))
             .andExpect(jsonPath("$.inactiveStudents", org.hamcrest.Matchers.hasSize(1)))
             .andExpect(jsonPath("$.inactiveStudents[0].student_name").value("Ravi Teja"))
             .andExpect(jsonPath("$.inactiveStudents[0].duration_minutes").value(80))
             .andExpect(jsonPath("$.unmatchedStudents", org.hamcrest.Matchers.hasSize(1)))
             .andExpect(jsonPath("$.unmatchedStudents[0].student_name").value("Unmatched Guy"))
             .andExpect(jsonPath("$.unmatchedStudents[0].duration_minutes").value(10));
      }

      @Test
      void noFileIs400() throws Exception {
          mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                  .param("batch_id", "965701")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isBadRequest())
             .andExpect(jsonPath("$.message").value("No file uploaded"));
      }

      @Test
      void tooFewRowsIs400() throws Exception {
          MockMultipartFile tiny = new MockMultipartFile("file", "tiny.csv", "text/csv",
                  "Name,User Email,User Type,Duration (Total Meeting),Time Joined,Time Left\n".getBytes());
          mvc.perform(multipart("/api/coordinator/attendance/csv/preview")
                  .file(tiny)
                  .param("batch_id", "965701")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isBadRequest())
             .andExpect(jsonPath("$.message").value("CSV missing data rows."));
      }
  }
  ```

- [ ] **2.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceCsvPreviewIT` — expect **FAIL**
  (no `/csv/preview` route mapped, `AttendanceCsvPreviewService` does not exist).

- [ ] **2.3** Add `attendanceStudentsByBatch` to `AttendanceReadRepository` (append this method inside the
  existing class from Task 1, alongside `getOrFindSession`/`checkOverlap`):
  ```java
      /** previewCSVAttendance's dbStudentsRes query (live, attendanceController.js:498-503). Scoped by
       *  batch_id (NOT classroom_id -- see plan Disagreements #2, the live handler queries by batch, matching
       *  ported here exactly). */
      public java.util.List<Map<String, Object>> attendanceStudentsByBatch(String batchId) {
          return jdbc.sql("""
                  SELECT student_id, student_name, enr_id, active_yn
                  FROM pp.student_master
                  WHERE batch_id = :batchId::integer
                  """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
      }
  ```

- [ ] **2.4** Implement `AttendanceCsvPreviewService` — the fuzzy-match algorithm.

  `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceCsvPreviewService.java`:
  ```java
  package com.rcf.imas.modules.coordinator.service;

  import com.rcf.imas.modules.coordinator.persistence.AttendanceReadRepository;
  import com.rcf.imas.platform.error.ApiException;
  import org.apache.commons.csv.CSVFormat;
  import org.apache.commons.csv.CSVParser;
  import org.apache.commons.csv.CSVRecord;
  import org.springframework.stereotype.Service;
  import org.springframework.web.multipart.MultipartFile;

  import java.io.IOException;
  import java.io.InputStreamReader;
  import java.io.UncheckedIOException;
  import java.nio.charset.StandardCharsets;
  import java.util.ArrayList;
  import java.util.LinkedHashMap;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;

  /**
   * previewCSVAttendance ported verbatim (live, attendanceController.js:474-614). In-memory only, NO DB write.
   * Positional (array-of-arrays) CSV parse -- Zoom export shape, row 0 = header, row 1 col D (index 3) = total
   * meeting duration, data rows from index 2, columns A=name(0), D=duration(3), E=time_joined(4), F=time_exited(5).
   * Java reads the MultipartFile stream directly (Firm Decision 4) instead of Node's write-to-disk-then-unlink
   * dance -- wire-invisible, and avoids Node's real leak on early-return/exception paths.
   */
  @Service
  public class AttendanceCsvPreviewService {

      private record CsvRow(String originalName, int durationMinutes, String timeJoined, String timeExited) {}

      private final AttendanceReadRepository reads;

      public AttendanceCsvPreviewService(AttendanceReadRepository reads) { this.reads = reads; }

      public Map<String, Object> preview(MultipartFile file, String batchId) {
          if (file == null || file.isEmpty()) {
              throw ApiException.message(400, "No file uploaded");
          }

          List<CSVRecord> records;
          try (InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
              records = CSVParser.parse(reader, CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build())
                      .getRecords();
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }

          if (records.size() < 2) {
              throw ApiException.message(400, "CSV missing data rows.");
          }

          String summaryDurationRaw = cell(records.get(1), 3);
          int totalCSVDurationMins = AttendanceSupport.parseDurationToMinutes(summaryDurationRaw);

          // csvMap: keyed by name.toLowerCase(), keeping the LARGEST-duration row per key. LinkedHashMap
          // preserves insertion order -- required for "first match wins" substring search below.
          Map<String, CsvRow> csvMap = new LinkedHashMap<>();
          for (int i = 2; i < records.size(); i++) {
              CSVRecord row = records.get(i);
              String rawName = cell(row, 0);
              if (rawName == null || rawName.isBlank()) continue;
              rawName = rawName.trim();
              String key = rawName.toLowerCase();
              int duration = AttendanceSupport.parseDurationToMinutes(cell(row, 3));

              CsvRow existing = csvMap.get(key);
              if (existing == null || existing.durationMinutes() < duration) {
                  csvMap.put(key, new CsvRow(rawName, duration, cell(row, 4), cell(row, 5)));
              }
          }

          List<Map<String, Object>> previewData = new ArrayList<>();
          List<Map<String, Object>> unmatchedStudents = new ArrayList<>();
          List<Map<String, Object>> inactiveStudents = new ArrayList<>();
          Set<String> matchedCSVKeys = new LinkedHashSet<>();

          for (Map<String, Object> student : reads.attendanceStudentsByBatch(batchId)) {
              String studentName = String.valueOf(student.get("student_name"));
              String dbNameClean = studentName.trim().toLowerCase();

              String matchedKey = null;
              CsvRow matched = null;
              for (Map.Entry<String, CsvRow> e : csvMap.entrySet()) {
                  if (e.getKey().contains(dbNameClean)) {
                      matchedKey = e.getKey();
                      matched = e.getValue();
                      break;
                  }
              }

              boolean active = "ACTIVE".equals(student.get("active_yn"));
              if (!active) {
                  if (matched != null) {
                      Map<String, Object> row = new LinkedHashMap<>();
                      row.put("student_name", studentName);
                      row.put("duration_minutes", matched.durationMinutes());
                      inactiveStudents.add(row);
                      matchedCSVKeys.add(matchedKey);
                  }
                  continue;
              }

              Map<String, Object> row = new LinkedHashMap<>();
              row.put("student_id", student.get("student_id"));
              row.put("student_name", studentName);
              row.put("enr_id", student.get("enr_id"));
              if (matched != null) {
                  double pct = totalCSVDurationMins > 0
                          ? (matched.durationMinutes() / (double) totalCSVDurationMins) * 100 : 0;
                  row.put("duration_minutes", matched.durationMinutes());
                  row.put("time_joined", matched.timeJoined());
                  row.put("time_exited", matched.timeExited());
                  row.put("status", pct >= 75 ? "PRESENT" : (pct >= 40 ? "LATE JOINED" : "ABSENT"));
                  matchedCSVKeys.add(matchedKey);
              } else {
                  row.put("duration_minutes", 0);
                  row.put("time_joined", "N/A");
                  row.put("time_exited", "N/A");
                  row.put("status", "ABSENT");
              }
              previewData.add(row);
          }

          for (Map.Entry<String, CsvRow> e : csvMap.entrySet()) {
              if (!matchedCSVKeys.contains(e.getKey())) {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("student_name", e.getValue().originalName());
                  row.put("duration_minutes", e.getValue().durationMinutes());
                  unmatchedStudents.add(row);
              }
          }

          previewData.sort((a, b) -> String.valueOf(a.get("student_name"))
                  .compareTo(String.valueOf(b.get("student_name"))));

          Map<String, Object> response = new LinkedHashMap<>();
          response.put("previewData", previewData);
          response.put("unmatchedStudents", unmatchedStudents);
          response.put("inactiveStudents", inactiveStudents);
          return response;
      }

      private static String cell(CSVRecord row, int index) {
          return index < row.size() ? row.get(index) : null;
      }
  }
  ```

  > Note on `String.compareTo` vs. Node's `localeCompare`: for the plain-ASCII names used across this module's
  > test data and typical Kannada-English-mixed rosters rendered in Latin script, `String.compareTo` and
  > `localeCompare` agree. If diacritics/non-Latin ordering ever matters, swap in `java.text.Collator.getInstance()`
  > -- flagged here rather than silently deviating from a simple, predictable sort.

- [ ] **2.5** Add the `/csv/preview` endpoint to `AttendanceController` (append inside the existing class from
  Task 1; also update the constructor to accept the new service and add the import):
  ```java
      // add field:
      private final com.rcf.imas.modules.coordinator.service.AttendanceCsvPreviewService csvPreviewService;

      // update constructor signature to:
      public AttendanceController(AttendanceReadRepository reads, AttendanceWriteRepository writes,
                                    com.rcf.imas.modules.coordinator.service.AttendanceCsvPreviewService csvPreviewService) {
          this.reads = reads;
          this.writes = writes;
          this.csvPreviewService = csvPreviewService;
      }

      /** previewCSVAttendance -- multipart CSV, in-memory fuzzy match, NO DB write. */
      @PostMapping(value = "/csv/preview", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
      public Map<String, Object> previewCsv(
              @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
              @RequestParam("batch_id") String batchId) {
          return csvPreviewService.preview(file, batchId);
      }
  ```

- [ ] **2.6** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceCsvPreviewIT` — expect
  **PASS**.

- [ ] **2.7** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/AttendanceCsvPreviewService.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvPreviewIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): attendance CSV preview -- in-memory fuzzy match, no DB write

  Ports endpoint #16. Positional (array-of-arrays) CSV parse over the Zoom
  export shape, largest-duration-per-name dedup, substring fuzzy match
  (dbName is a substring of a csv key, first match wins by insertion
  order), 75/40 PRESENT/LATE-JOINED/ABSENT thresholds, and the three-way
  previewData/unmatchedStudents/inactiveStudents partition -- all ported
  verbatim from attendanceController.js:474-614.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 3 — CSV commit (#17), `@Transactional`

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceWriteRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvCommitIT.java`

Seed id range: `965801`-`965899`.

- [ ] **3.1** Write the failing IT for CSV commit.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvCommitIT.java`:
  ```java
  package com.rcf.imas.modules.coordinator;

  import com.rcf.imas.PgIntegrationTest;
  import com.rcf.imas.platform.security.JwtService;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.web.servlet.MockMvc;

  import java.math.BigDecimal;
  import java.util.List;
  import java.util.Map;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @AutoConfigureMockMvc
  class AttendanceCsvCommitIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;
      @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965801,'coordUser965801','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965801,'CSV Commit Cohort 965801')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965801,'CSV Commit Batch 965801',965801)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965801,965801)").update();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965801,'CSV Commit Teacher 965801')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965801,'CSV Commit Classroom 965801',965801)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965801,965801)").update();

          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                  VALUES (965811,'Commit Student One',965801,'F','ACTIVE')
                  """).update();
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn)
                  VALUES (965812,'Commit Student Two',965801,'M','ACTIVE')
                  """).update();
          jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

          coordToken = jwt.issueFinalToken("965801", "coordUser965801", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (SELECT session_id FROM pp.class_session WHERE classroom_id = 965801)").update();
          jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965801").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965801").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965801").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965801").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965801").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965801").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965801").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965801").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965801").update();
      }

      private Map<String, Object> commitBody() {
          // Session 09:00-10:00 = 60 total minutes. Student One: 45 min -> 75% capped-nowhere -> PRESENT sent as-is.
          // Student Two: 60 min -> 100%.
          return Map.of(
                  "session_date", "2026-07-08",
                  "classroom_id", "965801",
                  "start_time", "09:00",
                  "end_time", "10:00",
                  "previewData", List.of(
                          Map.of("student_id", "965811", "student_name", "Commit Student One",
                                  "duration_minutes", 45, "time_joined", "09:00 AM", "time_exited", "09:45 AM",
                                  "status", "PRESENT"),
                          Map.of("student_id", "965812", "student_name", "Commit Student Two",
                                  "duration_minutes", 60, "time_joined", "09:00 AM", "time_exited", "10:00 AM",
                                  "status", "PRESENT")
                  )
          );
      }

      @Test
      void commitCreatesSessionAndAttendanceRows() throws Exception {
          String body = json.writeValueAsString(commitBody());

          mvc.perform(post("/api/coordinator/attendance/csv/commit")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON).content(body))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$.session_id").exists());

          Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
          assertThat(sessionId).isNotNull();

          Long teacherId = jdbc.sql("SELECT teacher_id FROM pp.class_session WHERE session_id = ?", sessionId).query(Long.class).single();
          assertThat(teacherId).isEqualTo(965801L);

          Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?", sessionId).query(Long.class).single();
          assertThat(attendanceRows).isEqualTo(2L);

          BigDecimal pctOne = jdbc.sql("SELECT attendance_percent FROM pp.student_attendance WHERE session_id = ? AND student_id = 965811", sessionId)
                  .query(BigDecimal.class).single();
          assertThat(pctOne).isEqualByComparingTo("75.00");

          BigDecimal pctTwo = jdbc.sql("SELECT attendance_percent FROM pp.student_attendance WHERE session_id = ? AND student_id = 965812", sessionId)
                  .query(BigDecimal.class).single();
          assertThat(pctTwo).isEqualByComparingTo("100.00");
      }

      @Test
      void commitTwiceIsIdempotentViaOnConflictUpdate() throws Exception {
          String body = json.writeValueAsString(commitBody());

          mvc.perform(post("/api/coordinator/attendance/csv/commit")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON).content(body))
             .andExpect(status().isOk());

          // second commit, same session window, Student One's status flips to ABSENT/duration 0
          Map<String, Object> secondBody = Map.of(
                  "session_date", "2026-07-08",
                  "classroom_id", "965801",
                  "start_time", "09:00",
                  "end_time", "10:00",
                  "previewData", List.of(
                          Map.of("student_id", "965811", "student_name", "Commit Student One",
                                  "duration_minutes", 0, "time_joined", "N/A", "time_exited", "N/A",
                                  "status", "ABSENT"),
                          Map.of("student_id", "965812", "student_name", "Commit Student Two",
                                  "duration_minutes", 60, "time_joined", "09:00 AM", "time_exited", "10:00 AM",
                                  "status", "PRESENT")
                  )
          );

          mvc.perform(post("/api/coordinator/attendance/csv/commit")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(secondBody)))
             .andExpect(status().isOk());

          Long sessionCount = jdbc.sql("SELECT COUNT(*) FROM pp.class_session WHERE classroom_id = 965801").query(Long.class).single();
          assertThat(sessionCount).isEqualTo(1L);   // ON CONFLICT DO UPDATE, not a second row

          Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
          Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?", sessionId).query(Long.class).single();
          assertThat(attendanceRows).isEqualTo(2L);   // no duplicate rows, ON CONFLICT DO UPDATE

          String statusOne = jdbc.sql("SELECT status FROM pp.student_attendance WHERE session_id = ? AND student_id = 965811", sessionId)
                  .query(String.class).single();
          assertThat(statusOne).isEqualTo("ABSENT");
      }

      @Test
      void skipsPreviewRowsWithoutStudentId() throws Exception {
          Map<String, Object> body = Map.of(
                  "session_date", "2026-07-08",
                  "classroom_id", "965801",
                  "start_time", "09:00",
                  "end_time", "10:00",
                  "previewData", List.of(
                          Map.of("student_name", "No Id Row", "duration_minutes", 10, "status", "ABSENT")
                  )
          );
          mvc.perform(post("/api/coordinator/attendance/csv/commit")
                  .header("Authorization", "Bearer " + coordToken)
                  .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
             .andExpect(status().isOk());

          Integer sessionId = jdbc.sql("SELECT session_id FROM pp.class_session WHERE classroom_id = 965801").query(Integer.class).single();
          Long attendanceRows = jdbc.sql("SELECT COUNT(*) FROM pp.student_attendance WHERE session_id = ?", sessionId).query(Long.class).single();
          assertThat(attendanceRows).isZero();
      }
  }
  ```

- [ ] **3.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceCsvCommitIT` — expect **FAIL**
  (no `/csv/commit` route mapped).

- [ ] **3.3** Add `commitCsvAttendance` to `AttendanceWriteRepository` (append inside the existing class from
  Task 1, alongside `undoAttendance`; add the needed imports — `java.util.List`, `java.util.Map`,
  `org.springframework.transaction.annotation.Transactional`):
  ```java
      /**
       * commitCSVAttendance (live, attendanceController.js:617-685) + getOrCreateSession (live,
       * attendanceModel.js:50-80), fused into ONE @Transactional method on ONE connection (Firm Decision 2 /
       * ground truth §7.2, §8.2: Node's getOrCreateSession uses the module-level `pool`, not the caller's
       * `client`, so the session INSERT commits independently of the surrounding BEGIN/COMMIT/ROLLBACK -- a
       * real atomicity hole this Java port deliberately closes). Both ON CONFLICT clauses are ported VERBATIM
       * (Firm Decision 1) -- class_session_classroom_id_session_date_start_time_end_time_key and
       * student_attendance_session_id_student_id_key both exist in V1__baseline.sql (lines 1293, 1440).
       */
      @Transactional
      public Integer commitCsvAttendance(String classroomId, String sessionDate,
                                           String normalizedStartTime, String normalizedEndTime,
                                           List<Map<String, Object>> previewData) {
          Long teacherId = jdbc.sql("SELECT teacher_id FROM pp.classroom WHERE classroom_id = :classroomId::integer")
                  .param("classroomId", classroomId).query(Long.class).optional().orElse(null);

          Integer sessionId = jdbc.sql("""
                  INSERT INTO pp.class_session (classroom_id, session_date, start_time, end_time, teacher_id)
                  VALUES (:classroomId::integer, :sessionDate::date, :startTime::time, :endTime::time, :teacherId::integer)
                  ON CONFLICT (classroom_id, session_date, start_time, end_time)
                  DO UPDATE SET teacher_id = EXCLUDED.teacher_id, updated_at = CURRENT_TIMESTAMP
                  RETURNING session_id
                  """)
                  .param("classroomId", classroomId).param("sessionDate", sessionDate)
                  .param("startTime", normalizedStartTime).param("endTime", normalizedEndTime)
                  .param("teacherId", teacherId)
                  .query(Integer.class).single();

          int startMins = AttendanceSupport.timeToMinutes(normalizedStartTime);
          int endMins = AttendanceSupport.timeToMinutes(normalizedEndTime);
          int totalSessionMins = endMins > startMins ? (endMins - startMins) : 0;

          for (Map<String, Object> r : previewData) {
              Object studentId = r.get("student_id");
              if (studentId == null) continue;

              Number durationObj = (Number) r.getOrDefault("duration_minutes", 0);
              int durationMinutes = durationObj == null ? 0 : durationObj.intValue();
              double attPct = totalSessionMins > 0 ? (durationMinutes / (double) totalSessionMins) * 100 : 0;
              double cappedPct = Math.min(100, Math.round(attPct * 100.0) / 100.0);

              jdbc.sql("""
                      INSERT INTO pp.student_attendance
                          (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent)
                      VALUES (:sessionId, :studentId::numeric, :status, :timeJoined::time, :timeExited::time, :duration, :pct)
                      ON CONFLICT (session_id, student_id)
                      DO UPDATE SET
                          status = EXCLUDED.status,
                          duration_minutes = EXCLUDED.duration_minutes,
                          time_joined = EXCLUDED.time_joined,
                          time_exited = EXCLUDED.time_exited,
                          attendance_percent = EXCLUDED.attendance_percent,
                          updated_at = NOW()
                      """)
                      .param("sessionId", sessionId)
                      .param("studentId", String.valueOf(studentId))
                      .param("status", r.get("status"))
                      .param("timeJoined", AttendanceSupport.normalizeTimeToDB((String) r.get("time_joined")))
                      .param("timeExited", AttendanceSupport.normalizeTimeToDB((String) r.get("time_exited")))
                      .param("duration", durationMinutes)
                      .param("pct", cappedPct)
                      .update();
          }

          return sessionId;
      }
  ```
  Also add the import `com.rcf.imas.modules.coordinator.service.AttendanceSupport` at the top of
  `AttendanceWriteRepository.java`.

- [ ] **3.4** Add the `/csv/commit` endpoint to `AttendanceController` (append inside the existing class):
  ```java
      /** commitCSVAttendance -- @Transactional write, see Firm Decision 2. */
      @SuppressWarnings("unchecked")
      @PostMapping("/csv/commit")
      public Map<String, Object> commitCsv(@RequestBody Map<String, Object> body) {
          try {
              String classroomId = String.valueOf(body.get("classroom_id"));
              String sessionDate = String.valueOf(body.get("session_date"));
              String startTime = String.valueOf(body.get("start_time"));
              String endTime = String.valueOf(body.get("end_time"));
              List<Map<String, Object>> previewData = (List<Map<String, Object>>) body.get("previewData");

              Integer sessionId = writes.commitCsvAttendance(
                      classroomId, sessionDate,
                      AttendanceSupport.normalizeTimeToDB(startTime), AttendanceSupport.normalizeTimeToDB(endTime),
                      previewData == null ? java.util.List.of() : previewData);

              return Map.of("session_id", sessionId);
          } catch (Exception e) {
              throw ApiException.message(500, e.getMessage());
          }
      }
  ```
  Add the import `java.util.List` at the top of `AttendanceController.java` if not already present.

- [ ] **3.5** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceCsvCommitIT` — expect
  **PASS**.

- [ ] **3.6** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceWriteRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceCsvCommitIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): attendance CSV commit -- transactional session+attendance upsert

  Ports endpoint #17. Fuses Node's split getOrCreateSession (own pool
  connection) + commitCSVAttendance (separate client transaction) into one
  @Transactional method on one connection, closing a real atomicity hole
  (ground truth §7.2/§8.2). Both ON CONFLICT clauses ported verbatim --
  the matching unique constraints already exist in V1__baseline.sql
  (class_session and student_attendance), contrary to the ground-truth
  doc's mistaken "broken ON CONFLICT" claim.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 4 — `fetchAttendance` manual-entry tab (#21)

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceFetchIT.java`

Seed id range: `965901`-`965999`.

- [ ] **4.1** Write the failing IT for `fetchAttendance`.

  `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceFetchIT.java`:
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
  class AttendanceFetchIT extends PgIntegrationTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired JwtService jwt;

      String coordToken;

      @BeforeEach
      void seed() {
          cleanup();
          jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (965901,'coordUser965901','x','N')").update();
          jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (965901,'Fetch Cohort 965901')").update();
          jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (965901,'Fetch Batch 965901',965901)").update();
          jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (965901,965901)").update();

          jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (965901,'Fetch Teacher 965901')").update();
          jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, teacher_id) VALUES (965901,'Fetch Classroom 965901',965901)").update();
          jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
          jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (965901,965901)").update();

          jdbc.sql("""
                  INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                  VALUES (965901, 965901, DATE '2026-07-09', '09:00:00', '10:00:00')
                  """).update();
          jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

          // ACTIVE student, marked PRESENT for this session -> appears with db_status
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                  VALUES (965911,'Fetch Student Active Marked','9000000001','a@example.com',965901,'F','ACTIVE')
                  """).update();
          // ACTIVE student, not yet marked -> appears with db_status null
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                  VALUES (965912,'Fetch Student Active Unmarked','9000000002','b@example.com',965901,'M','ACTIVE')
                  """).update();
          // INACTIVE student, NOT marked for this session -> excluded entirely
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                  VALUES (965913,'Fetch Student Inactive Unmarked','9000000003','c@example.com',965901,'F','INACTIVE')
                  """).update();
          // INACTIVE student, but WAS marked for this session -> still appears (Node's OR sa.session_id IS NOT NULL)
          jdbc.sql("""
                  INSERT INTO pp.student_master(student_id, student_name, contact_no1, student_email, batch_id, gender, active_yn)
                  VALUES (965914,'Fetch Student Inactive Marked','9000000004','d@example.com',965901,'M','INACTIVE')
                  """).update();
          jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901, 965911, 'PRESENT')").update();
          jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (965901, 965914, 'LATE JOINED')").update();

          coordToken = jwt.issueFinalToken("965901", "coordUser965901", "COORDINATOR");
      }

      @AfterEach
      void tearDown() { cleanup(); }

      private void cleanup() {
          jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 965901").update();
          jdbc.sql("DELETE FROM pp.class_session WHERE classroom_id = 965901").update();
          jdbc.sql("DELETE FROM pp.student_master WHERE batch_id = 965901").update();
          jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 965901").update();
          jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 965901").update();
          jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 965901").update();
          jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 965901").update();
          jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 965901").update();
          jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 965901").update();
          jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 965901").update();
      }

      @Test
      void fetchReturnsActivePlusPreviouslyMarkedStudentsOrderedByName() throws Exception {
          mvc.perform(get("/api/coordinator/attendance")
                  .param("session_id", "965901").param("batchId", "965901")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
             // ORDER BY sm.student_name -- alphabetical
             .andExpect(jsonPath("$[0].student_name").value("Fetch Student Active Marked"))
             .andExpect(jsonPath("$[0].db_status").value("PRESENT"))
             .andExpect(jsonPath("$[0].student_id").value("965911"))
             .andExpect(jsonPath("$[1].student_name").value("Fetch Student Active Unmarked"))
             .andExpect(jsonPath("$[1].db_status").doesNotExist())
             .andExpect(jsonPath("$[2].student_name").value("Fetch Student Inactive Marked"))
             .andExpect(jsonPath("$[2].db_status").value("LATE JOINED"));
      }

      @Test
      void fetchWithoutSessionIdStillReturnsActiveStudentsWithNullStatus() throws Exception {
          mvc.perform(get("/api/coordinator/attendance")
                  .param("batchId", "965901")
                  .header("Authorization", "Bearer " + coordToken))
             .andExpect(status().isOk())
             .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
             .andExpect(jsonPath("$[0].student_name").value("Fetch Student Active Marked"))
             .andExpect(jsonPath("$[0].db_status").doesNotExist())
             .andExpect(jsonPath("$[1].student_name").value("Fetch Student Active Unmarked"));
      }
  }
  ```

- [ ] **4.2** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceFetchIT` — expect **FAIL**
  (no `GET /api/coordinator/attendance` route mapped).

- [ ] **4.3** Add `fetchAttendance` to `AttendanceReadRepository` (append inside the existing class):
  ```java
      /** attendanceController.fetchAttendance (live, attendanceController.js:338-366). sessionId may be null
       *  (Node: `session_id || null`) -- comparison against NULL is always unknown/false in SQL, matching
       *  Node's pg parameterization exactly; ACTIVE students always show, INACTIVE only if already marked. */
      public List<Map<String, Object>> fetchAttendance(String sessionId, String batchId) {
          return jdbc.sql("""
                  SELECT sm.student_id, sm.student_name, sm.enr_id, sm.contact_no1, sm.student_email,
                         sm.active_yn, sa.status AS db_status
                  FROM pp.student_master sm
                  LEFT JOIN pp.student_attendance sa
                         ON sa.student_id = sm.student_id AND sa.session_id = :sessionId::integer
                  WHERE sm.batch_id = :batchId::integer
                    AND (sm.active_yn = 'ACTIVE' OR sa.session_id IS NOT NULL)
                  ORDER BY sm.student_name
                  """)
                  .param("sessionId", sessionId)
                  .param("batchId", batchId)
                  .query((rs, i) -> genericRow(rs)).list();
      }
  ```
  Add the import `java.util.List` at the top of `AttendanceReadRepository.java` if not already present.

- [ ] **4.4** Add the `GET /api/coordinator/attendance` endpoint to `AttendanceController` (append inside the
  existing class; note this endpoint is mapped at the CONTROLLER'S BASE PATH `/api/coordinator/attendance`
  with no further suffix, i.e. `@GetMapping` with no path argument):
  ```java
      /** fetchAttendance -- manual-entry tab: students + their db_status for a session. */
      @GetMapping
      public java.util.List<Map<String, Object>> fetchAttendance(
              @RequestParam(value = "session_id", required = false) String sessionId,
              @RequestParam("batchId") String batchId) {
          try {
              return reads.fetchAttendance(sessionId, batchId);
          } catch (Exception e) {
              throw ApiException.message(500, e.getMessage());
          }
      }
  ```

- [ ] **4.5** Run `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceFetchIT` — expect **PASS**.

- [ ] **4.6** Run the full attendance test slice together to confirm no cross-task regressions:
  `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=AttendanceSupportTest,AttendanceSessionOverlapUndoIT,AttendanceCsvPreviewIT,AttendanceCsvCommitIT,AttendanceFetchIT`
  — expect **PASS** (all 5 classes).

- [ ] **4.7** Commit.
  ```
  git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/AttendanceReadRepository.java imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/AttendanceController.java imas-backend/src/test/java/com/rcf/imas/modules/coordinator/AttendanceFetchIT.java
  git commit -m "$(cat <<'EOF'
  feat(coordinator): attendance manual-entry fetch (#21)

  Ports the last attendance endpoint -- students scoped to a batch, ACTIVE
  always shown, INACTIVE shown only if already marked for the session,
  ordered by name with each row's db_status. Completes phase 4e-2
  (endpoints #15-22).

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Self-review checklist (confirmed before handoff)

- [x] Every endpoint #15-22 maps to a task: #15 session (T1), #16 preview (T2), #17 commit (T3), #18 undo (T1),
  #19 check-overlap (T1), #20 bulk stub (T1), #21 fetch (T4), #22 sample CSV (T1).
- [x] Time helpers (`AttendanceSupport`) reproduce Node's regex/AM-PM logic exactly, unit-tested for the
  tricky inputs (nbsp, AM/PM edge cases, hr/min/sec combos, "null" literal, blank).
- [x] Both `ON CONFLICT` clauses ported VERBATIM in `AttendanceWriteRepository.commitCsvAttendance` — not
  rewritten as SELECT-then-upsert (Firm Decision 1, confirmed against `V1__baseline.sql` lines 1293, 1440).
- [x] `commitCsvAttendance` is `@Transactional`, single connection for session-upsert + attendance-loop.
- [x] CSV fuzzy-match thresholds (75/40), substring-match direction (dbName ⊆ csvKey), largest-duration
  dedup, and first-match-wins-by-insertion-order are all verbatim.
- [x] Response envelopes use `{message}` (never `{error}`) throughout attendance, matching the live source.
- [x] No placeholders — every code block above is complete, copy-pasteable Java/SQL/CSV.
- [x] Class/method names consistent across tasks (`AttendanceController`, `AttendanceReadRepository`,
  `AttendanceWriteRepository`, `AttendanceSupport`, `AttendanceCsvPreviewService`).
