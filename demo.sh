#!/usr/bin/env bash
#
# Cadence end-to-end demo.
#
#   docker compose up --build -d
#   ./demo.sh
#
# Drives the full closed loop in about ninety seconds:
#
#   authenticate -> start a 50% rollout -> generate healthy traffic -> break the candidate
#   -> watch the platform withdraw it on its own -> read the evidence it recorded
#
# Re-runnable: a previous run deliberately leaves the flag ROLLED_BACK, and this script returns it
# to a clean OFF state first.
#
# Nothing here is privileged magic: every step is a call an operator could make from the API docs
# at http://localhost:8080/swagger-ui.html

set -euo pipefail

SERVICE="${SERVICE:-http://localhost:8080}"
DEMO="${DEMO:-http://localhost:8081}"
FLAG_KEY="${FLAG_KEY:-pricing.engine.v2}"

ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-cadence-admin-2026}"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

step()  { printf '\n%s==> %s%s\n' "$BOLD" "$1" "$RESET"; }
info()  { printf '    %s%s%s\n' "$DIM" "$1" "$RESET"; }
ok()    { printf '    %s%s%s\n' "$GREEN" "$1" "$RESET"; }
warn()  { printf '    %s%s%s\n' "$YELLOW" "$1" "$RESET"; }
fail()  { printf '    %s%s%s\n' "$RED" "$1" "$RESET"; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but not installed"; }
need curl
need python3

# Extract a field from JSON on stdin, e.g.  echo "$body" | field "['token']"
field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

# ---------------------------------------------------------------------------
step "Waiting for the control plane at $SERVICE"
for i in $(seq 1 60); do
    if curl -fsS "$SERVICE/actuator/health" >/dev/null 2>&1; then
        ok "control plane is up"
        break
    fi
    [ "$i" -eq 60 ] && fail "control plane never became healthy — try: docker compose logs cadence-service"
    sleep 2
done

for i in $(seq 1 60); do
    if curl -fsS "$DEMO/chaos/status" >/dev/null 2>&1; then
        ok "demo application is up"
        break
    fi
    [ "$i" -eq 60 ] && fail "demo app never became healthy — try: docker compose logs cadence-demo"
    sleep 2
done

# ---------------------------------------------------------------------------
step "Authenticating as '$ADMIN_USER'"
LOGIN_BODY=$(curl -fsS -X POST "$SERVICE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
TOKEN=$(echo "$LOGIN_BODY" | field "['token']")
ROLES=$(echo "$LOGIN_BODY" | field "['roles']")
ok "got a bearer token; roles=$ROLES"
AUTH=(-H "Authorization: Bearer $TOKEN")

# ---------------------------------------------------------------------------
step "Reading the seeded flag '$FLAG_KEY'"
FLAG=$(curl -fsS "${AUTH[@]}" "$SERVICE/api/v1/flags/by-key/$FLAG_KEY")
FLAG_ID=$(echo "$FLAG" | field "['id']")
STATE=$(echo "$FLAG" | field "['state']")
info "id=$FLAG_ID  state=$STATE  percentage=$(echo "$FLAG" | field "['rolloutPercentage']")%"

# ---------------------------------------------------------------------------
# A previous run leaves the flag ROLLED_BACK — that is the platform working, not a fault. Rolling it
# out again from that state is refused with a 409 on purpose, so put it back to OFF first. This makes
# the demo re-runnable without `docker compose down -v`.
if [ "$STATE" != "OFF" ]; then
    step "Flag is $STATE from a previous run — returning it to a clean OFF state"

    if [ "$STATE" != "ROLLED_BACK" ]; then
        info "withdrawing the live candidate first (ADMIN force rollback)"
        curl -fsS -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
            "$SERVICE/api/v1/flags/$FLAG_ID/rollback" \
            -d '{"reason":"demo: resetting to a clean state before a fresh run"}' >/dev/null
    fi

    # reset() also wipes the stale rolling windows and clears the post-rollback cooldown, so the
    # watcher starts this run guarding a genuinely empty candidate window.
    curl -fsS -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
        "$SERVICE/api/v1/flags/$FLAG_ID/reset" \
        -d '{"reason":"demo: previous run left the candidate withdrawn"}' >/dev/null
    ok "flag is OFF at 0%, metric windows cleared, cooldown released"
fi

# The demo app may still be degraded if a previous run was interrupted before its cleanup ran.
curl -fsS -X POST "$DEMO/chaos/recover" >/dev/null 2>&1 || true

# The seeded flag ships with production trigger values (50 consecutive breaches x 30s ~ 25 minutes).
# That is the right default and a terrible demo, so tighten it. This is a normal, audited flag edit.
step "Tightening the rollback trigger so the loop is observable"
curl -fsS -X PUT "${AUTH[@]}" -H 'Content-Type: application/json' \
    "$SERVICE/api/v1/flags/$FLAG_ID" \
    -d '{"rollbackTrigger":{"autoRollbackEnabled":true,"consecutiveBreaches":3,"minSamples":20,"cooldownMinutes":1},
         "reason":"demo: compress the breach streak from 50 ticks to 3"}' >/dev/null
ok "3 consecutive bad ticks at 5s each will now trigger a rollback"

# ---------------------------------------------------------------------------
step "Rolling out the candidate to 50% of users"
ROLLOUT=$(curl -fsS -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
    "$SERVICE/api/v1/flags/$FLAG_ID/rollout" \
    -d '{"percentage":50,"reason":"demo: begin progressive delivery of pricing v2"}') \
    || fail "rollout was refused (HTTP error). A ROLLED_BACK flag must be reset by an ADMIN first."
echo "$ROLLOUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"    state={d['state']} percentage={d['rolloutPercentage']}%\")"

# The demo app evaluates flags against its LOCAL cache, which polls the control plane every 5s
# (cadence.sdk.refresh-interval). Asking it immediately after the rollout returns the PREVIOUS
# state -- not a bug, but the eventually-consistent config cache doing exactly what it is designed
# to do. Without this wait the whole "healthy traffic" phase below runs against a stale snapshot,
# every request is served baseline, and the candidate window stays empty.
#
# Poll until the cache reports a bucketing decision (PERCENTAGE_ROLLOUT / PERCENTAGE_EXCLUDED),
# which only happens once it has seen state=ROLLING_OUT. Waiting for "anything but ROLLED_BACK"
# would be wrong: on a first run the stale reason is FLAG_OFF, which is equally stale.
info "waiting for the SDK's config cache to pick up the change (refresh-interval = 5s)"
CACHE_FRESH=false
for _ in $(seq 1 15); do
    REASON=$(curl -fsS "$DEMO/whoami?userId=carol" | field "['reason']" 2>/dev/null || echo "")
    case "$REASON" in
        PERCENTAGE_*) CACHE_FRESH=true; break ;;
    esac
    sleep 1
