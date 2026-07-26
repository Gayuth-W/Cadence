package com.cadence.flagservice.metrics.service;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.cadence.core.model.FlagState;
import com.cadence.flagservice.alert.AlertService;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import com.cadence.flagservice.metrics.model.WindowStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed loop's last link: watch every live candidate, and pull it when it misbehaves.
 *
 * <h2>The two failure modes this design defends against</h2>
 *
 * <p><b>Flapping.</b> A single bad 30-second tick is noise — a GC pause, a cold cache, one slow
 * downstream. Rolling back on it would make the platform less trustworthy than no automation at all.
 * So a breach must be <i>consecutive</i>: the counter increments on every breaching tick and is
 * deleted the moment the candidate looks healthy again. Only {@code consecutiveBreaches} unbroken
 * failures (50 × 30s ≈ 25 minutes by default) fire a rollback.
 *
 * <p><b>Rollback storms.</b> After a rollback, a Redis cooldown key with a TTL stops the watcher from
 * touching this flag again for {@code cooldownMinutes}. Without it, a flag that an operator resets and
 * re-advances during an ongoing incident would be withdrawn again on the very next tick, before enough
 * fresh events exist to say anything.
 *
 * <h2>Why "no data" never triggers a rollback</h2>
 * Every metric is gated on {@code minSamples}. An empty window means Redis is unreachable, or the
 * candidate is at 1% and has served nine requests. Neither is evidence of a bad release. Silence must
 * read as "unknown", never as "healthy" and never as "broken".
 */
@Service
public class RollbackWatcherService {

    private static final Logger log = LoggerFactory.getLogger(RollbackWatcherService.class);

    private static final String CANDIDATE = "candidate";
    private static final String BASELINE = "baseline";

    private final FeatureFlagService flagService;
    private final MetricWindowService windowService;
    private final AlertService alertService;
    private final RollbackGuardState guardState;
    private final CadenceProperties properties;

    public RollbackWatcherService(FeatureFlagService flagService,
                                  MetricWindowService windowService,
                                  AlertService alertService,
                                  RollbackGuardState guardState,
                                  CadenceProperties properties) {
        this.flagService = flagService;
        this.windowService = windowService;
        this.alertService = alertService;
        this.guardState = guardState;
        this.properties = properties;
    }

    /**
     * Runs on a virtual thread every {@code cadence.rollback.check-interval} (default 30s).
     * One slow flag cannot delay the others: each is evaluated independently and its failures are contained.
     */
    @Scheduled(fixedDelayString = "${cadence.rollback.check-interval:PT30S}")
    public void watch() {
        if (!properties.getRollback().isEnabled()) {
            return;
        }
        List<FeatureFlag> flags = flagService.findActiveRollouts();
        for (FeatureFlag flag : flags) {
            try {
                evaluate(flag);
            } catch (Exception e) {
                // Never let one flag's failure stop the watcher from checking the rest. A thrown
                // exception here would kill the scheduled task's tick entirely.
                log.error("Rollback evaluation failed for flag '{}': {}", flag.getKey(), e.getMessage(), e);
            }
        }
    }

    private void evaluate(FeatureFlag flag) {
        // SHADOW flags are watched for the dashboard's benefit but are never rolled back:
        // no user is receiving the candidate, so there is nothing to withdraw.
        if (flag.getState() != FlagState.ROLLING_OUT) {
            return;
        }
        RollbackTrigger trigger = flag.getRollbackTrigger();
        if (!trigger.autoRollbackEnabled()) {
            return;
        }
        if (guardState.isInCooldown(flag.getId())) {
            log.debug("Flag '{}' is in post-rollback cooldown; skipping", flag.getKey());
            return;
        }
        if (flag.getHealthMetrics().isEmpty()) {
            return;
        }

        List<String> breaches = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        WindowStats candidateStats = null;

        for (HealthMetricSpec spec : flag.getHealthMetrics()) {
            WindowStats stats = windowService.stats(flag.getKey(), CANDIDATE, spec.window());
            if (candidateStats == null) {
                candidateStats = stats;
            }

            int required = Math.max(spec.minSamples(), trigger.minSamples());
            if (stats.sampleCount() < required) {
                continue; // not enough evidence to judge this metric yet
            }

            double observed = stats.valueOf(spec.kind(), spec.name());
            if (Double.isNaN(observed)) {
                continue; // the app never reported this custom metric; no opinion
            }

            if (spec.direction().breaches(observed, spec.threshold())) {
                breaches.add("%s=%.4f (limit %.4f, %s)"
                        .formatted(spec.name(), observed, spec.threshold(), spec.direction()));
                evidence.put(spec.name(), Map.of(
                        "observed", observed,
                        "threshold", spec.threshold(),
                        "direction", spec.direction().name(),
                        "window", spec.window().name(),
                        "samples", stats.sampleCount()));
            }
        }

        if (breaches.isEmpty()) {
            // Healthy tick: the streak resets. This is what makes the counter mean "consecutive".
            guardState.clearBreaches(flag.getId());
            return;
        }

        long count = guardState.recordBreach(flag.getId(), breachCounterTtl(trigger));
        log.warn("Flag '{}' breach {}/{}: {}", flag.getKey(), count, trigger.consecutiveBreaches(), breaches);

        if (count < trigger.consecutiveBreaches()) {
            return;
        }

        fireRollback(flag, breaches, evidence,
                candidateStats == null ? WindowStats.empty(CANDIDATE, com.cadence.core.metrics.WindowType.LAST_100)
                        : candidateStats);
    }

    private void fireRollback(FeatureFlag flag, List<String> breaches,
                              Map<String, Object> evidence, WindowStats candidateStats) {
        int previousPercentage = flag.getRolloutPercentage();
        WindowStats baselineStats = windowService.stats(flag.getKey(), BASELINE,
                com.cadence.core.metrics.WindowType.LAST_1H);

        Map<String, Object> metadata = new HashMap<>(evidence);
        metadata.put("breachingMetrics", breaches);
        metadata.put("candidateErrorRate", candidateStats.errorRate());
        metadata.put("candidateP95Ms", candidateStats.p95LatencyMs());
        metadata.put("baselineErrorRate", baselineStats.errorRate());
        metadata.put("baselineP95Ms", baselineStats.p95LatencyMs());

        String reason = "Automatic rollback: " + String.join("; ", breaches);

        // The cooldown lock is taken before the rollback, not after. If the rollback transaction throws
        // (an optimistic-lock collision with an operator doing the same thing manually), the flag is
        // already being withdrawn by someone; the watcher should still back off.
        guardState.enterCooldown(flag.getId(), Duration.ofMinutes(flag.getRollbackTrigger().cooldownMinutes()));
        guardState.clearBreaches(flag.getId());

        flagService.rollbackAsSystem(flag.getId(), reason, metadata);
        alertService.automaticRollback(flag, previousPercentage, breaches, candidateStats, baselineStats);
    }

    private Duration breachCounterTtl(RollbackTrigger trigger) {
        long tickSeconds = Math.max(1, properties.getRollback().getCheckInterval().toSeconds());
        return Duration.ofSeconds(tickSeconds * (trigger.consecutiveBreaches() + 2L));
    }
}
