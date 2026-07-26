package com.cadence.flagservice.rollout.dto;

import com.cadence.flagservice.rollout.domain.RolloutSchedule;
import com.cadence.flagservice.rollout.domain.RolloutStage;
import com.cadence.flagservice.rollout.domain.RolloutStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        List<RolloutStage> stages,
        int currentStageIndex,
        Integer currentPercentage,
        RolloutStatus status,
        Instant startedAt,
        Instant nextTransitionAt,
        Instant completedAt,
        String createdBy,
        Instant createdAt
) {
    public static ScheduleResponse from(RolloutSchedule s) {
        RolloutStage current = s.currentStage();
        return new ScheduleResponse(s.getId(), s.getFlagId(), s.getFlagKey(), s.getStages(),
                s.getCurrentStageIndex(), current == null ? null : current.percentage(),
                s.getStatus(), s.getStartedAt(), s.getNextTransitionAt(), s.getCompletedAt(),
                s.getCreatedBy(), s.getCreatedAt());
    }
}