done
[ "$CACHE_FRESH" = true ] || fail "SDK cache never picked up the rollout (last reason: $REASON)"
ok "SDK cache refreshed"

info "user bucketing is stable — the same user always gets the same variant:"
for u in alice bob carol; do
    V=$(curl -fsS "$DEMO/whoami?userId=$u" | field "['variant']")
    R=$(curl -fsS "$DEMO/whoami?userId=$u" | field "['reason']")
    info "  $u -> $V ($R)"
done

# ---------------------------------------------------------------------------
step "Generating healthy traffic (both variants report clean metrics)"
seq 1 200 | xargs -P 20 -I{} curl -fsS -o /dev/null "$DEMO/quote/sku-42?userId=user-{}" || true
sleep 8   # let the SDK flush its event buffer and the watcher take at least one tick

# Verify, do not assert. If the candidate window were empty (stale cache) or unhealthy, this is
# where the demo should notice -- not three steps later when the story stops making sense.
HEALTHY_STATE=$(curl -fsS "${AUTH[@]}" "$SERVICE/api/v1/flags/$FLAG_ID" | field "['state']")
[ "$HEALTHY_STATE" = "ROLLING_OUT" ] \
    || fail "expected ROLLING_OUT after healthy traffic, got $HEALTHY_STATE"
CAND=$(curl -fsS "${AUTH[@]}" "$SERVICE/api/v1/metrics/flags/$FLAG_ID?window=LAST_100" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['candidate']['sampleCount'])" 2>/dev/null || echo 0)
ok "200 requests served; candidate window holds $CAND event(s) and the rollout holds at 50%"

# ---------------------------------------------------------------------------
step "Breaking the candidate: pricing v2 now takes ~800ms and fails 35% of the time"
curl -fsS -X POST "$DEMO/chaos/degrade" | field "['note']" | sed 's/^/    /'
warn "the baseline is untouched — only users bucketed into the candidate are affected"

