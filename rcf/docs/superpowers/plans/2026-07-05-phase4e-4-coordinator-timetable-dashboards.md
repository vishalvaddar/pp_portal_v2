# Coordinator Timetable + Dashboards (4e-4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the final 8 endpoints (#30-37) of the Node `coordinatorRoutes.js` coordinator module to Spring Boot — timetable CRUD + conflict-check (5 routes) and 3 dashboard/analytics routes — completing the 37-endpoint `com.rcf.imas.modules.coordinator` module byte-compatible on the wire.

**Architecture:** Two new repositories (`CoordinatorTimetableRepository`, `CoordinatorDashboardRepository`) alongside the existing `CoordinatorReadRepository`/`CoordinatorReportsRepository`, reusing `CoordinatorReadRepository.genericRow` where the row shape is "whole-number ids + text", and bespoke row mappers where a query returns decimal `numeric` or `jsonb` columns (genericRow's NUMERIC branch truncates to an integer string, which is wrong for a 2-decimal percentage). One new service (`CoordinatorDashboardService`) holds the N+1 weekly-average orchestration and the JS-`Date`-accurate "last Mon-Sun" window math so it's unit-testable without MockMvc. Two new controllers (`CoordinatorTimetableController`, `CoordinatorDashboardController`), both class-level `@PreAuthorize("isAuthenticated()")`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Maven, plain `JdbcClient` (no JPA/Hibernate), embedded-postgres for ITs (`PgIntegrationTest`), Jackson `ObjectMapper` (already a `@Primary` bean in `JacksonConfig`) for jsonb parsing, JUnit 5 + MockMvc + `com.jayway.jsonpath.JsonPath` for captured-id assertions.

---

## Ground truth already confirmed by reading the live Node source (not just the ground-truth doc)

Read directly: `server/controllers/coordinator/timetableController.js` (157 lines), `server/models/coordinator/timetableModel.js` (150 lines), `server/controllers/coordinator/attendanceAnalyticsController.js` (58 lines), `server/models/coordinator/attendanceModel.js:318-337` (`getWeeklyBatchAverage`), `server/controllers/coordinator/reportsController.js:369-450` (`getGlobalAttendanceStats`, `getTeacherSubjectMonthlyStats`), and the live route table `server/routes/coordinatorRoutes.js:554-565`.

### Response shapes / status codes (exact, per endpoint)

| # | Route | Success | Error paths |
|---|---|---|---|
| 30 | `GET /timetable` | bare array, 200 | missing `batchId` → `{error:"batchId is required"}` 400; catch → `{error:"Failed to fetch timetable"}` 500 |
| 31 | `GET /timetable/check-conflict` | `{overlap:true, conflicts:[...]}` 200 if any conflict, **else `{overlap:false}` 200 with NO `conflicts` key at all** | catch → `{error:"Failed to check conflicts"}` 500 |
| 32 | `POST /timetable` | `{success:true, data:<RETURNING * row>}`, default status **200** (not 201 — Node never calls `.status()` on success) | missing required field → `{error:"Missing required fields"}` 400 (note: `error` key); conflict → `{overlap:true, conflicts:[...], message:"Conflict detected with existing schedule."}` 400 (note: `message` key, no `error` key, alongside `overlap`/`conflicts`); catch → `{error:"Failed to create timetable slot"}` 500 |
| 33 | `PUT /timetable/:id` | `{success:true, data:<RETURNING * row>}`, 200 | same three error shapes as #32, with `"Failed to update timetable slot"` for the catch-all |
| 34 | `DELETE /timetable/:id` | `{success:true}` 200 | catch → `{error:"Failed to delete timetable slot"}` 500 |
| 35 | `GET /attendance/batch-weekly-avg` | bare array `[{batch_id,batch_name,cohort_name,avg_attendance}]`, 200 — `avg_attendance` is `Number(rows[0].avg_attendance \|\| 0)`, a genuine JS number (NOT a numeric-as-string) | catch → `{error:"Failed to load weekly attendance"}` 500 |
| 36 | `GET /reports/global-attendance` | bare array `[{cohort_name,cohort_number,cohort_avg,batches:[{batch_name,avg,classes_held}]}]`, 200 | catch → `{error: err.message}` 500 — **dynamic message, not a static string** |
| 37 | `GET /reports/teacher-subject-stats` | bare array `[{subject_code,teacher_name,percentage}]`, 200 | catch → `{error: err.message}` 500 — dynamic, same as #36 |

### Findings that correct or add to the ground-truth doc (report these back for adjudication)

1. **`server/controllers/coordinator/reportsController.js:432-434` — `getTeacherSubjectMonthlyStats` (#37) ALSO excludes `'LATE JOINED'` from its numerator** (`COUNT(sa.attendance_id) FILTER (WHERE sa.status IN ('PRESENT', 'LEAVE'))`). The ground-truth doc's §4.8/§8.6/LOCKED-DECISIONS-item-5 calls this quirk out only for #36 (`getGlobalAttendanceStats`). It is present verbatim in #37 too — both dashboard percentage endpoints share the same non-standard "present" set, unlike every other report in the module (`getAttendanceReport`, `getBatchClassDetails`'s FILTER, etc.) which use `('PRESENT','LATE JOINED','LEAVE')`. Port verbatim in both; Task 4's IT pins the quirk for #36, Task 3's IT should pin it for #37 too (added below, ground truth did not ask for this but it is the same class of bug and equally worth a regression pin).
2. **`timetableController.createSlot`/`timetableModel.createSlot` — `batch_id` is a required 400-validated field that is never persisted anywhere.** The controller destructures `batch_id` from `req.body` and 400s if it's falsy, then passes it through to `TimetableModel.createSlot({batch_id, classroom_id, day, start_time, end_time, class_link})` — but the model function's own destructuring signature is `({ classroom_id, day, start_time, end_time, class_link })` (`models/coordinator/timetableModel.js:82`), which **does not include `batch_id`**, so it is silently dropped. `batch_id` is not written to `pp.timetable`, not used to insert into `pp.classroom_batch`, nothing. This matches the ground truth's own §4.5 SQL (which never has a batch_id column) but the doc doesn't call out that `batch_id` is *validated-and-discarded* rather than merely unused. Port the "require it in the body, 400 if absent, then ignore it" behavior verbatim — do not wire it up to anything, that would be a behavior change from Node.
3. **`createSlot`/`updateSlot` return HTTP 200 on success, not 201/204.** Neither ground truth nor a casual read would flag this, but it's worth being explicit: Node's `res.json({success:true,data:created})` with no preceding `.status()` call defaults to 200. Java must NOT add `@ResponseStatus(CREATED)` here (unlike `CoordinatorController.createClassroom`, which genuinely does 201 for `/classrooms`).
4. **New finding not in ground truth at all — wire-format risk for `avg`/`avg_attendance` (JS-number vs Java-double formatting).** `jsonb_agg(jsonb_build_object('avg', ROUND(...,2)::numeric, ...))` puts a `numeric` value inside a `jsonb` object. Postgres's jsonb text form preserves the numeric's original decimal text (e.g. `50.00`), but when Node's `pg` driver auto-parses that jsonb column, the result passes through `JSON.parse`, which collapses `50.00` and `50` into the same JS `Number` — so `JSON.stringify` back out prints `50`, not `50.00` or `50.0`. The same applies to `Number(rows[0].avg_attendance || 0)` in #35 (a real JS float from `AVG(...)`, not a decimal-formatted string). If Java naively deserializes to a `Double` and lets Jackson serialize it, a whole-number result renders as `50.0` — **breaking byte-compatibility**. `CoordinatorReportsService.teacherPerformanceReport`'s `completion` field already solves this exact problem (`completion == Math.rint(completion) ? (long) completion : completion`) — Task 3/4 below reuse that trick via a new shared `CoordinatorDashboardRepository.jsNumber(double)` helper. This is the single most important non-obvious correctness point in this plan; get it wrong and every "round number" dashboard value will fail parity even though the underlying data is right.

---

## Firm Decisions (LOCKED — do not re-litigate)

1. **Auth**: `@PreAuthorize("isAuthenticated()")` at class level on both `CoordinatorTimetableController` and `CoordinatorDashboardController`. Node's live `/reports/global-attendance` and `/reports/teacher-subject-stats` use the non-verifying `requireAuth` (any `Authorization` header, never `jwt.verify`'d) — this is a deliberate hardening, same pattern as 4e-3's `CoordinatorReportsController`.
2. **`createSlot`/`updateSlot` are `@Transactional`** at the repository method level (the `CoordinatorWriteRepository.markStudentInactive` pattern — plain sequential `jdbc.sql(...)` calls inside one `@Transactional` method; Spring's transaction synchronization binds them to the same connection, no manual `Connection`/`client` plumbing needed). Each does: INSERT/UPDATE `pp.timetable` (`RETURNING` an explicit column list, not `*` — see Decision 6) THEN `UPDATE pp.classroom SET class_link=... WHERE classroom_id=...`.
3. **`created_by=1, updated_by=1` hard-coded literal on createSlot — preserved, not principal-substituted.** `pp.timetable.created_by` has a real FK (`timetable_created_by_fkey → pp."user"(user_id)`, confirmed in `V1__baseline.sql:1973`), so **every IT that calls createSlot must seed `pp."user"` with `user_id=1`** (`INSERT ... VALUES (1, ...) ON CONFLICT (user_id) DO NOTHING`, never deleted in `@AfterEach` — it's a permanent, shared, idempotent fixture, not per-test data). `updateSlot` does NOT set `updated_by` at all (ported as-is, matches Node).
4. **`checkConflict` SQL verbatim** (interval-overlap + classroom/teacher/cross-batch-EXISTS + exclude-id), bound as **named params**, never interpolated. `createSlot`/`updateSlot` always pass `teacher_id=null` internally; only the standalone `GET /timetable/check-conflict` can pass a real `teacherId` from the query string.
5. **Dashboard quirks preserved verbatim, not "fixed"**: #36 and (per the finding above) #37 both use `sa.status IN ('PRESENT','LEAVE')` — excluding `'LATE JOINED'` — unlike every other report in the module. #35 uses a third, different weighting: `PRESENT=100, 'LATE JOINED'=50, else 0`, unweighted `AVG()` over only rows that exist (no attendance row at all for a student contributes nothing, not a 0).
6. **`RETURNING` uses an explicit column list with `::text` casts on `start_time`/`end_time`, never `RETURNING *`.** This module's established convention (`AttendanceReadRepository`) casts Postgres `time` columns to `::text` in SQL rather than adding a `TIME` case to `genericRow` (which this module's `CoordinatorReadRepository.genericRow` does not have). If `RETURNING *` were used, `start_time`/`end_time` would come back as raw `java.sql.Time` and fall through `genericRow`'s `default -> rs.getObject(i)` branch, which Jackson would then serialize incorrectly (not `"09:00:00"`).
7. **jsonb `batches` column parsed to a real `List<Map<String,Object>>`**, not left as a raw string — inject the `@Primary` `ObjectMapper` bean (`com.rcf.imas.platform.config.JacksonConfig`) into `CoordinatorDashboardRepository` and `objectMapper.readValue(rs.getString("batches"), new TypeReference<List<Map<String,Object>>>(){})` (pgjdbc's `getString()` on a `jsonb` column returns the raw JSON text — no `PGobject` casting needed).
8. **`jsNumber(double)` helper (new, not in ground truth) applied to `avg_attendance` (#35) and the jsonb `avg` field (#36)**: whole-number doubles serialize as a `Long` (no trailing `.0`), fractional doubles stay `Double` — mirrors `Number(x)`/`JSON.stringify` losing the "this came from a decimal column" information. `classes_held` (a `COUNT`, always integral) needs no such handling — Jackson already deserializes whole JSON numbers to `Integer`/`Long`.
9. **Plain (non-jsonb) decimal `numeric` columns — `cohort_avg` (#36) and `percentage` (#37) — stay Strings**, using `rs.getBigDecimal(...).toPlainString()` (preserves the DB's 2-decimal scale, e.g. `"50.00"`), matching node-pg's default (unconfigured) `numeric`-as-string behavior — this is a DIFFERENT rule from Decision 8 because these two fields are plain `SELECT` columns, not values nested inside a `jsonb_agg`.
10. **Test date-relative seeding**: #35 uses "last Mon-Sun" computed with `LocalDate`/`DayOfWeek` in `CoordinatorDashboardService` (`today.getDayOfWeek().getValue() % 7` reproduces JS `Date#getDay()`'s Sunday=0 numbering); #36/#37 use `date_trunc('month', CURRENT_DATE)` directly in SQL. Every IT seeds session/attendance dates computed relative to "now" at test-run time (Java `LocalDate.now()` in the test, or the SQL expression `date_trunc('month', CURRENT_DATE)` literally in the INSERT), never a hard-coded calendar date.

---

## Task 1: Timetable reads — `getTimetable`, `checkConflict`, `deleteSlot` (#30, #31, #34)

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorTimetableRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorTimetableController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTimetableReadsIT.java`

- [ ] **Step 1: Write the failing test**

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorTimetableReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966101,'ttUser966101','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966101,'TT Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966101,'TT Batch',966101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966101,'MTH','Math')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966101,'TT Teacher')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        // Room A has the existing timetable slot; Room B shares the SAME batch (via classroom_batch) but
        // has no timetable row of its own -- this is what exercises the EXISTS cross-batch-share branch.
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966101,'Room A',966101,966101)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966102,'Room B')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966101,966101),(966102,966101)").update();

        jdbc.sql("""
                INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
                VALUES (966101,966101,'MONDAY','09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("966101", "ttUser966101", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966101").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966101").update();
    }

    @Test
    void getTimetableReturnsRowsForBatch() throws Exception {
        mvc.perform(get("/api/coordinator/timetable").param("batchId", "966101")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].classroom_name").value("Room A"))
           .andExpect(jsonPath("$[0].subject_code").value("MTH"))
           .andExpect(jsonPath("$[0].teacher_name").value("TT Teacher"))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"))
           .andExpect(jsonPath("$[0].end_time").value("10:00:00"));
    }

    @Test
    void getTimetableMissingBatchIdReturns400() throws Exception {
        mvc.perform(get("/api/coordinator/timetable").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("batchId is required"));
    }

    @Test
    void checkConflictDirectClassroomOverlapReturnsTrue() throws Exception {
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966101").param("day", "MONDAY")
                .param("startTime", "09:30:00").param("endTime", "10:30:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", hasSize(1)))
           .andExpect(jsonPath("$.conflicts[0].timetable_id").value(966101));
    }

    @Test
    void checkConflictNoOverlapReturnsFalseWithNoConflictsKey() throws Exception {
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966101").param("day", "MONDAY")
                .param("startTime", "11:00:00").param("endTime", "12:00:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(false))
           .andExpect(jsonPath("$.conflicts").doesNotExist());
    }

    @Test
    void checkConflictCrossBatchShareExistsBranchReturnsTrue() throws Exception {
        // Room B (966102) has no timetable row of its own, but shares batch 966101 with Room A (966101),
        // which DOES have an overlapping slot -- the EXISTS(classroom_batch cb1 JOIN cb2) branch must fire.
        mvc.perform(get("/api/coordinator/timetable/check-conflict")
                .param("classroomId", "966102").param("day", "MONDAY")
                .param("startTime", "09:30:00").param("endTime", "10:30:00")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", hasSize(1)));
    }

    @Test
    void deleteSlotRemovesRowAndReturnsSuccess() throws Exception {
        mvc.perform(delete("/api/coordinator/timetable/966101").header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true));

        Integer remaining = jdbc.sql("SELECT COUNT(*)::int FROM pp.timetable WHERE timetable_id = 966101")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableReadsIT`
Expected: FAIL — compile error (`CoordinatorTimetableRepository`/`CoordinatorTimetableController` do not exist yet) or 404s once compiling against a stub.

- [ ] **Step 3: Write the repository**

```java
package com.rcf.imas.modules.coordinator.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository.genericRow;

/**
 * Backs timetable endpoints #30-34 (ground truth phase4e-coordinator-ground-truth.md §4.4/§4.5). start_time/
 * end_time are always cast to ::text in SQL (this module's established convention, see AttendanceReadRepository)
 * rather than adding a TIME case to genericRow, so they come back as "HH:mm:ss" strings matching node-pg's
 * default text format for a `time` column.
 */
@Repository
public class CoordinatorTimetableRepository {

    private final JdbcClient jdbc;

    public CoordinatorTimetableRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** timetableModel.getTimetableByBatch -- day-of-week CASE ordering, then start_time. */
    public List<Map<String, Object>> getTimetableByBatch(String batchId) {
        return jdbc.sql("""
                SELECT t.timetable_id, t.classroom_id, t.day_of_week,
                       t.start_time::text AS start_time, t.end_time::text AS end_time,
                       t.created_at, t.updated_at, t.created_by, t.updated_by,
                       c.classroom_name, c.class_link, s.subject_name, s.subject_code, te.teacher_name
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher te ON c.teacher_id = te.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE cb.batch_id = :batchId::integer
                ORDER BY
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1 WHEN 'MONDAY' THEN 2 WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4 WHEN 'THURSDAY' THEN 5 WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7 END,
                    t.start_time
                """).param("batchId", batchId).query((rs, i) -> genericRow(rs)).list();
    }

    /** timetableModel.checkConflicts -- verbatim SQL (ground truth §4.4), named params, all six nullable. */
    public List<Map<String, Object>> checkConflicts(String day, String startTime, String endTime,
                                                       String classroomId, String teacherId, String excludeId) {
        return jdbc.sql("""
                SELECT t.timetable_id, t.start_time::text AS start_time, t.end_time::text AS end_time,
                       c.classroom_name, s.subject_name, te.teacher_name
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.teacher te ON c.teacher_id = te.teacher_id
                WHERE
                    t.day_of_week = :day
                    AND (:startTime::time < t.end_time AND :endTime::time > t.start_time)
                    AND (
                          ( :classroomId::int IS NOT NULL AND t.classroom_id = :classroomId::int )
                       OR ( :teacherId::int IS NOT NULL AND c.teacher_id = :teacherId::int )
                       OR EXISTS (
                            SELECT 1 FROM pp.classroom_batch cb1
                            JOIN pp.classroom_batch cb2 ON cb1.batch_id = cb2.batch_id
                            WHERE cb1.classroom_id = t.classroom_id AND cb2.classroom_id = :classroomId::int
                          )
                    )
                    AND (:excludeId::int IS NULL OR t.timetable_id <> :excludeId::int)
                """)
                .param("day", day).param("startTime", startTime).param("endTime", endTime)
                .param("classroomId", classroomId).param("teacherId", teacherId).param("excludeId", excludeId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** timetableModel.createSlot -- @Transactional, explicit RETURNING column list (Firm Decision 6),
     *  created_by/updated_by hard-coded to the literal 1 (Firm Decision 3, Node bug, preserved verbatim). */
    @Transactional
    public Map<String, Object> createSlot(String classroomId, String day, String startTime, String endTime, String classLink) {
        Map<String, Object> created = jdbc.sql("""
                INSERT INTO pp.timetable (classroom_id, day_of_week, start_time, end_time, created_by, updated_by)
                VALUES (:classroomId::integer, :day, :startTime::time, :endTime::time, 1, 1)
                RETURNING timetable_id, classroom_id, day_of_week,
                          start_time::text AS start_time, end_time::text AS end_time,
                          created_at, updated_at, created_by, updated_by
                """)
                .param("classroomId", classroomId).param("day", day)
                .param("startTime", startTime).param("endTime", endTime)
                .query((rs, i) -> genericRow(rs)).single();

        jdbc.sql("UPDATE pp.classroom SET class_link = :classLink WHERE classroom_id = :classroomId::integer")
                .param("classLink", classLink).param("classroomId", classroomId).update();

        return created;
    }

    /** timetableModel.updateSlotAndLink -- @Transactional, does NOT set updated_by (ported as-is). */
    @Transactional
    public Map<String, Object> updateSlot(String id, String classroomId, String day, String startTime, String endTime, String classLink) {
        Map<String, Object> updated = jdbc.sql("""
                UPDATE pp.timetable
                SET classroom_id = :classroomId::integer, day_of_week = :day,
                    start_time = :startTime::time, end_time = :endTime::time, updated_at = NOW()
                WHERE timetable_id = :id::integer
                RETURNING timetable_id, classroom_id, day_of_week,
                          start_time::text AS start_time, end_time::text AS end_time,
                          created_at, updated_at, created_by, updated_by
                """)
                .param("id", id).param("classroomId", classroomId).param("day", day)
                .param("startTime", startTime).param("endTime", endTime)
                .query((rs, i) -> genericRow(rs)).single();

        jdbc.sql("UPDATE pp.classroom SET class_link = :classLink WHERE classroom_id = :classroomId::integer")
                .param("classLink", classLink).param("classroomId", classroomId).update();

        return updated;
    }

    /** timetableModel.deleteSlot -- single statement, no @Transactional needed. */
    public void deleteSlot(String id) {
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = :id::integer").param("id", id).update();
    }
}
```

- [ ] **Step 4: Write the controller (Task 1 subset: GET, GET check-conflict, DELETE)**

```java
package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorTimetableRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Timetable endpoints #30-34 (ground truth §1). Firm Decision 1: real @PreAuthorize("isAuthenticated()")
 * at class level -- matches Node's `authenticate` (JWT-verifying) middleware used for all timetable routes.
 */
@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")
public class CoordinatorTimetableController {

    private final CoordinatorTimetableRepository timetable;

    public CoordinatorTimetableController(CoordinatorTimetableRepository timetable) {
        this.timetable = timetable;
    }

    @GetMapping("/timetable")
    public List<Map<String, Object>> getTimetable(@RequestParam(required = false) String batchId) {
        if (isBlank(batchId)) throw ApiException.error(400, "batchId is required");
        try {
            return timetable.getTimetableByBatch(batchId);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch timetable");
        }
    }

    /** checkConflict -- accepts both camelCase and snake_case query params (timetableController.js:24-29). */
    @GetMapping("/timetable/check-conflict")
    public Map<String, Object> checkConflict(
            @RequestParam(required = false) String classroomId,
            @RequestParam(name = "classroom_id", required = false) String classroomIdSnake,
            @RequestParam(required = false) String teacherId,
            @RequestParam(name = "teacher_id", required = false) String teacherIdSnake,
            @RequestParam(required = false) String day,
            @RequestParam(required = false) String dayOfWeek,
            @RequestParam(required = false) String startTime,
            @RequestParam(name = "start_time", required = false) String startTimeSnake,
            @RequestParam(required = false) String endTime,
            @RequestParam(name = "end_time", required = false) String endTimeSnake,
            @RequestParam(required = false) String excludeId,
            @RequestParam(name = "exclude_id", required = false) String excludeIdSnake) {
        try {
            String cid = firstNonBlank(classroomId, classroomIdSnake);
            String tid = firstNonBlank(teacherId, teacherIdSnake);
            String d = firstNonBlank(day, dayOfWeek);
            String st = firstNonBlank(startTime, startTimeSnake);
            String et = firstNonBlank(endTime, endTimeSnake);
            String ex = firstNonBlank(excludeId, excludeIdSnake);

            List<Map<String, Object>> conflicts = timetable.checkConflicts(d, st, et, cid, tid, ex);
            Map<String, Object> body = new LinkedHashMap<>();
            if (!conflicts.isEmpty()) {
                body.put("overlap", true);
                body.put("conflicts", conflicts);
            } else {
                body.put("overlap", false);
            }
            return body;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to check conflicts");
        }
    }

    @DeleteMapping("/timetable/{id}")
    public Map<String, Object> deleteSlot(@PathVariable String id) {
        try {
            timetable.deleteSlot(id);
            return Map.of("success", true);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to delete timetable slot");
        }
    }

    static boolean isBlank(String s) { return s == null || s.isBlank(); }

    static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableReadsIT`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorTimetableRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorTimetableController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTimetableReadsIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): timetable reads -- getTimetable, checkConflict, deleteSlot (#30,#31,#34)

Ports the day-of-week-ordered timetable list, the verbatim interval-overlap +
classroom/teacher/cross-batch-EXISTS conflict-check SQL, and slot delete. TIME
columns cast to ::text per this module's convention (no TIME case in genericRow).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Timetable writes — `createSlot`, `updateSlot` (#32, #33), transactional

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorTimetableController.java` (add POST/PUT)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTimetableWritesIT.java`

(`CoordinatorTimetableRepository.createSlot`/`updateSlot` were already written in Task 1 Step 3 — this task only wires them into the controller and tests them.)

- [ ] **Step 1: Write the failing test**

```java
package com.rcf.imas.modules.coordinator;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorTimetableWritesIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        // Permanent, idempotent fixture: createSlot always writes created_by=1/updated_by=1 (Firm Decision 3),
        // which has a real FK to pp."user"(user_id) -- seed once, never delete.
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (1,'systemUser1','x','N') ON CONFLICT (user_id) DO NOTHING").update();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966201,'ttwUser966201','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966201,'TTW Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966201,'TTW Batch',966201)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966201,'TTW Room')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966201,966201)").update();

        // Two existing slots for conflict tests: MONDAY 09-10 (966201) and TUESDAY 09-10 (966202).
        jdbc.sql("""
                INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time)
                VALUES (966201,966201,'MONDAY','09:00:00','10:00:00'),
                       (966202,966201,'TUESDAY','09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        coordToken = jwt.issueFinalToken("966201", "ttwUser966201", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.timetable WHERE classroom_id = 966201").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966201").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966201").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966201").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966201").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966201").update();
        // NOTE: user_id=1 is a permanent shared fixture, deliberately never deleted here.
    }

    @Test
    void createSlotSuccessSyncsClassroomLinkAndUsesLiteralCreatedBy() throws Exception {
        String body = """
                {"batch_id":"966201","classroom_id":"966201","day":"WEDNESDAY",
                 "start_time":"14:00:00","end_time":"15:00:00","class_link":"https://zoom.example/ww1"}
                """;
        MvcResult result = mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.classroom_id").value(966201))
           .andExpect(jsonPath("$.data.day_of_week").value("WEDNESDAY"))
           .andExpect(jsonPath("$.data.start_time").value("14:00:00"))
           .andExpect(jsonPath("$.data.end_time").value("15:00:00"))
           .andExpect(jsonPath("$.data.created_by").value("1"))
           .andExpect(jsonPath("$.data.updated_by").value("1"))
           .andReturn();

        int newId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.timetable_id");
        try {
            String classLink = jdbc.sql("SELECT class_link FROM pp.classroom WHERE classroom_id = 966201")
                    .query(String.class).single();
            org.junit.jupiter.api.Assertions.assertEquals("https://zoom.example/ww1", classLink);
        } finally {
            jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id = :id").param("id", newId).update();
        }
    }

    @Test
    void createSlotConflictReturns400WithMessageKeyNotErrorKey() throws Exception {
        String body = """
                {"batch_id":"966201","classroom_id":"966201","day":"MONDAY",
                 "start_time":"09:30:00","end_time":"10:30:00"}
                """;
        mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.conflicts", org.hamcrest.Matchers.hasSize(1)))
           .andExpect(jsonPath("$.message").value("Conflict detected with existing schedule."))
           .andExpect(jsonPath("$.error").doesNotExist());

        Integer count = jdbc.sql("SELECT COUNT(*)::int FROM pp.timetable WHERE classroom_id = 966201")
                .query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(2, count); // no new row inserted
    }

    @Test
    void createSlotMissingRequiredFieldReturns400WithErrorKey() throws Exception {
        String body = """
                {"batch_id":"966201","day":"WEDNESDAY","start_time":"14:00:00","end_time":"15:00:00"}
                """;
        mvc.perform(post("/api/coordinator/timetable")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing required fields"));
    }

    @Test
    void updateSlotSuccessSyncsClassroomLink() throws Exception {
        String body = """
                {"classroom_id":"966201","day":"THURSDAY",
                 "start_time":"11:00:00","end_time":"12:00:00","class_link":"https://zoom.example/ww2"}
                """;
        mvc.perform(put("/api/coordinator/timetable/966201")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.data.timetable_id").value(966201))
           .andExpect(jsonPath("$.data.day_of_week").value("THURSDAY"))
           .andExpect(jsonPath("$.data.start_time").value("11:00:00"));

        String classLink = jdbc.sql("SELECT class_link FROM pp.classroom WHERE classroom_id = 966201")
                .query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals("https://zoom.example/ww2", classLink);
    }

    @Test
    void updateSlotConflictWithOtherExistingSlotReturns400() throws Exception {
        // 966201 is currently MONDAY 09-10; moving it to overlap 966202 (TUESDAY 09-10) must conflict --
        // exclude_id only excludes 966201 itself, not the other row.
        String body = """
                {"classroom_id":"966201","day":"TUESDAY","start_time":"09:30:00","end_time":"10:30:00"}
                """;
        mvc.perform(put("/api/coordinator/timetable/966201")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.overlap").value(true))
           .andExpect(jsonPath("$.message").value("Conflict detected with existing schedule."));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableWritesIT`
Expected: FAIL — 404s (no `POST`/`PUT` mappings yet).

- [ ] **Step 3: Add createSlot/updateSlot to the controller**

Add these methods and helpers to `CoordinatorTimetableController` (edit the file created in Task 1):

```java
    @PostMapping("/timetable")
    public Map<String, Object> createSlot(@RequestBody Map<String, Object> body) {
        String batchId = str(body.get("batch_id"));
        String classroomId = str(body.get("classroom_id"));
        String day = str(body.get("day"));
        String startTime = str(body.get("start_time"));
        String endTime = str(body.get("end_time"));
        String classLink = str(body.get("class_link"));

        if (isBlank(batchId) || isBlank(classroomId) || isBlank(day) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            List<Map<String, Object>> conflicts = timetable.checkConflicts(day, startTime, endTime, classroomId, null, null);
            if (!conflicts.isEmpty()) {
                throw ApiException.message(400, "Conflict detected with existing schedule.")
                        .with("overlap", true).with("conflicts", conflicts);
            }
            Map<String, Object> created = timetable.createSlot(classroomId, day, startTime, endTime, classLink);
            return Map.of("success", true, "data", created);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to create timetable slot");
        }
    }

    @PutMapping("/timetable/{id}")
    public Map<String, Object> updateSlot(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String classroomId = str(body.get("classroom_id"));
        String day = str(body.get("day"));
        String startTime = str(body.get("start_time"));
        String endTime = str(body.get("end_time"));
        String classLink = str(body.get("class_link"));

        if (isBlank(classroomId) || isBlank(day) || isBlank(startTime) || isBlank(endTime)) {
            throw ApiException.error(400, "Missing required fields");
        }
        try {
            List<Map<String, Object>> conflicts = timetable.checkConflicts(day, startTime, endTime, classroomId, null, id);
            if (!conflicts.isEmpty()) {
                throw ApiException.message(400, "Conflict detected with existing schedule.")
                        .with("overlap", true).with("conflicts", conflicts);
            }
            Map<String, Object> updated = timetable.updateSlot(id, classroomId, day, startTime, endTime, classLink);
            return Map.of("success", true, "data", updated);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to update timetable slot");
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableWritesIT`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full timetable suite together (regression check)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableReadsIT,CoordinatorTimetableWritesIT`
Expected: PASS (11 tests total).

- [ ] **Step 6: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorTimetableController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorTimetableWritesIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): timetable writes -- createSlot, updateSlot (#32,#33), transactional

@Transactional insert/update of pp.timetable + classroom.class_link sync, internal
conflict pre-check (400 with {overlap,conflicts,message}, no "error" key). Preserves
Node's hard-coded created_by=updated_by=1 on createSlot (RETURNING row parity).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Dashboards part 1 — `getBatchWeeklyAverage` (#35), `getTeacherSubjectMonthlyStats` (#37)

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorDashboardRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorDashboardService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorDashboardController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorDashboardWeeklyAndSubjectStatsIT.java`

- [ ] **Step 1: Write the failing test**

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

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorDashboardWeeklyAndSubjectStatsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    /** Reproduces JS `Date#getDay()` (Sunday=0) math from attendanceAnalyticsController.js, independently
     *  of the production Java, so the test seeds the SAME window the endpoint will query no matter which
     *  day this test actually runs on. */
    private static LocalDate[] lastMondayToSundayWindow(LocalDate today) {
        int day = today.getDayOfWeek().getValue() % 7; // MONDAY=1..SATURDAY=6, SUNDAY=7 -> 0 (Sunday=0 parity)
        LocalDate lastSunday = today.minusDays(day);
        LocalDate lastMonday = lastSunday.minusDays(6);
        return new LocalDate[]{lastMonday, lastSunday};
    }

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966301,'dashUser966301','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // --- Fixture A: weekly-avg (batch 966301) ---
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966301,'Dash Cohort A')").update();
        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966311,'Dash Cohort B')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966301,'Dash Batch A',966301)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966311,'Dash Batch B',966311)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966301,966301),(966301,966311)").update();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966301,'Dash Room A')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966301,966301)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966301,'Weekly Student 1',966301,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966302,'Weekly Student 2',966301,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966303,'Weekly Student 3',966301,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        LocalDate[] window = lastMondayToSundayWindow(LocalDate.now());
        String sessionDate = window[0].toString(); // lastMonday, guaranteed inside [fromDate,toDate]

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966301,966301, :sessionDate::date, '09:00:00','10:00:00')
                """).param("sessionDate", sessionDate).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        // PRESENT=100, LATE JOINED=50, ABSENT=0 -> avg = (100+50+0)/3 = 50.0
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966301,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966302,'LATE JOINED')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966301,966303,'ABSENT')").update();

        // --- Fixture B: teacher-subject-stats, current month (batch 966311) ---
        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966311,'SCI','Science')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, teacher_name) VALUES (966311,'Dash Teacher B')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) VALUES (966311,'Dash Room B',966311,966311)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966311,966311)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966311,'Subj Student 1',966311,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966312,'Subj Student 2',966311,'ACTIVE')").update();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966311,966311, date_trunc('month', CURRENT_DATE)::date, '09:00:00','10:00:00')
                """).update();

        // 1 PRESENT (counted), 1 LATE JOINED (must be EXCLUDED per the quirk shared with #36) -> 2 active
        // students, 1 session held, numerator=1 -> percentage = (1/(1*2))*100 = 50.00
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966311,966311,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966311,966312,'LATE JOINED')").update();

        coordToken = jwt.issueFinalToken("966301", "dashUser966301", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966301,966302,966303,966311,966312)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id = 966311").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966311").update();
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE user_id = 966301").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number IN (966301,966311)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966301").update();
    }

    @Test
    void batchWeeklyAverageReturnsWeightedAverageAsNumber() throws Exception {
        mvc.perform(get("/api/coordinator/attendance/batch-weekly-avg")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.batch_id==966301)]", hasSize(1)))
           .andExpect(jsonPath("$[?(@.batch_id==966301)].batch_name", org.hamcrest.Matchers.contains("Dash Batch A")))
           .andExpect(jsonPath("$[?(@.batch_id==966301)].avg_attendance", org.hamcrest.Matchers.contains(50.0)));
    }

    @Test
    void teacherSubjectStatsExcludesLateJoinedFromNumerator() throws Exception {
        mvc.perform(get("/api/coordinator/reports/teacher-subject-stats").param("batchId", "966311")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].subject_code").value("SCI"))
           .andExpect(jsonPath("$[0].teacher_name").value("Dash Teacher B"))
           .andExpect(jsonPath("$[0].percentage").value("50.00"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorDashboardWeeklyAndSubjectStatsIT`
Expected: FAIL — compile error (classes don't exist yet).

- [ ] **Step 3: Write the repository**

```java
package com.rcf.imas.modules.coordinator.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the 3 dashboard/analytics endpoints (#35-37, ground truth §4.8/§4.9). Deliberately does NOT reuse
 * CoordinatorReadRepository.genericRow -- these queries return DECIMAL numeric columns (a 2-decimal
 * percentage/average), and genericRow's NUMERIC branch truncates to a whole-number id string
 * (bd.toBigInteger()), which would silently drop the fractional part. Bespoke row mappers here instead.
 */
@Repository
public class CoordinatorDashboardRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CoordinatorDashboardRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Mirrors JS Number(x) + JSON.stringify semantics: a numerically-whole double serializes without a
     *  trailing ".0" (JS has no int/float distinction), a genuine fraction keeps full double precision.
     *  Same trick as CoordinatorReportsService.teacherPerformanceReport's "completion" field. */
    static Object jsNumber(double d) {
        return (d == Math.rint(d) && !Double.isInfinite(d)) ? (Object) (long) d : (Object) d;
    }

    /** attendanceModel.getWeeklyBatchAverage -- PRESENT=100, LATE JOINED=50, else 0, unweighted AVG over
     *  only rows that exist (no attendance row at all contributes nothing). Returns BigDecimal.ZERO when
     *  no matching rows exist at all (Node: `Number(rows[0].avg_attendance || 0)`). */
    public BigDecimal weeklyBatchAverage(String batchId, String fromDate, String toDate) {
        BigDecimal avg = jdbc.sql("""
                SELECT AVG(CASE WHEN sa.status = 'PRESENT' THEN 100 WHEN sa.status = 'LATE JOINED' THEN 50 ELSE 0 END) AS avg_attendance
                FROM pp.student_attendance sa
                JOIN pp.student_master sm ON sa.student_id = sm.student_id
                JOIN pp.class_session cs ON sa.session_id = cs.session_id
                WHERE sm.batch_id = :batchId::integer
                  AND cs.session_date BETWEEN :fromDate::date AND :toDate::date
                """)
                .param("batchId", batchId).param("fromDate", fromDate).param("toDate", toDate)
                .query(BigDecimal.class).single();
        return avg == null ? BigDecimal.ZERO : avg;
    }

    /** reportsController.getTeacherSubjectMonthlyStats -- current-month per-subject/teacher percentage for
     *  one batch. Shares the 'PRESENT','LEAVE' (LATE JOINED excluded) numerator with #36 -- see plan's
     *  "findings" section, this is NOT called out in the ground truth doc for #37 but is present verbatim
     *  in the live source (reportsController.js:432-434). percentage stays a String (Firm Decision 9). */
    public List<Map<String, Object>> teacherSubjectStats(String batchId) {
        return jdbc.sql("""
                WITH current_month AS (
                    SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                           (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
                ),
                student_pop AS (
                    SELECT COUNT(*) as active_students FROM pp.student_master
                    WHERE batch_id = :batchId::integer AND active_yn = 'ACTIVE'
                )
                SELECT
                    s.subject_code, t.teacher_name,
                    ROUND(CASE WHEN (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop)) > 0
                          THEN (COUNT(sa.attendance_id) FILTER (WHERE sa.status IN ('PRESENT', 'LEAVE'))::float
                                / (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop))) * 100
                          ELSE 0 END::numeric, 2) as percentage
                FROM pp.class_session cs
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.teacher t ON c.teacher_id = t.teacher_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                LEFT JOIN pp.student_attendance sa ON sa.session_id = cs.session_id
                CROSS JOIN current_month cm
                WHERE cb.batch_id = :batchId::integer AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt
                GROUP BY s.subject_code, t.teacher_name ORDER BY percentage DESC
                """)
                .param("batchId", batchId)
                .query((rs, i) -> teacherSubjectRow(rs)).list();
    }

    private static Map<String, Object> teacherSubjectRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject_code", rs.getString("subject_code"));
        m.put("teacher_name", rs.getString("teacher_name"));
        BigDecimal pct = rs.getBigDecimal("percentage");
        m.put("percentage", pct == null ? null : pct.toPlainString());
        return m;
    }

    /** reportsController.getGlobalAttendanceStats -- current-month rainbow-gauge, one row per cohort with
     *  a nested jsonb batches array. See Task 4 for the row mapper (added there so this file compiles
     *  standalone after Task 3 without a forward reference to Task 4's globalAttendanceStats method). */
}
```

- [ ] **Step 4: Write the service**

```java
package com.rcf.imas.modules.coordinator.service;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorDashboardRepository;
import com.rcf.imas.modules.coordinator.persistence.CoordinatorReadRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * attendanceAnalyticsController.getBatchWeeklyAverage -- N+1 loop (fine for a handful of batches per
 * coordinator, ported as-is per ground truth §4.9/§8's "flag, don't silently fix" instruction) + the
 * "last Mon-Sun" week window computed with java.time, matching JS `Date#getDay()` (Sunday=0) numbering.
 */
@Service
public class CoordinatorDashboardService {

    private final CoordinatorReadRepository reads;
    private final CoordinatorDashboardRepository dashboard;

    public CoordinatorDashboardService(CoordinatorReadRepository reads, CoordinatorDashboardRepository dashboard) {
        this.reads = reads;
        this.dashboard = dashboard;
    }

    public List<Map<String, Object>> batchWeeklyAverage(String coordinatorUserId) {
        LocalDate[] window = lastMondayToSundayWindow(LocalDate.now());
        String fromDate = window[0].toString();
        String toDate = window[1].toString();

        List<Map<String, Object>> batches = reads.allBatchesForCoordinator(coordinatorUserId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> b : batches) {
            String batchId = String.valueOf(b.get("batch_id"));
            BigDecimal avg = dashboard.weeklyBatchAverage(batchId, fromDate, toDate);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", b.get("batch_id"));
            row.put("batch_name", b.get("batch_name"));
            row.put("cohort_name", b.get("cohort_name"));
            row.put("avg_attendance", CoordinatorDashboardRepository.jsNumber(avg.doubleValue()));
            results.add(row);
        }
        return results;
    }

    /** JS: `const day = today.getDay(); const lastSunday = today - day days; const lastMonday = lastSunday - 6 days`.
     *  java.time's DayOfWeek is MONDAY=1..SUNDAY=7 -- `% 7` remaps SUNDAY to 0, matching JS's Sunday=0. */
    static LocalDate[] lastMondayToSundayWindow(LocalDate today) {
        int day = today.getDayOfWeek().getValue() % 7;
        LocalDate lastSunday = today.minusDays(day);
        LocalDate lastMonday = lastSunday.minusDays(6);
        return new LocalDate[]{lastMonday, lastSunday};
    }
}
```

- [ ] **Step 5: Write the controller**

```java
package com.rcf.imas.modules.coordinator.web;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorDashboardRepository;
import com.rcf.imas.modules.coordinator.service.CoordinatorDashboardService;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dashboard endpoints #35-37 (ground truth §1). Firm Decision 1: real @PreAuthorize("isAuthenticated()")
 * -- Node's live /reports/global-attendance and /reports/teacher-subject-stats use the non-verifying
 * requireAuth (any Authorization header, never jwt.verify'd); #35 uses the real `authenticate` already.
 * This is a deliberate hardening for the two /reports/* routes, matching CoordinatorReportsController's
 * (4e-3) precedent. Kept as its own controller/file rather than folded into CoordinatorReportsController,
 * matching the ground truth's task-decomposition split (4e-3 = reports, 4e-4 = timetable+dashboards).
 */
@RestController
@RequestMapping("/api/coordinator")
@PreAuthorize("isAuthenticated()")
public class CoordinatorDashboardController {

    private final CoordinatorDashboardService dashboardService;
    private final CoordinatorDashboardRepository dashboardRepo;

    public CoordinatorDashboardController(CoordinatorDashboardService dashboardService, CoordinatorDashboardRepository dashboardRepo) {
        this.dashboardService = dashboardService;
        this.dashboardRepo = dashboardRepo;
    }

    @GetMapping("/attendance/batch-weekly-avg")
    public List<Map<String, Object>> batchWeeklyAverage(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return dashboardService.batchWeeklyAverage(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to load weekly attendance");
        }
    }

    @GetMapping("/reports/teacher-subject-stats")
    public List<Map<String, Object>> teacherSubjectStats(@RequestParam(required = false) String batchId) {
        try {
            return dashboardRepo.teacherSubjectStats(batchId);
        } catch (Exception e) {
            // Node: `res.status(500).json({error: err.message})` -- dynamic message, not a static string.
            throw ApiException.error(500, e.getMessage());
        }
    }
}
```

(`/reports/global-attendance` is added to this same controller in Task 4.)

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorDashboardWeeklyAndSubjectStatsIT`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorDashboardRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/coordinator/service/CoordinatorDashboardService.java \
        imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorDashboardController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorDashboardWeeklyAndSubjectStatsIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): weekly attendance avg + teacher-subject stats dashboards (#35,#37)

N+1 per-batch weekly average (PRESENT=100/LATE JOINED=50 weighting) with a
java.time reproduction of JS Date#getDay()'s Sunday=0 "last Mon-Sun" window,
and the current-month per-subject/teacher percentage query. Adds jsNumber()
(shared with Task 4) so whole-number decimal results serialize without a
misleading trailing ".0" the way JS Number()/JSON.stringify do.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Dashboards part 2 — `getGlobalAttendanceStats` (#36), jsonb parsing

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorDashboardRepository.java` (add `globalAttendanceStats`)
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorDashboardController.java` (add `/reports/global-attendance`)
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorDashboardGlobalAttendanceIT.java`

- [ ] **Step 1: Write the failing test**

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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CoordinatorDashboardGlobalAttendanceIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String coordToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966401,'gaUser966401','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966401,'GA Cohort')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966401,'GA Batch',966401)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name) VALUES (966401,'GA Room')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966401,966401)").update();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966401,'GA Student 1',966401,'ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, active_yn) VALUES (966402,'GA Student 2',966401,'ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("""
                INSERT INTO pp.class_session(session_id, classroom_id, session_date, start_time, end_time)
                VALUES (966401,966401, date_trunc('month', CURRENT_DATE)::date, '09:00:00','10:00:00')
                """).update();
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        // 966401=PRESENT (counted), 966402='LATE JOINED' (must be EXCLUDED -- pins the quirk). s_count=2,
        // sess_count=1, p_count=1 -> cohort_avg/batch avg = (1/(1*2))*100 = 50.00, classes_held=1.
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966401,966401,'PRESENT')").update();
        jdbc.sql("INSERT INTO pp.student_attendance(session_id, student_id, status) VALUES (966401,966402,'LATE JOINED')").update();

        coordToken = jwt.issueFinalToken("966401", "gaUser966401", "COORDINATOR");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE session_id = 966401").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id = 966401").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966401,966402)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE batch_id = 966401").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966401").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966401").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966401").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 966401").update();
    }

    @Test
    void globalAttendanceStatsExcludesLateJoinedAndParsesJsonbBatchesArray() throws Exception {
        mvc.perform(get("/api/coordinator/reports/global-attendance")
                .header("Authorization", "Bearer " + coordToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.cohort_number==966401)]", hasSize(1)))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].cohort_name", contains("GA Cohort")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].cohort_avg", contains("50.00")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].batch_name", contains("GA Batch")))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].avg", contains(50)))
           .andExpect(jsonPath("$[?(@.cohort_number==966401)].batches[*].classes_held", contains(1)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorDashboardGlobalAttendanceIT`
Expected: FAIL — 404 (`/reports/global-attendance` not mapped yet).

- [ ] **Step 3: Add `globalAttendanceStats` to the repository**

Add this method and row mapper to `CoordinatorDashboardRepository` (replace the placeholder comment left at the end of the file in Task 3 Step 3):

```java
    /** reportsController.getGlobalAttendanceStats -- current-month rainbow gauge, one row per cohort. Uses
     *  sa.status IN ('PRESENT','LEAVE') -- excludes 'LATE JOINED' -- ported verbatim (Firm Decision 5/9,
     *  ground truth §4.8/§8.6). The jsonb `batches` column is parsed to a real List<Map> (Firm Decision 7),
     *  and its nested `avg` field goes through jsNumber() (Firm Decision 8) so a whole-number percentage
     *  serializes as `50`, not `50.0`, matching JS JSON.parse/JSON.stringify round-tripping a jsonb numeric. */
    public List<Map<String, Object>> globalAttendanceStats() {
        return jdbc.sql("""
                WITH current_month AS (
                    SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                           (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
                ),
                metrics AS (
                    SELECT
                        b.batch_id, b.batch_name, b.cohort_number,
                        (SELECT COUNT(*) FROM pp.student_master WHERE batch_id = b.batch_id AND active_yn = 'ACTIVE') as s_count,
                        (SELECT COUNT(DISTINCT cs.session_id) FROM pp.classroom_batch cb
                         JOIN pp.class_session cs ON cs.classroom_id = cb.classroom_id
                         CROSS JOIN current_month cm
                         WHERE cb.batch_id = b.batch_id AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as sess_count,
                        (SELECT COUNT(sa.attendance_id) FROM pp.student_attendance sa
                         JOIN pp.class_session cs ON sa.session_id = cs.session_id
                         JOIN pp.student_master sm ON sm.student_id = sa.student_id
                         CROSS JOIN current_month cm
                         WHERE sm.batch_id = b.batch_id AND sm.active_yn = 'ACTIVE'
                         AND sa.status IN ('PRESENT', 'LEAVE')
                         AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as p_count
                    FROM pp.batch b
                )
                SELECT
                    c.cohort_name, c.cohort_number,
                    ROUND(AVG(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END)::numeric, 2) as cohort_avg,
                    jsonb_agg(jsonb_build_object(
                        'batch_name', m.batch_name,
                        'avg', ROUND(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END::numeric, 2),
                        'classes_held', m.sess_count
                    ) ORDER BY m.batch_name) as batches
                FROM pp.cohort c
                JOIN metrics m ON m.cohort_number = c.cohort_number
                GROUP BY c.cohort_name, c.cohort_number ORDER BY c.cohort_number
                """)
                .query((rs, i) -> globalAttendanceRow(rs)).list();
    }

    private Map<String, Object> globalAttendanceRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohort_name", rs.getString("cohort_name"));
        m.put("cohort_number", rs.getObject("cohort_number"));
        BigDecimal avg = rs.getBigDecimal("cohort_avg");
        m.put("cohort_avg", avg == null ? null : avg.toPlainString());

        String batchesJson = rs.getString("batches"); // pgjdbc getString() on jsonb returns the raw JSON text
        List<Map<String, Object>> batches;
        try {
            batches = batchesJson == null ? List.of()
                    : objectMapper.readValue(batchesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        for (Map<String, Object> batch : batches) {
            Object rawAvg = batch.get("avg");
            if (rawAvg instanceof Number n) {
                batch.put("avg", jsNumber(n.doubleValue()));
            }
        }
        m.put("batches", batches);
        return m;
    }
```

- [ ] **Step 4: Add `/reports/global-attendance` to the controller**

Add this method to `CoordinatorDashboardController`:

```java
    @GetMapping("/reports/global-attendance")
    public List<Map<String, Object>> globalAttendance() {
        try {
            return dashboardRepo.globalAttendanceStats();
        } catch (Exception e) {
            // Node: `res.status(500).json({error: err.message})` -- dynamic message, not a static string.
            throw ApiException.error(500, e.getMessage());
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorDashboardGlobalAttendanceIT`
Expected: PASS (1 test).

- [ ] **Step 6: Run the full 4e-4 suite together (regression check)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=CoordinatorTimetableReadsIT,CoordinatorTimetableWritesIT,CoordinatorDashboardWeeklyAndSubjectStatsIT,CoordinatorDashboardGlobalAttendanceIT`
Expected: PASS (14 tests total across the 4 IT classes).

- [ ] **Step 7: Run the full coordinator module test suite (final regression check for the whole 37-endpoint module)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=com.rcf.imas.modules.coordinator.*IT`
Expected: PASS (all coordinator ITs across 4e-1 through 4e-4 green).

- [ ] **Step 8: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/coordinator/persistence/CoordinatorDashboardRepository.java \
        imas-backend/src/main/java/com/rcf/imas/modules/coordinator/web/CoordinatorDashboardController.java \
        imas-backend/src/test/java/com/rcf/imas/modules/coordinator/CoordinatorDashboardGlobalAttendanceIT.java
git commit -m "$(cat <<'EOF'
feat(coordinator): global attendance rainbow-gauge dashboard (#36) -- completes 37/37

Current-month per-cohort/batch attendance % with a jsonb_agg'd batches array,
parsed to a real List<Map> via the shared ObjectMapper bean rather than left as
raw jsonb text. Pins the PRESENT+LEAVE-only (LATE JOINED excluded) numerator
quirk with a seeded LATE JOINED row that must NOT count. This is the last of
the 37 coordinator endpoints (#1-37), closing out com.rcf.imas.modules.coordinator.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Deferred / Flagged (do not silently fix; record for a future product decision)

1. **`created_by=1, updated_by=1` hard-coded on `createSlot`** — preserved verbatim (Firm Decision 3). A genuine Node bug (never uses `req.user.user_id`), kept for `RETURNING`-row wire parity. Every environment that runs this code needs a `user_id=1` row in `pp."user"`.
2. **`global-attendance` (#36) and `teacher-subject-stats` (#37) both exclude `'LATE JOINED'`** from their "present" numerator (`'PRESENT','LEAVE'` only) — the ONLY two endpoints in the whole coordinator module with this definition; every other report/dashboard uses `'PRESENT','LATE JOINED','LEAVE'`. The ground-truth doc only documented this for #36 — confirmed by reading the live source that #37 has the identical quirk (reportsController.js:432-434). Flag both for a product decision; do not unify.
3. **`getBatchWeeklyAverage` (#35) uses a THIRD distinct attendance-percentage formula**: PRESENT=100, LATE JOINED=50 (partial credit), everything else (including "no attendance row at all") excluded/0 — an unweighted `AVG()` over only rows that exist. Three different "attendance %" definitions now live in this one module (`getAttendanceReport`'s FILTER-based one from 4e-3, the #36/#37 PRESENT+LEAVE one, and this PRESENT=100/LATE=50 one). Do not consolidate without a business-owner decision.
4. **`createSlot`'s `batch_id` is a required-but-discarded field** (see "Findings" §2 above) — 400s if absent from the request body, never persisted or used to link `pp.classroom_batch`. Ported verbatim for parity; flagged as dead validation, not wired up to anything new.
5. **`.single()` throwing 500 on `updateSlot`/`createSlot` targeting a non-existent id/classroom** diverges from Node, which would `RETURNING *` zero rows and return `res.rows[0]` = `undefined`, silently dropping the `data` key from the response (`{"success":true}` with no `data`) instead of erroring. This plan's Java throws (caught by the controller's catch-all → `500 {error:"Failed to ... timetable slot"}`) rather than reproducing the undefined-key-drop behavior. Documented, deliberate simplification (edge case only — updating/creating against a nonexistent classroom_id/timetable_id), not silently different.
6. **`teacher-subject-stats` (#37) has no `batchId` presence guard** in Node (`const { batchId } = req.query`, used unchecked) — a missing `batchId` throws inside the pg driver and 500s with a dynamic `err.message`. This plan does not add a Java-side guard either (matches Node's lack of one) but does not attempt to reproduce the exact driver error text; a missing `batchId` will most likely execute with a `NULL::integer` bind and return an empty `200 []` in Java rather than Node's `500`. Flagged as an acceptable, documented divergence for an edge case Node itself does not handle intentionally.
