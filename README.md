# Cadence

A progressive delivery platform. It ships a change to a small slice of users, watches whether that slice is having a worse time than everyone else, and takes the change away before a human notices — without ever asking one.

Most feature flag systems answer *"is this on?"*. Cadence answers the question that actually matters during a release: *"is this safe to keep turning up?"*

```
staged rollout  →  release-health metrics  →  statistical canary gate  →  automatic rollback
      ↑                                                                          │
      └───────────────────── every transition audited, attributed ───────────────┘
```

Java 21 · Spring Boot 3.2 · PostgreSQL · Redis

---

## Quick start

```bash
docker compose up --build -d
./demo.sh
```

`demo.sh` runs the whole loop in about ninety seconds: it authenticates, rolls a new pricing engine out to 50% of users, generates healthy traffic, then breaks the candidate (≈800 ms latency, 35% error rate) and waits. The platform notices, withdraws the candidate, and records why.

```
==> Watching the platform decide, on its own, to withdraw the candidate
    t=15s  state=ROLLED_BACK  percentage=0%

==> The evidence the platform recorded
    action    : ROLLBACK_AUTOMATIC
    actor     : SYSTEM
    reason    : Automatic rollback: error_rate=0.3421 (limit 0.0200, LOWER_IS_BETTER)
    from      : 50% -> 0%
    breach    : error_rate observed=0.3421 threshold=0.0200 samples=100
    candidate : errorRate=0.3421 p95=889ms
    baseline  : errorRate=0.0000 p95=58ms
```

Interactive API docs: <http://localhost:8080/swagger-ui.html>

Seeded accounts (development only — delete them before anything real):

| user | password | role |
|---|---|---|
| `admin` | `cadence-admin-2026` | ADMIN |
| `operator` | `cadence-operator-2026` | OPERATOR |
| `viewer` | `cadence-viewer-2026` | VIEWER |

---

## Modules

| module | what it is |
|---|---|
| `cadence-core` | Domain model, MurmurHash3 bucketing, the targeting engine. No Spring, no I/O. |
| `cadence-sdk` | Drop-in Spring Boot starter for consuming applications. |
| `cadence-service` | The control plane: flags, RBAC, ingestion, canary analysis, rollback. |
| `cadence-demo` | A pricing service that uses the SDK, with a lever to break itself. |

`cadence-core` exists so that the SDK's local evaluation and the control plane's server-side evaluation run *the same class*. If the evaluation logic lived in two places it would eventually disagree in two places, and the platform would compute release health for a traffic split that never actually happened.

---

## The closed loop

### 1. Stable bucketing

`bucket = murmur3_32(flagKey + ":" + userId) mod 10_000`

Two properties carry the design:

- **A user never flips.** The same user lands in the same bucket for the life of the rollout, so advancing 5% → 10% only ever *adds* users to the candidate. It never reshuffles the ones already there.
- **Flags are independent.** The flag key is salted into the hash, so the unlucky cohort in the first 1% of one flag is not the first 1% of every other flag.

MurmurHash3 is implemented in-tree rather than pulled from Guava, because this function *is* the traffic split. A transitive dependency bump that changed it would silently re-bucket every user in every in-flight release. (It is verified bit-for-bit against the canonical `MurmurHash3_x86_32` vectors.)

`String.hashCode()` would not do: its avalanche behaviour on short similar keys (`user-1`, `user-2`, …) clusters sequential IDs into the same buckets and skews a 1% canary.

### 2. Release-health windows

Every `flag:variant` pair keeps three rolling windows in Redis — last 100 events, last hour, last 24 hours — as sorted sets:

```
cadence:{env}:m:{flagKey}:{variant}:idx:{window}    score = timestamp     ← membership & eviction
cadence:{env}:m:{flagKey}:{variant}:lat:{window}    score = latency ms    ← percentiles by rank
cadence:{env}:m:{flagKey}:{variant}:err:{window}    score = timestamp     ← failures only
cadence:{env}:m:{flagKey}:{variant}:cst:{name}:{w}  score = value         ← custom metrics
```

Two sorted sets, not one, and the reason is load-bearing. Scored by timestamp, a ZSET evicts correctly by age but cannot answer a percentile. Scored by latency, `ZREMRANGEBYRANK` would evict the *fastest* requests rather than the oldest — quietly biasing every percentile upward over time. The index set decides *what* is in the window; the latency set answers *how slow* it is.

Ingestion runs on virtual threads. Each event fans out into roughly a dozen Redis writes and the work is essentially all waiting; a fixed thread pool saturates during a spike and `POST /sdk/v1/events` starts timing out — leaving the platform blind at exactly the moment a rollout is going wrong.

### 3. The canary gate

At every stage transition the candidate's latency distribution is compared against the baseline's with a **Mann-Whitney U test**.

