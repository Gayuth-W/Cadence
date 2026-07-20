package com.cadence.core.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * When the platform is allowed to withdraw the candidate without a human.
 *
 * @param autoRollbackEnabled   master switch; a team can opt a flag out of automation entirely
 * @param consecutiveBreaches   how many back-to-back watcher evaluations must breach before firing.
 *                              At the default 30-second watcher interval, 50 breaches ≈ 25 minutes of
 *                              sustained degradation — long enough to ignore a transient blip,
 *                              short enough to beat most incident pages.
 * @param minSamples            floor on window size before any breach counts at all
 * @param cooldownMinutes       TTL of the Redis anti-flap lock; after a rollback the watcher will not
 *                              touch this flag again for this long
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RollbackTrigger(
        boolean autoRollbackEnabled,
        int consecutiveBreaches,
        int minSamples,
        int cooldownMinutes
) {
    public RollbackTrigger {
        consecutiveBreaches = consecutiveBreaches <= 0 ? 50 : consecutiveBreaches;
        minSamples = minSamples <= 0 ? 20 : minSamples;
        cooldownMinutes = cooldownMinutes <= 0 ? 10 : cooldownMinutes;
    }

    public static RollbackTrigger defaults() {
        return new RollbackTrigger(true, 50, 20, 10);
    }
}
