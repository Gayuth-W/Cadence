package com.cadence.sdk;

import com.cadence.core.evaluation.TargetingEngine;
import com.cadence.core.event.EventType;
import com.cadence.core.event.OutcomeEvent;
import com.cadence.core.model.EvaluationReason;
import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.UserContext;
import com.cadence.core.model.VariantName;
import com.cadence.sdk.internal.EventReporter;
import com.cadence.sdk.internal.FlagConfigCache;
import com.cadence.sdk.internal.ShadowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The entire public surface of the SDK. Inject it and call {@link #evaluate}.
 *
 * <pre>{@code
 * @Autowired
 * FlagClient flagClient;
 *
 * public Price quote(String userId, Cart cart) {
 *     return flagClient.run("pricing.v2", UserContext.of(userId),
 *             () -> legacyPricing(cart), // baseline
 *             () -> newPricing(cart)); // candidate
 * }
 * }</pre>
 *
 * <p>
 * {@link #run} is the recommended entry point: it evaluates the flag, executes
 * the right side,
 * times it, catches failures, reports the outcome event, and transparently
 * handles SHADOW mode.
 * The calling code never mentions metrics, and yet the rollback watcher has
 * everything it needs.
 *
 * <h2>Failure policy</h2>
 * Evaluation never throws and never blocks on the network. If the control plane
 * is unreachable, the
 * config cache serves its last snapshot; if it never had one, every flag
 * resolves to
 * {@link VariantName#BASELINE} with reason
 * {@link EvaluationReason#ERROR_FALLBACK}. The failure mode
 * of a feature flag system must be "the old code runs", never "the request
 * fails".
 */
public class FlagClient {

    private static final Logger log = LoggerFactory.getLogger(FlagClient.class);

    private final FlagConfigCache configCache;
    private final EventReporter eventReporter;
    private final ShadowExecutor shadowExecutor;
    private final boolean enabled;

    public FlagClient(FlagConfigCache configCache,
            EventReporter eventReporter,
            ShadowExecutor shadowExecutor,
            boolean enabled) {
        this.configCache = configCache;
        this.eventReporter = eventReporter;
        this.shadowExecutor = shadowExecutor;
        this.enabled = enabled;
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Resolve which variant this user should receive. Pure and cheap: a Caffeine
     * lookup plus a
     * MurmurHash3. Safe to call on every request.
     */
    public EvaluationResult evaluate(String flagKey, UserContext ctx) {
        Objects.requireNonNull(flagKey, "flagKey");
        Objects.requireNonNull(ctx, "ctx");

        if (!enabled) {
            return EvaluationResult.baseline(flagKey, Map.of(), EvaluationReason.ERROR_FALLBACK);
        }
        try {
            Optional<FlagDefinition> flag = configCache.find(flagKey);
            if (flag.isEmpty()) {
                // Unknown flag, or the cache was never primed because the control plane is
                // down.
                // Both resolve to baseline; both are safe.
                return EvaluationResult.baseline(flagKey, Map.of(),
                        configCache.isPrimed() ? EvaluationReason.FLAG_NOT_FOUND : EvaluationReason.ERROR_FALLBACK);
            }
            return TargetingEngine.evaluate(flag.get(), ctx);
        } catch (Exception e) {
            // Belt and braces. Nothing in TargetingEngine should throw, but a flag platform
            // is not
            // permitted to be the cause of a 500 in the application it is supposed to
            // protect.
            log.warn("Cadence evaluation of '{}' failed, defaulting to baseline: {}", flagKey, e.toString());
            return EvaluationResult.baseline(flagKey, Map.of(), EvaluationReason.ERROR_FALLBACK);
        }
    }

    /** Convenience for the plain on/off case. */
    public boolean isEnabled(String flagKey, UserContext ctx) {
        return evaluate(flagKey, ctx).isCandidate();
    }

    // ------------------------------------------------------------------
    // Guarded execution: the recommended integration
    // ------------------------------------------------------------------

    /**
     * Evaluate the flag, run the chosen implementation, and report the outcome. In
     * {@link com.cadence.core.model.FlagState#SHADOW} state the baseline result is
     * returned to the
     * user while the candidate executes on a virtual thread and its latency, errors
     * and output diff
     * are recorded.
     *
     * <p>
     * Exceptions from the chosen implementation are reported as failure events and
     * then rethrown:
     * the SDK observes your errors, it does not swallow them.
     */
    public <T> T run(String flagKey, UserContext ctx, Supplier<T> baseline, Supplier<T> candidate) {
        EvaluationResult result = evaluate(flagKey, ctx);

        if (result.shadow()) {
            return runShadowed(flagKey, ctx, result, baseline, candidate);
        }

        Supplier<T> chosen = result.isCandidate() ? candidate : baseline;
        long start = System.nanoTime();
        try {
            T value = chosen.get();
            reportOutcome(flagKey, result, elapsedMs(start), true, null, EventType.LIVE, null, ctx.userId());
            return value;
        } catch (RuntimeException e) {
            reportOutcome(flagKey, result, elapsedMs(start), false, e.getClass().getSimpleName(),
                    EventType.LIVE, null, ctx.userId());
            throw e;
        }
    }

    private <T> T runShadowed(String flagKey, UserContext ctx, EvaluationResult result,
            Supplier<T> baseline, Supplier<T> candidate) {
        long start = System.nanoTime();
        T baselineValue;
        try {
            baselineValue = baseline.get();
        } catch (RuntimeException e) {
            reportOutcome(flagKey, result, elapsedMs(start), false, e.getClass().getSimpleName(),
                    EventType.LIVE, null, ctx.userId());
            throw e;
        }
        long baselineLatency = elapsedMs(start);
        reportOutcome(flagKey, result, baselineLatency, true, null, EventType.LIVE, null, ctx.userId());

        final T captured = baselineValue;
        shadowExecutor.runShadow(candidate::get, (candidateValue, latencyMs, error) -> {
            String diff = error != null ? null : describeDiff(captured, candidateValue);
            OutcomeEvent event = new OutcomeEvent(
                    flagKey,
                    VariantName.CANDIDATE,
                    EventType.SHADOW,
                    latencyMs,
                    error == null,
                    error == null ? null : error.getClass().getSimpleName(),
                    Map.of("baseline_latency_ms", (double) baselineLatency),
                    diff,
                    ctx.userId(),
                    result.reason(),
                    Instant.now());
            eventReporter.report(event);
        });

        return baselineValue;
    }

    /**
     * Cheap, bounded description of how the shadow output differed. The full
     * objects are never sent:
     * they may contain user data, and the control plane has no business storing it.
     */
    private String describeDiff(Object baselineValue, Object candidateValue) {
        if (Objects.equals(baselineValue, candidateValue)) {
            return null;
        }
        String b = String.valueOf(baselineValue);
        String c = String.valueOf(candidateValue);
        return "baseline=%s candidate=%s".formatted(truncate(b), truncate(c));
    }

    private static String truncate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    // ------------------------------------------------------------------
    // Manual reporting, for callers that cannot wrap their code in run()
    // ------------------------------------------------------------------

    /**
     * Report a custom business metric (conversion, cart value, ...) against a
     * previous evaluation.
     */
    public void recordCustomMetric(String flagKey, VariantName variant, String userId, Map<String, Double> metrics) {
        eventReporter.report(new OutcomeEvent(flagKey, variant, EventType.LIVE, 0, true,
                null, metrics, null, userId, null, Instant.now()));
    }

    /** Report an outcome the SDK did not itself execute. */
    public void recordOutcome(String flagKey, VariantName variant, long latencyMs, boolean success, String errorType) {
        eventReporter.report(new OutcomeEvent(flagKey, variant, EventType.LIVE, latencyMs, success,
                errorType, Map.of(), null, null, null, Instant.now()));
    }

    private void reportOutcome(String flagKey, EvaluationResult result, long latencyMs, boolean success,
            String errorType, EventType type, String diff, String userId) {
        // A flag that resolved to ERROR_FALLBACK produced no real split; reporting it
        // would poison
        // the baseline window with events the control plane never actually assigned.
        if (result.reason() == EvaluationReason.ERROR_FALLBACK || result.reason() == EvaluationReason.FLAG_NOT_FOUND) {
            return;
        }
        eventReporter.report(new OutcomeEvent(flagKey, result.variant(), type, latencyMs, success,
                errorType, Map.of(), diff, userId, result.reason(), Instant.now()));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------

    /** True once the local cache has been populated at least once. */
    public boolean isReady() {
        return configCache.isPrimed();
    }
}
