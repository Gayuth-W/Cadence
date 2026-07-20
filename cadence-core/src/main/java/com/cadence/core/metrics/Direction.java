package com.cadence.core.metrics;

/**
 * Which way is "good" for a metric. Without this, the platform cannot tell a
 * conversion-rate drop
 * (bad) from a latency drop (good), and would either roll back on improvements
 * or sit through regressions.
 */
public enum Direction {

    /**
     * Conversion rate, click-through, throughput. A value below threshold is a
     * breach.
     */
    HIGHER_IS_BETTER,

    /** Error rate, latency. A value above threshold is a breach. */
    LOWER_IS_BETTER;

    /**
     * @return true when {@code observed} violates {@code threshold} in this
     *         direction.
     */
    public boolean breaches(double observed, double threshold) {
        return this == LOWER_IS_BETTER ? observed > threshold : observed < threshold;
    }
}
