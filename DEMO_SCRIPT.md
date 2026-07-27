# Cadence — Demo Script (Presentation-Ready)

Audience: evaluators / interview panel
Duration: ~8 minutes
Goal: show the **closed loop** — Cadence doesn't just track a release, it watches release health and
withdraws a bad candidate on its own — plus the evidence that it's correct (Newman) and fast (k6).

## The one-sentence framing

> "A release-management tool records that a release happened. Cadence decides, from live traffic and a
> statistical test, whether a rollout is *safe to continue* — and pulls it automatically if it isn't."

## Pre-demo checklist (before screen share)

```bash
# from the repo root
docker compose up --build -d          # postgres, redis, control plane :8080, demo app :8081
cd console && npm install && npm start # console on :4200
```

Keep open in tabs:
- the console (`http://localhost:4200`)
- `K6_RESULTS_SHEET.md` and `QA_TESTING_GUIDE.md`
- a terminal at the repo root

Sign in to the console as **admin** (`admin` / `cadence-admin-2026`).

---

## Part A — The closed loop, live (~4 min)

This is the centrepiece. Open the `pricing.engine.v2` flag from the rail.

### 1. Start a canary

In the command bar, type:

```
rollout 25 : begin canary
```

**Point on screen:** the bucket ribbon *extends* to 25% (it doesn't redraw) — say why that matters:
consistent hashing means a user never flips back to the baseline as the rollout grows. The ledger
records the action against **you**, the signed-in admin.

Show a refusal too — it's the product working:

```
rollout 25
```

→ **400**, reason required. "An unexplained change to a live release is unanswerable in a post-mortem."

### 2. Generate real candidate traffic

The release-health panel is empty until traffic flows. In the terminal:

```bash
seq 1 200 | xargs -P 20 -I{} curl -s -o /dev/null "http://localhost:8081/quote/sku-42?userId=user-{}"
```

**Point on screen:** after a few seconds the error-rate and p95 readings populate, and the canary gate
stops abstaining once it has 20 samples per side. (`/quote` reports outcome events; `/whoami` would
only show in the traffic log — worth mentioning as the read-vs-execute distinction.)

### 3. Break the candidate and watch the platform react

Click **Break the candidate** (or `POST http://localhost:8081/chaos/degrade`), then generate more
traffic:

```bash
seq 1 300 | xargs -P 20 -I{} curl -s -o /dev/null "http://localhost:8081/quote/sku-42?userId=user-{}"
```

**Point on screen:**
- the error-rate and p95 rules cross their limit (engraved hatching, no red needed),
- the **breach streak fills** — this flag's trigger is 5 consecutive breaches at a 5s tick,
- within ~25 seconds the control plane **withdraws the candidate on its own**, and because the change
  is **pushed over WebSocket** the sheet flips to **ROLLED_BACK at 0%** the instant it happens (no
  refresh, no poll) — the watcher strip goes idle and the ledger gets a **SYSTEM** entry naming the
  breaching metric, its limit, and the sample count. (The masthead's live dot shows the socket is connected.)

### One-line close

> "No one clicked rollback. The platform saw the candidate degrade, ran the numbers, and pulled it —
> with the evidence written to the audit trail."

### 4. Recover

```
reset : candidate fixed
```

Explain that reset is deliberately more than a state flip: it clears the cooldown **and** wipes the
metric windows, so the fixed candidate is judged fresh instead of being instantly re-rolled-back on
the old failures.

---

## Part B — Correctness evidence (Newman) (~1.5 min)

> "That was one path. Here's the whole API surface verified — including every refusal."

```bash
newman run postman/Cadence.postman_collection.json \
  -e postman/Cadence.postman_environment.json
```

**Point on screen:** 85 requests, assertions green — RBAC `403`s, validation `400`s, the latched
`409`, the canary, both SDK planes, the audit trail. Note that the refusals are asserted as
first-class outcomes.

---

## Part C — Performance evidence (k6) (~1.5 min)

> "And here's the hot path under load — the endpoint that resolves a variant on every gated request."

```bash
k6 run performance/k6/load-evaluate.js
```

**Point on screen:** VUs, evaluation throughput (req/s), p95 latency, `http_req_failed`, and the
threshold line (pass/fail gate). Mention that reads and writes are tested separately and that Java 21
virtual threads are why a few hundred concurrent clients don't exhaust a thread pool. If short on
time, open `K6_RESULTS_SHEET.md` and walk the recorded numbers instead of running live.

---

## Fast re-run block (if asked)

```bash
docker compose up --build -d
# closed loop
#   console: rollout 25 : begin canary  → Break the candidate → wait ~25s → ROLLED_BACK
seq 1 300 | xargs -P 20 -I{} curl -s -o /dev/null "http://localhost:8081/quote/sku-42?userId=user-{}"
# correctness
newman run postman/Cadence.postman_collection.json -e postman/Cadence.postman_environment.json
# performance
k6 run performance/k6/load-evaluate.js
```

## Anticipated questions (15–20s each)

1. **How is this different from a release-management tool?** That tool records releases; Cadence acts
   on live production traffic and decides, statistically, whether a rollout is safe.
2. **Why Mann-Whitney U for the canary?** It's a non-parametric test — latency distributions aren't
   normal, and it compares candidate vs baseline without assuming a shape. It abstains below 20
   samples per side rather than guessing.
3. **Why two auth models?** Humans use JWTs and role permissions; services use scoped API keys. A
   human token doesn't work on `/sdk`, and a service key doesn't work on `/api` — the planes are
   separate.
4. **What makes the bucketing safe?** `murmur3(flagKey + ":" + userId) mod 10000` — deterministic, so
   raising the percentage only *adds* users; no one already on the candidate flips back.
5. **Where's the concurrency story?** Java 21 virtual threads for request handling, Redis for the
   rolling windows, optimistic locking on flag mutations so two operators can't corrupt a rollout.

## Closing line

> "Cadence is a control system for releases: it gates traffic, measures health, and makes the
> rollback decision itself — verified for correctness and load, with an audit trail for every call."
