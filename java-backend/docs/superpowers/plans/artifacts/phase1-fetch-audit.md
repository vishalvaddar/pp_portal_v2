# Phase-1 Frontend Auth Audit — raw `fetch()` call sites

**Date:** 2026-07-05
**Scope:** The Spring Boot backend enforces JWT auth on Phase-1 endpoints that the Node
backend left open. Every Phase-1 frontend call must therefore send `Authorization: Bearer <token>`,
or it will 401 under the new backend. Axios calls are covered globally by the request interceptor
in `client/src/config/axiosConfig.js` (attaches `Bearer <user.token>` from localStorage to every
axios request). Raw `fetch()` calls bypass that interceptor and were audited individually.

**Search performed** (in `client/src`):

```
grep -rn "fetch(" --include=*.js . | grep -iE "states|divisions|districts|blocks|clusters|institutes|juris|system-config|/users|/roles|auth/"
```

## Findings

| File | Line | Endpoint | Phase-1? | Sends token | Note |
|------|------|----------|----------|-------------|------|
| `pages/Admin/Evaluation/EvaluationDashboard.js` | 53 | `${API_BASE_URL}/jurisdictions/${displayYear}` | No | N | False positive — matched substring "juris". This is the evaluation-dashboard `/jurisdictions/{year}` endpoint, **not** a Phase-1 jurisdiction route (`/api/juris-name/`, `/api/juris-names`, `/api/states`, `/api/districts/…`, etc.). Not migrated in Phase 1; still served by Node. Not a blocker. |
| `pages/Admin/TabInventory.js` | 300 | `${API_BASE}/tabs/users` | No | N | False positive — matched substring "/users". This is the tab-inventory `/tabs/users` endpoint, **not** Phase-1 `/api/users`. Not migrated in Phase 1; still served by Node. Not a blocker. |

## Result

- **Total `fetch()` hits from the audit grep:** 2
- **Hits targeting an actual Phase-1 endpoint:** 0
- **Phase-1 hits sending the token:** N/A (no Phase-1 raw-fetch hits)
- **Cutover blockers (Phase-1 endpoint, `N`):** **0**

**Conclusion:** No raw `fetch()` call targets a Phase-1 endpoint. Both grep hits are false
positives from substring matches ("juris", "/users") against endpoints that remain on Node and
are out of Phase-1 scope. All Phase-1 backend calls in the frontend go through axios and therefore
carry the JWT via the global interceptor in `config/axiosConfig.js`.

**No cutover blockers. No frontend changes required for Phase-1 auth.** (Per instructions, the
React app was not modified — this is audit-only.)

> Note: the two false-positive fetches do not send a token today. That is fine while their
> endpoints stay on Node, but keep them in mind if `/jurisdictions/{year}` or `/tabs/*` are
> migrated to Spring Boot in a later phase — they would need the header (or conversion to axios)
> before that cutover.