A t-test would be the wrong tool: latency is not normally distributed, it is right-skewed with a long tail, and a handful of slow tail requests can drag the mean around while the rank-based U statistic barely notices. The rank test also assumes nothing about variance.

The gate is one-sided *in effect*. A significant difference where the candidate is **faster** must not block the rollout — rejecting an improvement because it was statistically significant is the classic failure of a naive two-sided gate:

```java
boolean significant = p < alpha;                                   // 0.05
boolean worse       = Direction.LOWER_IS_BETTER.isWorse(candidateMedian, baselineMedian);
boolean passed      = !(significant && worse);
```

Below 30 samples per side the test has no power, so the gate **abstains** rather than guessing, and defers to the operator.

### 4. Automatic rollback

A watcher evaluates every active flag every 30 seconds against its declared thresholds:

```json
{"name":"error_rate",  "kind":"ERROR_RATE",  "direction":"LOWER_IS_BETTER", "threshold":0.02, "window":"LAST_100", "minSamples":20}
{"name":"p95_latency", "kind":"LATENCY_P95", "direction":"LOWER_IS_BETTER", "threshold":400,  "window":"LAST_100", "minSamples":20}
```

- **`minSamples` is a floor on judgement.** One unlucky error at 1% traffic must not withdraw a good release.
- **Breaches must be consecutive.** The default 50 breaches × 30 s ≈ 25 minutes of *sustained* degradation — long enough to ignore a blip, short enough to beat the incident page. A single healthy tick resets the counter to zero.
- **A cooldown lock prevents flapping.** After a rollback the watcher will not touch that flag for ten minutes.
- **`Double.NaN` means "no opinion".** An unreported custom metric is not a conversion rate of zero.
- **An unreachable Redis reports an empty window.** "Unknown" never trips a rollback.

The flag lands in `ROLLED_BACK`, not `OFF`. The distinction matters: `OFF` is *"not started"*, `ROLLED_BACK` is *"we tried and it went wrong"*, and only the latter blocks the scheduler from quietly advancing it again. Clearing it takes an explicit `ADMIN` reset, which forces a human to look at why.

---

## Security

Two authentication planes, because there are two genuinely different callers.

| | control plane `/api/v1/**` | data plane `/sdk/v1/**` |
|---|---|---|
| caller | a human operator | an application pod |
| credential | JWT (HS256) | scoped service API key |
| authorised by | role | scope |
| can | everything their role permits | read flag config, write events |
| cannot | — | create a flag, change a rollout, force a rollback, read the audit log |

Handing an application a user's JWT so it can evaluate a flag would mean every pod in the fleet holds a credential that can force a production rollback. The SDK holds a key that is *strictly less powerful than a VIEWER token*.

```
VIEWER    read flags, metrics, snapshots, audit log
OPERATOR  + advance, pause, resume a rollout
ADMIN     + create/delete flags, edit thresholds, manage users and keys, FORCE ROLLBACK
```

Forced rollback is ADMIN-only on purpose: it is the one action that unilaterally overrides both the automation and every other operator mid-release, and *"who forced the 22:14 rollback"* should have a short list of possible answers.

Details worth naming:

- **Authorisation lives on service methods**, not URL patterns, so a new controller cannot accidentally expose `rollback()` to an OPERATOR.
- **API keys are stored as SHA-256, never plaintext.** SHA-256 rather than BCrypt because the key is 256 bits of `SecureRandom` — there is no dictionary to attack, and a deliberately slow KDF on every data-plane request is a self-inflicted denial of service. Comparison is constant-time. The plaintext is shown exactly once.
- **The environment comes from the authenticated key**, never a request header, so a staging key cannot read production flag config by flipping a header.
- **The JWT secret has no default.** The application refuses to start without one.
- **Failed logins are indistinguishable** whether or not the username exists.
- **The Slack webhook is a bearer credential** and lives in the environment, never in the repository.

---

## The audit trail

Every mutation writes an immutable, actor-attributed record inside the same transaction as the change itself. If the flag update rolls back, so does its audit line; if the audit write fails, the mutation fails. An unauditable change to a production rollout should not be allowed to succeed.

**The actor is never a parameter.** It is resolved from the security context — the authenticated username, or `SYSTEM` when the platform acted on its own. If callers could pass an actor string, the log would record whatever the caller claimed. The one thing an audit log must never do is lie about who acted.

Immutability is enforced three times, because one is not enough:

1. Hibernate `@Immutable` — never issues an `UPDATE`.
2. No setters — application code physically cannot mutate a persisted record.
3. A Postgres trigger that raises on `UPDATE` or `DELETE` — the only one of the three that survives someone opening `psql`.

An automatic rollback carries its evidence: the breaching metric, its observed value, its threshold, the sample count, and the candidate-versus-baseline comparison at the moment of the decision.

