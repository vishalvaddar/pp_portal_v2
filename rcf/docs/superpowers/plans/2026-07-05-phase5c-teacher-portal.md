# IMAS Spring Boot Migration — Plan 5c: Teacher Self-Service Portal

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the 9-route Node `teacherStudentRoutes.js` module (mounted `/api/teacher`) — the teacher self-service portal (cohorts, batches, timetable, students, inactive-history, profile, coordinators, dashboard, reports/my-classes) — to a new `com.rcf.imas.modules.teacher` module, preserving exact SQL, response shapes, status codes, per-endpoint error envelopes, and every documented quirk (the inactive-history IDOR, the `/students` fan-out, the dashboard `monthlyTrend` earliest-6-months mislabel, the hardcoded photo-path convention). This module was missed in the original Node→Spring Boot migration and is being closed now.

**Architecture:** Continues the modular monolith (`imas-backend/`, plain `JdbcClient` + SQL, no JPA). New module `teacher` with one controller (`web/TeacherController.java`) and one repository (`persistence/TeacherReadRepository.java`). No new Flyway migration — every table used by this module (`pp.teacher`, `pp.teacher_subject`, `pp.classroom`, `pp.classroom_batch`, `pp.batch`, `pp.cohort`, `pp.student_master`, `pp.inactive_students`, `pp.institute`, `pp.subject`, `pp.timetable`, `pp.class_session`, `pp.student_attendance`, `pp.batch_coordinator_batches`, `pp."user"`) already exists in `V1__baseline.sql`. Schema is clean — no landmines.

**Tech Stack (no additions):** Plain `JdbcClient`, already on the classpath. No new Maven dependency — this module is 9 read-only GET endpoints, no file generation, no writes, no transactions.

**Spec ground truth:** `docs/superpowers/plans/artifacts/phase5c-teacher-portal-ground-truth.md` (§1 inventory, §2 verbatim SQL, §3 DDL, §4 identity/scoping, §5 response shapes, §7 quirks). Node source (read to the bottom — no dead/commented predecessor code found anywhere in this module, unlike the coordinator module): `server/routes/teacherStudentRoutes.js`, `server/controllers/teacher/{TeacherStudentController,TeacherDashboardController,TeacherProfileController,TeacherReportController,TeacherCoordinatorController,TeacherTimetableController}.js`, `server/models/teacher/{TeacherStudentModel,TeacherDashboardModel,TeacherProfileModel,TeacherReportModel,TeacherCoordinatorModel,TeacherTimetableModel}.js`. Every controller/model file was re-read verbatim while writing this plan (independently of the ground-truth doc) — **no disagreements found**; the ground-truth doc's SQL and response-shape tables match the live source exactly, character-for-character on every query. Assumes Phases 0/1 and the coordinator module (4e) are merged and green: `PgIntegrationTest`, `JwtService` (`issueFinalToken(userId, userName, roleName)`, `FinalToken.userId()`, `@AuthenticationPrincipal JwtService.FinalToken`), `SecurityConfig`, `ApiException`/`GlobalExceptionHandler`, global snake_case `ObjectMapper`.

---

