package com.cadence.flagservice.metrics.model;

import com.cadence.core.metrics.MetricKind;
import com.cadence.core.metrics.WindowType;

import java.util.Map;

/**
 * Everything the platform knows about one {@code flag:variant} pair over one rolling window.
 *
 * @param sampleCount        events in the window; the gate on whether any of this is trustworthy
 * @param errorRate          failures / samples, in [0, 1]
 * @param meanLatencyMs      arithmetic mean
 * @param stdDevLatencyMs    population standard deviation
 * @param throughputPerMinute events per minute observed across the window
 * @param customMetrics      mean of each app-reported dimension
 */
public record WindowStats(
        String variantKey,
        WindowType window,
        long sampleCount,
        long errorCount,
        double errorRate,
        double meanLatencyMs,
        double stdDevLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double throughputPerMinute,
        Map<String, Double> customMetrics
) {
    public static WindowStats empty(String variantKey, WindowType window) {
        return new WindowStats(variantKey, window, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    public boolean isEmpty() {
        return sampleCount == 0;
    }

    /**
     * Pull the value a {@link com.cadence.core.metrics.HealthMetricSpec} refers to.
     * Returns {@code Double.NaN} for an unreported custom metric, which callers must treat as
     * "no opinion" rather than as zero — a missing conversion-rate reading is not a conversion rate of 0.
     */
    public double valueOf(MetricKind kind, String customName) {
        return switch (kind) {
            case ERROR_RATE -> errorRate;
            case LATENCY_P50 -> p50LatencyMs;
            case LATENCY_P95 -> p95LatencyMs;
            case LATENCY_P99 -> p99LatencyMs;
            case LATENCY_MEAN -> meanLatencyMs;
            case THROUGHPUT -> throughputPerMinute;
            case CUSTOM -> customMetrics.getOrDefault(customName, Double.NaN);
        };
    }
}
