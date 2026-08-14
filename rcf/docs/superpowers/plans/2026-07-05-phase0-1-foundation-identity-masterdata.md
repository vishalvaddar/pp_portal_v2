# IMAS Spring Boot Migration — Plan 1 of 6: Phase 0 (Foundation) + Phase 1 (Identity & Masterdata)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Spring Boot modular monolith (platform layer, security, Flyway, CI, nginx strangler map) and cut over the first traffic: `/api/auth`, users/roles, system-config, and the jurisdiction/masterdata reads.

**Architecture:** One Spring Boot 3.x app (`imas-backend/`) beside the existing Node server, both talking to the same PostgreSQL `pp` schema. nginx routes migrated prefixes to Spring Boot (:8080), everything else to Node (:4000). JWTs are byte-compatible (same secret, HS256, same claims) so sessions survive cutover. Modules: `platform` (security, error, config) + `identity` + `masterdata`.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Maven, Spring Security (stateless JWT via auth0 `java-jwt` 4.4 — same HMAC semantics as Node's `jsonwebtoken`, crucially with no 256-bit minimum-key restriction, so existing short `JWT_SECRET` values keep working), Spring Data JPA + `JdbcClient`, Flyway (baseline), Testcontainers-PostgreSQL, PostgreSQL 15+.

**Spec:** `docs/superpowers/specs/2026-07-04-imas-springboot-migration-design.md`. Plans 2–6 (admission, examination/evaluation, academics/portals, scheduling/events/inventory, decommission) will be written after this plan's patterns are validated in production.

---

## Ground truth used by this plan (verified against Node source)

- **Test-seed sequences:** several PKs (`user_id`, `role_id`, `institute_id`, `system_config_id`) have `DEFAULT nextval(...)`. If a test seed inserts rows with **explicit** PK values, it must then advance the sequence, e.g. `SELECT setval('pp.role_id_seq', (SELECT MAX(role_id)::bigint FROM pp.role))` — otherwise a later API insert using the sequence default collides at PK=1 (duplicate-key 500). The `::bigint` cast is required (`setval` takes `bigint`, columns are `numeric`). Applies only to seeds that mix manual-PK inserts with sequence-default inserts.
- **Node source root:** `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/` (read it whenever in doubt; its SQL is authoritative over `setup/db_version_04.sql`, which is stale — e.g. it lacks `system_config.system_config_id/is_active/updated_at` that the live code uses).
- **`pg` returns `NUMERIC` columns as JSON strings.** Node responses therefore serialize `user_id`, `juris_code` ids, etc. as strings (`"123"`, not `123`). To stay parity-safe, all DTO id fields in this plan are Java `String` mapped from `numeric` columns.
- **JWT (from `loginController.js` / `authorizeRoleController.js`):** HS256 with env `JWT_SECRET`. Pre-auth token claims: `user_id`, `user_name`, `type:"PRE_AUTH_ROLE_SELECT"`, `allowed_roles` (array), expiry env `PRE_AUTH_JWT_EXPIRES_IN` default 15m. Final token claims: `user_id`, `user_name`, `role_name`, expiry env `JWT_EXPIRES_IN` default 1d.
- **bcrypt:** Node uses `bcrypt` (`$2b$` prefix, cost 10). Use `new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10)`.
- **Role strings (exact):** `ADMIN`, `BATCH COORDINATOR`, `TEACHER`, `STUDENT`, `INTERVIEWER`.
- **Key tables (verified against the live `pg_dump`, `docs/superpowers/plans/artifacts/live-schema.sql`):**
  - `pp."user"` — quoted (reserved word); `user_id numeric(8) PK`, `user_name varchar(100) NOT NULL UNIQUE`, `enc_password varchar(300)`, `locked_yn char(1) CHECK Y/N`, plus nullable audit/profile columns (`created_at/updated_at/created_by/updated_by/full_name/user_email/contact_no/active_yn/last_login_at/password_changed_at`). Phase-1 inserts only `(user_name, enc_password, locked_yn)`; the rest default or stay null.
  - `pp.role(role_id numeric(4) PK, role_name varchar(100) NOT NULL UNIQUE, active_yn char(1) CHECK Y/N, +audit)`.
  - `pp.user_role(user_id, role_id)` PK `(user_id, role_id)`, FKs cascade.
  - `pp.teacher(teacher_id integer PK default seq, user_id numeric(8) **UNIQUE** FK→user ON DELETE CASCADE, teacher_name, qualification, ...)`. Node's `INSERT INTO pp.teacher(user_id) ON CONFLICT (user_id)` is valid because of the UNIQUE on `user_id`; `teacher_id` auto-generates.
  - `pp.jurisdiction(juris_code numeric(12) PK, juris_name varchar(100), juris_type varchar(100) FK, parent_juris numeric(12), +audit)`; `pp.jurisdiction_type(juris_type varchar(100) PK)`.
  - `pp.institute(institute_id numeric(14) PK, dise_code **varchar(15)** UNIQUE, institute_name varchar(200), juris_code numeric(12) FK, + many CHECK-constrained varchar columns, +audit)`. **`dise_code` is a string, not numeric.**
  - `pp.system_config(system_config_id integer PK default seq, academic_year varchar(9) NOT NULL, phase varchar(50) NOT NULL, is_active boolean DEFAULT true, created_at, updated_at, CHECK academic_year format)`. **There is NO UNIQUE constraint on `academic_year`** — duplicates are allowed; Node's 23505 handler never fires.

## Endpoint contract for this plan (exact, from Node routes/controllers)

| # | Method + Path | Success response | Notable errors |
|---|---|---|---|
| 1 | POST `/api/auth/login` | 200 `{message:"Credentials verified", user_name, roles:[...], preAuthToken}` | 400 `{error:"Username and password are required"}`; 401 `{error:"Invalid credentials"}`; 403 `{error:"Account is locked. Contact support."}` |
| 2 | POST `/api/auth/authorize-role` | 200 `{message:"Login complete", token, user:{user_id, user_name, role_name}}` | 400 `{error:"Missing session token or role selection"}`; 401 `{error:"Invalid token type"}`; 403 `{error:"You are not authorized for this role"}`; 401 `{error:"Session expired. Please login again.", code:"PRE_AUTH_TOKEN_EXPIRED"}`; 401 `{error:"Invalid session. Please login again.", code:"PRE_AUTH_TOKEN_INVALID"}` |
| 3 | GET `/api/users` | 200 bare array `[{id, username, status, roles:[...]|null}]` | 500 `{message}` |
| 4 | POST `/api/users` | 201 `{message:'User "<name>" created successfully', userId}` | 400/409/500 `{message}` |
| 5 | PUT `/api/users/:userId` | 200 `{message:"User updated successfully"}` | 400/409/500 `{message}` |
| 6 | DELETE `/api/users/:userId` | 204 empty | 404 `{message:"User not found."}` |
| 7 | PUT `/api/users/:userId/status` | 200 `{message:"User status updated successfully.", user:{...}}` | 400/404 `{message}` |
| 8 | GET `/api/roles` | 200 bare array `[{id, role_name, status}]` | 500 `{message}` |
| 9 | POST `/api/roles` | 201 `{message:'Role "<NAME>" created successfully', role:{...}}` | 400/409 `{message}` |
| 10 | DELETE `/api/roles/:roleId` | 204 empty | 400 in-use / 404 `{message}` |
| 11 | PUT `/api/roles/:roleId/status` | 200 `{message:"Role status updated successfully.", role:{...}}` | 400/404 `{message}` |
| 12 | POST `/api/users/:userId/roles/:roleId` | 200 `{message:"Role assigned successfully."}` | 404 `{message}` |
| 13 | DELETE `/api/users/:userId/roles/:roleId` | 200 `{message:"Role removed successfully."}` | 404 `{message:"User-role assignment not found."}` |
| 14 | PUT `/api/user/change-username/:userId` | 200 `{message:"Username updated successfully."}` | 400/409/404 `{message}` |
| 15 | PUT `/api/user/change-password/:userId` | 200 `{message:"Password updated successfully."}` | 400 `{message}`; 401 `{message:"Current password is not correct."}`; 404 `{message}` |
| 16 | GET `/api/states` | 200 bare array `[{id, name}]` | 500 `{error}` |
| 17 | GET `/api/divisions-by-state/:stateId` | same as 16 | same |
| 18 | GET `/api/districts-by-division/:divisionId` | same | same |
| 19 | GET `/api/blocks-by-district/:districtId` | same | same |
| 20 | GET `/api/clusters-by-block/:blockId` | same | same |
| 21 | GET `/api/institutes-by-cluster/:clusterId` | 200 bare array `[{institute_id, institute_name, dise_code}]` | same |
| 22 | GET `/api/juris-name/:juris_code` | 200 `{name}` (single object) | same |
| 23 | GET `/api/districts/all` | 200 bare array `[{district, district_code}]` | 500 `{error}` |
| 24 | GET `/api/institutes/all` | 200 bare array `[{institute_name}]` | 500 `{error}` |
| 25 | GET `/api/institutes/search?query=x` | 200 bare array `[{dise_code, institute_name}]` (LIMIT 10) | 400 `{error:"Missing query parameter"}` |
| 26 | POST `/api/juris-names` | 200 `{districts:{id:name}, blocks:{id:name}, institutes:{id:name}}` | 500 `{error}` |
| 27 | GET `/api/system-config/all` | 200 bare array | 500 `{error}` |
| 28 | POST `/api/system-config` | 200 config object (no wrapper) | (no UNIQUE on academic_year in the live schema — duplicates succeed; the Node 23505→400 branch is dead. Keep a defensive `DuplicateKeyException`→400 catch but do not assert it in tests.) |
| 29 | PUT `/api/system-config/:id` | 200 config object | 400 invalid id / duplicate; 404 `{error:"Configuration not found"}` |
| 30 | DELETE `/api/system-config/:id` | 200 `{message:"Configuration deleted successfully"}` | 404 `{error}` |
| 31 | PUT `/api/system-config/:id/activate` | 200 config object | 404 `{error}` |
| 32 | GET `/api/system-config/active` | 200 bare array | 500 `{error}` |

**Authorization for this plan** (new enforcement; spec §5): endpoints 1–2 public. 3–13 ADMIN only. 14–15 ADMIN or self (`user_id` claim equals path `userId`). 16–27, 32 any authenticated role. 28–31 ADMIN only.

## File structure (created by this plan)

```
imas-backend/
├── pom.xml
├── .gitignore
├── Dockerfile
├── src/main/java/com/rcf/imas/
│   ├── ImasApplication.java
│   ├── platform/
│   │   ├── config/JacksonConfig.java
│   │   ├── error/ApiException.java
│   │   ├── error/GlobalExceptionHandler.java
│   │   └── security/JwtProperties.java
│   │   └── security/JwtService.java
│   │   └── security/JwtAuthFilter.java
│   │   └── security/SecurityConfig.java
│   └── modules/
│       ├── identity/
│       │   ├── web/AuthController.java
│       │   ├── web/UserRoleAdminController.java
│       │   ├── service/AuthService.java
│       │   ├── service/UserAdminService.java
│       │   └── persistence/IdentityRepository.java
│       └── masterdata/
│           ├── web/JurisdictionController.java
│           ├── web/SystemConfigController.java
│           ├── service/SystemConfigService.java
│           └── persistence/JurisdictionRepository.java
│           └── persistence/SystemConfigRepository.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-prod.yml
│   └── db/migration/V1__baseline.sql
└── src/test/java/com/rcf/imas/
    ├── PgIntegrationTest.java          (Testcontainers base)
    ├── platform/... (per-task tests)
    └── modules/... (per-task tests)
docker-compose.prod.yml                  (modified: add imas-backend service)
nginx: PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/nginx.conf (modified: strangler map)
.github/workflows/backend-java.yml       (new CI job)
```

Notes for the implementing engineer:
- The repo root is `C:\work\rcf`. All `imas-backend` paths are relative to repo root.
- Every task = red test → green → commit. Run commands from `imas-backend/` unless stated.
- JSON snake_case is global (Task 3); DTOs are Java records with snake_case-compatible names.
- **Handler-method visibility (applies to every controller in this plan):** declare all `@RequestMapping`/`@GetMapping`/`@PostMapping`/etc. handler methods **`public`**. The controller *classes* stay package-private (that alone satisfies the module-boundary rule — no other module can reference them). Spring method security (`@PreAuthorize`) is reliably applied only to `public` methods; a package-private handler can silently skip authorization on some proxy configurations. The code blocks below show package-private methods for brevity — change each handler and each `@Bean` method to `public` as you type it. The plan's own 403 assertions (Tasks 6, 8, 10) will catch it if enforcement is ever skipped.

---

## Task 0: Preconditions & live-schema snapshot

**Files:**
- Create: `imas-backend/` (directory)
- Create: `docs/superpowers/plans/artifacts/live-schema.sql` (schema dump, committed for reference)

- [ ] **Step 1: Verify toolchain**

Run: `java -version` (expect 21.x), `mvn -version` (expect 3.9+), `docker --version`.
If Java 21 or Maven missing on Windows: `winget install EclipseAdoptium.Temurin.21.JDK Apache.Maven`.

- [ ] **Step 2: Dump the LIVE database schema (authoritative over setup/db_version_04.sql)**

Ask the operator for dev/prod DB credentials (same env vars the Node server uses: `server/.env` → `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`). Then:

```bash
pg_dump -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME --schema=pp --schema-only --no-owner --no-privileges > docs/superpowers/plans/artifacts/live-schema.sql
```

If no live DB is reachable in this environment, fall back to `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/server/setup/db_version_04.sql` **plus** manual patches for known drift (at minimum, `pp.system_config` must have `system_config_id`, `is_active boolean`, `updated_at timestamp`, and a UNIQUE constraint on `academic_year` — the Node model queries all of these). Record which source was used in the commit message.

- [ ] **Step 3: Verify the drift points in the dump**

Confirm in `live-schema.sql`: (a) `pp.system_config` columns match the Node model (`system_config_id`, `academic_year`, `phase`, `is_active`, `created_at`, `updated_at`); (b) `pp.user`, `pp.role`, `pp.user_role`, `pp.jurisdiction`, `pp.institute`, `pp.teacher` exist as described in "Ground truth" above. If anything differs, the dump wins — update entity/SQL code in later tasks accordingly and note the difference in the commit.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/artifacts/live-schema.sql
git commit -m "chore(migration): snapshot live pp schema as baseline reference"
```

---

## Task 1: Maven skeleton + application boot

**Files:**
- Create: `imas-backend/pom.xml`
- Create: `imas-backend/.gitignore`
- Create: `imas-backend/src/main/java/com/rcf/imas/ImasApplication.java`
- Create: `imas-backend/src/main/resources/application.yml`
- Test: `imas-backend/src/test/java/com/rcf/imas/ImasApplicationTest.java`

- [ ] **Step 1: Write pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.rcf</groupId>
  <artifactId>imas-backend</artifactId>
  <version>2.1.0-SNAPSHOT</version>
  <name>imas-backend</name>
  <properties>
    <java.version>21</java.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <!-- auth0 java-jwt: same HMAC-SHA256 semantics as Node 'jsonwebtoken', no min-key-length
         restriction (jjwt would reject short legacy JWT_SECRET values and break token parity) -->
    <dependency><groupId>com.auth0</groupId><artifactId>java-jwt</artifactId><version>4.4.0</version></dependency>
    <!-- test -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write .gitignore**

```
target/
*.iml
.idea/
.vscode/
```

- [ ] **Step 3: Write the application class and config**

`src/main/java/com/rcf/imas/ImasApplication.java`:
```java
package com.rcf.imas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ImasApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImasApplication.class, args);
    }
}
```

`src/main/resources/application.yml` — env var names deliberately match the Node server's `.env` so one env file drives both during migration:
```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:pp_portal_db}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:1234}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: pp
  flyway:
    enabled: true
    schemas: pp
    baseline-on-migrate: true   # existing DBs: mark V1 as applied, never run it
    baseline-version: "1"

