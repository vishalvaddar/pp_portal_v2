# IMAS Migration — Resume Handoff (read this first)

This is the single source of truth for resuming the IMAS Node→Spring Boot migration in a fresh session. Everything below is committed to git; nothing depends on prior conversation memory.

## Where we are (2026-07-05)

- **Branch:** `feat/springboot-migration-phase1` (all work lives here; not yet merged to `master`/`main`).
- **Backend:** `C:\work\rcf\imas-backend\` (Maven, Java 21, Spring Boot 3.3.5).
- **Done and green — 77 tests, no Docker:**
  - **Phase 0** (foundation): platform layer (snake_case JSON `JacksonConfig`, `ApiException`+`GlobalExceptionHandler`, Node-compatible `JwtService` via auth0 java-jwt, `JwtAuthFilter`+`SecurityConfig`), Flyway `V1__baseline.sql` derived from the live production `pg_dump`, embedded-postgres test base `PgIntegrationTest`.
  - **Phase 1** (identity + masterdata): two-step auth (`/api/auth/login` + `authorize-role`), users/roles admin (13 endpoints, ADMIN-enforced, teacher-sync), jurisdiction cascade, districts, institutes, juris-names, system-config CRUD. **Reviewed — no blocking findings.**
  - **Phase 2a** (admission — applicants + bulk upload): 12 applicant endpoints (list, get-by-id, get-by-reg, 5 counts, create, update, delete) + bulk CSV/XLSX upload. Apache Commons CSV + POI. ADMIN-enforced.
- **Next:** **Phase 2b (NMMS merge)** → then **2c (shortlisting)** → then phases 3–6 (examination/evaluation, academics/portals, scheduling/events/inventory, decommission).

## How to build/test (CRITICAL — toolchain is non-standard)

- **No Docker anywhere.** Tests use Zonky embedded-postgres (bundled PG16 binary). Deployment is plain `java -jar` (big-bang cutover, no compose/nginx-strangler).
- **Java 21 (Temurin) + Maven 3.9.9 are installed but NOT on the default PATH.** They are reachable from **git-bash** via shims in `~/bin` (which is on the bash PATH):
  - `C:\Users\shrid\bin\java`, `javac`, `mvn` (shell shims).
  - JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
  - Maven: `C:\Users\shrid\tools\apache-maven-3.9.9`
  - **If a fresh session's bash can't find `java`/`mvn`,** recreate the shims:
    ```bash
    JDK="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"; MVN="/c/Users/shrid/tools/apache-maven-3.9.9"; BIN="/c/Users/shrid/bin"; mkdir -p "$BIN"
    printf '#!/bin/sh\nexec "%s/bin/java" "$@"\n' "$JDK" > "$BIN/java"
    printf '#!/bin/sh\nexec "%s/bin/javac" "$@"\n' "$JDK" > "$BIN/javac"
    printf '#!/bin/sh\nexport JAVA_HOME="%s"\nexec "%s/bin/mvn" "$@"\n' "$JDK" "$MVN" > "$BIN/mvn"
    chmod +x "$BIN/java" "$BIN/javac" "$BIN/mvn"
    ```
- **Run tests:** `mvn -f C:/work/rcf/imas-backend/pom.xml test` (use the explicit `-f` path to avoid `cd` permission prompts). First DB-backed run downloads the PG16 binary (~1 min) and takes ~35s to start postgres. Full suite currently = **77**.
- `*IT` tests run in the normal `test` phase (Surefire `<includes>` covers `**/*IT.java`).

## LOCKED conventions (apply in every task)

1. **Plain SQL via Spring `JdbcClient` only.** No JPA, no `@Entity`, no Spring Data. Row mappers build the exact JSON.
2. **Numeric-column params: cast the PARAM, not the column** — `WHERE nmms_reg_number = :reg::numeric` (NOT `nmms_reg_number::text = :reg`, which defeats the index on 150k-row tables). Also matches Node's pg behavior (garbage id → 500, not silent 404). Applies to `applicant_id`, `nmms_reg_number`, `nmms_year`, `juris_code`, etc. Jackson-boxed `Integer`/`Long` body fields bind as numeric natively (no cast).
3. **Numeric ids serialize as Strings** (`rs.getBigDecimal(...).toBigInteger().toString()`), EXCEPT `system_config_id` which is a JSON number. `dise_code` is `varchar(15)` → `getString`. Map keys are literal (snake_case strategy only transforms POJO fields, not Map keys).
4. **snake_case JSON** is the global default (`JacksonConfig`). Request DTOs that receive camelCase keys need `@JsonProperty` pins.
5. **Errors:** throw `com.rcf.imas.platform.error.ApiException.error(status,msg)` for `{error:...}` bodies or `.message(status,msg)` for `{message:...}`. Match each endpoint's exact Node body key. The `GlobalExceptionHandler` re-throws `AccessDeniedException`/`AuthorizationDeniedException` so `@PreAuthorize` produces 403 (not 500) — do not remove that handler.
6. **Controllers:** classes package-private, but every `@RequestMapping` handler method **`public`** (package-private methods can skip `@PreAuthorize`).
7. **Auth:** enforce it. Admission/PII endpoints are `@PreAuthorize("hasRole('ADMIN')")` (audit's critical finding). Node left most open; this is intended new enforcement. Public only: `/api/auth/*` and (later) `/api/exams/hallticket/**`.
8. **Transactions:** `@Transactional` service/repository methods for multi-statement writes. **Batch-insert / multi-step transactions MUST live in a dedicated `@Repository` bean** — Spring's proxy does NOT intercept self-invoked `@Transactional` methods (a self-invoked one silently won't roll back). Verified by rollback tests.
9. **Test isolation:** all `*IT` extend `PgIntegrationTest` and share ONE JVM-wide embedded postgres. Each IT MUST `@AfterEach`-delete the FK-referencing rows it seeds (children before parents). Seed `pp.jurisdiction_type` rows before `pp.jurisdiction` rows (`INSERT ... ON CONFLICT DO NOTHING`). Advance sequences after explicit-PK seeds: `SELECT setval('pp.<seq>', (SELECT MAX(<col>)::bigint FROM pp.<table>))`.
10. **`pp."user"`** is a quoted reserved word in the schema; queries may use unquoted `pp.user` (Postgres allows the reserved word after the schema dot).
11. **JWT** stays Node-compatible (auth0 java-jwt, HS256, same claims: `user_id`, `user_name`, `role_name`; pre-auth token has `type:"PRE_AUTH_ROLE_SELECT"`+`allowed_roles`). Same `JWT_SECRET`.

## Key file locations

- **Design spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md` (+ `.pdf`).
- **Plan 1 (Phase 0/1):** `docs/superpowers/plans/2026-07-05-phase0-1-foundation-identity-masterdata.md`.
- **Plan 2a (applicants+bulk):** `docs/superpowers/plans/2026-07-05-phase2a-applicants-bulk-upload.md`.
- **Authoritative schema (live pg_dump):** `docs/superpowers/plans/artifacts/live-schema.sql` (PostgreSQL 18; `pp` schema + unrelated `public.*` tables which the baseline excludes).
- **Node source (parity target):** `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/`.
- **React client (contract):** `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/src/` (all real calls use axios w/ a global JWT interceptor in `config/axiosConfig.js`).
- **Ground truth for next phases (READ before planning):**
  - `docs/superpowers/plans/artifacts/phase2b-nmms-merge-ground-truth.md`
  - `docs/superpowers/plans/artifacts/phase2c-shortlisting-ground-truth.md`

## How to resume (the exact procedure that's been working)

For each sub-plan (2b, then 2c, then phases 3–6):
1. **Read the ground-truth artifact** for that module (already captured — see above) and skim the relevant Node source for exact SQL.
2. **Write the plan** to `docs/superpowers/plans/2026-07-05-phase2b-nmms-merge.md` (mirror Plan 2a's structure: header, ground-truth notes, endpoint contract table, File Structure, then bite-sized TDD tasks with complete code, `- [ ]` steps, red→green→commit). Delegating the draft to a subagent (with the ground truth + conventions above) then reviewing it has worked well.
3. **Review the plan** for placeholders/parity/simplicity, fix inline.
4. **Execute task-by-task** via one implementer subagent per task (point it at the specific `## Task N` section + the conventions above). After each: verify the `Tests run:` line and full-suite BUILD SUCCESS, then move on. Serialize (don't run implementers in parallel — git index races).
5. **Commit** after each task (implementer does this). Do a light spec+quality review; a consolidated review before merge like Phase 1.

## Proposed Plan 2b (NMMS merge) decomposition

Merge is the hardest module (17 endpoints). Suggested tasks:
1. **Merge module skeleton + the two fuzzy-suggestion algorithms as pure functions with GOLDEN unit tests.** (a) Dice-coefficient bigram similarity (Node `string-similarity`, threshold >0.5 in `getSuggestion`); (b) the hand-rolled prefix-char-match ratio >0.4 (`suggestValue`). These produce suggestion TEXT only (never auto-decide) but must match Node output exactly — golden-test against known inputs. Also the deterministic `generateStudentNameKey(name)=name.toLowerCase().replace(/[^a-z0-9]/g,"")` (the REAL match key).
2. **Staging uploads** `/upload-p1`, `/upload-p2` (multipart `file` + body fields; CSV via a parser; p1 strips BOM, p2 does not — reproduce; all-or-nothing for p1, silent partial for p2 reg/name failures; batch insert 5000/query into staging tables; store `student_name_key`).
3. **Read/list endpoints** `/jurisdiction`, `/applications`, `/results` (paginated 50), `/merged-status`, `/commit-status`, `/merge-status`, `/draft-districts`, `/draft-district-students`.
4. **Preview** `/preview-merge` (LEFT JOIN phase1↔phase2 on `student_name_key AND nmms_block AND district AND nmms_year AND r.match_status IS DISTINCT FROM 'MATCHED'`, group by `phase1_id` into candidates; count mapped/conflicts; return `{summary, blockWise}`).
5. **Merge writes (transactional, dedicated @Repository)** `/bulk-auto-map` (INSERT…SELECT unique 1:1 into `std_applicant_primary_info`, `ON CONFLICT (nmms_reg_number) DO NOTHING`, mark phase2 MATCHED), `/resolve-lively` (manual pair + domino auto-match), `/commit-to-primary` (INSERT…SELECT std→`applicant_primary_info`).
6. **Downloads + delete** `/download-template?phase=`, `/district/:id/download-csv` (blob CSV via a CSV writer), `/delete-district-data` (use a **whitelist enum** `p1→stg_nmms_phase1_applications`, `p2→stg_nmms_phase2_results`, `merge→std_applicant_primary_info` — NEVER interpolate the request value; the guard checks already-committed/already-drafted).

Parity risks: the two fuzzy algos (golden tests), the domino resolve (transactional order-dependence), text↔numeric staging casts, BOM handling divergence, `ON CONFLICT` idempotency.

## Notes / decisions carried

- **Big-bang cutover** (no strangler). **Secrets:** the Node `.env`/`.env.production` committed the DB password + JWT secret — rotate + purge from git history at cutover (out of code scope; in `phase1-cutover-notes.md`).
- **Simplicity > comprehensive security** (user's explicit priority): fold in only cheap/clarity-improving hardening; skip rate-limiting, CSP, password-strength, lockout. httpOnly cookies impossible (frozen React reads token from body).
- **Dropped from baseline (deferred):** the dump's PL/pgSQL functions/triggers (they live in `public.`); `updated_at` auto-update + timetable-overlap triggers absent — recreate in `pp` in a later migration if a phase needs them.
- Two known Node bugs in shortlisting (2c) to decide on: `freeze` ignores `filterMediums` (arg-count mismatch); `bulk-update-mediums` drops `allowedMediums` at the controller. Pin current behavior in tests first.
