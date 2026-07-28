package com.cadence.flagservice;

import com.cadence.core.event.EventBatch;
import com.cadence.core.event.EventType;
import com.cadence.core.event.OutcomeEvent;
import com.cadence.core.model.EvaluationReason;
import com.cadence.core.model.VariantName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Synthetic traffic generators.
 *
 * <p>Mann-Whitney is a probabilistic test: at {@code alpha = 0.05} a gate fed unseeded random traffic
 * will reject a perfectly healthy candidate about once in twenty runs. A canary test built on an
 * unseeded RNG is therefore a flaky test by construction, and one that fails at 3 a.m. in CI with no
 * way to reproduce it.
 *
 * <p>Seeding a single shared {@link Random} would not be enough either. JUnit does not guarantee
 * method execution order, so a shared generator would hand different draws to the same test depending
 * on which tests ran before it — deterministic in principle, unreproducible in practice. Instead each
 * call derives its own seed from its arguments, so a given (flag, variant, count, latency, error-rate)
 * request always yields byte-for-byte the same traffic, whatever else the suite did first.
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** A batch of healthy events: latency centred on {@code meanLatencyMs}, no failures. */
    public static EventBatch healthy(String flagKey, VariantName variant, int count, double meanLatencyMs) {
        return batch(flagKey, variant, count, meanLatencyMs, 0.0);
    }

    /** A batch where roughly {@code errorRate} of the events failed. */
    public static EventBatch batch(String flagKey, VariantName variant, int count,
                                   double meanLatencyMs, double errorRate) {
        // variant.name(), not variant: Enum.hashCode() is the identity hash, which differs on every
        // JVM run. Hashing the enum directly would produce a seed that looks deterministic and is not.
        Random random = new Random(Objects.hash(flagKey, variant.name(), count, meanLatencyMs, errorRate));

        List<OutcomeEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean success = random.nextDouble() >= errorRate;
            long latency = Math.max(1, Math.round(meanLatencyMs + random.nextGaussian() * (meanLatencyMs * 0.15)));
            events.add(new OutcomeEvent(
                    flagKey,
                    variant,
                    EventType.LIVE,
                    latency,
                    success,
                    success ? null : "SyntheticFailure",
                    Map.of(),
                    null,
                    "user-" + i,
                    variant == VariantName.CANDIDATE
                            ? EvaluationReason.PERCENTAGE_ROLLOUT
                            : EvaluationReason.PERCENTAGE_EXCLUDED,
                    Instant.now()));
        }
        return new EventBatch(events);
    }
}