---

## Integrating the SDK

Add the dependency:

```xml
<dependency>
    <groupId>com.cadence</groupId>
    <artifactId>cadence-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Configure two properties:

```yaml
cadence:
  sdk:
    base-url: http://cadence-service:8080
    api-key: ${CADENCE_API_KEY}     # scoped service key: flags:read + events:write
    environment: production
```

Inject `FlagClient` and wrap the thing you are changing:

```java
@Autowired FlagClient flagClient;

public Quote quote(String userId, String sku) {
    return flagClient.run("pricing.engine.v2", UserContext.of(userId),
            () -> legacyPricing(sku),    // baseline
            () -> newPricing(sku));      // candidate
}
```

That is the whole integration. `run()` evaluates the flag, executes the chosen side, times it, catches and reports failures, and transparently handles shadow mode. Your code never mentions metrics, and the rollback watcher has everything it needs.

**Failure policy.** Evaluation is a Caffeine lookup plus a hash — no network, no blocking, and it never throws. If the control plane is unreachable, a circuit breaker opens and the cache keeps serving its last known-good snapshot; if it never had one, every flag resolves to `BASELINE`. The failure mode of a feature flag system must be *"the old code runs"*, never *"the request fails"*.

Event reporting is fire-and-forget on a bounded buffer. When the control plane is down and the buffer fills, the oldest events are dropped — losing telemetry is strictly better than OOM-killing a caller that only wanted to check a flag.

### Other shapes this takes

```java
// Plain on/off
if (flagClient.isEnabled("new.checkout", ctx)) { ... }

// Config-driven A/B, no branching
var result = flagClient.evaluate("search.ranking", ctx);
int depth = result.configValue("candidateDepth", 10);

// Business metrics the canary gate can read
flagClient.recordCustomMetric("checkout.v2", result.variant(), userId,
        Map.of("cart_value", cart.total(), "completed", 1.0));
