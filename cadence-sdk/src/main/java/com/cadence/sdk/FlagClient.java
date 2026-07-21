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
}