imas:
  jwt:
    secret: ${JWT_SECRET:}
    expires-in: ${JWT_EXPIRES_IN:1d}
    pre-auth-expires-in: ${PRE_AUTH_JWT_EXPIRES_IN:15m}

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: Write the boot smoke test (no DB — slice test comes later; this just verifies the build compiles and context config parses)**

`src/test/java/com/rcf/imas/ImasApplicationTest.java`:
```java
package com.rcf.imas;

import org.junit.jupiter.api.Test;

class ImasApplicationTest {
    @Test
    void mainClassExists() {
        // Full context boot is covered by PgIntegrationTest subclasses (Task 2).
        org.assertj.core.api.Assertions.assertThat(ImasApplication.class).isNotNull();
    }
}
```

- [ ] **Step 5: Run the build**

Run: `mvn -q test`
Expected: BUILD SUCCESS, 1 test passing.

- [ ] **Step 6: Commit**

```bash
git add imas-backend
git commit -m "feat(backend): Spring Boot 3.3 skeleton for IMAS Java migration"
```

---

## Task 2: Flyway baseline (from live pg_dump) + embedded-postgres integration base

No Docker. Integration tests run against **Zonky embedded-postgres** (a real PostgreSQL 16 from a bundled binary). The baseline is derived from the authoritative production dump `docs/superpowers/plans/artifacts/live-schema.sql` (PostgreSQL 18, `pp` schema + unrelated `public.*` tables).

> **Known baseline gap (deferred):** the derivation drops the dump's PL/pgSQL functions and triggers because they live in / reference the `public.` schema (e.g. `updated_at` auto-update and timetable-overlap triggers). Phase 1 does not depend on them (`updated_at` still gets `DEFAULT CURRENT_TIMESTAMP` on insert; the app sets values explicitly elsewhere). If a later phase needs that trigger behavior, recreate the trigger function inside `pp` in a follow-up migration.

**Files:**
- Create: `imas-backend/src/main/resources/db/migration/V1__baseline.sql`
- Test: `imas-backend/src/test/java/com/rcf/imas/PgIntegrationTest.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/FlywayBaselineIT.java`

- [ ] **Step 1: Derive `V1__baseline.sql` from the dump (pp schema only)**

Start from `docs/superpowers/plans/artifacts/live-schema.sql`. Produce a Flyway-runnable file that contains **only the `pp` schema**. Strip everything that Flyway/embedded-postgres can't or shouldn't run:
- psql meta-commands: lines starting `\restrict`, `\unrestrict`, `\connect`.
- `SET ...` lines (incl. PG18-only `transaction_timeout`, `default_table_access_method`), `SELECT pg_catalog.set_config(...)`, comment lines (`--`, `/* */` banners).
- Ownership/permissions: `ALTER ... OWNER TO ...`, `GRANT`, `REVOKE`, `COMMENT ON`.
- **All `public.*` objects** — the 8 unrelated tables (`account, customer, customeracc, deposittx, staging_excel_data, transact, transfertx, withdrawaltx`) and any `CREATE SCHEMA public` / `public.` statements. These belong to a different app sharing the database.
- Change `CREATE SCHEMA pp;` → `CREATE SCHEMA IF NOT EXISTS pp;`.

Keep, in original dump order, all `pp` `CREATE SEQUENCE`, `CREATE TABLE`, `ALTER TABLE ... ADD CONSTRAINT` (PK/UNIQUE/CHECK/FK), `ALTER TABLE ... ALTER COLUMN ... SET DEFAULT`, and `CREATE INDEX` statements. Note `pp."user"` is a quoted reserved word — keep the quotes.

Suggested approach: a small script (bash `sed`/`awk` or a throwaway Python script in the scratchpad) that deletes the strip-list and the `public.`-referencing statement blocks. **The real verification is Step 4** — if the derived file fails to load under embedded-postgres, read the exact error and fix that statement, iterating until green. This file runs only on empty databases (CI/embedded-postgres/fresh dev); production uses `baseline-on-migrate: true` to record V1 as already applied.

- [ ] **Step 2: Write the embedded-postgres base class**

`src/test/java/com/rcf/imas/PgIntegrationTest.java`:
```java
package com.rcf.imas;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

@SpringBootTest
public abstract class PgIntegrationTest {

    // One real PostgreSQL (bundled binary, no Docker) for the whole test JVM.
    static final EmbeddedPostgres PG;
    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.flyway.baseline-on-migrate", () -> "false"); // empty DB: actually run V1
        r.add("imas.jwt.secret", () -> "test-secret-test-secret-test-secret-1234");
    }
}
```

- [ ] **Step 3: Write the failing baseline test**

`src/test/java/com/rcf/imas/FlywayBaselineIT.java`:
```java
package com.rcf.imas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayBaselineIT extends PgIntegrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void baselineCreatesCoreTables() {
        Integer n = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='pp'
                AND table_name IN ('user','role','user_role','jurisdiction','institute','system_config','teacher')
                """).query(Integer.class).single();
        assertThat(n).isEqualTo(7);
    }

    @Test
    void systemConfigHasLiveColumnsAndNoUniqueOnAcademicYear() {
        Integer cols = jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='pp' AND table_name='system_config'
                AND column_name IN ('system_config_id','academic_year','phase','is_active','created_at','updated_at')
                """).query(Integer.class).single();
        assertThat(cols).isEqualTo(6);

        // academic_year must NOT be unique (duplicates are allowed in production)
        Integer uniq = jdbc.sql("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema='pp' AND tc.table_name='system_config'
                AND tc.constraint_type='UNIQUE' AND kcu.column_name='academic_year'
                """).query(Integer.class).single();
        assertThat(uniq).isZero();
    }

    @Test
    void diseCodeIsVarcharAndTeacherUserIdUnique() {
        String diseType = jdbc.sql("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema='pp' AND table_name='institute' AND column_name='dise_code'
                """).query(String.class).single();
        assertThat(diseType).isEqualTo("character varying");

        Integer teacherUserUnique = jdbc.sql("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema='pp' AND tc.table_name='teacher'
                AND tc.constraint_type='UNIQUE' AND kcu.column_name='user_id'
                """).query(Integer.class).single();
        assertThat(teacherUserUnique).isEqualTo(1);
    }
}
```

- [ ] **Step 4: Run; fix V1 until green (no Docker needed)**

Run: `mvn -f C:/work/rcf/imas-backend/pom.xml test -Dtest=FlywayBaselineIT`
First run downloads the PostgreSQL 16 binary once. If Flyway fails to apply V1, the exception names the offending statement — fix that line in `V1__baseline.sql` (usually a stray `public.` reference, an owner/grant that slipped through, or a PG18-only clause) and re-run until all three tests pass.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/resources/db/migration imas-backend/src/test
git commit -m "feat(backend): Flyway V1 baseline from live pp pg_dump + embedded-postgres base"
```

---

## Task 3: Global snake_case JSON + date format

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/config/JacksonConfig.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/platform/config/JacksonConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rcf.imas.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    record Sample(String userName, LocalDate examDate) {}

    @Test
    void serializesSnakeCaseAndIsoDates() throws Exception {
        ObjectMapper om = new JacksonConfig().objectMapper();
        String json = om.writeValueAsString(new Sample("admin", LocalDate.of(2026, 7, 5)));
        assertThat(json).isEqualTo("{\"user_name\":\"admin\",\"exam_date\":\"2026-07-05\"}");
    }

    @Test
    void deserializesSnakeCase() throws Exception {
        ObjectMapper om = new JacksonConfig().objectMapper();
        Sample s = om.readValue("{\"user_name\":\"x\",\"exam_date\":\"2026-01-31\"}", Sample.class);
        assertThat(s.userName()).isEqualTo("x");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=JacksonConfigTest`
Expected: FAIL — `JacksonConfig` does not exist.

- [ ] **Step 3: Implement**

```java
package com.rcf.imas.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=JacksonConfigTest` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/platform/config imas-backend/src/test/java/com/rcf/imas/platform/config
git commit -m "feat(platform): global snake_case JSON and ISO date serialization"
```

---

## Task 4: Error handling — ApiException + GlobalExceptionHandler

Node has no uniform envelope; identity endpoints use `{message}` bodies, auth and masterdata use `{error}` bodies (see contract table). `ApiException` therefore carries its exact body key.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/error/ApiException.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/error/GlobalExceptionHandler.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/platform/error/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rcf.imas.platform.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionRendersChosenKeyAndStatus() {
        ResponseEntity<Object> res =
                handler.handleApi(ApiException.error(401, "Invalid credentials"));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().toString()).isEqualTo("{error=Invalid credentials}");
    }

    @Test
    void messageKeyVariant() {
        ResponseEntity<Object> res =
                handler.handleApi(ApiException.message(409, "Username already exists."));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().toString()).isEqualTo("{message=Username already exists.}");
    }

    @Test
    void extraFieldsAreIncluded() {
        ApiException ex = ApiException.error(401, "Session expired. Please login again.")
                .with("code", "PRE_AUTH_TOKEN_EXPIRED");
        ResponseEntity<Object> res = handler.handleApi(ex);
        assertThat(res.getBody().toString())
                .contains("code=PRE_AUTH_TOKEN_EXPIRED")
                .contains("error=Session expired. Please login again.");
    }

    @Test
    void unexpectedExceptionIs500WithGenericBody() {
        ResponseEntity<Object> res = handler.handleUnexpected(new RuntimeException("boom"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().toString()).isEqualTo("{error=Internal Server Error}");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest` — Expected: FAIL, classes missing.