```

### Shadow mode

Set a flag to `SHADOW` and the user is served baseline while the candidate runs in parallel on a virtual thread. Its latency, errors and output diff are recorded; its result is discarded. Zero user impact, real production traffic — the safest way to learn that your rewrite throws on 3% of inputs.

Shadow executions are bounded by a semaphore. Virtual threads are cheap, but the connection pool the candidate touches is not, and shadow traffic must never be why real traffic queues for a connection.

---

## Staged rollouts

```bash
POST /api/v1/rollouts
{
  "flagId": "...",
  "stages": [
    {"percentage": 1,   "durationMinutes": 60},
    {"percentage": 5,   "durationMinutes": 120},
    {"percentage": 25,  "durationMinutes": 240},
    {"percentage": 50,  "durationMinutes": 240},
    {"percentage": 100, "durationMinutes": 0}
  ]
}
```

Stage one is entered ungated — nothing has seen the candidate yet, so there is nothing to compare against. Every subsequent transition runs the canary gate. If it passes, the flag advances and the transition is audited with the U statistic and p-value attached. If it fails, the schedule pauses and the flag *freezes at its current percentage* rather than rolling back: users already on the candidate stay there. Freezing and withdrawing are different decisions and Cadence keeps them different.

Percentages must strictly increase. A schedule that went backwards would re-bucket users out of the candidate — a rollback wearing a schedule's clothes — and rollbacks have their own audited path.

---

## Concurrency

The scheduler, the rollback watcher and a human operator can all try to change the same flag at the same instant. Without optimistic locking, an automatic rollback to 0% and a manual advance to 25% interleave into a lost update: the flag ends at 25% while the audit log says it was withdrawn.

`FeatureFlag` carries a `@Version`, and the automation reads with `OPTIMISTIC_FORCE_INCREMENT` so its writes bump the version even on a read path. The loser of the race gets a clean `409 Conflict`.

A database `CHECK` constraint makes the illegal states unrepresentable too — a flag that reads `FULLY_ON` at 60% cannot be persisted, whatever the application layer believes.

---

## Testing

Real Postgres and real Redis via Testcontainers. Nothing is mocked.

A rollback decision is the product of Redis sorted-set semantics, Postgres optimistic locking and Flyway's constraints acting together. H2 plus a mocked Redis would happily pass a suite for a platform that could never work: H2 has no `jsonb`, and a mocked ZSET cannot reproduce the rank-versus-score distinction the percentile code depends on.

```bash
mvn test
```

| suite | proves |
|---|---|
| `SecurityAndAuditIntegrationTest` | the RBAC matrix; forged tokens rejected; no user enumeration; audit records the real actor |
| `AutomaticRollbackIntegrationTest` | a degraded candidate is withdrawn; healthy ones never are; `minSamples` abstains; the breach counter resets on recovery |
| `CanaryGateIntegrationTest` | a slower candidate is blocked, an equivalent one passes, a **faster** one passes, too few samples abstains |
| `StagedRolloutIntegrationTest` | stages advance on health, freeze on regression, never move backwards |
| `SdkDataPlaneIntegrationTest` | the two auth planes do not cross over |

Synthetic traffic is seeded per-call, not from one shared generator: JUnit does not guarantee method order, and Mann-Whitney at α = 0.05 rejects a healthy candidate about once in twenty runs. A canary test on an unseeded RNG is a flaky test by construction.

---

## Operations

| | |
|---|---|
| Health | `GET /actuator/health` (readiness and liveness probes) |
| Metrics | `GET /actuator/prometheus` — ingestion rate, rejected events, ingestion latency |
| Live events | STOMP over WebSocket at `/ws`, topics `/topic/rollouts` and `/topic/flags/{id}/metrics` |
| Alerts | Slack on automatic rollback, canary block and rollout completion |

An alert can never abort a rollback. Every send is wrapped and time-boxed; notifying humans is strictly less urgent than pulling a bad candidate out of production. The same holds for the WebSocket broadcast — a dead socket must not be able to stop a withdrawal.

### Known limits

- The STOMP broker is in-memory, so it is correct for a single control-plane instance. More than one replica needs an external relay: a rollback fired on instance A would not reach a dashboard connected to instance B. Rollback correctness itself is unaffected — that is guarded by Postgres, not the broker.
- **The scheduled components assume a single instance.** Two replicas means `RollbackWatcherService` ticks twice per interval, so the Redis breach counter reaches its threshold in half the intended time. `RolloutReconciler` double-firing is safe (optimistic locking makes the loser take a 409) and the cooldown key prevents a double rollback, but the watcher's timing is genuinely wrong. Horizontal scaling needs leader election or a distributed lock — ShedLock is the usual answer.
- The 24-hour window is capped at `cadence.metrics.max-window-size` events. Age-based eviction alone would happily hold ten million members for a day.
- `V2__seed.sql` creates development accounts and a demo key. Delete them before any real deployment; the first real ADMIN should be created through `POST /api/v1/users`.

### Build notes

The parent POM pins `flyway.version` to 10.x, overriding the Flyway 9.22.3 that Spring Boot 3.2.5 manages. This is deliberate and both halves are load-bearing:

- Flyway 10 moved database support **out of `flyway-core`** into per-database modules, so `flyway-database-postgresql` is required. Without it, Flyway starts and then dies with `Unsupported Database: PostgreSQL 16.x`.
- Boot 3.2.5's BOM has no entry for that artifact (Boot only started managing Flyway 10 in 3.3), so it carries an explicit version. Removing the override without also removing the module breaks the build with `'dependencies.dependency.version' ... is missing`.

Flyway 10 is compatible with Spring Boot from 3.1.6 onward. If you upgrade the parent to Boot 3.3+, delete both the `flyway.version` property and the `flyway-database-postgresql` entry in `dependencyManagement` — Boot manages both from there.

The Dockerfiles do **not** pre-warm the dependency cache with `dependency:go-offline`. In a multi-module reactor that goal tries to resolve `com.cadence:cadence-core` from Maven Central rather than from the reactor, and fails ([MDEP-568](https://issues.apache.org/jira/browse/MDEP-568)). A BuildKit cache mount on `~/.m2` gives the same benefit without the broken goal, so builds require BuildKit (the default in Docker Compose v2).

---

## Configuration

Everything binds under `cadence.*`. The values that matter:

```yaml
cadence:
  environment: production
  jwt:
    secret: ${CADENCE_JWT_SECRET}     # required, >= 32 bytes; no default
  rollback:
    check-interval: PT30S             # how often the watcher runs
    consecutive-breaches: 50          # 50 x 30s ~= 25 min of sustained degradation
    min-samples: 20
    cooldown: PT10M
  canary:
    alpha: 0.05
    min-samples: 30                   # below this the gate abstains
    window: LAST_1H
  alerting:
    slack-webhook-url: ${CADENCE_SLACK_WEBHOOK_URL:}
```

`check-interval` and the `canary.*` block are live settings. The other `rollback.*` values are **defaults applied when a flag is created** — the watcher always reads the trigger stored on the flag itself. A rollout's safety envelope belongs to the rollout, so retuning the control plane never silently changes the thresholds of a release that is already in flight. Override per flag:

```json
PUT /api/v1/flags/{id}
{"rollbackTrigger": {"autoRollbackEnabled": true, "consecutiveBreaches": 20,
                     "minSamples": 50, "cooldownMinutes": 30},
 "reason": "high-traffic flag: tighten the sample floor, shorten the streak"}
```

The `demo` Spring profile compresses these timescales so the loop is observable in ninety seconds. It is not a production profile: three consecutive bad ticks is noise, not a regression.
