package com.cadence.core.evaluation;

import com.cadence.core.hash.BucketAssigner;
import com.cadence.core.model.EvaluationReason;
import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.TargetingRule;
import com.cadence.core.model.UserContext;
import com.cadence.core.model.VariantName;

import java.util.Map;

/**
 * The single source of truth for "which variant does this user get".
 *
 * <p>
 * Deliberately a pure, stateless function with no I/O, no Spring, and no
 * logging. The SDK runs it
 * against its local Caffeine cache; the control plane runs the exact same class
 * against Postgres.
 * If evaluation lived in two places it would eventually disagree in two places,
 * and a rollout would
 * report metrics for a split that never actually happened.
 *
 * <p>
 * Order of resolution:
 * <ol>
 * <li>terminal flag states (OFF / ROLLED_BACK / FULLY_ON / SHADOW)</li>
 * <li>targeting rules, ascending priority, first match wins</li>
 * <li>percentage bucketing via {@link BucketAssigner}</li>
 * </ol>
 */
public final class TargetingEngine {

    private TargetingEngine() {
    }

    public static EvaluationResult evaluate(FlagDefinition flag, UserContext ctx) {
        Map<String, Object> baseline = flag.baselineConfig();
        Map<String, Object> candidate = flag.candidateConfig();

        // ---- 1. Terminal states short-circuit everything, including targeting. ----
        // A rolled-back flag must not honour an allowlist: "rolled back" means nobody,
        // no exceptions.
        switch (flag.state()) {
            case OFF -> {
                return EvaluationResult.baseline(flag.key(), baseline, EvaluationReason.FLAG_OFF);
            }
            case ROLLED_BACK -> {
                return EvaluationResult.baseline(flag.key(), baseline, EvaluationReason.ROLLED_BACK);
            }
            case FULLY_ON -> {
                return new EvaluationResult(flag.key(), VariantName.CANDIDATE, candidate,
                        EvaluationReason.FULLY_ON, -1, false);
            }
            case SHADOW -> {
                // The user is served baseline; the shadow flag tells the SDK to also execute
                // the
                // candidate on a virtual thread and report its metrics.
                return new EvaluationResult(flag.key(), VariantName.BASELINE, baseline,
                        EvaluationReason.SHADOW_BASELINE, -1, true);
            }
            default -> {
                // ROLLING_OUT and PAUSED both serve a real split; fall through.
            }
        }

        // ---- 2. Targeting rules, in priority order. ----
        for (TargetingRule rule : flag.targeting().rules()) {
            if (matches(rule, ctx)) {
                VariantName variant = rule.variant();
                return new EvaluationResult(
                        flag.key(),
                        variant,
                        variant == VariantName.CANDIDATE ? candidate : baseline,
                        EvaluationReason.TARGETING_MATCH,
                        -1,
                        false);
            }
        }

        // ---- 3. Stable percentage bucketing. ----
        int bucket = BucketAssigner.bucket(flag.key(), ctx.userId());
        int threshold = flag.rolloutPercentage() * (BucketAssigner.TOTAL_BUCKETS / 100);
        boolean inRollout = bucket < threshold;

        return new EvaluationResult(
                flag.key(),
                inRollout ? VariantName.CANDIDATE : VariantName.BASELINE,
                inRollout ? candidate : baseline,
                inRollout ? EvaluationReason.PERCENTAGE_ROLLOUT : EvaluationReason.PERCENTAGE_EXCLUDED,
                bucket,
                false);
    }

    private static boolean matches(TargetingRule rule, UserContext ctx) {
        return switch (rule.type()) {
            // Note: BLOCKLIST rules must be authored with variant=BASELINE
            case ALLOWLIST, BLOCKLIST -> rule.values().contains(ctx.userId());
            case SEGMENT -> rule.values().stream().anyMatch(ctx.segments()::contains);
            case COUNTRY -> ctx.country() != null && rule.values().contains(ctx.country());
            case ATTRIBUTE -> rule.attribute() != null
                    && rule.operator().test(ctx.attribute(rule.attribute()), rule.values());
        };
    }
}