step "Generating traffic against the broken candidate"
seq 201 500 | xargs -P 25 -I{} curl -sS -o /dev/null "$DEMO/quote/sku-42?userId=user-{}" 2>/dev/null || true
ok "300 requests served (roughly half hit the failing candidate)"

# ---------------------------------------------------------------------------
step "Watching the platform decide, on its own, to withdraw the candidate"
info "the watcher evaluates error-rate and P95 every 5s; 3 consecutive breaches fire a rollback"

ROLLED_BACK=false
for i in $(seq 1 40); do
    SNAPSHOT=$(curl -fsS "${AUTH[@]}" "$SERVICE/api/v1/flags/$FLAG_ID")
    STATE=$(echo "$SNAPSHOT" | field "['state']")
    PCT=$(echo "$SNAPSHOT" | field "['rolloutPercentage']")
    printf '\r    t=%02ds  state=%-12s percentage=%s%%   ' "$((i * 3))" "$STATE" "$PCT"
    if [ "$STATE" = "ROLLED_BACK" ]; then
        ROLLED_BACK=true
        printf '\n'
        break
    fi
    sleep 3
done

[ "$ROLLED_BACK" = true ] || fail "no rollback after 120s. Check: docker compose logs cadence-service | grep -i rollback"
ok "AUTOMATIC ROLLBACK — every user is back on the baseline pricing engine"

# ---------------------------------------------------------------------------
step "The evidence the platform recorded"
# No f-strings and no escaped quotes here. This block is wrapped in shell single quotes, so a
# backslash reaches Python literally -- and `f"{r[\"action\"]}"` is a SyntaxError, not an escape.
# %-formatting with double-quoted keys sidesteps the quoting problem entirely.
curl -fsS "${AUTH[@]}" "$SERVICE/api/v1/audit/flags/$FLAG_ID/rollbacks" | python3 -c '
import sys, json
records = json.load(sys.stdin)
if not records:
    print("    (no rollbacks recorded)")
for r in records[:1]:                       # findRollbacks() returns newest first
    m = r.get("metadata") or {}
    print("    action    :", r["action"])
    print("    actor     :", r["actor"])
    print("    reason    :", r["reason"])
    print("    from      : %s%% -> 0%%" % m.get("percentageBeforeRollback"))
    for name in ("error_rate", "p95_latency"):
        e = m.get(name)
        if isinstance(e, dict):
            print("    breach    : %s observed=%.4f threshold=%.4f samples=%s"
                  % (name, e["observed"], e["threshold"], e["samples"]))
    print("    candidate : errorRate=%.4f p95=%.0fms"
          % (m.get("candidateErrorRate", 0), m.get("candidateP95Ms", 0)))
    print("    baseline  : errorRate=%.4f p95=%.0fms"
          % (m.get("baselineErrorRate", 0), m.get("baselineP95Ms", 0)))
earlier = len(records) - 1
if earlier > 0:
    print()
    print("    (%d earlier rollback(s) of this flag remain in the trail -- the audit log is" % earlier)
    print("     append-only, so previous runs are history, not clutter to be deleted)")
'

step "Proving the audit trail cannot be forged"
info "the rollback was attributed to SYSTEM, not to a person — no human touched it"
info "an OPERATOR cannot force a rollback either; only an ADMIN can:"
OP_TOKEN=$(curl -fsS -X POST "$SERVICE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"operator","password":"cadence-operator-2026"}' | field "['token']")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $OP_TOKEN" -H 'Content-Type: application/json' \
    "$SERVICE/api/v1/flags/$FLAG_ID/rollback" -d '{"reason":"operator attempts a force rollback"}')
[ "$CODE" = "403" ] && ok "operator -> 403 Forbidden (as designed)" || warn "expected 403, got $CODE"

# ---------------------------------------------------------------------------
step "Cleaning up"
curl -fsS -X POST "$DEMO/chaos/recover" >/dev/null
info "candidate repaired. It stays ROLLED_BACK until an ADMIN explicitly resets it:"
info "  curl -X POST $SERVICE/api/v1/flags/$FLAG_ID/reset \\"
info "       -H 'Authorization: Bearer \$TOKEN' -H 'Content-Type: application/json' \\"
info "       -d '{\"reason\":\"candidate fixed\"}'"

printf '\n%sDone.%s Audit log: %s/api/v1/audit   API docs: %s/swagger-ui.html\n\n' \
    "$BOLD" "$RESET" "$SERVICE" "$SERVICE"