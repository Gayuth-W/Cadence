package com.cadence.flagservice.metrics.service;

import com.cadence.core.metrics.WindowType;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import com.cadence.flagservice.metrics.domain.MetricSnapshot;
import com.cadence.flagservice.metrics.model.WindowStats;
import com.cadence.flagservice.metrics.repository.MetricSnapshotRepository;
import com.cadence.flagservice.websocket.RolloutBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Copies the live Redis windows into Postgres every five minutes.
 *
 * <p>Redis is a rolling window: ask it about 3 a.m. tomorrow morning and it has already forgotten.
 * That is fine for the watcher, which only ever cares about "now", and useless for the post-mortem,
 * which only ever cares about "then". These rows are what let the dashboard draw a time series and let
 * an engineer see the exact latency curve that preceded an automatic rollback.
 */
@Service
public class MetricSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(MetricSnapshotService.class);

    private static final String[] VARIANTS = {"baseline", "candidate", "candidate-shadow"};
    /** Snapshots older than this are pruned; the rolling windows they describe are long gone anyway. */
    private static final Duration RETENTION = Duration.ofDays(30);

    private final FeatureFlagService flagService;
    private final MetricWindowService windowService;
    private final MetricSnapshotRepository repository;
    private final RolloutBroadcaster broadcaster;
    private final CadenceProperties properties;

    public MetricSnapshotService(FeatureFlagService flagService,
                                 MetricWindowService windowService,
                                 MetricSnapshotRepository repository,
                                 RolloutBroadcaster broadcaster,
                                 CadenceProperties properties) {
        this.flagService = flagService;
        this.windowService = windowService;
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cadence.metrics.snapshot-interval:PT5M}")
    @Transactional
    public void capture() {
        List<FeatureFlag> flags = flagService.findActiveRollouts();
        for (FeatureFlag flag : flags) {
            try {
                captureFlag(flag);
            } catch (Exception e) {
                log.error("Snapshot failed for flag '{}': {}", flag.getKey(), e.getMessage());
            }
        }
    }

    private void captureFlag(FeatureFlag flag) {
        for (String variant : VARIANTS) {
            WindowStats hour = windowService.stats(flag.getKey(), variant, WindowType.LAST_1H);
            if (hour.isEmpty()) {
                continue; // no traffic on this variant; a row of zeroes would be a lie
            }
            WindowStats recent = windowService.stats(flag.getKey(), variant, WindowType.LAST_100);

            MetricSnapshot snapshot = MetricSnapshot.builder()
                    .flag(flag.getId(), flag.getKey())
                    .variantKey(variant)
                    .windowType(WindowType.LAST_1H)
                    .sampleCount(hour.sampleCount())
                    .errorCount(hour.errorCount())
                    .errorRate(hour.errorRate())
                    .meanLatencyMs(hour.meanLatencyMs())
                    .stdDevLatencyMs(hour.stdDevLatencyMs())
                    .p50LatencyMs(hour.p50LatencyMs())
                    .p95LatencyMs(hour.p95LatencyMs())
                    .p99LatencyMs(hour.p99LatencyMs())
                    .throughputPerMinute(hour.throughputPerMinute())
                    .customMetrics(hour.customMetrics())
                    .rolloutPercentage(flag.getRolloutPercentage())
                    .trend(trendOf(recent, hour))
                    .build();

            repository.save(snapshot);
            broadcaster.metrics(flag.getId(), snapshot);
        }
    }

    /**
     * Trend compares the short window (the most recent 100 events) against the hour behind it. If the
     * recent slice is materially worse than the hour, the candidate is deteriorating <i>right now</i>,
     * even if the hour's aggregate still sits inside its threshold. That is the signal an operator
     * wants ten minutes before the watcher fires, not after.
     *
     * <p>The 10% band keeps ordinary jitter from being reported as a trend.
     */
    static MetricSnapshot.Trend trendOf(WindowStats recent, WindowStats hour) {
        if (recent.isEmpty() || hour.isEmpty()) {
            return MetricSnapshot.Trend.STABLE;
        }
        double recentScore = compositeScore(recent);
        double hourScore = compositeScore(hour);
        if (hourScore == 0) {
            return MetricSnapshot.Trend.STABLE;
        }
        double delta = (recentScore - hourScore) / hourScore;
        if (delta > 0.10) {
            return MetricSnapshot.Trend.DEGRADING;
        }
        if (delta < -0.10) {
            return MetricSnapshot.Trend.IMPROVING;
        }
        return MetricSnapshot.Trend.STABLE;
    }

    /**
     * A single "badness" number combining tail latency and error rate. Errors are weighted heavily
     * (an error rate of 1% contributes as much as 1000ms of P95) because a failed request is worse
     * for a user than a slow one.
     */
    private static double compositeScore(WindowStats stats) {
        return stats.p95LatencyMs() + stats.errorRate() * 100_000;
    }

    /** Housekeeping. Runs daily; keeps the snapshot table from growing without bound. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void prune() {
        Instant cutoff = Instant.now().minus(RETENTION);
        repository.deleteByCapturedAtBefore(cutoff);
        log.info("Pruned metric snapshots captured before {}", cutoff);
    }
}
