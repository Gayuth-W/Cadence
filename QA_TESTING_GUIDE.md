# Cadence — QA & Testing Guide

Cadence is verified at three layers, each answering a different question:

| Layer | Tool | Question it answers | Where |
|---|---|---|---|
| **Backend logic** | JUnit + Testcontainers | Does the domain behave correctly? (bucketing, canary math, auto-rollback, RBAC) | `cadence-*/src/test` |
| **API contract** | Postman / Newman | Does every endpoint return the right thing, including the refusals? | `postman/` |
| **Performance** | k6 | Do the hot paths hold up under concurrent load? | `performance/k6/` |

The three are complementary: JUnit covers timing- and statistics-heavy logic that HTTP tests can't
reach; Postman covers the full API surface including error paths; k6 covers throughput and latency.

---

## 1) Backend integration tests (JUnit)

The service ships an integration suite that runs the real Spring context against Testcontainers
(Postgres + Redis), so the tests exercise actual SQL, Redis windows, and the security filter chain —
not mocks.

### Run

```bash
./mvnw verify            # or: mvn verify
```

### What is covered

| Suite | Validates |
|---|---|
| `BucketAssignerTest` | murmur3 bucketing is deterministic and evenly distributed across 10,000 buckets |
| `TargetingEngineTest` | targeting-rule evaluation (country/segment/attribute matching) |
| `StagedRolloutIntegrationTest` | the rollout state machine and stage advancement |
| `CanaryGateIntegrationTest` | the Mann-Whitney gate passes, blocks, and abstains at the right sample counts |
| `AutomaticRollbackIntegrationTest` | sustained breaching triggers a `SYSTEM` rollback with evidence |
| `SecurityAndAuditIntegrationTest` | RBAC (`@PreAuthorize`) and the append-only audit trail |
| `SdkDataPlaneIntegrationTest` | API-key auth, scopes, evaluation, and event ingestion |

These are the tests that cover what Postman can't: the watcher's consecutive-breach counting over real
ticks, the canary statistics, and bucket distribution across thousands of users.

---

## 2) API functional tests (Postman / Newman)

An importable collection in `postman/` exercises the whole HTTP surface — **85 requests across 12
folders** — each with assertions on the behavior it tests: status codes, the RBAC matrix, validation
(`400` with `fieldErrors`), the rollout state machine (including `409` when a rolled-back flag is
latched), the canary gate, both SDK planes, and the audit trail. The refusals (`403`, `409`, `400`)
are tested as first-class outcomes, because in this system a refusal is the product working.

### Run in the Postman app

Import `postman/Cadence.postman_collection.json` and `postman/Cadence.postman_environment.json`,
select the *Cadence — local* environment, and run folder **1 · Auth** first (it populates the role
tokens) — or run the whole collection with the Collection Runner.

### Run headless in CI (Newman)

```bash
npm install -g newman
docker compose up --build -d
newman run postman/Cadence.postman_collection.json \
  -e postman/Cadence.postman_environment.json \
  --reporters cli,junit --reporter-junit-export newman-report.xml
```

`newman` exits non-zero if any assertion fails, so it works as a quality gate. The `junit` reporter
emits a report CI can display.

### What is covered

Auth & RBAC · flag reads · the full lifecycle (`OFF → shadow → rollout → pause → resume → rollback →
409 latched → reset → delete`) · metrics & canary · CSV export · the SDK data plane (with a
determinism check: same `userId` → same variant) · rollout schedules · users · API keys · audit.

See `postman/README.md` for the folder-by-folder breakdown.

---

## 3) Performance tests (k6)

`performance/k6/` load-tests the endpoints that take production traffic — SDK **evaluation** (read)
and **event ingestion** (write) — plus one control-plane read. See `K6_RESULTS_SHEET.md` for the
scenario table, the acceptance thresholds, and where to record results.

### Install k6

```bash
# macOS
brew install k6
# Debian/Ubuntu
sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt update && sudo apt install k6
# Windows
winget install k6.k6
```

### Environment variables (all optional — defaults match the seeded stack)

```bash
export K6_BASE_URL="http://localhost:8080"
export K6_API_KEY="cad_development_ZGVtby1rZXktZG8tbm90LXVzZS1pbi1wcm9k"
export K6_FLAG_KEY="pricing.engine.v2"
export K6_VUS="75"          # steady-load scripts
export K6_DURATION="2m"
```

### Run

```bash
k6 run performance/k6/load-evaluate.js     # read hot path
k6 run performance/k6/load-flags.js        # control-plane read
k6 run performance/k6/stress-evaluate.js   # ramp to saturation
k6 run performance/k6/spike-evaluate.js    # burst resilience
k6 run performance/k6/write-events.js      # ingestion write path
```

### What each validates

- `load-evaluate.js` — steady evaluation throughput and latency (p95 < 500 ms, avg < 200 ms).
- `load-flags.js` — the console's dashboard read under load.
- `stress-evaluate.js` — the saturation point and how latency grows before failures.
- `spike-evaluate.js` — burst resilience and recovery (the shape of a launch).
- `write-events.js` — ingestion throughput; acknowledges async with `202`.

---

## Viva / interview prep

**Why three testing layers instead of one?**
Each reaches something the others can't: JUnit exercises statistics and timing (the canary math, the
watcher counting breaches over ticks); Postman/Newman covers the full API surface including every
error path; k6 measures behavior under concurrency. Correctness and performance are different claims
and need different evidence.

**Why test the refusals (403 / 409 / 400) as first-class cases?**
In a delivery-control system the refusals *are* the safety behavior. A viewer being blocked from a
rollback, a rolled-back flag rejecting a re-rollout, a mutation demanding a reason — those aren't edge
cases, they're the guarantees. If they silently stopped working, the product would be unsafe while
still looking fine.

**Why load-test the SDK endpoints and not the admin CRUD?**
Because that's where production traffic lands. Operators touch the control plane a few times per
rollout; the data plane resolves a variant on *every* gated request and ingests an event on every
executed one. The performance story should match where the load actually is.

**What is a VU in k6?**
A Virtual User — one independent execution context simulating one concurrent client running the
script loop.

**Why does the ingestion endpoint return 202 instead of 200?**
It accepts the batch asynchronously: the moment the payload validates it returns `202 Accepted` and
hands the fan-out into Redis to a virtual thread. The write is acknowledged fast and completed off the
request path.

**How do you keep the tests from interfering with each other?**
The Postman lifecycle folder creates a uniquely-keyed throwaway flag and deletes it; k6 uses a
distinct `userId` per VU/iteration; the JUnit suite runs against ephemeral Testcontainers. Nothing
depends on hand-seeded mutable state.
