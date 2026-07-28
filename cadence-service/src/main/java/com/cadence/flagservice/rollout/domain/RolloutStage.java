package com.cadence.flagservice.rollout.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One step of a staged rollout: hold at {@code percentage} for {@code durationMinutes}, then let the
 * canary gate decide whether to move on.
 *
 * <p>Duration is in minutes rather than hours so a demo, an integration test and a real 24-hour
 * bake can all use the same code path. A production schedule is typically
 * {@code [1% for 60m, 5% for 120m, 25% for 240m, 50% for 240m, 100%]}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RolloutStage(int percentage, long durationMinutes) {

    public RolloutStage {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Stage percentage must be between 0 and 100");
        }
        if (durationMinutes < 0) {
            throw new IllegalArgumentException("Stage duration must not be negative");
        }
    }
}
