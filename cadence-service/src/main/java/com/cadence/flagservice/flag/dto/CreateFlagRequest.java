package com.cadence.flagservice.flag.dto;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.cadence.core.model.TargetingRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

public record CreateFlagRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9._-]{3,200}$",
                message = "Flag key must be 3-200 chars of letters, digits, dot, underscore or hyphen")
        String key,
        String description,
        Map<String, Object> baselineConfig,
        Map<String, Object> candidateConfig,
        TargetingRules targetingRules,
        List<HealthMetricSpec> healthMetrics,
        RollbackTrigger rollbackTrigger
) {
}
