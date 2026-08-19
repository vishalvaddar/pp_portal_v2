# Phase-1 Cutover Notes — big-bang swap (no Docker)

Operator runbook for cutting the Phase-1 surface (auth, users/roles, jurisdiction, system-config)
from the Node backend to the Spring Boot `imas-backend`. This is a **single big-bang swap** — the
whole backend is replaced at once, not a per-route strangler. There is **no Docker/compose/nginx
strangler**; run the JAR directly as a process.

## 1. Build and run the JAR

```bash
# Build (tests already run in CI; skip here for a fast package)
mvn -f imas-backend/pom.xml -DskipTests package
# Produces target/imas-backend-<version>.jar

# Run it with the SAME env vars the Node server used
DB_HOST=...            \
DB_PORT=5432           \
DB_USER=...            \
DB_PASSWORD=...        \
DB_NAME=...            \
JWT_SECRET=...         \
JWT_EXPIRES_IN=...     \
PRE_AUTH_JWT_EXPIRES_IN=... \
java -jar imas-backend/target/imas-backend-*.jar
```

The Spring Boot config reads these same names so tokens issued by Node validate on Java and vice
versa (identical `JWT_SECRET`), and both point at the same production DB. Confirm the app is up
before proceeding (health endpoint / logs show it bound to its port).

## 2. Run the parity harness (production Node vs new Spring Boot)

With **production Node still live** and the new Spring Boot backend running on a separate port
(not yet receiving traffic), compare response status + JSON shape across every Phase-1 route:

```bash
NODE_BASE=http://<node-host>:4000  JAVA_BASE=http://<java-host>:8080 \
PARITY_USER=<admin user> PARITY_PASS=<pw> \
STATE_ID=... DIVISION_ID=... DISTRICT_ID=... BLOCK_ID=... CLUSTER_ID=... \
node scripts/parity/capture-replay.mjs
```

Expected: `All routes match`. Investigate every `FAIL` before swapping.
Two **known-acceptable** diffs (documented in the plan): system-config update/delete with a
non-numeric id (Node 500 vs Java 400), and Java's 401/403 on endpoints Node left unauthenticated
— the frontend always sends the token via its axios interceptor, so these never surface in the app.

## 3. Run the frontend auth audit

Re-run / re-read the auth audit (`phase1-fetch-audit.md`) to confirm no raw `fetch()` call to a
Phase-1 endpoint is missing the `Authorization: Bearer` header. The Spring Boot backend enforces
auth on endpoints Node left open, so any un-tokened Phase-1 call would 401 after the swap.
Current status: **0 blockers** — all Phase-1 calls use axios (global interceptor attaches the JWT).

## 4. The swap

1. Point the reverse proxy / frontend at the Spring Boot backend: set the frontend build's
   `REACT_APP_BACKEND_API_URL` (and/or the reverse proxy upstream) to the Spring Boot host:port.
2. Bring the Spring Boot backend into rotation and **retire the Node server**.
3. **Keep the Node build git-tagged** (e.g. `git tag node-backend-pre-cutover && git push --tags`)
   so rollback is instant: repoint `REACT_APP_BACKEND_API_URL` / the proxy back at a redeployed
   Node from that tag.
4. Smoke-test in the browser: login for each role present in prod, role selection, Users & Roles
   admin page, System Config page, and any admission form that uses the jurisdiction cascade.
5. Watch Spring Boot + proxy logs for 30–60 minutes of live traffic.

**Rollback:** repoint the frontend/proxy back to Node (redeployed from the git tag) and restart.

## 5. Secret rotation (do this at/after cutover — required)

The Node `.env` / `.env.production` committed real secrets to git. After cutover:

- **Rotate `JWT_SECRET`** — issue a new value, set it in the Spring Boot env. (All existing tokens
  invalidate; users re-login. Do this during/after the swap, not before parity testing, since the
  harness needs both backends to share the current secret.)
- **Rotate the DB password** (`DB_PASSWORD`) — change it in Postgres and update the env.
- **Purge both from git history** — the committed `.env`/`.env.production` values must be scrubbed
  (e.g. `git filter-repo` / BFG) and the repo force-pushed; rotating alone is not enough because the
  old values remain recoverable from history.

Track this as a hard follow-up — the leaked secrets are a live exposure until rotated **and** purged.
