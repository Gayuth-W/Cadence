package com.cadence.flagservice.metrics.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The retrospective view of one flag's whole life: how many times it was withdrawn, why, and how long
 * it took to reach 100%.
 *
 * @param rollbackCount       forced plus automatic
 * @param rollbackCauses      the reason recorded on each rollback, newest first
 * @param automaticRollbacks  how many the platform decided on its own
 * @param timeToFullRollout   from the first non-zero percentage to FULLY_ON, null if never completed
 * @param usersAffectedByStage estimated share of traffic exposed at each percentage the flag passed through
 */
public record FlagAnalytics(
        UUID flagId,
        String flagKey,
        int rollbackCount,
        int automaticRollbacks,
        List<String> rollbackCauses,
        Instant firstRolloutAt,
        Instant completedAt,
        String timeToFullRollout,
        Map<String, Integer> usersAffectedByStage
) {
}
