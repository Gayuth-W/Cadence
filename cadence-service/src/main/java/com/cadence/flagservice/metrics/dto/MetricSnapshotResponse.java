package com.cadence.flagservice.metrics.dto;

import com.cadence.core.metrics.WindowType;
import com.cadence.flagservice.metrics.domain.MetricSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MetricSnapshotResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        String variantKey,
        WindowType windowType,
        long sampleCount,
        long errorCount,
        double errorRate,
        double meanLatencyMs,
        double stdDevLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double throughputPerMinute,
        MetricSnapshot.Trend trend,
        Map<String, Double> customMetrics,
        int rolloutPercentage,
        Instant capturedAt
) {
    public static MetricSnapshotResponse from(MetricSnapshot s) {
        return new MetricSnapshotResponse(s.getId(), s.getFlagId(), s.getFlagKey(), s.getVariantKey(),
                s.getWindowType(), s.getSampleCount(), s.getErrorCount(), s.getErrorRate(),
                s.getMeanLatencyMs(), s.getStdDevLatencyMs(), s.getP50LatencyMs(), s.getP95LatencyMs(),
                s.getP99LatencyMs(), s.getThroughputPerMinute(), s.getTrend(), s.getCustomMetrics(),
                s.getRolloutPercentage(), s.getCapturedAt());
    }
}
