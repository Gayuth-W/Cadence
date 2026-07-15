package com.cadence.core.model;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire representation of a flag, as served to the SDK from {@code GET /sdk/v1/flags} and
 * cached locally in Caffeine. This is the only flag shape the evaluation engine understands, so
 * both the SDK (local evaluation) and the control plane (server-side evaluation) agree by construction.
 *
 * @param id                the flag's stable identifier
 * @param key               the human-facing key used in {@code flagClient.evaluate("checkout.v2", ctx)}
 * @param state             lifecycle state
 * @param rolloutPercentage 0–100, the share of unpinned traffic that receives the candidate
 * @param baselineConfig    payload served with the baseline variant
 * @param candidateConfig   payload served with the candidate variant
 * @param targeting         ordered targeting policy, evaluated before percentage bucketing
 * @param healthMetrics     the signals that decide whether the candidate is healthy
 * @param rollbackTrigger   the conditions under which the platform withdraws the candidate on its own
 * @param version           optimistic-locking version; also used by the SDK to detect config changes
 * @param updatedAt         last mutation time
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlagDefinition(
        UUID id,
        String key,
        FlagState state,
        int rolloutPercentage,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targeting,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger,
        long version,
        Instant updatedAt
) {
    public FlagDefinition {
        baselineConfig = baselineConfig == null ? Map.of() : Map.copyOf(baselineConfig);
        candidateConfig = candidateConfig == null ? Map.of() : Map.copyOf(candidateConfig);
        targeting = targeting == null ? TargetingRules.empty() : targeting;
        healthMetrics = healthMetrics == null ? List.of() : List.copyOf(healthMetrics);
        rollbackTrigger = rollbackTrigger == null ? RollbackTrigger.defaults() : rollbackTrigger;
    }
}
