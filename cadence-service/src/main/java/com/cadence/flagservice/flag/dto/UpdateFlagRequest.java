package com.cadence.flagservice.flag.dto;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.cadence.core.model.TargetingRules;

import java.util.List;
import java.util.Map;

/** All fields optional; nulls are left untouched. Note that {@code state} and {@code rolloutPercentage}
 *  are absent by design — those move only through the audited rollout/pause/rollback operations. */
public record UpdateFlagRequest(
        String description,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targetingRules,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger,
        String reason
) {
}
