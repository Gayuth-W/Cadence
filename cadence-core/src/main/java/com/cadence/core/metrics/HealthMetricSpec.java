package com.cadence.core.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One release-health condition the candidate must respect, stored as an element of the flag's
 * {@code health_metrics} JSON array.
 *
 * <pre>
 *   {"name":"error_rate","kind":"ERROR_RATE","direction":"LOWER_IS_BETTER","threshold":0.02,"window":"LAST_100","minSamples":20}
 *   {"name":"p95","kind":"LATENCY_P95","direction":"LOWER_IS_BETTER","threshold":400,"window":"LAST_100","minSamples":20}
 *   {"name":"checkout_completion","kind":"CUSTOM","direction":"HIGHER_IS_BETTER","threshold":0.6,"window":"LAST_1H","minSamples":50}
 * </pre>
 *
 * @param name       identifier; for {@link MetricKind#CUSTOM} this must match the key the SDK reports
 * @param kind       which built-in signal, or CUSTOM
 * @param direction  whether higher or lower is better
 * @param threshold  the limit the candidate must respect
 * @param window     which rolling window to read
 * @param minSamples do not judge the candidate on fewer than this many events; prevents a single
 *                   unlucky error at 1% traffic from tripping a rollback
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthMetricSpec(
        String name,
        MetricKind kind,
        Direction direction,
        double threshold,
        WindowType window,
        int minSamples
) {
    public HealthMetricSpec {
        window = window == null ? WindowType.LAST_100 : window;
        direction = direction == null ? Direction.LOWER_IS_BETTER : direction;
        minSamples = minSamples <= 0 ? 20 : minSamples;
    }

    public static HealthMetricSpec errorRate(double threshold) {
        return new HealthMetricSpec("error_rate", MetricKind.ERROR_RATE, Direction.LOWER_IS_BETTER,
                threshold, WindowType.LAST_100, 20);
    }

    public static HealthMetricSpec p95(double thresholdMillis) {
        return new HealthMetricSpec("p95_latency", MetricKind.LATENCY_P95, Direction.LOWER_IS_BETTER,
                thresholdMillis, WindowType.LAST_100, 20);
    }
}