- [ ] **Step 3: Implement**

`ApiException.java`:
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

    public static ApiException error(int status, String text)   { return new ApiException(status, "error", text); }
    public static ApiException message(int status, String text) { return new ApiException(status, "message", text); }

    public ApiException with(String key, Object value) { body.put(key, value); return this; }

    public int status() { return status; }
    public Map<String, Object> body() { return body; }
}
```

`GlobalExceptionHandler.java`:
```java
package com.rcf.imas.platform.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(ex.body());
    }

    // CRITICAL: the catch-all below would otherwise convert Spring method-security denials
    // (@PreAuthorize failures) into 500s. Re-throw them so the security layer produces the 403.
    // Without this, every role-based authorization check in the app returns 500 instead of 403.
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public void handleAccessDenied(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(500).body(Map.of("error", "Internal Server Error"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/platform/error imas-backend/src/test/java/com/rcf/imas/platform/error
git commit -m "feat(platform): ApiException with legacy body-key parity + global handler"
```

---

## Task 5: JwtService — Node-compatible tokens

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/security/JwtProperties.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/security/JwtService.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/ImasApplication.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/platform/security/JwtServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.rcf.imas.platform.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService svc = new JwtService(new JwtProperties("shortsecret", "1d", "15m"));

    @Test
    void issuesAndParsesFinalToken() {
        String token = svc.issueFinalToken("123", "admin", "ADMIN");
        JwtService.FinalToken parsed = svc.parseFinalToken(token);
        assertThat(parsed.userId()).isEqualTo("123");
        assertThat(parsed.userName()).isEqualTo("admin");
        assertThat(parsed.roleName()).isEqualTo("ADMIN");
    }

    @Test
    void acceptsTokenSignedTheWayNodeSignsIt() {
        // exactly what jsonwebtoken.sign() produces: HS256, utf-8 secret, numeric exp
        String nodeToken = JWT.create()
                .withClaim("user_id", "77")
                .withClaim("user_name", "coord1")
                .withClaim("role_name", "BATCH COORDINATOR")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.HMAC256("shortsecret"));
        JwtService.FinalToken parsed = svc.parseFinalToken(nodeToken);
        assertThat(parsed.roleName()).isEqualTo("BATCH COORDINATOR");
    }

    @Test
    void issuesAndParsesPreAuthToken() {
        String token = svc.issuePreAuthToken("9", "multi", List.of("ADMIN", "TEACHER"));
        JwtService.PreAuthToken parsed = svc.parsePreAuthToken(token);
        assertThat(parsed.allowedRoles()).containsExactly("ADMIN", "TEACHER");
    }

    @Test
    void rejectsPreAuthTokenAsFinalToken() {
        String pre = svc.issuePreAuthToken("9", "multi", List.of("ADMIN"));
        assertThatThrownBy(() -> svc.parseFinalToken(pre))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void expiredTokenThrowsExpired() {
        String expired = JWT.create()
                .withClaim("user_id", "1").withClaim("user_name", "x").withClaim("role_name", "ADMIN")
                .withExpiresAt(Date.from(Instant.now().minusSeconds(5)))
                .sign(Algorithm.HMAC256("shortsecret"));
        assertThatThrownBy(() -> svc.parseFinalToken(expired))
                .isInstanceOf(JwtService.ExpiredTokenException.class);
    }

    @Test
    void parsesDurationStringsLikeNode() {
        // vercel/ms syntax used by jsonwebtoken: "15m", "1d", "12h", plain seconds "3600"
        assertThat(JwtService.parseDuration("15m").toMinutes()).isEqualTo(15);
        assertThat(JwtService.parseDuration("1d").toHours()).isEqualTo(24);
        assertThat(JwtService.parseDuration("12h").toHours()).isEqualTo(12);
        assertThat(JwtService.parseDuration("3600").toSeconds()).isEqualTo(3600);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=JwtServiceTest` — Expected: FAIL, classes missing.

- [ ] **Step 3: Implement**

`JwtProperties.java`:
```java
package com.rcf.imas.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imas.jwt")
public record JwtProperties(String secret, String expiresIn, String preAuthExpiresIn) {}
```

`JwtService.java`:
```java
package com.rcf.imas.platform.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    public static final String PRE_AUTH_TYPE = "PRE_AUTH_ROLE_SELECT";

    public record FinalToken(String userId, String userName, String roleName) {}
    public record PreAuthToken(String userId, String userName, List<String> allowedRoles) {}

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String m) { super(m); }
    }
    public static class ExpiredTokenException extends RuntimeException {
        public ExpiredTokenException(String m) { super(m); }
    }

    private final Algorithm algorithm;
    private final Duration finalTtl;
    private final Duration preAuthTtl;

    public JwtService(JwtProperties props) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set");
        }
        this.algorithm = Algorithm.HMAC256(props.secret());
        this.finalTtl = parseDuration(props.expiresIn());
        this.preAuthTtl = parseDuration(props.preAuthExpiresIn());
    }

    public String issueFinalToken(String userId, String userName, String roleName) {
        return JWT.create()
                .withClaim("user_id", userId)
                .withClaim("user_name", userName)
                .withClaim("role_name", roleName)
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plus(finalTtl)))
                .sign(algorithm);
    }

    public String issuePreAuthToken(String userId, String userName, List<String> allowedRoles) {
        return JWT.create()
                .withClaim("user_id", userId)
                .withClaim("user_name", userName)
                .withClaim("type", PRE_AUTH_TYPE)
                .withClaim("allowed_roles", allowedRoles)
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plus(preAuthTtl)))
                .sign(algorithm);
    }

    public FinalToken parseFinalToken(String token) {
        DecodedJWT jwt = verify(token);
        if (!jwt.getClaim("type").isMissing()) {
            throw new InvalidTokenException("Invalid token type");
        }
        String role = jwt.getClaim("role_name").asString();
        if (role == null) throw new InvalidTokenException("Missing role_name");
        return new FinalToken(claimAsString(jwt, "user_id"),
                jwt.getClaim("user_name").asString(), role);
    }

    public PreAuthToken parsePreAuthToken(String token) {
        DecodedJWT jwt = verify(token);
        if (!PRE_AUTH_TYPE.equals(jwt.getClaim("type").asString())) {
            throw new InvalidTokenException("Invalid token type");
        }
        List<String> roles = jwt.getClaim("allowed_roles").asList(String.class);
        return new PreAuthToken(claimAsString(jwt, "user_id"),
                jwt.getClaim("user_name").asString(),
                roles == null ? List.of() : roles);
    }

    private DecodedJWT verify(String token) {
        try {
            return JWT.require(algorithm).build().verify(token);
        } catch (TokenExpiredException e) {
            throw new ExpiredTokenException(e.getMessage());
        } catch (JWTVerificationException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

    /** Node's pg driver returns numeric as string, so user_id may be string or number in old tokens. */
    private static String claimAsString(DecodedJWT jwt, String name) {
        var c = jwt.getClaim(name);
        String s = c.asString();
        if (s != null) return s;
        Integer i = c.asInt();
        return i == null ? null : String.valueOf(i);
    }

    /** Minimal vercel/ms parser covering values used by this project: Ns/Nm/Nh/Nd or bare seconds. */
    static Duration parseDuration(String v) {
        String s = v.trim().toLowerCase();
        if (s.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(s));
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Unsupported duration: " + v);
        };
    }
}
```

Register the properties record — `ImasApplication.java` becomes:
```java
package com.rcf.imas;

import com.rcf.imas.platform.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class ImasApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImasApplication.class, args);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=JwtServiceTest` — Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas imas-backend/src/test/java/com/rcf/imas/platform/security
git commit -m "feat(platform): Node-compatible JwtService (pre-auth + final tokens)"
```

---

## Task 6: Spring Security chain — JwtAuthFilter + SecurityConfig

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/security/JwtAuthFilter.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/platform/security/SecurityConfig.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/platform/security/SecurityConfigTest.java`

- [ ] **Step 1: Write the failing test** (throwaway probe controller; no DB)

```java
package com.rcf.imas.platform.security;

import com.rcf.imas.platform.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest is a SLICE context: it does NOT load ImasApplication, so the
// @EnableConfigurationProperties(JwtProperties.class) declared there is absent.
// JwtService needs a JwtProperties bean — bind it explicitly here or the context
// fails to load with "No qualifying bean of type JwtProperties". Every future
// @WebMvcTest that pulls in JwtService/JwtAuthFilter needs this same line.
@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@EnableConfigurationProperties(JwtProperties.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class,
         GlobalExceptionHandler.class, SecurityConfigTest.ProbeController.class})
@TestPropertySource(properties = {
        "imas.jwt.secret=shortsecret",
        "imas.jwt.expires-in=1d",
        "imas.jwt.pre-auth-expires-in=15m"
})
class SecurityConfigTest {

    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        String open() { return "ok"; }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/probe-admin")
        String admin() { return "admin-ok"; }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        String t = jwt.issueFinalToken("1", "u", "TEACHER");
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + t))
           .andExpect(status().isOk());
    }

    @Test
    void roleEnforced() throws Exception {
        String teacher = jwt.issueFinalToken("1", "u", "TEACHER");
        String admin = jwt.issueFinalToken("2", "a", "ADMIN");
        mvc.perform(get("/api/probe-admin").header("Authorization", "Bearer " + teacher))
           .andExpect(status().isForbidden());
        mvc.perform(get("/api/probe-admin").header("Authorization", "Bearer " + admin))
           .andExpect(status().isOk());
    }

    @Test
    void roleWithSpaceWorks() throws Exception {
        // "BATCH COORDINATOR" must round-trip as an authority
        String t = jwt.issueFinalToken("3", "c", "BATCH COORDINATOR");
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + t))
           .andExpect(status().isOk());
    }

    @Test
    void badTokenBodyMatchesNodeAuthMiddleware() throws Exception {
        // authMiddleware.js returns {error, code:"TOKEN_EXPIRED"|"TOKEN_INVALID"}
        mvc.perform(get("/api/probe").header("Authorization", "Bearer not-a-token"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SecurityConfigTest` — Expected: FAIL, classes missing.

- [ ] **Step 3: Implement**

`JwtAuthFilter.java`:
```java
package com.rcf.imas.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) { this.jwtService = jwtService; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtService.FinalToken t = jwtService.parseFinalToken(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        t, null, List.of(new SimpleGrantedAuthority("ROLE_" + t.roleName())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtService.ExpiredTokenException e) {
                reject(res, "Token expired", "TOKEN_EXPIRED"); return;
            } catch (JwtService.InvalidTokenException e) {
                reject(res, "Invalid token", "TOKEN_INVALID"); return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Body parity with Node authMiddleware.js */
    private void reject(HttpServletResponse res, String error, String code) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + error + "\",\"code\":\"" + code + "\"}");
    }
}
```

`SecurityConfig.java`:
```java
package com.rcf.imas.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/authorize-role").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/exams/hallticket/**").permitAll()
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

    /** $2b, cost 10 — verifies and re-hashes byte-compatibly with Node bcrypt in pp.user.enc_password. */
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10);
    }
}
```

CORS is deliberately not configured — in production nginx serves client and API same-origin. For local dev against the React dev server, the operator may add a `@Profile("dev")` CORS bean allowing `http://localhost:3000` (mirroring Node's allowlist).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SecurityConfigTest` — Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/platform/security imas-backend/src/test/java/com/rcf/imas/platform/security
git commit -m "feat(platform): stateless JWT security chain with role method-security"
```

---

## Task 7: identity — login + authorize-role endpoints

The two-step flow ported line-for-line from `controllers/loginController.js` and `controllers/authorizeRoleController.js`. Multi-statement SQL flows in identity are ported verbatim via `JdbcClient` (spec rule: complex/ported SQL stays native; JPA entities appear in later, CRUD-shaped modules).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/persistence/IdentityRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/service/LoginAuditLogger.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/service/AuthService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/web/AuthController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/identity/AuthFlowIT.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.rcf.imas.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcf.imas.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthFlowIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired BCryptPasswordEncoder bcrypt;
    @Autowired ObjectMapper om;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.user_role").update();
        jdbc.sql("DELETE FROM pp.user").update();
        jdbc.sql("DELETE FROM pp.role").update();
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (1,'ADMIN','Y'),(2,'TEACHER','Y'),(3,'STUDENT','N')").update();
        jdbc.sql("INSERT INTO pp.user(user_id, user_name, enc_password, locked_yn) VALUES (10,'admin1', :pw, 'N'), (11,'lockedguy', :pw, 'Y')")
            .param("pw", bcrypt.encode("secret123")).update();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (10,1),(10,2),(11,1)").update();
    }

    @Test
    void fullTwoStepLogin() throws Exception {
        MvcResult r1 = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"ADMIN1\",\"password\":\"secret123\"}")) // case-insensitive lookup
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Credentials verified"))
            .andExpect(jsonPath("$.user_name").value("admin1"))
            .andExpect(jsonPath("$.roles.length()").value(2))   // STUDENT role inactive -> excluded
            .andReturn();

        JsonNode body = om.readTree(r1.getResponse().getContentAsString());
        String pre = body.get("preAuthToken").asText();

        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"" + pre + "\",\"selectedRole\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Login complete"))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.user.user_id").value("10"))
            .andExpect(jsonPath("$.user.user_name").value("admin1"))
            .andExpect(jsonPath("$.user.role_name").value("ADMIN"));
    }

    @Test
    void wrongPasswordAndUnknownUserBothSay401InvalidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"admin1\",\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid credentials"));
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"ghost\",\"password\":\"x\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void lockedAccountIs403() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"lockedguy\",\"password\":\"secret123\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Account is locked. Contact support."));
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Username and password are required"));
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Missing session token or role selection"));
    }

    @Test
    void roleNotHeldIs403() throws Exception {
        MvcResult r1 = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"user_name\":\"admin1\",\"password\":\"secret123\"}")).andReturn();
        String pre = om.readTree(r1.getResponse().getContentAsString()).get("preAuthToken").asText();
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"" + pre + "\",\"selectedRole\":\"INTERVIEWER\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("You are not authorized for this role"));
    }

    @Test
    void garbagePreAuthTokenIs401WithCode() throws Exception {
        mvc.perform(post("/api/auth/authorize-role").contentType(APPLICATION_JSON)
                .content("{\"preAuthToken\":\"garbage\",\"selectedRole\":\"ADMIN\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("PRE_AUTH_TOKEN_INVALID"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AuthFlowIT` — Expected: FAIL (404s — controller missing).

- [ ] **Step 3: Implement**

`IdentityRepository.java`:
```java
package com.rcf.imas.modules.identity.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IdentityRepository {

    /** Row of the login join — one per (user, active role). Mirrors loginController.js SQL. */
    public record UserRoleRow(String userId, String userName, String encPassword, String lockedYn, String roleName) {}

    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<UserRoleRow> findUserWithActiveRoles(String userName) {
        return jdbc.sql("""
                SELECT u.user_id, u.user_name, u.enc_password, u.locked_yn, r.role_name
                FROM pp.user u
                JOIN pp.user_role ur ON u.user_id = ur.user_id
                JOIN pp.role r ON ur.role_id = r.role_id
                WHERE LOWER(u.user_name) = LOWER(:userName) AND r.active_yn = 'Y'
                """)
                .param("userName", userName)
                .query((rs, i) -> new UserRoleRow(
                        rs.getBigDecimal("user_id").toBigInteger().toString(),
                        rs.getString("user_name"),
                        rs.getString("enc_password"),
                        rs.getString("locked_yn"),
                        rs.getString("role_name")))
                .list();
    }
}
```

`LoginAuditLogger.java` (replaces Node `utils/logger.logLogin` — dedicated logback logger, file-only, never HTTP-served):
```java
package com.rcf.imas.modules.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoginAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("LOGIN_AUDIT");

    public void log(String userName, String status, String reason, String ip) {
        AUDIT.info("user_name={} status={} reason={} ip={}", userName, status, reason, ip);
    }
}
```

Add a rolling file appender for it — `src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
  <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

  <appender name="LOGIN_AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_DIR:-logs}/login-audit.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>${LOG_DIR:-logs}/login-audit.%d{yyyy-MM}.log</fileNamePattern>
      <maxHistory>24</maxHistory>
    </rollingPolicy>
    <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n</pattern></encoder>
  </appender>

  <logger name="LOGIN_AUDIT" level="INFO" additivity="false">
    <appender-ref ref="LOGIN_AUDIT_FILE"/>
  </logger>

  <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

`AuthService.java`:
```java
package com.rcf.imas.modules.identity.service;

import com.rcf.imas.modules.identity.persistence.IdentityRepository;
import com.rcf.imas.modules.identity.persistence.IdentityRepository.UserRoleRow;
import com.rcf.imas.platform.error.ApiException;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final IdentityRepository repo;
    private final BCryptPasswordEncoder bcrypt;
    private final JwtService jwt;
    private final LoginAuditLogger audit;

    public AuthService(IdentityRepository repo, BCryptPasswordEncoder bcrypt,
                       JwtService jwt, LoginAuditLogger audit) {
        this.repo = repo; this.bcrypt = bcrypt; this.jwt = jwt; this.audit = audit;
    }

    /** Port of loginController.js — response keys and status codes are contract. */
    public Map<String, Object> login(String userName, String password, String clientIp) {
        if (userName == null || userName.isBlank() || password == null || password.isBlank()) {
            throw ApiException.error(400, "Username and password are required");
        }
        List<UserRoleRow> rows = repo.findUserWithActiveRoles(userName);
        if (rows.isEmpty()) { // anti-enumeration: same message as bad password
            audit.log(userName, "failed", "user_not_found", clientIp);
            throw ApiException.error(401, "Invalid credentials");
        }
        UserRoleRow user = rows.get(0);
        if ("Y".equals(user.lockedYn())) {
            throw ApiException.error(403, "Account is locked. Contact support.");
        }
        if (!bcrypt.matches(password, user.encPassword())) {
            audit.log(userName, "failed", "bad_password", clientIp);
            throw ApiException.error(401, "Invalid credentials");
        }
        List<String> roles = rows.stream().map(UserRoleRow::roleName).toList();
        String preAuthToken = jwt.issuePreAuthToken(user.userId(), user.userName(), roles);
        audit.log(userName, "success_pre_auth", null, clientIp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Credentials verified");
        body.put("user_name", user.userName());
        body.put("roles", roles);
        body.put("preAuthToken", preAuthToken);
        return body;
    }

    /** Port of authorizeRoleController.js */
    public Map<String, Object> authorizeRole(String preAuthToken, String selectedRole, String clientIp) {
        if (preAuthToken == null || preAuthToken.isBlank() || selectedRole == null || selectedRole.isBlank()) {
            throw ApiException.error(400, "Missing session token or role selection");
        }
        JwtService.PreAuthToken decoded;
        try {
            decoded = jwt.parsePreAuthToken(preAuthToken);
        } catch (JwtService.ExpiredTokenException e) {
            throw ApiException.error(401, "Session expired. Please login again.")
                    .with("code", "PRE_AUTH_TOKEN_EXPIRED");
        } catch (JwtService.InvalidTokenException e) {
            throw ApiException.error(401, "Invalid session. Please login again.")
                    .with("code", "PRE_AUTH_TOKEN_INVALID");
        }
        boolean allowed = decoded.allowedRoles().stream()
                .anyMatch(r -> r.equalsIgnoreCase(selectedRole));
        if (!allowed) {
            audit.log(decoded.userName(), "failed_unauthorized_role", "role_auth", clientIp);
            throw ApiException.error(403, "You are not authorized for this role");
        }
        String token = jwt.issueFinalToken(decoded.userId(), decoded.userName(), selectedRole);
        audit.log(decoded.userName(), "success", "login_complete", clientIp);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("user_id", decoded.userId());
        user.put("user_name", decoded.userName());
        user.put("role_name", selectedRole);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Login complete");
        body.put("token", token);
        body.put("user", user);
        return body;
    }
}
```

`AuthController.java`:
```java
package com.rcf.imas.modules.identity.web;

import com.rcf.imas.modules.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(String userName, String password) {}
    record AuthorizeRoleRequest(String preAuthToken, String selectedRole) {}

    private final AuthService authService;

    AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return authService.login(req.userName(), req.password(), clientIp(http));
    }

    @PostMapping("/authorize-role")
    Map<String, Object> authorizeRole(@RequestBody AuthorizeRoleRequest req, HttpServletRequest http) {
        return authService.authorizeRole(req.preAuthToken(), req.selectedRole(), clientIp(http));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
```

Jackson note: global snake_case maps `preAuthToken` field → JSON `pre_auth_token`, which would break the contract (frontend sends `preAuthToken` and reads `preAuthToken`). The login/authorize-role JSON uses **camelCase keys for the token fields** (Node contract) while the rest of the body is snake_case — the Map-based responses above keep the exact literal keys, and the request records need explicit overrides. Amend the two request records:

```java
    record LoginRequest(@com.fasterxml.jackson.annotation.JsonProperty("user_name") String userName,
                        @com.fasterxml.jackson.annotation.JsonProperty("password") String password) {}
    record AuthorizeRoleRequest(@com.fasterxml.jackson.annotation.JsonProperty("preAuthToken") String preAuthToken,
                                @com.fasterxml.jackson.annotation.JsonProperty("selectedRole") String selectedRole) {}
```

(`preAuthToken` in the response Map is a literal string key, so it serializes verbatim — Maps bypass the naming strategy. This is exactly why legacy-shaped responses in this plan use `LinkedHashMap` instead of records.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=AuthFlowIT` — Expected: PASS (6 tests). Requires Docker.

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main imas-backend/src/test/java/com/rcf/imas/modules/identity
git commit -m "feat(identity): two-step login (login + authorize-role) with Node contract parity"
```

---

## Task 8: identity — users & roles admin (endpoints 3–15)

Port of `controllers/userRolesController.js` + `routes/userRoleRoutes.js`. All 13 endpoints, ADMIN-only except change-username/change-password which allow self-service.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/service/UserAdminService.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/identity/web/UserRoleAdminController.java`
- Modify: `imas-backend/src/main/java/com/rcf/imas/modules/identity/persistence/IdentityRepository.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/identity/UserRoleAdminIT.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.rcf.imas.modules.identity;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class UserRoleAdminIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;
    @Autowired BCryptPasswordEncoder bcrypt;

    String adminToken;
    String teacherToken;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM pp.teacher").update();
        jdbc.sql("DELETE FROM pp.user_role").update();
        jdbc.sql("DELETE FROM pp.user").update();
        jdbc.sql("DELETE FROM pp.role").update();
        jdbc.sql("INSERT INTO pp.role(role_id, role_name, active_yn) VALUES (1,'ADMIN','Y'),(2,'TEACHER','Y')").update();
        jdbc.sql("INSERT INTO pp.user(user_id, user_name, enc_password, locked_yn) VALUES (10,'admin1', :pw,'N')")
            .param("pw", bcrypt.encode("x")).update();
        jdbc.sql("INSERT INTO pp.user_role(user_id, role_id) VALUES (10,1)").update();
        adminToken = jwt.issueFinalToken("10", "admin1", "ADMIN");
        teacherToken = jwt.issueFinalToken("99", "somebody", "TEACHER");
    }

    @Test
    void nonAdminGets403OnUserAdmin() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + teacherToken))
           .andExpect(status().isForbidden());
    }

    @Test
    void createUserWithTeacherRoleSyncsTeacherTable() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"newteach\",\"password\":\"pw12345\",\"roles\":[\"teacher\"]}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("User \"newteach\" created successfully"))
           .andExpect(jsonPath("$.userId").isNotEmpty());

        Integer inTeacher = jdbc.sql("""
                SELECT count(*) FROM pp.teacher t JOIN pp.user u ON u.user_id=t.user_id
                WHERE u.user_name='newteach'""").query(Integer.class).single();
        assertThat(inTeacher).isEqualTo(1);
    }

    @Test
    void duplicateUsernameIs409() throws Exception {
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin1\",\"password\":\"pw\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value("Username already exists."));
    }

    @Test
    void listUsersReturnsBareArrayWithAggregatedRoles() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].username").value("admin1"))
           .andExpect(jsonPath("$[0].status").value("N"))
           .andExpect(jsonPath("$[0].roles[0]").value("ADMIN"));
    }

    @Test
    void deleteUserIs204AndMissingUserIs404() throws Exception {
        mvc.perform(delete("/api/users/10").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNoContent());
        mvc.perform(delete("/api/users/10").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.message").value("User not found."));
    }

    @Test
    void roleLifecycle() throws Exception {
        mvc.perform(post("/api/roles").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON).content("{\"roleName\":\"librarian\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.message").value("Role \"LIBRARIAN\" created successfully"))
           .andExpect(jsonPath("$.role.role_name").value("LIBRARIAN"));

        // role in use cannot be deleted
        mvc.perform(delete("/api/roles/1").header("Authorization", "Bearer " + adminToken))
           .andExpect(status().isBadRequest());
    }

    @Test
    void selfServicePasswordChangeAllowedForOwnUserOnly() throws Exception {
        // admin1 (user_id 10) changes own password: requires correct current password
        mvc.perform(put("/api/user/change-password/10").header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"y1234567\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.message").value("Password updated successfully."));

        // teacher token (user_id 99) may not change user 10's password
        mvc.perform(put("/api/user/change-password/10").header("Authorization", "Bearer " + teacherToken)
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"x\",\"newPassword\":\"z1234567\"}"))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UserRoleAdminIT` — Expected: FAIL (404s).

- [ ] **Step 3: Implement the service**

`UserAdminService.java` — transactional ports of the Node flows. Uses `JdbcClient`; every method mirrors the Node function of the same name:

```java
package com.rcf.imas.modules.identity.service;

import com.rcf.imas.platform.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserAdminService {

    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder bcrypt;

    public UserAdminService(JdbcClient jdbc, BCryptPasswordEncoder bcrypt) {
        this.jdbc = jdbc; this.bcrypt = bcrypt;
    }

    public List<Map<String, Object>> listUsersWithRoles() {
        return jdbc.sql("""
                SELECT u.user_id AS id, u.user_name AS username, u.locked_yn AS status,
                       ARRAY_AGG(r.role_name) FILTER (WHERE r.role_name IS NOT NULL) AS roles
                FROM pp.user u
                LEFT JOIN pp.user_role ur ON u.user_id = ur.user_id
                LEFT JOIN pp.role r ON ur.role_id = r.role_id
                GROUP BY u.user_id
                ORDER BY u.user_name ASC
                """)
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getBigDecimal("id").toBigInteger().toString());
                    row.put("username", rs.getString("username"));
                    row.put("status", rs.getString("status"));
                    java.sql.Array arr = rs.getArray("roles");
                    row.put("roles", arr == null ? null : List.of((String[]) arr.getArray()));
                    return row;
                }).list();
    }

    public List<Map<String, Object>> listRoles() {
        return jdbc.sql("SELECT role_id AS id, role_name, active_yn AS status FROM pp.role ORDER BY role_name ASC")
                .query((rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getBigDecimal("id").toBigInteger().toString());
                    row.put("role_name", rs.getString("role_name"));
                    row.put("status", rs.getString("status"));
                    return row;
                }).list();
    }

    @Transactional
    public String createUserWithRoles(String username, String password, List<String> roles) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw ApiException.message(400, "Username and password are required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u")
                .param("u", username).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already exists.");

        String userId = jdbc.sql("""
                INSERT INTO pp.user (user_name, enc_password, locked_yn)
                VALUES (:u, :p, 'N') RETURNING user_id
                """)
                .param("u", username).param("p", bcrypt.encode(password))
                .query(java.math.BigDecimal.class).single().toBigInteger().toString();

        syncRoles(userId, roles);
        return userId;
    }

    @Transactional
    public void updateUserWithRoles(String userId, String username, String password, List<String> roles) {
        if (username == null || username.isBlank()) {
            throw ApiException.message(400, "Username is required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u AND user_id != :id::numeric")
                .param("u", username).param("id", userId).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already taken by another user.");

        String hash = (password == null || password.isBlank()) ? null : bcrypt.encode(password);
        // explicit VARCHAR type is required so a null hash binds as SQL NULL, not an inferred type
        jdbc.sql("UPDATE pp.user SET user_name = :u, enc_password = COALESCE(:p, enc_password) WHERE user_id = :id::numeric")
            .param("u", username)
            .param("p", hash, java.sql.Types.VARCHAR)
            .param("id", userId)
            .update();

        jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :id::numeric").param("id", userId).update();
        syncRoles(userId, roles);
        if (roles == null || roles.stream().noneMatch(r -> r.trim().equalsIgnoreCase("TEACHER"))) {
            jdbc.sql("DELETE FROM pp.teacher WHERE user_id = :id::numeric").param("id", userId).update();
        }
    }

    /** Shared by create/update: resolve active roles, insert user_role, sync pp.teacher. */
    private void syncRoles(String userId, List<String> roles) {
        if (roles == null || roles.isEmpty()) return;
        Set<String> unique = new LinkedHashSet<>(roles.stream().map(r -> r.trim().toUpperCase()).toList());
        boolean isTeacher = false;
        for (String roleName : unique) {
            var roleId = jdbc.sql("SELECT role_id FROM pp.role WHERE role_name = :r AND active_yn = 'Y'")
                    .param("r", roleName).query(java.math.BigDecimal.class).optional();
            if (roleId.isPresent()) {
                jdbc.sql("""
                        INSERT INTO pp.user_role (user_id, role_id) VALUES (:u::numeric, :r)
                        ON CONFLICT DO NOTHING""")
                    .param("u", userId).param("r", roleId.get()).update();
                if ("TEACHER".equals(roleName)) isTeacher = true;
            }
        }
        if (isTeacher) {
            jdbc.sql("INSERT INTO pp.teacher (user_id) VALUES (:u::numeric) ON CONFLICT (user_id) DO NOTHING")
                .param("u", userId).update();
        }
    }

    @Transactional
    public void deleteUser(String userId) {
        jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :id::numeric").param("id", userId).update();
        int n = jdbc.sql("DELETE FROM pp.user WHERE user_id = :id::numeric").param("id", userId).update();
        if (n == 0) throw ApiException.message(404, "User not found.");
    }

    public Map<String, Object> toggleUserStatus(String userId, String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw ApiException.message(400, "Invalid status. Must be 'Y' or 'N'.");
        }
        var row = jdbc.sql("""
                UPDATE pp.user SET locked_yn = :s WHERE user_id = :id::numeric
                RETURNING user_id, user_name, enc_password, locked_yn""")
                .param("s", status).param("id", userId)
                .query((rs, i) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("user_id", rs.getBigDecimal("user_id").toBigInteger().toString());
                    u.put("user_name", rs.getString("user_name"));
                    u.put("enc_password", rs.getString("enc_password")); // Node returns * — parity (see hardening note below)
                    u.put("locked_yn", rs.getString("locked_yn"));
                    return u;
                }).optional();
        return row.orElseThrow(() -> ApiException.message(404, "User not found."));
    }

    @Transactional
    public Map<String, Object> createRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw ApiException.message(400, "Role name is required.");
        }
        String formatted = roleName.trim().toUpperCase();
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.role WHERE role_name = :r")
                .param("r", formatted).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Role name already exists.");
        return jdbc.sql("INSERT INTO pp.role (role_name, active_yn) VALUES (:r, 'Y') RETURNING role_id, role_name, active_yn")
                .param("r", formatted)
                .query((rs, i) -> {
                    Map<String, Object> role = new LinkedHashMap<>();
                    role.put("role_id", rs.getBigDecimal("role_id").toBigInteger().toString());
                    role.put("role_name", rs.getString("role_name"));
                    role.put("active_yn", rs.getString("active_yn"));
                    return role;
                }).single();
    }

    @Transactional
    public void deleteRole(String roleId) {
        Integer inUse = jdbc.sql("SELECT count(*) FROM pp.user_role WHERE role_id = :id::numeric")
                .param("id", roleId).query(Integer.class).single();
        if (inUse > 0) throw ApiException.message(400, "Cannot delete role: It is currently assigned to one or more users.");
        int n = jdbc.sql("DELETE FROM pp.role WHERE role_id = :id::numeric").param("id", roleId).update();
        if (n == 0) throw ApiException.message(404, "Role not found.");
    }

    public Map<String, Object> toggleRoleStatus(String roleId, String status) {
        if (!"Y".equals(status) && !"N".equals(status)) {
            throw ApiException.message(400, "Invalid status. Must be 'Y' or 'N'.");
        }
        var row = jdbc.sql("""
                UPDATE pp.role SET active_yn = :s WHERE role_id = :id::numeric
                RETURNING role_id, role_name, active_yn""")
                .param("s", status).param("id", roleId)
                .query((rs, i) -> {
                    Map<String, Object> role = new LinkedHashMap<>();
                    role.put("role_id", rs.getBigDecimal("role_id").toBigInteger().toString());
                    role.put("role_name", rs.getString("role_name"));
                    role.put("active_yn", rs.getString("active_yn"));
                    return role;
                }).optional();
        return row.orElseThrow(() -> ApiException.message(404, "Role not found."));
    }

    @Transactional
    public void assignRole(String userId, String roleId) {
        Integer userExists = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_id = :id::numeric")
                .param("id", userId).query(Integer.class).single();
        if (userExists == 0) throw ApiException.message(404, "User not found.");
        var roleName = jdbc.sql("SELECT role_name FROM pp.role WHERE role_id = :id::numeric")
                .param("id", roleId).query(String.class).optional();
        if (roleName.isEmpty()) throw ApiException.message(404, "Role not found.");

        jdbc.sql("""
                INSERT INTO pp.user_role (user_id, role_id) VALUES (:u::numeric, :r::numeric)
                ON CONFLICT (user_id, role_id) DO NOTHING""")
            .param("u", userId).param("r", roleId).update();
        if ("TEACHER".equals(roleName.get().trim().toUpperCase())) {
            jdbc.sql("INSERT INTO pp.teacher (user_id) VALUES (:u::numeric) ON CONFLICT (user_id) DO NOTHING")
                .param("u", userId).update();
        }
    }

    @Transactional
    public void removeRole(String userId, String roleId) {
        var roleName = jdbc.sql("SELECT role_name FROM pp.role WHERE role_id = :id::numeric")
                .param("id", roleId).query(String.class).optional();
        int n = jdbc.sql("DELETE FROM pp.user_role WHERE user_id = :u::numeric AND role_id = :r::numeric")
                .param("u", userId).param("r", roleId).update();
        if (n == 0) throw ApiException.message(404, "User-role assignment not found.");
        if (roleName.isPresent() && "TEACHER".equals(roleName.get().trim().toUpperCase())) {
            jdbc.sql("DELETE FROM pp.teacher WHERE user_id = :u::numeric").param("u", userId).update();
        }
    }

    @Transactional
    public void updateUsername(String userId, String username) {
        if (username == null || username.isBlank()) {
            throw ApiException.message(400, "Username is required.");
        }
        Integer dup = jdbc.sql("SELECT count(*) FROM pp.user WHERE user_name = :u AND user_id != :id::numeric")
                .param("u", username.trim()).param("id", userId).query(Integer.class).single();
        if (dup > 0) throw ApiException.message(409, "Username already taken.");
        int n = jdbc.sql("UPDATE pp.user SET user_name = :u WHERE user_id = :id::numeric")
                .param("u", username.trim()).param("id", userId).update();
        if (n == 0) throw ApiException.message(404, "User not found.");
    }

    public void updatePassword(String userId, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isBlank()
                || newPassword == null || newPassword.isBlank()) {
            throw ApiException.message(400, "Current and new passwords are required.");
        }
        var hash = jdbc.sql("SELECT enc_password FROM pp.user WHERE user_id = :id::numeric")
                .param("id", userId).query(String.class).optional();
        if (hash.isEmpty()) throw ApiException.message(404, "User not found.");
        if (!bcrypt.matches(currentPassword, hash.get())) {
            throw ApiException.message(401, "Current password is not correct.");
        }
        jdbc.sql("UPDATE pp.user SET enc_password = :p WHERE user_id = :id::numeric")
            .param("p", bcrypt.encode(newPassword)).param("id", userId).update();
    }
}
```

- [ ] **Step 4: Implement the controller**

`UserRoleAdminController.java`:
```java
package com.rcf.imas.modules.identity.web;

import com.rcf.imas.modules.identity.service.UserAdminService;
import com.rcf.imas.platform.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class UserRoleAdminController {

    record UserUpsertRequest(String username, String password, List<String> roles) {}
    record StatusRequest(String status) {}
    record RoleCreateRequest(String roleName) {}
    record UsernameRequest(String username) {}
    record PasswordRequest(String currentPassword, String newPassword) {}

    private final UserAdminService svc;

    UserRoleAdminController(UserAdminService svc) { this.svc = svc; }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    List<Map<String, Object>> listUsers() { return svc.listUsersWithRoles(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users")
    ResponseEntity<Map<String, Object>> createUser(@RequestBody UserUpsertRequest req) {
        String userId = svc.createUserWithRoles(req.username(), req.password(), req.roles());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "User \"" + req.username() + "\" created successfully");
        body.put("userId", userId);
        return ResponseEntity.status(201).body(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/users/{userId}")
    Map<String, Object> updateUser(@PathVariable String userId, @RequestBody UserUpsertRequest req) {
        svc.updateUserWithRoles(userId, req.username(), req.password(), req.roles());
        return Map.of("message", "User updated successfully");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/users/{userId}")
    ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        svc.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/users/{userId}/status")
    Map<String, Object> toggleUserStatus(@PathVariable String userId, @RequestBody StatusRequest req) {
        Map<String, Object> user = svc.toggleUserStatus(userId, req.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "User status updated successfully.");
        body.put("user", user);
        return body;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/roles")
    List<Map<String, Object>> listRoles() { return svc.listRoles(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/roles")
    ResponseEntity<Map<String, Object>> createRole(@RequestBody RoleCreateRequest req) {
        Map<String, Object> role = svc.createRole(req.roleName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Role \"" + role.get("role_name") + "\" created successfully");
        body.put("role", role);
        return ResponseEntity.status(201).body(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/roles/{roleId}")
    ResponseEntity<Void> deleteRole(@PathVariable String roleId) {
        svc.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/roles/{roleId}/status")
    Map<String, Object> toggleRoleStatus(@PathVariable String roleId, @RequestBody StatusRequest req) {
        Map<String, Object> role = svc.toggleRoleStatus(roleId, req.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Role status updated successfully.");
        body.put("role", role);
        return body;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}/roles/{roleId}")
    Map<String, Object> assignRole(@PathVariable String userId, @PathVariable String roleId) {
        svc.assignRole(userId, roleId);
        return Map.of("message", "Role assigned successfully.");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/users/{userId}/roles/{roleId}")
    Map<String, Object> removeRole(@PathVariable String userId, @PathVariable String roleId) {
        svc.removeRole(userId, roleId);
        return Map.of("message", "Role removed successfully.");
    }

    @PreAuthorize("hasRole('ADMIN') or #userId == principal.userId()")
    @PutMapping("/user/change-username/{userId}")
    Map<String, Object> changeUsername(@PathVariable String userId, @RequestBody UsernameRequest req,
                                       @AuthenticationPrincipal JwtService.FinalToken principal) {
        svc.updateUsername(userId, req.username());
        return Map.of("message", "Username updated successfully.");
    }

    @PreAuthorize("hasRole('ADMIN') or #userId == principal.userId()")
    @PutMapping("/user/change-password/{userId}")
    Map<String, Object> changePassword(@PathVariable String userId, @RequestBody PasswordRequest req,
                                       @AuthenticationPrincipal JwtService.FinalToken principal) {
        svc.updatePassword(userId, req.currentPassword(), req.newPassword());
        return Map.of("message", "Password updated successfully.");
    }
}
```

Contract notes:
- Node returns 204 on role delete (`.send()` after `status(204)`) — `ResponseEntity.noContent()` matches.
- Request bodies use camelCase keys from the frontend (`roleName`, `currentPassword`, `newPassword`, `username`, `password`, `roles`) — since global Jackson is snake_case, add `@JsonProperty` pins to every request-record component, e.g. `record RoleCreateRequest(@JsonProperty("roleName") String roleName) {}`. Do this for all five request records above.
- Delete-role parity difference: Node returns 400 with a `{message}` when the role is in use — `ApiException.message(400, ...)` covers it.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=UserRoleAdminIT` — Expected: PASS (7 tests).

- [ ] **Step 6: Run full suite and commit**

Run: `mvn -q test` — Expected: all green.

```bash
git add imas-backend/src/main imas-backend/src/test
git commit -m "feat(identity): users & roles admin endpoints with ADMIN enforcement + teacher sync"
```

---

## Task 9: masterdata — jurisdiction cascade + districts + institutes + juris-names (endpoints 16–26)

> **⚠ SCHEMA CORRECTION (live pg_dump):** `institute.dise_code` is **`varchar(15)`**, not numeric. In every row mapper below, map `dise_code` with `rs.getString("dise_code")` (NOT `getBigDecimal(...).toBigInteger()`). In `/api/juris-names`, the `instituteIds` are strings — the request field and `instituteNamesByDise(...)` parameter must be `List<String>`, and the SQL `dise_code = ANY(:ids)` binds a `String[]`. The JSON output is unchanged (dise_code was already a string over the wire). `jurisdiction.juris_code` is `numeric(12)` (still mapped as String).

Port of `models/jurisdictionModel.js`, `routes/jurisdictionRoutes.js`, `routes/districtRoutes.js`, `routes/institutesRoutes.js`, `routes/jurisNameRoutes.js`. All read-only except `/api/juris-names` (POST but read-only semantics). Any authenticated role may call these.

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/masterdata/persistence/JurisdictionRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/masterdata/web/JurisdictionController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/masterdata/JurisdictionIT.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.rcf.imas.modules.masterdata;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class JurisdictionIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String token;

    @BeforeEach
    void seed() {
        token = jwt.issueFinalToken("1", "any", "STUDENT"); // reads open to all authenticated roles
        jdbc.sql("DELETE FROM pp.institute").update();
        jdbc.sql("DELETE FROM pp.jurisdiction").update();
        // jurisdiction_type rows must exist first (FK) — baseline seeds them; insert defensively:
        jdbc.sql("""
            INSERT INTO pp.jurisdiction_type(juris_type) VALUES
            ('STATE'),('DIVISION'),('EDUCATION DISTRICT'),('BLOCK'),('CLUSTER')
            ON CONFLICT DO NOTHING""").update();
        jdbc.sql("""
            INSERT INTO pp.jurisdiction(juris_code, juris_name, juris_type, parent_juris) VALUES
            (1,'Karnataka','STATE',NULL),
            (2,'Belagavi Division','DIVISION',1),
            (3,'Dharwad','EDUCATION DISTRICT',2),
            (4,'Hubballi Block','BLOCK',3),
            (5,'Cluster-A','CLUSTER',4)""").update();
        jdbc.sql("""
            INSERT INTO pp.institute(institute_id, dise_code, institute_name, juris_code) VALUES
            (100, 29010100101, 'Govt High School A', 5)""").update();
    }

    @Test
    void statesCascade() throws Exception {
        mvc.perform(get("/api/states").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("1"))
           .andExpect(jsonPath("$[0].name").value("Karnataka"));
        mvc.perform(get("/api/divisions-by-state/1").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Belagavi Division"));
        mvc.perform(get("/api/districts-by-division/2").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Dharwad"));
        mvc.perform(get("/api/blocks-by-district/3").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Hubballi Block"));
        mvc.perform(get("/api/clusters-by-block/4").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].name").value("Cluster-A"));
        mvc.perform(get("/api/institutes-by-cluster/5").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"))
           .andExpect(jsonPath("$[0].dise_code").value("29010100101"));
    }

    @Test
    void jurisNameSingleObject() throws Exception {
        mvc.perform(get("/api/juris-name/3").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("Dharwad"));
    }

    @Test
    void districtsAllAndInstituteSearch() throws Exception {
        mvc.perform(get("/api/districts/all").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].district").value("Dharwad"))
           .andExpect(jsonPath("$[0].district_code").value("3"));
        mvc.perform(get("/api/institutes/all").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"));
        mvc.perform(get("/api/institutes/search?query=govt").header("Authorization", "Bearer " + token))
           .andExpect(jsonPath("$[0].institute_name").value("Govt High School A"));
        mvc.perform(get("/api/institutes/search").header("Authorization", "Bearer " + token))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Missing query parameter"));
    }

    @Test
    void jurisNamesBulkResolve() throws Exception {
        mvc.perform(post("/api/juris-names").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("{\"districtIds\":[3],\"blockIds\":[4],\"instituteIds\":[29010100101]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.districts.3").value("Dharwad"))
           .andExpect(jsonPath("$.blocks.4").value("Hubballi Block"))
           .andExpect(jsonPath("$.institutes.29010100101").value("Govt High School A"));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/states")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=JurisdictionIT` — Expected: FAIL (404s). If the seed itself fails because `pp.jurisdiction_type` is absent from the baseline, fix `V1__baseline.sql` (the live dump must contain it — it is a FK target of `jurisdiction`).

- [ ] **Step 3: Implement**

`JurisdictionRepository.java`:
```java
package com.rcf.imas.modules.masterdata.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JurisdictionRepository {

    private final JdbcClient jdbc;

    public JurisdictionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** id/name pair used by every cascade level. numeric ids -> String (node-pg parity). */
    private static Map<String, Object> idName(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getBigDecimal("id").toBigInteger().toString());
        m.put("name", rs.getString("name"));
        return m;
    }

    public List<Map<String, Object>> states() {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = 'STATE' AND parent_juris IS NULL""")
                .query((rs, i) -> idName(rs)).list();
    }

    public List<Map<String, Object>> childrenOf(String jurisType, String parentId) {
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = :t AND parent_juris = :p::numeric""")
                .param("t", jurisType).param("p", parentId)
                .query((rs, i) -> idName(rs)).list();
    }

    public List<Map<String, Object>> institutesByCluster(String clusterId) {
        return jdbc.sql("""
                SELECT institute_id, institute_name, dise_code FROM pp.institute
                WHERE juris_code = :c::numeric""")
                .param("c", clusterId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("institute_id", rs.getBigDecimal("institute_id").toBigInteger().toString());
                    m.put("institute_name", rs.getString("institute_name"));
                    var dise = rs.getBigDecimal("dise_code");
                    m.put("dise_code", dise == null ? null : dise.toBigInteger().toString());
                    return m;
                }).list();
    }

    public Optional<Map<String, Object>> jurisName(String jurisCode) {
        return jdbc.sql("SELECT juris_name AS name FROM pp.jurisdiction WHERE juris_code = :c::numeric")
                .param("c", jurisCode)
                .query((rs, i) -> Map.<String, Object>of("name", rs.getString("name")))
                .optional();
    }

    public List<Map<String, Object>> allDistricts() {
        return jdbc.sql("""
                SELECT juris_name AS district, juris_code AS district_code FROM pp.jurisdiction
                WHERE juris_type = 'EDUCATION DISTRICT' ORDER BY juris_name""")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("district", rs.getString("district"));
                    m.put("district_code", rs.getBigDecimal("district_code").toBigInteger().toString());
                    return m;
                }).list();
    }

    public List<Map<String, Object>> allInstituteNames() {
        return jdbc.sql("SELECT institute_name FROM pp.institute ORDER BY institute_name")
                .query((rs, i) -> Map.<String, Object>of("institute_name", rs.getString("institute_name")))
                .list();
    }

    public List<Map<String, Object>> searchInstitutes(String query) {
        return jdbc.sql("""
                SELECT dise_code, institute_name FROM pp.institute
                WHERE institute_name ILIKE :q LIMIT 10""")
                .param("q", "%" + query + "%")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    var dise = rs.getBigDecimal("dise_code");
                    m.put("dise_code", dise == null ? null : dise.toBigInteger().toString());
                    m.put("institute_name", rs.getString("institute_name"));
                    return m;
                }).list();
    }

    /** juris-names bulk resolve: {id -> name} maps, keyed exactly like Node's reduce(). */
    public Map<String, String> namesByType(String jurisType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT juris_code AS id, juris_name AS name FROM pp.jurisdiction
                WHERE juris_type = :t AND juris_code = ANY(:ids)""")
                .param("t", jurisType)
                .param("ids", ids.toArray(new Long[0]))
                .query((rs, i) -> Map.entry(
                        rs.getBigDecimal("id").toBigInteger().toString(), rs.getString("name")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public Map<String, String> instituteNamesByDise(List<Long> diseCodes) {
        if (diseCodes == null || diseCodes.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT dise_code AS id, institute_name AS name FROM pp.institute
                WHERE dise_code = ANY(:ids)""")
                .param("ids", diseCodes.toArray(new Long[0]))
                .query((rs, i) -> Map.entry(
                        rs.getBigDecimal("id").toBigInteger().toString(), rs.getString("name")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
```

`JurisdictionController.java`:
```java
package com.rcf.imas.modules.masterdata.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rcf.imas.modules.masterdata.persistence.JurisdictionRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class JurisdictionController {

    record JurisNamesRequest(@JsonProperty("districtIds") List<Long> districtIds,
                             @JsonProperty("blockIds") List<Long> blockIds,
                             @JsonProperty("instituteIds") List<Long> instituteIds) {}

    private final JurisdictionRepository repo;

    JurisdictionController(JurisdictionRepository repo) { this.repo = repo; }

    @GetMapping("/states")
    List<Map<String, Object>> states() { return repo.states(); }

    @GetMapping("/divisions-by-state/{stateId}")
    List<Map<String, Object>> divisions(@PathVariable String stateId) {
        return repo.childrenOf("DIVISION", stateId);
    }

    @GetMapping("/districts-by-division/{divisionId}")
    List<Map<String, Object>> districts(@PathVariable String divisionId) {
        return repo.childrenOf("EDUCATION DISTRICT", divisionId);
    }

    @GetMapping("/blocks-by-district/{districtId}")
    List<Map<String, Object>> blocks(@PathVariable String districtId) {
        return repo.childrenOf("BLOCK", districtId);
    }

    @GetMapping("/clusters-by-block/{blockId}")
    List<Map<String, Object>> clusters(@PathVariable String blockId) {
        return repo.childrenOf("CLUSTER", blockId);
    }

    @GetMapping("/institutes-by-cluster/{clusterId}")
    List<Map<String, Object>> institutesByCluster(@PathVariable String clusterId) {
        return repo.institutesByCluster(clusterId);
    }

    @GetMapping("/juris-name/{jurisCode}")
    Map<String, Object> jurisName(@PathVariable String jurisCode) {
        // Node returns rows[0]; missing code returned undefined -> empty body. Preserve leniently:
        return repo.jurisName(jurisCode).orElse(Map.of());
    }

    @GetMapping("/districts/all")
    List<Map<String, Object>> allDistricts() { return repo.allDistricts(); }

    @GetMapping("/institutes/all")
    List<Map<String, Object>> allInstitutes() { return repo.allInstituteNames(); }

    @GetMapping("/institutes/search")
    List<Map<String, Object>> searchInstitutes(@RequestParam(required = false) String query) {
        if (query == null || query.isBlank()) {
            throw ApiException.error(400, "Missing query parameter");
        }
        return repo.searchInstitutes(query);
    }

    @PostMapping("/juris-names")
    Map<String, Object> jurisNames(@RequestBody JurisNamesRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("districts", repo.namesByType("EDUCATION DISTRICT", req.districtIds()));
        body.put("blocks", repo.namesByType("BLOCK", req.blockIds()));
        body.put("institutes", repo.instituteNamesByDise(req.instituteIds()));
        return body;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=JurisdictionIT` — Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/masterdata imas-backend/src/test/java/com/rcf/imas/modules/masterdata
git commit -m "feat(masterdata): jurisdiction cascade, districts, institutes, juris-names"
```

---

## Task 10: masterdata — system-config (endpoints 27–32)

> **⚠ SCHEMA CORRECTION (live pg_dump):** `system_config.academic_year` has **no UNIQUE constraint** — inserting a duplicate year SUCCEEDS (200). Keep the `DuplicateKeyException`→400 catch as harmless defensive code, but the integration test must NOT assert a 400 on duplicate; instead assert the second insert returns 200 with its own `system_config_id`. `system_config_id` is `integer` (SERIAL-like) → JSON number, as the code already has it.
>
> **⚠ CODE FIX (Java text blocks):** in `SystemConfigRepository`, the `insert`/`update`/`activate` SQL ends with `RETURNING """ + COLS`. A Java text block strips the trailing space before `"""`, yielding `RETURNINGsystem_config_id` (syntax error → 500). Write `RETURNING """ + " " + COLS` (explicit space). The `delete` query is a plain string literal and already has the space.

Port of `models/systemConfigModel.js` + `controllers/systemConfigController.js`. Success bodies are the raw row objects (`RETURNING *` parity, including timestamps). ADMIN for writes; any authenticated role for reads (`/active` feeds the frontend's academic-year selector).

**Files:**
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/masterdata/persistence/SystemConfigRepository.java`
- Create: `imas-backend/src/main/java/com/rcf/imas/modules/masterdata/web/SystemConfigController.java`
- Test: `imas-backend/src/test/java/com/rcf/imas/modules/masterdata/SystemConfigIT.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.rcf.imas.modules.masterdata;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SystemConfigIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String admin;
    String student;

    @BeforeEach
    void seed() {
        admin = jwt.issueFinalToken("1", "a", "ADMIN");
        student = jwt.issueFinalToken("2", "s", "STUDENT");
        jdbc.sql("DELETE FROM pp.system_config").update();
    }

    @Test
    void createReadActivateFlow() throws Exception {
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"Admissions in Progress\",\"is_active\":true}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.academic_year").value("2026-27"))
           .andExpect(jsonPath("$.is_active").value(true))
           .andExpect(jsonPath("$.system_config_id").isNotEmpty());

        mvc.perform(get("/api/system-config/active").header("Authorization", "Bearer " + student))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].academic_year").value("2026-27"));

        mvc.perform(get("/api/system-config/all").header("Authorization", "Bearer " + admin))
           .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void duplicateAcademicYearIs400() throws Exception {
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"P1\",\"is_active\":false}"))
           .andExpect(status().isOk());
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2026-27\",\"phase\":\"P2\",\"is_active\":false}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Academic year already exists."));
    }

    @Test
    void updateDeleteNotFoundBehaviour() throws Exception {
        mvc.perform(put("/api/system-config/999999").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2027-28\",\"phase\":\"X\",\"is_active\":false}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").value("Configuration not found"));
        mvc.perform(put("/api/system-config/abc").header("Authorization", "Bearer " + admin)
                .contentType(APPLICATION_JSON).content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Invalid or missing config ID"));
        mvc.perform(delete("/api/system-config/999999").header("Authorization", "Bearer " + admin))
           .andExpect(status().isNotFound());
    }

    @Test
    void writesAreAdminOnly() throws Exception {
        mvc.perform(post("/api/system-config").header("Authorization", "Bearer " + student)
                .contentType(APPLICATION_JSON)
                .content("{\"academic_year\":\"2030-31\",\"phase\":\"X\"}"))
           .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SystemConfigIT` — Expected: FAIL (404s).

- [ ] **Step 3: Implement**

`SystemConfigRepository.java` — the row mapper serializes timestamps exactly like node-pg (`YYYY-MM-DDTHH:mm:ss.SSSZ` UTC):
```java
package com.rcf.imas.modules.masterdata.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SystemConfigRepository {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public SystemConfigRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_config_id", rs.getLong("system_config_id"));
        m.put("academic_year", rs.getString("academic_year"));
        m.put("phase", rs.getString("phase"));
        m.put("is_active", rs.getBoolean("is_active"));
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        m.put("created_at", c == null ? null : TS.format(c.toInstant()));
        m.put("updated_at", u == null ? null : TS.format(u.toInstant()));
        return m;
    }

    private static final String COLS = "system_config_id, academic_year, phase, is_active, created_at, updated_at";

    public List<Map<String, Object>> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM pp.system_config ORDER BY created_at DESC")
                .query((rs, i) -> mapRow(rs)).list();
    }

    public List<Map<String, Object>> findActive() {
        return jdbc.sql("SELECT " + COLS + " FROM pp.system_config WHERE is_active = true ORDER BY academic_year DESC")
                .query((rs, i) -> mapRow(rs)).list();
    }

    public Map<String, Object> insert(String academicYear, String phase, Boolean isActive) {
        return jdbc.sql("""
                INSERT INTO pp.system_config (academic_year, phase, is_active)
                VALUES (:y, :p, COALESCE(:a, false)) RETURNING """ + COLS)
                .param("y", academicYear).param("p", phase)
                .param("a", isActive, java.sql.Types.BOOLEAN)
                .query((rs, i) -> mapRow(rs)).single();
    }

    public Optional<Map<String, Object>> update(long id, String academicYear, String phase, Boolean isActive) {
        return jdbc.sql("""
                UPDATE pp.system_config SET academic_year = :y, phase = :p, is_active = :a
                WHERE system_config_id = :id RETURNING """ + COLS)
                .param("y", academicYear).param("p", phase)
                .param("a", isActive, java.sql.Types.BOOLEAN).param("id", id)
                .query((rs, i) -> mapRow(rs)).optional();
    }

    public Optional<Map<String, Object>> delete(long id) {
        return jdbc.sql("DELETE FROM pp.system_config WHERE system_config_id = :id RETURNING " + COLS)
                .param("id", id).query((rs, i) -> mapRow(rs)).optional();
    }

    public Optional<Map<String, Object>> activate(long id) {
        return jdbc.sql("""
                UPDATE pp.system_config SET is_active = true
                WHERE system_config_id = :id RETURNING """ + COLS)
                .param("id", id).query((rs, i) -> mapRow(rs)).optional();
    }
}
```

`SystemConfigController.java`:
```java
package com.rcf.imas.modules.masterdata.web;

import com.rcf.imas.modules.masterdata.persistence.SystemConfigRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system-config")
class SystemConfigController {

    record ConfigRequest(String academicYear, String phase, Boolean isActive) {}
    // NOTE: global snake_case Jackson maps academic_year -> academicYear, is_active -> isActive
    // automatically — the frontend sends snake_case keys, so NO @JsonProperty pins here.

    private final SystemConfigRepository repo;

    SystemConfigController(SystemConfigRepository repo) { this.repo = repo; }

    @GetMapping("/all")
    List<Map<String, Object>> all() { return repo.findAll(); }

    @GetMapping("/active")
    List<Map<String, Object>> active() { return repo.findActive(); }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    Map<String, Object> create(@RequestBody ConfigRequest req) {
        try {
            return repo.insert(req.academicYear(), req.phase(), req.isActive());
        } catch (DuplicateKeyException e) {
            throw ApiException.error(400, "Academic year already exists.");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @RequestBody ConfigRequest req) {
        long configId = parseId(id);
        try {
            return repo.update(configId, req.academicYear(), req.phase(), req.isActive())
                    .orElseThrow(() -> ApiException.error(404, "Configuration not found"));
        } catch (DuplicateKeyException e) {
            throw ApiException.error(400, "Academic year already exists.");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        repo.delete(parseId(id)).orElseThrow(() -> ApiException.error(404, "Configuration not found"));
        return Map.of("message", "Configuration deleted successfully");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/activate")
    Map<String, Object> activate(@PathVariable String id) {
        return repo.activate(parseId(id))
                .orElseThrow(() -> ApiException.error(404, "Configuration not found"));
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.error(400, "Invalid or missing config ID");
        }
    }
}
```

Parity notes: Node's create/update return **200** (plain `res.json`), not 201 — matched. Delete-not-found in Node hits `deleteConfig` returning undefined → 404 `{error}` — matched. `DELETE /api/system-config/abc` in Node throws a DB cast error → 500; here `parseId` gives a clean 400 for update (Node validates) and 400 for delete (minor, strictly better; verify with the parity harness that no frontend flow depends on the 500).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SystemConfigIT` — Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add imas-backend/src/main/java/com/rcf/imas/modules/masterdata imas-backend/src/test/java/com/rcf/imas/modules/masterdata
git commit -m "feat(masterdata): system-config CRUD with RETURNING-row parity"
```

---

## Task 11: Parity harness + frontend fetch audit

**Files:**
- Create: `scripts/parity/phase1-routes.json`
- Create: `scripts/parity/capture-replay.mjs`
- Create: `docs/superpowers/plans/artifacts/phase1-fetch-audit.md`

- [ ] **Step 1: Write the route manifest**

`scripts/parity/phase1-routes.json` — every Phase-1 endpoint with a sample request (fill tokens/ids at run time via env):
```json
{
  "routes": [
    {"method": "POST", "path": "/api/auth/login", "body": {"user_name": "$PARITY_USER", "password": "$PARITY_PASS"}, "public": true},
    {"method": "GET", "path": "/api/users"},
    {"method": "GET", "path": "/api/roles"},
    {"method": "GET", "path": "/api/states"},
    {"method": "GET", "path": "/api/divisions-by-state/$STATE_ID"},
    {"method": "GET", "path": "/api/districts-by-division/$DIVISION_ID"},
    {"method": "GET", "path": "/api/blocks-by-district/$DISTRICT_ID"},
    {"method": "GET", "path": "/api/clusters-by-block/$BLOCK_ID"},
    {"method": "GET", "path": "/api/institutes-by-cluster/$CLUSTER_ID"},
    {"method": "GET", "path": "/api/juris-name/$DISTRICT_ID"},
    {"method": "GET", "path": "/api/districts/all"},
    {"method": "GET", "path": "/api/institutes/all"},
    {"method": "GET", "path": "/api/institutes/search?query=school"},
    {"method": "POST", "path": "/api/juris-names", "body": {"districtIds": ["$DISTRICT_ID"], "blockIds": [], "instituteIds": []}},
    {"method": "GET", "path": "/api/system-config/all"},
    {"method": "GET", "path": "/api/system-config/active"}
  ]
}
```

- [ ] **Step 2: Write the capture/replay script**

`scripts/parity/capture-replay.mjs` (run with Node 18+; no deps):
```js
// Usage: node capture-replay.mjs
//   env: NODE_BASE=http://localhost:4000  JAVA_BASE=http://localhost:8080
//        PARITY_USER, PARITY_PASS, STATE_ID, DIVISION_ID, DISTRICT_ID, BLOCK_ID, CLUSTER_ID
// Compares status + JSON *shape* (keys and value types, recursively) of both backends.
import { readFileSync } from "node:fs";

const NODE_BASE = process.env.NODE_BASE ?? "http://localhost:4000";
const JAVA_BASE = process.env.JAVA_BASE ?? "http://localhost:8080";

const sub = (s) => s.replace(/\$([A-Z_]+)/g, (_, k) => process.env[k] ?? `$${k}`);

function shape(v) {
  if (Array.isArray(v)) return v.length ? [shape(v[0])] : [];
  if (v === null) return "null";
  if (typeof v === "object") {
    return Object.fromEntries(Object.entries(v).sort().map(([k, x]) => [k, shape(x)]));
  }
  return typeof v;
}

async function call(base, r, token) {
  const res = await fetch(base + sub(r.path), {
    method: r.method,
    headers: {
      "content-type": "application/json",
      ...(r.public ? {} : { authorization: `Bearer ${token}` }),
    },
    body: r.body ? sub(JSON.stringify(r.body)) : undefined,
  });
  let body = null;
  try { body = await res.json(); } catch { /* non-JSON */ }
  return { status: res.status, shape: shape(body) };
}

async function login(base) {
  const res = await fetch(base + "/api/auth/login", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ user_name: process.env.PARITY_USER, password: process.env.PARITY_PASS }),
  });
  const { preAuthToken } = await res.json();
  const res2 = await fetch(base + "/api/auth/authorize-role", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ preAuthToken, selectedRole: "ADMIN" }),
  });
  return (await res2.json()).token;
}

const { routes } = JSON.parse(readFileSync(new URL("./phase1-routes.json", import.meta.url)));
const [nodeTok, javaTok] = [await login(NODE_BASE), await login(JAVA_BASE)];
let failures = 0;

for (const r of routes) {
  const [a, b] = [await call(NODE_BASE, r, nodeTok), await call(JAVA_BASE, r, javaTok)];
  const ok = a.status === b.status && JSON.stringify(a.shape) === JSON.stringify(b.shape);
  console.log(`${ok ? "PASS" : "FAIL"}  ${r.method} ${r.path}`);
  if (!ok) {
    failures++;
    console.log("  node:", a.status, JSON.stringify(a.shape).slice(0, 300));
    console.log("  java:", b.status, JSON.stringify(b.shape).slice(0, 300));
  }
}
console.log(failures ? `\n${failures} route(s) differ` : "\nAll routes match");
process.exit(failures ? 1 : 0);
```

- [ ] **Step 3: Run both backends against the same dev DB and execute**

```bash
# terminal 1: Node backend (existing) on :4000
# terminal 2: cd imas-backend && mvn spring-boot:run   (same DB env vars)
PARITY_USER=<admin user> PARITY_PASS=<pw> STATE_ID=... node scripts/parity/capture-replay.mjs
```
Expected: `All routes match`. Investigate and fix every FAIL before cutover. Two **known acceptable** diffs (documented in Task 10): system-config delete/update with non-numeric id (Node 500 vs Java 400), and Java's 401/403 on endpoints Node left unauthenticated — the frontend always sends the token via its axios interceptor.

- [ ] **Step 4: Audit Phase-1 frontend call sites for missing Authorization headers**

The global axios interceptor (`client/src/config/axiosConfig.js`) covers all axios calls. Raw `fetch()` calls must be checked individually:

```bash
cd PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/src
grep -rn "fetch(" --include=*.js | grep -v node_modules | \
  grep -iE "states|divisions|districts|blocks|clusters|institutes|juris|system-config|/users|/roles|auth/"
```

For every hit, confirm the call passes `Authorization: Bearer` (most Coordinator/Teacher fetch sites do). Record findings in `docs/superpowers/plans/artifacts/phase1-fetch-audit.md` as a table: file, line, endpoint, sends token Y/N. Any **N** for a Phase-1 endpoint is a blocker: fix the frontend call (add the header the way `Coordinator/AttendanceTracker.js` does) — a one-line change permitted by the spec's out-of-scope clause ("the fetch() audit may recommend fixes").

- [ ] **Step 5: Commit**

```bash
git add scripts/parity docs/superpowers/plans/artifacts/phase1-fetch-audit.md
git commit -m "test(migration): phase-1 parity harness and frontend fetch audit"
```

---

## Task 12: Dockerfile, compose, nginx strangler map, CI — and cutover

**Files:**
- Create: `imas-backend/Dockerfile`
- Modify: `docker-compose.prod.yml` (repo copy under `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/`)
- Modify: `PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/nginx.conf`
- Create: `.github/workflows/backend-java.yml`

- [ ] **Step 1: Write the Dockerfile**

`imas-backend/Dockerfile`:
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/imas-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Add the service to docker-compose.prod.yml**

Add alongside the existing `server` service (names/env copied from the existing Node service definition — reuse the same `env_file`/environment block so both backends see identical `DB_*`, `JWT_*` values):

```yaml
  imas-backend:
    build:
      context: ../../imas-backend   # adjust relative to compose file location
      dockerfile: Dockerfile
    restart: unless-stopped
    env_file: ./server/.env.production
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    expose:
      - "8080"
    depends_on:
      - db   # match the existing postgres service name in this compose file
```

Open the existing `docker-compose.prod.yml` first and mirror its conventions (service name for postgres, network names, volume mounts for logs). The one non-negotiable: **same env source as the Node service** so `JWT_SECRET` is identical.

- [ ] **Step 3: Add the strangler map to nginx.conf**

In `client/nginx.conf`, before the existing `location /api/` block that proxies to the Node server, add exact-prefix routes for Phase 1 (nginx `^~` prefix match beats the shorter `/api/` prefix):

```nginx
    # ---- Phase 1: migrated to Spring Boot ----
    location ^~ /api/auth/           { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/users           { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/roles           { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/user/           { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/system-config   { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/states          { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/divisions-by-state/    { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/districts-by-division/ { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/blocks-by-district/    { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/clusters-by-block/     { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/institutes-by-cluster/ { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/juris-name/     { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/juris-names     { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/districts/      { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    location ^~ /api/institutes/     { proxy_pass http://imas-backend:8080; include /etc/nginx/proxy-common.conf; }
    # ---- everything else stays on Node ----
    # existing: location /api/ { proxy_pass http://server:4000; ... }
```

If the existing config has no `proxy-common.conf` include, inline the same `proxy_set_header` lines the existing `/api/` block uses (`Host`, `X-Real-IP`, `X-Forwarded-For`) instead. **Rollback = delete (or comment) these lines and reload nginx.**

Watch one trap: the Node config may already route `/api/` with `proxy_pass http://server:4000/` (trailing slash strips the prefix) — mirror whatever slash convention the existing block uses, or paths will double/lose `/api`.

- [ ] **Step 4: CI workflow**

`.github/workflows/backend-java.yml`:
```yaml
name: backend-java
on:
  push:
    paths: ["imas-backend/**"]
  pull_request:
    paths: ["imas-backend/**"]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - name: Test
        run: mvn -q -f imas-backend/pom.xml verify
```
(Testcontainers works out of the box on ubuntu-latest runners.)

- [ ] **Step 5: Cutover checklist (run in order, production)**

1. `docker compose build imas-backend && docker compose up -d imas-backend` — verify `curl http://imas-backend:8080/actuator/health` from the nginx container returns `{"status":"UP"}`.
2. Run the Task-11 parity harness against production Node + the not-yet-routed Spring Boot container (read-only routes only: skip login mutation rows by running with a pre-issued token).
3. Deploy the nginx map; `nginx -s reload`.
4. Smoke-test in the browser: login (each of the 5 roles present in prod), role selection, Users & Roles admin page, System Config page, any admission form that uses the jurisdiction cascade.
5. Watch Spring Boot logs and nginx error log for 30–60 minutes of live traffic.
6. Rollback if needed: comment the Phase-1 `location` blocks, `nginx -s reload` — Node resumes serving everything instantly.

- [ ] **Step 6: Commit**

```bash
git add imas-backend/Dockerfile .github/workflows/backend-java.yml "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/docker-compose.prod.yml" "PP-Portal-v1-2.0.0/PP-Portal-v1-2.0.0/client/nginx.conf"
git commit -m "feat(migration): phase-1 strangler cutover — docker, nginx map, CI"
```

---

## Self-review record

- **Spec coverage:** Phase 0 items (skeleton ✅ T1, platform security ✅ T5–6, error advice ✅ T4, snake_case ✅ T3, Flyway baseline ✅ T2, CI + nginx map ✅ T12) and Phase 1 prefixes (auth ✅ T7, users/roles ✅ T8, jurisdiction cascade + districts + institutes + juris-names ✅ T9, system-config ✅ T10, parity + fetch audit ✅ T11, cutover ✅ T12). FileStorageService and exports from the spec's platform layer are **deliberately deferred to Plan 2** — no Phase-1 endpoint touches files.
- **Placeholder scan:** clean — every code step contains complete code; the one flagged pseudo-line in Task 8 was replaced with the real `COALESCE` statement.
- **Type consistency:** ids are `String` end-to-end (node-pg numeric parity) except `system_config_id` which is `long`/JSON number — correct, because its column is `SERIAL` (integer), and node-pg serializes *integer* columns as JSON numbers; only `numeric` becomes string. `JwtService.FinalToken` is the `@AuthenticationPrincipal` type used in `@PreAuthorize("#userId == principal.userId()")` — matches the principal set in `JwtAuthFilter`.
- **Known intentional diffs from Node** (all strictly safer, verified against frontend usage): enforced auth on previously open endpoints; 400 instead of 500 for malformed system-config ids; `/logs` and `/Data` not served.
- **Hardening decision — `enc_password` in `toggleUserStatus` response:** Node's `RETURNING *` leaks the bcrypt hash in the JSON. The plan preserves it for byte-parity, but the frontend never reads `user.enc_password` (confirm in the Task 11 fetch audit). **Recommended:** drop `enc_password` from the returned `user` object once the audit confirms non-use — the parity harness only checks shape, so removing a field it doesn't assert on is safe, and shipping password hashes over the wire contradicts spec §5. Treat as the one place where hardening should win over strict parity.
- **Hard dependency for the duplicate-year test:** endpoint 28's `DuplicateKeyException` → 400 path only fires if `pp.system_config.academic_year` carries a UNIQUE constraint in the live schema (Node relies on SQLSTATE 23505). Task 0 Step 3 must confirm it exists in the dump; if it is missing, add it via a real Flyway migration `V2__system_config_unique_year.sql` (this is an additive constraint, not a schema change to existing columns, and is in scope) before Task 10.

## Execution handoff

Execute with superpowers:subagent-driven-development (fresh subagent per task, review between tasks) or superpowers:executing-plans (inline with checkpoints). Plans 2–6 will be authored the same way once this phase is live and stable.
