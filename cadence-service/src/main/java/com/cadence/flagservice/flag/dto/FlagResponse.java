package com.cadence.flagservice.flag.dto;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.cadence.core.model.FlagState;
import com.cadence.core.model.TargetingRules;
import com.cadence.flagservice.flag.domain.FeatureFlag;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FlagResponse(
        UUID id,
        String key,
        String description,
        FlagState state,
        int rolloutPercentage,
        String environment,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targetingRules,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger,
        long version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static FlagResponse from(FeatureFlag f) {
        return new FlagResponse(f.getId(), f.getKey(), f.getDescription(), f.getState(),
                f.getRolloutPercentage(), f.getEnvironment(), f.getBaselineConfig(), f.getCandidateConfig(),
                f.getTargetingRules(), f.getHealthMetrics(), f.getRollbackTrigger(), f.getVersion(),
                f.getCreatedBy(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