> **⚠ LOCKED CONVENTIONS (apply in every task):**
> 1. **Plain SQL via `JdbcClient` only.** No JPA. Row mappers build the exact JSON via a module-local `genericRow` helper (copy-and-adapt of the coordinator module's, see #3 below for the one deliberate difference).
> 2. **Numeric-column params: cast the PARAM.** `t.user_id = :userId::numeric`, `b.cohort_number = :cohortNumber::integer`, `cb.batch_id = :batchId::integer`, `sm.student_id = :studentId::numeric`, `cs.session_date >= :fromDate::date`, etc. Java JDBC binds an unqualified string param as `VARCHAR`; Postgres will not implicitly compare `VARCHAR` to `numeric`/`integer`/`date`.
> 3. **Numeric-column serialization — `toPlainString()`, NOT the coordinator module's `toBigInteger()`.** This is a deliberate deviation from `CoordinatorReadRepository.genericRow`, documented here because it is easy to copy-paste wrong: every `NUMERIC`/`DECIMAL` column in the coordinator module's 14-endpoint slice happened to be a whole-number id, so `bd.toBigInteger().toString()` was safe there. **This module's `/dashboard` query 1 returns `avg_attendance` as `COALESCE(ROUND(AVG(sa.attendance_percent), 2), 0)` — a genuinely fractional `NUMERIC`** (e.g. `85.00`, or `0` when there is no attendance data at all, per ground-truth §5/§7.7 and the `x||fallback` numeric-stays-string quirk called out in the brief). Using `toBigInteger()` here would silently truncate `"85.00"` to `"85"`, corrupting the dashboard. The module-local `genericRow`'s `NUMERIC`/`DECIMAL` branch therefore uses `bd.toPlainString()` — this is correct for both whole-number ids (`student_id`, `enr_id`, `applicant_id`, `user_id` all print with no trailing zeros/decimal point, since they are stored with scale 0) and the one fractional column, matching node-pg's numeric-always-a-string behavior exactly in every case.
> 4. **`BIGINT` (every `COUNT(...)`/`COUNT(DISTINCT ...)` result: `total_conducted`, `total_batches`, `classes_taken`) → JSON string** via `rs.getLong(i)` + `String.valueOf`. **Plain `integer` columns** (`cohort_number`, `batch_id`, `classroom_id`, `timetable_id`, `session_id`, `subject_id`, `teacher_id`, `experience_yrs`, `day_order`) → **native JSON numbers** via the `else -> rs.getObject(i)` passthrough branch. `boolean` (`attendance_marked`) → native JSON boolean via passthrough.
> 5. **`DATE` → `"yyyy-MM-dd"`. `TIME` → `"HH:mm:ss"` (new case vs. the coordinator module's `genericRow`, needed for `/timetable`'s `start_time`/`end_time` and `/reports/my-classes`'s date-typed `date` alias — actually `date` is a `DATE` column via `cs.session_date AS date`, but `start_time`/`end_time` on `pp.timetable` are `TIME WITHOUT TIME ZONE`, requiring the new `java.sql.Types.TIME` branch). `TIMESTAMP` → ISO-Z (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) — not used by any of this module's 9 queries directly (no `created_at`/`updated_at` columns are selected anywhere in this module's SQL) but kept in `genericRow` for parity/future-proofing. Map keys are literal snake_case (the SQL column alias, verbatim, via `LinkedHashMap` so key order matches the `SELECT` list).
> 6. **snake_case JSON** global default (already configured). No request bodies in this module (all 9 routes are GET) — no request DTOs needed.
> 7. **Auth: class-level `@PreAuthorize("isAuthenticated()")` on `TeacherController`.** Node's `auth` middleware (JWT-verify only, no role check) gates all 9 routes — `isAuthenticated()` is the faithful match, same posture as the coordinator module. Do **not** add `hasRole('TEACHER')` — that would be a behavior regression (a logged-in coordinator/admin hitting these routes today gets 200 + empty/zeroed results, not 403; changing that is out of scope for a wire-parity port).
> 8. **Scoping: `principal.userId()` bound inline as the SQL parameter compared against `pp.teacher.user_id`, in every scoped query, exactly as Node's `req.user.user_id` is** — no separate "resolve teacher_id from user_id" pre-step/service call anywhere. If the logged-in user has no matching `pp.teacher` row, `JOIN`-based queries return zero rows (or `404` for `/profile`, since its `rows[0]` is `undefined`); the one `LEFT JOIN`-based query (`/dashboard` overview) returns zeroed/COALESCEd stats instead of 404. **Never take the teacher/user id from a client-supplied param.**
> 9. **Quirks preserved verbatim (do NOT fix), each with a code comment pointing at this section:**
>    - **§7.1 `/students/:id/inactive-history` has NO teacher-ownership check (IDOR).** Ported as-is for wire parity — same posture as the existing coordinator-module `inactive-history` endpoint, which has the same gap. Flagged, not silently fixed.
>    - **§7.3 `/students` fans out duplicate rows** via the unbounded `LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id AND sm.active_yn='INACTIVE'` (no `ORDER BY`/dedup on that join). A student with >1 `pp.inactive_students` row appears once per row, each carrying a different `inactive_reason`. Do **not** add `DISTINCT ON` or any dedup — preserve the exact query text and row order; the client dedups client-side (last-wins `Map`).
>    - **§7.2 `/dashboard`'s `monthlyTrend` returns the EARLIEST 6 months**, not the most recent 6, despite the SQL comment `-- 3. Get month-wise trend (Last 6 months)`. `ORDER BY DATE_TRUNC('month', cs.session_date) ASC LIMIT 6` is ported verbatim — do not "fix" to `DESC` + reverse.
>    - **§7.5 hardcoded `user-photos/{id}.jpg` photo-path convention** on `/profile` (keyed by `principal.userId()`) and `/coordinators` (keyed by each row's own `user_id`) — a literal Java string template (`"user-photos/" + id + ".jpg"`), never a DB column read (neither `pp.teacher` nor `pp."user"` has a `photo_link` column).
> 10. **No transactions anywhere** — all 9 endpoints are pure reads. `/dashboard`'s 4 sub-queries run as 4 independent, sequential `JdbcClient` calls (Node runs them concurrently via `Promise.all` on 4 pooled connections with no cross-query consistency guarantee to preserve; sequential Java calls are observably equivalent for this read-only self-service screen).
> 11. **Test isolation:** all `*IT` extend `PgIntegrationTest`, `@AutoConfigureMockMvc`. `@AfterEach` cleans children-before-parents. FK chain to respect for seeds: `pp."user"` → `pp.teacher` (`user_id`, no FK constraint declared but semantically linked) → `pp.subject` / `pp.cohort` → `pp.teacher_subject` (composite PK, FKs to `teacher`/`subject`) → `pp.batch` (FK `cohort_number` CASCADE) → `pp.classroom` (FKs `subject_id`/`teacher_id` SET NULL) → `pp.classroom_batch` (junction, both FKs CASCADE) → `pp.timetable` (FK `classroom_id`) → `pp.class_session` (FK `classroom_id` + own `teacher_id` FK, no `ON DELETE` — direct column, not derived through `classroom.teacher_id`, per ground-truth §3) → `pp.student_master` (FK `batch_id`, no `ON DELETE`) → `pp.student_attendance` (FK `session_id` CASCADE, FK `student_id`) → `pp.inactive_students` (FK `student_id`, no `ON DELETE`) → `pp.batch_coordinator_batches` (junction, FKs to `"user"`/`batch`, no `ON DELETE`). Advance every sequence (`setval`) after an explicit-PK insert. **Distinct numeric-prefix range per task** (Task 1: `966101xx`, Task 2: `966201xx`, Task 3: `966301xx`) to avoid any cross-class collision in the shared embedded-Postgres JVM.
> 12. **`pp."user"`** is a quoted reserved word; unquoted `pp.user` (after the dot, e.g. `JOIN pp.user u`) is accepted by Postgres as a reference — the DDL itself must use the quoted form.
> 13. **jsonPath gotchas** (from prior phases): `.value(List.of(...))` on a filter array silently fails — use `Matchers.contains(...)`/`hasSize(...)` instead. `.value(genericCall())` resolves to the `Matcher` overload if the call's static type is ambiguous — bind to an `Object` local first, then `.value(theLocal)`.

---

## Firm Decisions (the 6 locked items from the brief, restated for traceability)

| # | Decision |
|---|---|
| 1 | Auth = class-level `@PreAuthorize("isAuthenticated()")` on `TeacherController` — no role check, matching Node's `auth` middleware. |
| 2 | Scoping = `principal.userId()` bound inline in every query's `JOIN pp.teacher t ON ... WHERE t.user_id = :userId::numeric` (or equivalent alias) — never a client param, never a separate teacher-id-lookup step. |
| 3 | `genericRow` module-local static (numeric/bigint → String via `toPlainString()`/`getLong`, integer/boolean → native JSON via passthrough, DATE → `yyyy-MM-dd`, TIME → `HH:mm:ss`, TIMESTAMP → ISO-Z). Reads return `List<Map<String,Object>>` / nested `Map<String,Object>` — no typed DTOs. |
| 4 | SQL ported VERBATIM from the ground truth/live source, with 4 quirks explicitly preserved (IDOR, fan-out, monthlyTrend-earliest-6, hardcoded photo path) — see Deferred/Flagged below. |
| 5 | Error envelopes exact per endpoint (`{error:...}` vs `{message:...}` + status), including the numeric-0-stays-string quirk. |
| 6 | No transactions — all 9 endpoints read-only, plain `JdbcClient`. |

## Deferred / Flagged (do not fix in this phase)

| # | Issue | Where | Disposition |
|---|---|---|---|
| 1 | **IDOR**: `/students/:id/inactive-history` has no teacher-ownership check — any authenticated user can read any student's inactive-history log by id. | `TeacherStudentModel.js:156-174`, ground truth §7.1 | Port as-is. Same posture as the existing coordinator-module `inactive-history` IDOR. Recommend a follow-up security ticket, out of scope here. |
| 2 | **Fan-out**: `/students` duplicates rows per `pp.inactive_students` entry via an unbounded `LEFT JOIN` with no dedup. | `TeacherStudentModel.js:46-91,97-150`, ground truth §7.3 | Port the exact query + row order; do not add `DISTINCT ON`. Client dedups (last-wins) client-side. |
| 3 | **Mislabeled monthlyTrend**: `/dashboard` returns the earliest 6 months of session data, not the most recent 6, despite the code comment. | `TeacherDashboardModel.js:29-40`, ground truth §7.2 | Port `ORDER BY ... ASC LIMIT 6` verbatim. Do not "fix" to `DESC` + reverse without product sign-off. |
| 4 | **Hardcoded photo path**: `user-photos/{id}.jpg` on `/profile` and `/coordinators`, not DB-backed. | `TeacherProfileController.js:14`, `TeacherCoordinatorController.js:10-13`, ground truth §7.5 | Reproduce as a literal Java string template, never a DB column read. |
| 5 | **Asymmetric LEFT JOIN vs JOIN across the 4 dashboard sub-queries**: query 1 (overview) uses `LEFT JOIN` so a teacher with zero sessions still gets a zeroed row (`total_conducted:"0"`, `avg_attendance:"0"`); queries 2/3 (`subjectAnalysis`/`monthlyTrend`) use `JOIN` + `GROUP BY` so the same teacher gets `[]`, not `[{...,0}]`. | `TeacherDashboardModel.js`, ground truth §7.7 | Preserve the asymmetry — do not backfill zeroed rows into the empty-array cases. |

---

## Task 1 — Module skeleton + simple scoped reads: `/cohorts`, `/batches`, `/profile`, `/coordinators`

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/web/TeacherController.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/persistence/TeacherReadRepository.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherScopedReadsIT.java`

- [ ] **Step 1 — failing test.** Create `TeacherScopedReadsIT.java`:

```java
package com.rcf.imas.modules.teacher;

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
class TeacherScopedReadsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;
    String noTeacherRowToken;

    @BeforeEach
    void seed() {
        cleanup();

        // pp."user" rows: the teacher whose portal we're testing, a second teacher with a teacher row but
        // NO classroom (to prove scoping returns empty, not a leak), and the coordinator who will surface
        // in /coordinators.
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966101,'teacherUser966101','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966102,'otherTeacherUser966102','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn, full_name, user_email, contact_no, active_yn) " +
                "VALUES (966103,'coordUser966103','x','N','Coord Full Name 966103','coord966103@example.com','9000000003','Y')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name, qualification, experience_yrs, doj, contact_no) " +
                "VALUES (966101,966101,'Teacher 966101','B.Ed',5,'2020-01-01','9000000001')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966102,966102,'Other Teacher 966102')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966101,'ENG1','English')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher_subject(teacher_id, subject_id, medium) VALUES (966101,966101,'ENGLISH')").update();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966101,'Cohort 966101')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966101,'Batch 966101',966101)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id, class_link) " +
                "VALUES (966101,'Classroom 966101',966101,966101,'http://class.link/966101')").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966101,966101)").update();

        jdbc.sql("INSERT INTO pp.batch_coordinator_batches(user_id, batch_id) VALUES (966103,966101)").update();

        teacherToken = jwt.issueFinalToken("966101", "teacherUser966101", "TEACHER");
        otherTeacherToken = jwt.issueFinalToken("966102", "otherTeacherUser966102", "TEACHER");
        noTeacherRowToken = jwt.issueFinalToken("999999", "noSuchTeacher999999", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.batch_coordinator_batches WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id = 966101").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id = 966101").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id = 966101").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966101").update();
        jdbc.sql("DELETE FROM pp.teacher_subject WHERE teacher_id = 966101").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966101").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966101,966102)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966101,966102,966103)").update();
    }

    @Test
    void cohortsReturnsOnlyReachableCohort() throws Exception {
        mvc.perform(get("/api/teacher/cohorts").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].cohort_number").value(966101))
           .andExpect(jsonPath("$[0].cohort_name").value("Cohort 966101"));
    }

    @Test
    void cohortsScopedToTeacherReturnsEmptyForTeacherWithNoClassroom() throws Exception {
        mvc.perform(get("/api/teacher/cohorts").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void batchesNoFilterReturnsReachableBatch() throws Exception {
        mvc.perform(get("/api/teacher/batches").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(966101))
           .andExpect(jsonPath("$[0].batch_name").value("Batch 966101"));
    }

    @Test
    void batchesFilteredByCohortNumber() throws Exception {
        mvc.perform(get("/api/teacher/batches").param("cohort_number", "966101")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].batch_id").value(966101));
    }

    @Test
    void profileReturnsOwnProfileWithSubjectsClassroomsAndPhotoLink() throws Exception {
        mvc.perform(get("/api/teacher/profile").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.teacher_id").value(966101))
           .andExpect(jsonPath("$.teacher_name").value("Teacher 966101"))
           .andExpect(jsonPath("$.username").value("teacherUser966101"))
           .andExpect(jsonPath("$.subjects_taught").value("English (ENGLISH)"))
           .andExpect(jsonPath("$.assigned_classrooms").value("Classroom 966101"))
           .andExpect(jsonPath("$.photo_link").value("user-photos/966101.jpg"));
    }

    @Test
    void profileReturns404WhenNoTeacherRowForUser() throws Exception {
        mvc.perform(get("/api/teacher/profile").header("Authorization", "Bearer " + noTeacherRowToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Teacher profile not found"));
    }

    @Test
    void coordinatorsReturnsSharedCoordinatorWithPhotoLink() throws Exception {
        mvc.perform(get("/api/teacher/coordinators").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].user_id").value("966103"))
           .andExpect(jsonPath("$[0].full_name").value("Coord Full Name 966103"))
           .andExpect(jsonPath("$[0].shared_batches").value("Batch 966101"))
           .andExpect(jsonPath("$[0].photo_link").value("user-photos/966103.jpg"));
    }

    @Test
    void unauthenticatedRequestRejected() throws Exception {
        mvc.perform(get("/api/teacher/cohorts")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherScopedReadsIT` — expect **FAIL** (compile error: `TeacherController`/`TeacherReadRepository` do not exist yet).

- [ ] **Step 2 — implement.** Create `TeacherReadRepository.java`:

```java
package com.rcf.imas.modules.teacher.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TeacherReadRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcClient jdbc;

    public TeacherReadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The ONE genericRow definition for the teacher module (LOCKED CONVENTIONS #3): deliberately DIFFERENT
     * from CoordinatorReadRepository.genericRow's NUMERIC/DECIMAL branch. That branch uses
     * bd.toBigInteger().toString() safely ONLY because every NUMERIC column in the coordinator module's
     * 14-endpoint slice happens to be a whole-number id. This module's /dashboard avg_attendance is a
     * genuinely fractional NUMERIC (e.g. "85.00", or "0" via COALESCE when there's no attendance data) --
     * toBigInteger() would silently truncate "85.00" to "85". toPlainString() is correct for BOTH
     * whole-number ids (student_id, enr_id, applicant_id, user_id -- stored scale 0, so toPlainString()
     * prints with no decimal point) AND the one fractional column, matching node-pg's numeric-is-always-
     * a-string behavior exactly.
     */
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
                    val = bd == null ? null : bd.toPlainString();
                }
                case java.sql.Types.BIGINT -> {
                    long v = rs.getLong(i); val = rs.wasNull() ? null : String.valueOf(v);
                }
                case java.sql.Types.DATE -> {
                    java.sql.Date d = rs.getDate(i);
                    val = d == null ? null : DATE_FMT.format(d.toLocalDate());
                }
                case java.sql.Types.TIME -> {
                    Time t = rs.getTime(i);
                    val = t == null ? null : TIME_FMT.format(t.toLocalTime());
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

    /** #1 getCohortsController -- TeacherStudentController.js:16-25. */
    public List<Map<String, Object>> cohorts(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT c.cohort_number, c.cohort_name
                FROM pp.cohort c
                JOIN pp.batch b ON c.cohort_number = b.cohort_number
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric
                ORDER BY c.cohort_number DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #2 getBatchesController, unfiltered branch -- TeacherStudentController.js:43-59. */
    public List<Map<String, Object>> batches(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric
                ORDER BY b.batch_name ASC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #2 getBatchesController, cohort_number-filtered branch -- TeacherStudentController.js:43-59. */
    public List<Map<String, Object>> batchesByCohort(String userId, String cohortNumber) {
        return jdbc.sql("""
                SELECT DISTINCT b.batch_id, b.batch_name
                FROM pp.batch b
                JOIN pp.classroom_batch cb ON b.batch_id = cb.batch_id
                JOIN pp.classroom cl ON cb.classroom_id = cl.classroom_id
                JOIN pp.teacher t ON cl.teacher_id = t.teacher_id
                WHERE t.user_id = :userId::numeric AND b.cohort_number = :cohortNumber::integer
                ORDER BY b.batch_name ASC
                """).param("userId", userId).param("cohortNumber", cohortNumber)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** #6 getTeacherProfileByUserId -- TeacherProfileModel.js:3-33. Returns null if no pp.teacher row
     *  matches (controller maps that to 404, matching Node's rows[0] === undefined check). */
    public Map<String, Object> profile(String userId) {
        return jdbc.sql("""
                SELECT
                    t.teacher_id,
                    t.teacher_name,
                    t.qualification,
                    t.experience_yrs,
                    t.doj,
                    t.contact_no,
                    u.user_name AS username,
                    (
                        SELECT string_agg(DISTINCT s.subject_name || ' (' || ts.medium || ')', ', ')
                        FROM pp.teacher_subject ts
                        JOIN pp.subject s ON ts.subject_id = s.subject_id
                        WHERE ts.teacher_id = t.teacher_id
                    ) AS subjects_taught,
                    (
                        SELECT string_agg(DISTINCT c.classroom_name, ', ')
                        FROM pp.classroom c
                        WHERE c.teacher_id = t.teacher_id
                    ) AS assigned_classrooms
                FROM pp.teacher t
                JOIN pp."user" u ON t.user_id = u.user_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).optional().orElse(null);
    }

    /** #7 getCoordinatorsForTeacher -- TeacherCoordinatorModel.js:3-32. */
    public List<Map<String, Object>> coordinators(String userId) {
        return jdbc.sql("""
                SELECT
                    u.user_id,
                    u.full_name,
                    u.user_email,
                    u.contact_no,
                    u.active_yn,
                    string_agg(DISTINCT b.batch_name, ', ') AS shared_batches
                FROM pp.teacher t
                JOIN pp.classroom cl ON t.teacher_id = cl.teacher_id
                JOIN pp.classroom_batch cb ON cl.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.batch_coordinator_batches bcb ON b.batch_id = bcb.batch_id
                JOIN pp."user" u ON bcb.user_id = u.user_id
                WHERE t.user_id = :userId::numeric
                  AND u.active_yn = 'Y'
                GROUP BY
                    u.user_id,
                    u.full_name,
                    u.user_email,
                    u.contact_no,
                    u.active_yn
                ORDER BY u.full_name ASC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }
}
```

Create `TeacherController.java`:

```java
package com.rcf.imas.modules.teacher.web;

import com.rcf.imas.modules.teacher.persistence.TeacherReadRepository;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teacher")
@PreAuthorize("isAuthenticated()")   // teacherStudentRoutes.js: every one of the 9 routes is `auth`-gated,
                                       // no role check (ground truth §0) -- mirror CoordinatorController.
public class TeacherController {

    private final TeacherReadRepository reads;

    public TeacherController(TeacherReadRepository reads) {
        this.reads = reads;
    }

    /** #1 getCohortsController. */
    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            return reads.cohorts(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #2 getBatchesController -- Node's `if (cohort_number)` is JS-truthiness: "" is falsy, so a cleared
     *  filter dropdown (?cohort_number=) must fall through to the unfiltered branch, not ''::integer -> 500. */
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(name = "cohort_number", required = false) String cohortNumber,
                                                @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            cohortNumber = blankToNull(cohortNumber);
            if (cohortNumber != null) {
                return reads.batchesByCohort(principal.userId(), cohortNumber);
            }
            return reads.batches(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #6 getTeacherProfileController -- photo_link is a hardcoded string template (ground truth §7.5),
     *  never a DB column; keyed by the AUTHENTICATED principal's userId, never a client param. */
    @GetMapping("/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal JwtService.FinalToken principal) {
        Map<String, Object> profile;
        try {
            profile = reads.profile(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
        if (profile == null) {
            throw ApiException.error(404, "Teacher profile not found");
        }
        Map<String, Object> withPhoto = new HashMap<>(profile);
        withPhoto.put("photo_link", "user-photos/" + principal.userId() + ".jpg");
        return withPhoto;
    }

    /** #7 getTeacherCoordinatorsController -- photo_link injected per-row, same hardcoded convention. */
    @GetMapping("/coordinators")
    public List<Map<String, Object>> coordinators(@AuthenticationPrincipal JwtService.FinalToken principal) {
        List<Map<String, Object>> coordinators;
        try {
            coordinators = reads.coordinators(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
        return coordinators.stream().map(row -> {
            Map<String, Object> withPhoto = new HashMap<>(row);
            withPhoto.put("photo_link", "user-photos/" + row.get("user_id") + ".jpg");
            return withPhoto;
        }).toList();
    }

    /** Mirrors Node's JS-truthiness param guards: a present-but-empty query param ("") is falsy in Node,
     *  so treat blank as absent rather than passing it on to an ::integer/::numeric cast. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherScopedReadsIT` — expect **PASS** (all 7 tests green: cohortsReturnsOnlyReachableCohort, cohortsScopedToTeacherReturnsEmptyForTeacherWithNoClassroom, batchesNoFilterReturnsReachableBatch, batchesFilteredByCohortNumber, profileReturnsOwnProfileWithSubjectsClassroomsAndPhotoLink, profileReturns404WhenNoTeacherRowForUser, coordinatorsReturnsSharedCoordinatorWithPhotoLink, unauthenticatedRequestRejected).

- [ ] Commit:
```
git add imas-backend/src/main/java/com/rcf/imas/modules/teacher/ imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherScopedReadsIT.java
git commit -m "feat(teacher): module skeleton + cohorts/batches/profile/coordinators (Phase 5c Task 1/3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2 — Timetable + students + inactive-history

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/persistence/TeacherReadRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/web/TeacherController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherTimetableAndStudentsIT.java`

- [ ] **Step 1 — failing test.** Create `TeacherTimetableAndStudentsIT.java`:

```java
package com.rcf.imas.modules.teacher;

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
class TeacherTimetableAndStudentsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966201,'teacherUser966201','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966202,'otherTeacherUser966202','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966201,966201,'Teacher 966201')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966202,966202,'Other Teacher 966202')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966201,'MAT1','Maths')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966201,'Cohort 966201')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        // Two batches under the same cohort: batch 966201 fed by classroom 966201, batch 966202 fed by
        // classroom 966202 -- lets the timetable batchId filter and the students cohort+batch filter
        // both discriminate cleanly.
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966201,'Batch 966201',966201)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966202,'Batch 966202',966201)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966201,'Classroom 966201',966201,966201)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966202,'Classroom 966202',966201,966201)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966201,966201)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966202,966202)").update();

        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966201,966201,'MONDAY','09:00:00','10:00:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966202,966201,'WEDNESDAY','11:00:00','12:00:00')").update();
        jdbc.sql("INSERT INTO pp.timetable(timetable_id, classroom_id, day_of_week, start_time, end_time) " +
                "VALUES (966203,966202,'FRIDAY','08:00:00','09:00:00')").update();
        jdbc.sql("SELECT setval('pp.timetable_id_seq', (SELECT MAX(timetable_id)::bigint FROM pp.timetable))").query(Long.class).single();

        // Students: 966211 ACTIVE in batch 966201; 966212 INACTIVE in batch 966201 with TWO
        // pp.inactive_students rows (fan-out quirk, ground truth §7.3); 966213 ACTIVE in batch 966202.
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966211,'Student 966211',966201,'F','ACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966212,'Student 966212',966201,'M','INACTIVE')").update();
        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966213,'Student 966213',966202,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) " +
                "VALUES (966212,'Reason A','2025-01-01')").update();
        jdbc.sql("INSERT INTO pp.inactive_students(student_id, inactive_reason, inactive_date) " +
                "VALUES (966212,'Reason B','2025-02-01')").update();

        teacherToken = jwt.issueFinalToken("966201", "teacherUser966201", "TEACHER");
        otherTeacherToken = jwt.issueFinalToken("966202", "otherTeacherUser966202", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.inactive_students WHERE student_id = 966212").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id IN (966211,966212,966213)").update();
        jdbc.sql("DELETE FROM pp.timetable WHERE timetable_id IN (966201,966202,966203)").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966201").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id = 966201").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966201,966202)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966201,966202)").update();
    }

    @Test
    void timetableNoFilterReturnsAllRowsOrderedByDayThenTime() throws Exception {
        mvc.perform(get("/api/teacher/timetable").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(3)))
           .andExpect(jsonPath("$[0].day_of_week").value("MONDAY"))
           .andExpect(jsonPath("$[1].day_of_week").value("WEDNESDAY"))
           .andExpect(jsonPath("$[2].day_of_week").value("FRIDAY"))
           .andExpect(jsonPath("$[0].start_time").value("09:00:00"));
    }

    @Test
    void timetableFilteredByBatchIdReturnsOnlyThatClassroomsRows() throws Exception {
        mvc.perform(get("/api/teacher/timetable").param("batchId", "966202")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].day_of_week").value("FRIDAY"));
    }

    @Test
    void timetableScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/timetable").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void studentsNoFilterFansOutOnInactiveJoin() throws Exception {
        // 966211 (1 row) + 966212 (2 rows, one per inactive_students entry) + 966213 (1 row) = 4 rows.
        mvc.perform(get("/api/teacher/students").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(4)))
           .andExpect(jsonPath("$[?(@.student_id=='966212')]", hasSize(2)));
    }

    @Test
    void studentsCohortAndBatchFilterScopesToOneBatch() throws Exception {
        mvc.perform(get("/api/teacher/students")
                .param("cohortNumber", "966201").param("batchId", "966202")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(1)))
           .andExpect(jsonPath("$[0].student_id").value("966213"));
    }

    @Test
    void studentsScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/students").header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void inactiveHistoryOrderedDescByDateNoOwnershipCheck() throws Exception {
        // IDOR preserved (ground truth §7.1): otherTeacherToken -- who does NOT teach this student -- can
        // still read the history. That's the documented, deliberately-ported behavior, not a test bug.
        mvc.perform(get("/api/teacher/students/966212/inactive-history")
                .header("Authorization", "Bearer " + otherTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$", hasSize(2)))
           .andExpect(jsonPath("$[0].inactive_reason").value("Reason B"))
           .andExpect(jsonPath("$[1].inactive_reason").value("Reason A"));
    }
}
```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherTimetableAndStudentsIT` — expect **FAIL** (404s: no `/timetable`, `/students`, `/students/{id}/inactive-history` mappings yet).

- [ ] **Step 2 — implement.** Add to `TeacherReadRepository.java` (insert before the closing `}`):

```java
    /** #3 getTimetableByBatchAndTeacher, unfiltered branch -- TeacherTimetableModel.js:3-53. */
    public List<Map<String, Object>> timetable(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT
                    t.timetable_id,
                    t.day_of_week,
                    t.start_time,
                    t.end_time,
                    c.classroom_id,
                    c.classroom_name,
                    c.class_link,
                    s.subject_name,
                    s.subject_code,
                    tch.teacher_name,
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1
                        WHEN 'MONDAY' THEN 2
                        WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4
                        WHEN 'THURSDAY' THEN 5
                        WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END as day_order
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
                WHERE tch.user_id = :userId::numeric
                ORDER BY day_order, t.start_time
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #3 getTimetableByBatchAndTeacher, batchId-filtered branch -- TeacherTimetableModel.js:3-53. */
    public List<Map<String, Object>> timetableByBatch(String userId, String batchId) {
        return jdbc.sql("""
                SELECT DISTINCT
                    t.timetable_id,
                    t.day_of_week,
                    t.start_time,
                    t.end_time,
                    c.classroom_id,
                    c.classroom_name,
                    c.class_link,
                    s.subject_name,
                    s.subject_code,
                    tch.teacher_name,
                    CASE t.day_of_week
                        WHEN 'SUNDAY' THEN 1
                        WHEN 'MONDAY' THEN 2
                        WHEN 'TUESDAY' THEN 3
                        WHEN 'WEDNESDAY' THEN 4
                        WHEN 'THURSDAY' THEN 5
                        WHEN 'FRIDAY' THEN 6
                        WHEN 'SATURDAY' THEN 7
                    END as day_order
                FROM pp.timetable t
                JOIN pp.classroom c ON t.classroom_id = c.classroom_id
                LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
                INNER JOIN pp.teacher tch ON c.teacher_id = tch.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE tch.user_id = :userId::numeric AND cb.batch_id = :batchId::integer
                ORDER BY day_order, t.start_time
                """).param("userId", userId).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    private static final String STUDENT_SELECT = """
            sm.student_id,
            sm.applicant_id,
            sm.enr_id,
            sm.student_name,
            sm.gender,
            sm.father_name,
            sm.father_occupation,
            sm.mother_name,
            sm.mother_occupation,
            sm.student_email,
            sm.student_email_password,
            sm.parent_email,
            sm.contact_no1,
            sm.contact_no2,
            sm.home_address,
            sm.current_institute_dise_code,
            sm.previous_institute_dise_code,
            ci.institute_name AS current_institute,
            pi.institute_name AS previous_institute,
            sm.sim_name,
            sm.teacher_name,
            sm.teacher_mobile_number,
            sm.active_yn,
            sm.recharge_status,
            sm.sponsor,
            sm.photo_link,
            sm.batch_id,
            b.batch_name,
            c.cohort_number,
            c.cohort_name,
            ins.inactive_reason,
            sm.created_at,
            sm.updated_at
            """;

    /** #4 getStudentsByTeacher -- TeacherStudentModel.js:46-91. FAN-OUT QUIRK (ground truth §7.3): the
     *  LEFT JOIN pp.inactive_students below has no ORDER BY/dedup -- a student with >1 inactive_students
     *  row appears once per row. Do NOT add DISTINCT ON here; the client dedups (last-wins) client-side. */
    public List<Map<String, Object>> studentsByTeacher(String userId) {
        return jdbc.sql("SELECT DISTINCT " + STUDENT_SELECT + """
                FROM pp.teacher t
                JOIN pp.classroom cr ON cr.teacher_id = t.teacher_id
                JOIN pp.classroom_batch cb ON cb.classroom_id = cr.classroom_id
                JOIN pp.batch b ON b.batch_id = cb.batch_id
                JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                JOIN pp.student_master sm ON sm.batch_id = b.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins
                    ON ins.student_id = sm.student_id
                   AND sm.active_yn = 'INACTIVE'
                WHERE t.user_id = :userId::numeric
                ORDER BY c.cohort_number, b.batch_name, sm.student_name
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #4 getStudentsByTeacherBatch -- TeacherStudentModel.js:97-150. Same fan-out quirk as above. */
    public List<Map<String, Object>> studentsByTeacherAndBatch(String userId, String cohortNumber, String batchId) {
        return jdbc.sql("SELECT DISTINCT " + STUDENT_SELECT + """
                FROM pp.teacher t
                JOIN pp.classroom cr ON cr.teacher_id = t.teacher_id
                JOIN pp.classroom_batch cb ON cb.classroom_id = cr.classroom_id
                JOIN pp.batch b ON b.batch_id = cb.batch_id
                JOIN pp.cohort c ON c.cohort_number = b.cohort_number
                JOIN pp.student_master sm ON sm.batch_id = b.batch_id
                LEFT JOIN pp.institute ci ON ci.dise_code = sm.current_institute_dise_code
                LEFT JOIN pp.institute pi ON pi.dise_code = sm.previous_institute_dise_code
                LEFT JOIN pp.inactive_students ins
                    ON ins.student_id = sm.student_id
                   AND sm.active_yn = 'INACTIVE'
                WHERE
                    t.user_id = :userId::numeric
                    AND c.cohort_number = :cohortNumber::integer
                    AND b.batch_id = :batchId::integer
                ORDER BY sm.student_name
                """).param("userId", userId).param("cohortNumber", cohortNumber).param("batchId", batchId)
                .query((rs, i) -> genericRow(rs)).list();
    }

    /** #5 getInactiveHistoryByStudentId -- TeacherStudentModel.js:156-174. NO teacher-ownership check
     *  (ground truth §7.1, IDOR) -- ported as-is, flagged in the plan's Deferred section, not fixed here. */
    public List<Map<String, Object>> inactiveHistory(String studentId) {
        return jdbc.sql("""
                SELECT
                    inactive_reason,
                    inactive_date,
                    created_by,
                    updated_by
                FROM pp.inactive_students
                WHERE student_id = :studentId::numeric
                ORDER BY inactive_date DESC
                """).param("studentId", studentId).query((rs, i) -> genericRow(rs)).list();
    }
```

Add to `TeacherController.java` (insert before the private `blankToNull` helper):

```java
    /** #3 getTimetableController -- Node "removed the strict requirement for batchId" (comment in
     *  TeacherTimetableController.js), so batchId is optional; blank ("") treated as absent. */
    @GetMapping("/timetable")
    public List<Map<String, Object>> timetable(@RequestParam(required = false) String batchId,
                                                  @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            batchId = blankToNull(batchId);
            if (batchId != null) {
                return reads.timetableByBatch(principal.userId(), batchId);
            }
            return reads.timetable(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #4 getStudentsController -- both cohortNumber AND batchId must be present to switch to the
     *  batch-scoped query; either missing falls through to the all-students-for-teacher branch. */
    @GetMapping("/students")
    public List<Map<String, Object>> students(@RequestParam(required = false) String cohortNumber,
                                                 @RequestParam(required = false) String batchId,
                                                 @AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            cohortNumber = blankToNull(cohortNumber);
            batchId = blankToNull(batchId);
            if (cohortNumber != null && batchId != null) {
                return reads.studentsByTeacherAndBatch(principal.userId(), cohortNumber, batchId);
            }
            return reads.studentsByTeacher(principal.userId());
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch students.");
        }
    }

    /** #5 getInactiveHistoryController -- studentId comes from the URL path, NOT the JWT principal (ground
     *  truth §7.1: no teacher-ownership check in Node, preserved verbatim -- see plan's Deferred section). */
    @GetMapping("/students/{id}/inactive-history")
    public List<Map<String, Object>> inactiveHistory(@PathVariable String id) {
        try {
            return reads.inactiveHistory(id);
        } catch (Exception e) {
            throw ApiException.error(500, "Failed to fetch inactive history.");
        }
    }

```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherTimetableAndStudentsIT` — expect **PASS** (all 7 tests green).

- [ ] Also run Task 1's test to confirm no regression: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherScopedReadsIT` — expect **PASS**.

- [ ] Commit:
```
git add imas-backend/src/main/java/com/rcf/imas/modules/teacher/ imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherTimetableAndStudentsIT.java
git commit -m "feat(teacher): timetable + students (fan-out preserved) + inactive-history IDOR-preserved (Phase 5c Task 2/3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3 — Dashboard + reports/my-classes

**Files:**
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/persistence/TeacherReadRepository.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/teacher/web/TeacherController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherDashboardAndReportsIT.java`

- [ ] **Step 1 — failing test.** Create `TeacherDashboardAndReportsIT.java`:

```java
package com.rcf.imas.modules.teacher;

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
class TeacherDashboardAndReportsIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String teacherToken;
    String emptyTeacherToken;

    @BeforeEach
    void seed() {
        cleanup();

        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966301,'teacherUser966301','x','N')").update();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (966303,'emptyTeacherUser966303','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();

        // 966301 has classrooms/sessions/attendance; 966302 has a pp.teacher row but ZERO classrooms/
        // sessions (proves the LEFT JOIN-vs-JOIN asymmetry, ground truth §7.7).
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966301,966301,'Teacher 966301')").update();
        jdbc.sql("INSERT INTO pp.teacher(teacher_id, user_id, teacher_name) VALUES (966302,966303,'Empty Teacher 966302')").update();
        jdbc.sql("SELECT setval('pp.teacher_id_seq', (SELECT MAX(teacher_id)::bigint FROM pp.teacher))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966301,'PHY1','Physics')").update();
        jdbc.sql("INSERT INTO pp.subject(subject_id, subject_code, subject_name) VALUES (966302,'CHE1','Chemistry')").update();
        jdbc.sql("SELECT setval('pp.subject_id_seq', (SELECT MAX(subject_id)::bigint FROM pp.subject))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.cohort(cohort_number, cohort_name) VALUES (966301,'Cohort 966301')").update();
        jdbc.sql("SELECT setval('pp.cohort_seq', (SELECT MAX(cohort_number)::bigint FROM pp.cohort))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966301,'Batch 966301',966301)").update();
        jdbc.sql("INSERT INTO pp.batch(batch_id, batch_name, cohort_number) VALUES (966302,'Batch 966302',966301)").update();
        jdbc.sql("SELECT setval('pp.batch_id_seq', (SELECT MAX(batch_id)::bigint FROM pp.batch))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966301,'Physics Room',966301,966301)").update();
        jdbc.sql("INSERT INTO pp.classroom(classroom_id, classroom_name, subject_id, teacher_id) " +
                "VALUES (966302,'Chem Room',966302,966301)").update();
        jdbc.sql("SELECT setval('pp.classroom_id_seq', (SELECT MAX(classroom_id)::bigint FROM pp.classroom))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966301,966301)").update();
        jdbc.sql("INSERT INTO pp.classroom_batch(classroom_id, batch_id) VALUES (966302,966302)").update();

        // 8 sessions across Jan-Aug 2025: Physics room Jan-May (5), Chem room Jun-Aug (3).
        // monthlyTrend's ORDER BY ... ASC LIMIT 6 must return Jan-Jun (the EARLIEST 6 months), excluding
        // Jul/Aug -- ground truth §7.2, the mislabeled-comment quirk.
        String[] months = {"01","02","03","04","05","06","07","08"};
        for (int idx = 0; idx < months.length; idx++) {
            int sessionId = 966301 + idx;
            int classroomId = idx < 5 ? 966301 : 966302;
            jdbc.sql("INSERT INTO pp.class_session(session_id, classroom_id, teacher_id, session_date, start_time, end_time) " +
                    "VALUES (:sid,:cid,966301,:date,'09:00:00','10:00:00')")
                    .param("sid", sessionId).param("cid", classroomId)
                    .param("date", java.sql.Date.valueOf("2025-" + months[idx] + "-15")).update();
        }
        jdbc.sql("SELECT setval('pp.class_session_seq', (SELECT MAX(session_id)::bigint FROM pp.class_session))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_master(student_id, student_name, batch_id, gender, active_yn) " +
                "VALUES (966311,'Student 966311',966301,'F','ACTIVE')").update();
        jdbc.sql("SELECT setval('pp.student_id_seq', (SELECT MAX(student_id)::bigint FROM pp.student_master))").query(Long.class).single();

        jdbc.sql("INSERT INTO pp.student_attendance(attendance_id, session_id, student_id, status, attendance_percent) " +
                "VALUES (966301,966301,966311,'PRESENT',90.00)").update();
        jdbc.sql("INSERT INTO pp.student_attendance(attendance_id, session_id, student_id, status, attendance_percent) " +
                "VALUES (966302,966302,966311,'PRESENT',80.00)").update();
        jdbc.sql("SELECT setval('pp.attendance_id_seq', (SELECT MAX(attendance_id)::bigint FROM pp.student_attendance))").query(Long.class).single();

        teacherToken = jwt.issueFinalToken("966301", "teacherUser966301", "TEACHER");
        emptyTeacherToken = jwt.issueFinalToken("966303", "emptyTeacherUser966303", "TEACHER");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.student_attendance WHERE attendance_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.class_session WHERE session_id BETWEEN 966301 AND 966308").update();
        jdbc.sql("DELETE FROM pp.student_master WHERE student_id = 966311").update();
        jdbc.sql("DELETE FROM pp.classroom_batch WHERE classroom_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.classroom WHERE classroom_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.batch WHERE batch_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.cohort WHERE cohort_number = 966301").update();
        jdbc.sql("DELETE FROM pp.subject WHERE subject_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.teacher WHERE teacher_id IN (966301,966302)").update();
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id IN (966301,966303)").update();
    }

    @Test
    void dashboardOverviewSubjectAnalysisAndMonthlyTrendEarliestSix() throws Exception {
        mvc.perform(get("/api/teacher/dashboard").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overview.total_conducted").value("8"))
           .andExpect(jsonPath("$.overview.avg_attendance").value("85.00"))
           .andExpect(jsonPath("$.overview.total_batches").value("2"))
           .andExpect(jsonPath("$.subjectAnalysis", hasSize(2)))
           .andExpect(jsonPath("$.subjectAnalysis[0].subject_name").value("Physics"))
           .andExpect(jsonPath("$.subjectAnalysis[0].classes_taken").value("5"))
           .andExpect(jsonPath("$.subjectAnalysis[1].subject_name").value("Chemistry"))
           .andExpect(jsonPath("$.subjectAnalysis[1].classes_taken").value("3"))
           // monthlyTrend quirk (ground truth §7.2): earliest 6 months (Jan-Jun 2025), NOT the most recent 6.
           .andExpect(jsonPath("$.monthlyTrend", hasSize(6)))
           .andExpect(jsonPath("$.monthlyTrend[0].month_label").value("Jan 2025"))
           .andExpect(jsonPath("$.monthlyTrend[5].month_label").value("Jun 2025"));
    }

    @Test
    void dashboardZeroedForTeacherWithNoSessions() throws Exception {
        // LEFT JOIN (query 1) vs JOIN+GROUP BY (queries 2-4) asymmetry, ground truth §7.7: overview is
        // zeroed, subjectAnalysis/monthlyTrend are empty arrays, total_batches is "0" (plain aggregate,
        // no GROUP BY, always returns one row even over zero matches).
        mvc.perform(get("/api/teacher/dashboard").header("Authorization", "Bearer " + emptyTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.overview.total_conducted").value("0"))
           .andExpect(jsonPath("$.overview.avg_attendance").value("0"))
           .andExpect(jsonPath("$.overview.total_batches").value("0"))
           .andExpect(jsonPath("$.subjectAnalysis", hasSize(0)))
           .andExpect(jsonPath("$.monthlyTrend", hasSize(0)));
    }

    @Test
    void reportsMissingDatesReturns400() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("fromDate and toDate are required"));
    }

    @Test
    void reportsReturnsSessionsInRangeOrderedByDateWithAttendanceFlag() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes")
                .param("fromDate", "2025-01-01").param("toDate", "2025-03-31")
                .header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.classes", hasSize(3)))
           .andExpect(jsonPath("$.classes[0].date").value("2025-01-15"))
           .andExpect(jsonPath("$.classes[0].subject_name").value("Physics"))
           .andExpect(jsonPath("$.classes[0].cohort_name").value("Cohort 966301"))
           .andExpect(jsonPath("$.classes[0].attendance_marked").value(true))
           .andExpect(jsonPath("$.classes[2].date").value("2025-03-15"))
           .andExpect(jsonPath("$.classes[2].attendance_marked").value(false));
    }

    @Test
    void reportsScopedToTeacherReturnsEmptyForOtherTeacher() throws Exception {
        mvc.perform(get("/api/teacher/reports/my-classes")
                .param("fromDate", "2025-01-01").param("toDate", "2025-12-31")
                .header("Authorization", "Bearer " + emptyTeacherToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.classes", hasSize(0)));
    }
}
```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherDashboardAndReportsIT` — expect **FAIL** (404s: no `/dashboard`, `/reports/my-classes` mappings yet).

- [ ] **Step 2 — implement.** Add to `TeacherReadRepository.java` (insert before the closing `}`):

```java
    /** #8 getTeacherDashboardStats -- TeacherDashboardModel.js:3-67. Node runs these 4 as Promise.all
     *  (no transaction, no cross-query consistency guarantee to preserve, ground truth §6) -- 4 sequential
     *  JdbcClient calls here are observably equivalent for this read-only self-service screen. */
    public Map<String, Object> dashboardOverview(String userId) {
        Map<String, Object> stats = jdbc.sql("""
                SELECT
                    COUNT(DISTINCT cs.session_id) as total_conducted,
                    COALESCE(ROUND(AVG(sa.attendance_percent), 2), 0) as avg_attendance
                FROM pp.teacher t
                LEFT JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                LEFT JOIN pp.student_attendance sa ON cs.session_id = sa.session_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).single();
        Object totalBatches = jdbc.sql("""
                SELECT COUNT(DISTINCT cb.batch_id) as total_batches
                FROM pp.teacher t
                JOIN pp.classroom c ON t.teacher_id = c.teacher_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                WHERE t.user_id = :userId::numeric
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).single().get("total_batches");
        Map<String, Object> overview = new java.util.LinkedHashMap<>(stats);
        overview.put("total_batches", totalBatches);
        return overview;
    }

    public List<Map<String, Object>> dashboardSubjectAnalysis(String userId) {
        return jdbc.sql("""
                SELECT
                    s.subject_name,
                    COUNT(cs.session_id) as classes_taken
                FROM pp.teacher t
                JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                WHERE t.user_id = :userId::numeric
                GROUP BY s.subject_name
                ORDER BY classes_taken DESC
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** QUIRK PRESERVED (ground truth §7.2): ORDER BY ... ASC LIMIT 6 returns the EARLIEST 6 months of
     *  session data, not the most recent 6, despite Node's own code comment claiming "Last 6 months". Do
     *  NOT "fix" to DESC + reverse -- that would be a behavior change requiring product sign-off. */
    public List<Map<String, Object>> dashboardMonthlyTrend(String userId) {
        return jdbc.sql("""
                SELECT
                    TO_CHAR(cs.session_date, 'Mon YYYY') as month_label,
                    COUNT(cs.session_id) as classes_taken
                FROM pp.teacher t
                JOIN pp.class_session cs ON t.teacher_id = cs.teacher_id
                WHERE t.user_id = :userId::numeric
                GROUP BY TO_CHAR(cs.session_date, 'Mon YYYY'), DATE_TRUNC('month', cs.session_date)
                ORDER BY DATE_TRUNC('month', cs.session_date) ASC
                LIMIT 6
                """).param("userId", userId).query((rs, i) -> genericRow(rs)).list();
    }

    /** #9 getMyClassReports -- TeacherReportModel.js:3-39. */
    public List<Map<String, Object>> myClassReports(String userId, String fromDate, String toDate) {
        return jdbc.sql("""
                SELECT
                    cs.session_id,
                    cs.session_date AS date,
                    co.cohort_name,
                    string_agg(DISTINCT b.batch_name, ', ') AS batch_name,
                    c.classroom_name,
                    s.subject_name,
                    EXISTS (
                        SELECT 1
                        FROM pp.student_attendance sa
                        WHERE sa.session_id = cs.session_id
                    ) AS attendance_marked
                FROM pp.class_session cs
                JOIN pp.teacher t ON cs.teacher_id = t.teacher_id
                JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
                JOIN pp.subject s ON c.subject_id = s.subject_id
                JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
                JOIN pp.batch b ON cb.batch_id = b.batch_id
                JOIN pp.cohort co ON b.cohort_number = co.cohort_number
                WHERE t.user_id = :userId::numeric
                  AND cs.session_date >= :fromDate::date
                  AND cs.session_date <= :toDate::date
                GROUP BY
                    cs.session_id,
                    cs.session_date,
                    co.cohort_name,
                    c.classroom_name,
                    s.subject_name
                ORDER BY cs.session_date ASC
                """).param("userId", userId).param("fromDate", fromDate).param("toDate", toDate)
                .query((rs, i) -> genericRow(rs)).list();
    }
```

Add to `TeacherController.java` (insert before the private `blankToNull` helper):

```java
    /** #8 getTeacherDashboardController. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal JwtService.FinalToken principal) {
        try {
            String userId = principal.userId();
            Map<String, Object> overview = reads.dashboardOverview(userId);
            List<Map<String, Object>> subjectAnalysis = reads.dashboardSubjectAnalysis(userId);
            List<Map<String, Object>> monthlyTrend = reads.dashboardMonthlyTrend(userId);
            return Map.of("overview", overview, "subjectAnalysis", subjectAnalysis, "monthlyTrend", monthlyTrend);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

    /** #9 getMyClassReportsController -- requires both fromDate and toDate present (raw strings, no
     *  server-side range validation beyond presence, matching Node -- ground truth §2 #9). */
    @GetMapping("/reports/my-classes")
    public Map<String, Object> myClassReports(@RequestParam(required = false) String fromDate,
                                                 @RequestParam(required = false) String toDate,
                                                 @AuthenticationPrincipal JwtService.FinalToken principal) {
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw ApiException.error(400, "fromDate and toDate are required");
        }
        try {
            List<Map<String, Object>> classes = reads.myClassReports(principal.userId(), fromDate, toDate);
            return Map.of("classes", classes);
        } catch (Exception e) {
            throw ApiException.error(500, "Internal Server Error");
        }
    }

```

- [ ] Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherDashboardAndReportsIT` — expect **PASS** (all 5 tests green).

- [ ] Full-module regression: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=TeacherScopedReadsIT,TeacherTimetableAndStudentsIT,TeacherDashboardAndReportsIT` — expect **PASS** (19 tests total).

- [ ] Commit:
```
git add imas-backend/src/main/java/com/rcf/imas/modules/teacher/ imas-backend/src/test/java/com/rcf/imas/modules/teacher/TeacherDashboardAndReportsIT.java
git commit -m "feat(teacher): dashboard (monthlyTrend earliest-6 preserved) + reports/my-classes (Phase 5c Task 3/3, FINAL module)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-review checklist

- [x] All 9 endpoints mapped to a task: Task 1 = cohorts, batches, profile, coordinators (4); Task 2 = timetable, students, inactive-history (3); Task 3 = dashboard, reports/my-classes (2). Total 9.
- [x] Every SQL block is verbatim from the ground truth / live Node source (re-verified against the live `.js` files while writing this plan, not just the ground-truth doc).
- [x] Scoping is `principal.userId()` bound inline in every query — never a client-supplied param.
- [x] All 4 flagged quirks preserved (IDOR, fan-out, monthlyTrend-earliest-6, hardcoded photo path) with inline code comments pointing at this plan's Deferred section.
- [x] `genericRow` reused as a module-local static, with one deliberate, documented deviation from the coordinator module's version (`toPlainString()` not `toBigInteger()`) plus a new `TIME` case.
- [x] `@PreAuthorize("isAuthenticated()")` at class level on `TeacherController`, no role check.
- [x] No placeholders — every step has complete Java/SQL/test code.
- [x] Naming consistent: `TeacherController`, `TeacherReadRepository`, `com.rcf.imas.modules.teacher.{web,persistence}`.
