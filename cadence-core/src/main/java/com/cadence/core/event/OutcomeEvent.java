package com.cadence.core.event;

import com.cadence.core.model.EvaluationReason;
import com.cadence.core.model.VariantName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * What the SDK reports back after a flag-gated call completes. This is the only input to the entire
 * release-health layer: rolling windows, canary analysis and the rollback watcher all read nothing else.
 *
 * <p>Reported fire-and-forget on a background virtual thread, so the calling request pays no latency.
 *
 * @param flagKey        which flag gated the call
 * @param variant        which side actually executed
 * @param type           LIVE or SHADOW
 * @param latencyMs      wall-clock duration of the gated call
 * @param success        false for an unhandled exception, a 5xx, a timeout, or an app-reported failure
 * @param errorType      exception class or error code, for grouping; null on success
 * @param customMetrics  app-reported numeric dimensions (conversion, cart value, tokens used, ...)
 * @param outputDiff     for SHADOW events only: a short description of how the candidate's output
 *                       differed from the baseline's, or null when identical
 * @param userId         the bucketing key, retained for debugging a bad rollout; never used for aggregation
 * @param reason         why this variant was chosen
 * @param timestamp      when the gated call finished; set by the SDK, clamped server-side
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeEvent(
        String flagKey,
        VariantName variant,
        EventType type,
        long latencyMs,
        boolean success,
        String errorType,
        Map<String, Double> customMetrics,
        String outputDiff,
        String userId,
        EvaluationReason reason,
        Instant timestamp
) {
    public OutcomeEvent {
        customMetrics = customMetrics == null ? Map.of() : Map.copyOf(customMetrics);
        type = type == null ? EventType.LIVE : type;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public boolean isShadow() {
        return type == EventType.SHADOW;
    }

    /** Redis window namespace: shadow candidate traffic is kept apart from live candidate traffic. */
    public String variantKey() {
        return variant.windowKey(isShadow());
    }

    public static OutcomeEvent success(String flagKey, VariantName variant, long latencyMs) {
        return new OutcomeEvent(flagKey, variant, EventType.LIVE, latencyMs, true,
                null, Map.of(), null, null, null, Instant.now());
    }

    public static OutcomeEvent failure(String flagKey, VariantName variant, long latencyMs, String errorType) {
        return new OutcomeEvent(flagKey, variant, EventType.LIVE, latencyMs, false,
                errorType, Map.of(), null, null, null, Instant.now());
    }
}
