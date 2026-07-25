package com.cadence.flagservice.metrics.domain;

import com.cadence.core.metrics.WindowType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A durable, point-in-time copy of one variant's window statistics, written every five minutes.
 *
 * <p>Redis holds the live rolling windows and forgets them; Postgres holds the history. This is what
 * backs the time-series chart, the "metric data that caused the rollback" panel, and the CSV export.
 * Without it, a post-mortem the morning after a 3 a.m. auto-rollback would have nothing to look at.
 */
@Entity
@Table(name = "metric_snapshot", indexes = {
        @Index(name = "idx_snapshot_flag_time", columnList = "flag_id, captured_at"),
        @Index(name = "idx_snapshot_flag_variant", columnList = "flag_id, variant_key")
})
public class MetricSnapshot {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "flag_id", nullable = false)
    private UUID flagId;

    @Column(name = "flag_key", nullable = false, length = 200)
    private String flagKey;

    /** {@code baseline}, {@code candidate}, or {@code candidate-shadow}. */
    @Column(name = "variant_key", nullable = false, length = 40)
    private String variantKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "window_type", nullable = false, length = 20)
    private WindowType windowType;

    @Column(name = "sample_count", nullable = false)
    private long sampleCount;

    @Column(name = "error_count", nullable = false)
    private long errorCount;

    @Column(name = "error_rate", nullable = false)
    private double errorRate;

    @Column(name = "mean_latency_ms", nullable = false)
    private double meanLatencyMs;

    @Column(name = "stddev_latency_ms", nullable = false)
    private double stdDevLatencyMs;

    @Column(name = "p50_latency_ms", nullable = false)
    private double p50LatencyMs;

    @Column(name = "p95_latency_ms", nullable = false)
    private double p95LatencyMs;

    @Column(name = "p99_latency_ms", nullable = false)
    private double p99LatencyMs;

    @Column(name = "throughput_per_minute", nullable = false)
    private double throughputPerMinute;

    /** IMPROVING / STABLE / DEGRADING, derived by comparing the short window against the long one. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Trend trend = Trend.STABLE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_metrics", columnDefinition = "jsonb")
    private Map<String, Double> customMetrics = Map.of();

    @Column(name = "rollout_percentage", nullable = false)
    private int rolloutPercentage;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt = Instant.now();

    public enum Trend {
        IMPROVING, STABLE, DEGRADING
    }

    protected MetricSnapshot() {
    }

    private MetricSnapshot(Builder b) {
        this.flagId = b.flagId;
        this.flagKey = b.flagKey;
        this.variantKey = b.variantKey;
        this.windowType = b.windowType;
        this.sampleCount = b.sampleCount;
        this.errorCount = b.errorCount;
        this.errorRate = b.errorRate;
        this.meanLatencyMs = b.meanLatencyMs;
        this.stdDevLatencyMs = b.stdDevLatencyMs;
        this.p50LatencyMs = b.p50LatencyMs;
        this.p95LatencyMs = b.p95LatencyMs;
        this.p99LatencyMs = b.p99LatencyMs;
        this.throughputPerMinute = b.throughputPerMinute;
        this.trend = b.trend;
        this.customMetrics = b.customMetrics;
        this.rolloutPercentage = b.rolloutPercentage;
        this.capturedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() { return id; }
    public UUID getFlagId() { return flagId; }
    public String getFlagKey() { return flagKey; }
    public String getVariantKey() { return variantKey; }
    public WindowType getWindowType() { return windowType; }
    public long getSampleCount() { return sampleCount; }
    public long getErrorCount() { return errorCount; }
    public double getErrorRate() { return errorRate; }
    public double getMeanLatencyMs() { return meanLatencyMs; }
    public double getStdDevLatencyMs() { return stdDevLatencyMs; }
    public double getP50LatencyMs() { return p50LatencyMs; }
    public double getP95LatencyMs() { return p95LatencyMs; }
    public double getP99LatencyMs() { return p99LatencyMs; }
    public double getThroughputPerMinute() { return throughputPerMinute; }
    public Trend getTrend() { return trend; }
    public Map<String, Double> getCustomMetrics() { return customMetrics; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public Instant getCapturedAt() { return capturedAt; }

    public static final class Builder {
        private UUID flagId;
        private String flagKey;
        private String variantKey;
        private WindowType windowType;
        private long sampleCount;
        private long errorCount;
        private double errorRate;
        private double meanLatencyMs;
        private double stdDevLatencyMs;
        private double p50LatencyMs;
        private double p95LatencyMs;
        private double p99LatencyMs;
        private double throughputPerMinute;
        private Trend trend = Trend.STABLE;
        private Map<String, Double> customMetrics = Map.of();
        private int rolloutPercentage;

        public Builder flag(UUID flagId, String flagKey) { this.flagId = flagId; this.flagKey = flagKey; return this; }
        public Builder variantKey(String v) { this.variantKey = v; return this; }
        public Builder windowType(WindowType w) { this.windowType = w; return this; }
        public Builder sampleCount(long v) { this.sampleCount = v; return this; }
        public Builder errorCount(long v) { this.errorCount = v; return this; }
        public Builder errorRate(double v) { this.errorRate = v; return this; }
        public Builder meanLatencyMs(double v) { this.meanLatencyMs = v; return this; }
        public Builder stdDevLatencyMs(double v) { this.stdDevLatencyMs = v; return this; }
        public Builder p50LatencyMs(double v) { this.p50LatencyMs = v; return this; }
        public Builder p95LatencyMs(double v) { this.p95LatencyMs = v; return this; }
        public Builder p99LatencyMs(double v) { this.p99LatencyMs = v; return this; }
        public Builder throughputPerMinute(double v) { this.throughputPerMinute = v; return this; }
        public Builder trend(Trend t) { this.trend = t; return this; }
        public Builder customMetrics(Map<String, Double> m) { this.customMetrics = m == null ? Map.of() : m; return this; }
        public Builder rolloutPercentage(int p) { this.rolloutPercentage = p; return this; }

        public MetricSnapshot build() {
            return new MetricSnapshot(this);
        }
    }
}
