// CUTOVER-TIME TOOL — NOT run in CI or during Phase-1 development.
// This script is executed at cutover to compare the live PRODUCTION Node backend
// against the new Spring Boot backend (both pointed at the production DB) before the
// single big-bang swap. It needs both backends running and the real data, neither of
// which is available in the dev/CI environment, so do not run it here.
//
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
