# Cadence — Performance Results Sheet (k6)

> **This is a results template, not measured data.** The numbers below are intentionally blank —
> run the scripts on your machine and paste the figures from k6's end-of-run summary. The pass/fail
> **thresholds are already encoded in each script** (`options.thresholds`), so a run either meets them
> (k6 exits 0) or it doesn't (k6 exits non-zero) — that's your quality gate. Don't publish numbers you
> didn't measure.

Date: `____`
Environment: `____` (CPU / RAM / OS)
Stack: `docker compose up` — Postgres 16, Redis 7, control plane on `:8080`, demo app on `:8081`
k6 version: `____`

## What was tested and why

Cadence has two planes. The **control plane** (`/api/**`, human JWT) is low-QPS admin traffic — a
handful of operators clicking through a rollout. The **data plane** (`/sdk/**`, API key) is where
production load actually lands: every gated call resolves a variant, and every executed call reports
an outcome event. So the performance suite targets the data plane's two hot paths — **evaluation
(read)** and **event ingestion (write)** — plus one representative control-plane read for contrast.

| Scenario | Script | Endpoint | Profile |
|---|---|---|---|
| Evaluate — steady load | `load-evaluate.js` | `POST /sdk/v1/evaluate` | 75 VUs for 2m |
| Flag list — steady load | `load-flags.js` | `GET /api/v1/flags` | 75 VUs for 2m |
| Evaluate — stress | `stress-evaluate.js` | `POST /sdk/v1/evaluate` | ramp 0 → 150 VUs over 4m30s |
| Evaluate — spike | `spike-evaluate.js` | `POST /sdk/v1/evaluate` | burst 20 → 250 → 20 VUs |
| Events — write load | `write-events.js` | `POST /sdk/v1/events` | ramp 0 → 60 VUs over 2m30s |

## Results (fill in from each run's summary)

| Scenario | Throughput (req/s) | Avg | P95 | P99 | Failure rate | Checks | Threshold met? |
|---|---:|---:|---:|---:|---:|---:|:--|
| Evaluate — steady | ____ | ____ ms | ____ ms | ____ ms | ____ % | ____ % | ⬜ |
| Flag list — steady | ____ | ____ ms | ____ ms | ____ ms | ____ % | ____ % | ⬜ |
| Evaluate — stress | ____ | ____ ms | ____ ms | ____ ms | ____ % | ____ % | ⬜ |
| Evaluate — spike | ____ | ____ ms | ____ ms | ____ ms | ____ % | ____ % | ⬜ |
| Events — write | ____ | ____ ms | ____ ms | ____ ms | ____ % | ____ % | ⬜ |

> Where to read each value in the k6 summary: throughput → `http_reqs` rate; Avg/P95/P99 →
> `http_req_duration`; failure rate → `http_req_failed`; checks → the `checks` line.

## Acceptance criteria (encoded as thresholds)

| Requirement | Target | Script that enforces it |
|---|---|---|
| Evaluation latency under steady load | p95 < 500 ms, avg < 200 ms | `load-evaluate.js` |
| Evaluation failure rate | < 1% | `load-evaluate.js`, `load-flags.js` (shared) |
| Flag-list latency | p95 < 500 ms, avg < 300 ms | `load-flags.js` |
| Stress resilience | p95 < 800 ms, failures < 5% | `stress-evaluate.js` |
| Spike resilience | p95 < 1500 ms, failures < 5% | `spike-evaluate.js` |
| Ingestion acknowledge latency | p95 < 800 ms, failures < 5% | `write-events.js` |

A run that trips a threshold prints it in red and exits non-zero. Treat that as a failing gate, not a
number to explain away.

## How to interpret your run (guidance, not claims)

- **Evaluation should be the fastest path.** It's an in-memory bucketing computation (murmur3 + rule
  check) with the flag config cached; there's no per-request DB write. If p95 here is high, look at
  connection-pool sizing or GC, not the algorithm.
- **Latency rising under stress with a flat failure rate is the good outcome** — it means requests
  queue and drain rather than erroring. A sudden jump in `http_req_failed` marks the saturation point;
  note the VU count where it starts.
- **Ingestion is write-heavy but acknowledges early.** The endpoint returns `202` as soon as the batch
  validates and hands fan-out to a virtual thread, so acknowledge latency should stay low even as the
  Redis write volume climbs. If it doesn't, the bottleneck is the ingestion queue, not the HTTP layer.
- **Virtual threads (Java 21) are the reason blocking I/O doesn't cap concurrency** — every handler in
  the service runs on a virtual thread, so a few hundred VUs don't exhaust a platform-thread pool.

## Viva talking points

1. We load-tested the **endpoints that take production traffic** (SDK evaluate + event ingestion), not
   the low-QPS admin CRUD — the performance story matches where the load actually is.
2. Thresholds are **committed in the scripts**, so a run is a pass/fail quality gate, not just a
   graph — it can go in CI.
3. **Reads and writes are exercised separately** because they stress different subsystems (bucketing +
   config cache vs the Redis ingestion pipeline).
4. **Virtual threads** let the service hold many concurrent blocking requests without a large thread
   pool — the stress/spike profiles are where that shows.

## Commands used

```bash
# 1. bring the stack up
docker compose up --build -d

# 2. (optional) warm the windows so evaluate has real config loaded
seq 1 50 | xargs -P 10 -I{} curl -s -o /dev/null "http://localhost:8081/quote/sku-42?userId=warm-{}"

# 3. run each profile
k6 run performance/k6/load-evaluate.js
k6 run performance/k6/load-flags.js
k6 run performance/k6/stress-evaluate.js
k6 run performance/k6/spike-evaluate.js
k6 run performance/k6/write-events.js
```
