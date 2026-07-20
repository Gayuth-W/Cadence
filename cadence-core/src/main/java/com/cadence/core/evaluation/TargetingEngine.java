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

    private static boolean matches(TargetingRule rule, UserContext ctx) {
        return switch (rule.type()) {
            case ALLOWLIST, BLOCKLIST -> rule.values().contains(ctx.userId());
            case SEGMENT -> rule.values().stream().anyMatch(ctx.segments()::contains);
            case COUNTRY -> ctx.country() != null && rule.values().contains(ctx.country());
            case ATTRIBUTE -> rule.attribute() != null
                    && rule.operator().test(ctx.attribute(rule.attribute()), rule.values());
        };
    }
}
