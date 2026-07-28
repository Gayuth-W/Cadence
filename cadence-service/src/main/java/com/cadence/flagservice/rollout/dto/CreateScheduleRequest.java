package com.cadence.flagservice.rollout.dto;

import com.cadence.flagservice.rollout.domain.RolloutStage;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * {
 *   "flagId": "...",
 *   "stages": [
 *     {"percentage": 1,  "durationMinutes": 60},
 *     {"percentage": 5,  "durationMinutes": 120},
 *     {"percentage": 25, "durationMinutes": 240},
 *     {"percentage": 50, "durationMinutes": 240},
 *     {"percentage": 100,"durationMinutes": 0}
 *   ]
 * }
 * </pre>
 */
public record CreateScheduleRequest(
        @NotNull UUID flagId,
        @NotEmpty List<RolloutStage> stages
) {
}
