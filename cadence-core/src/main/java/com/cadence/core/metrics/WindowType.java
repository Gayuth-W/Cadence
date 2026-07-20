package com.cadence.core.metrics;

import java.time.Duration;

/**
 * The rolling windows maintained in Redis for every {@code flag:variant} pair.
 *
 * <p>Three windows, three jobs: {@link #LAST_100} reacts fast enough to catch a catastrophic
 * candidate within seconds, {@link #LAST_1H} carries enough samples for the Mann-Whitney canary
 * gate to have real statistical power, and {@link #LAST_24H} backs the historical charts.
 */
public enum WindowType {

    /** Count-bounded: the most recent 100 events. Drives the 30-second rollback watcher. */
    LAST_100(100, null),

    /** Time-bounded: one hour. Drives canary analysis at stage transitions. */
    LAST_1H(0, Duration.ofHours(1)),

    /** Time-bounded: one day. Drives trend detection and the analytics view. */
    LAST_24H(0, Duration.ofHours(24));

    private final int maxEvents;
    private final Duration duration;

    WindowType(int maxEvents, Duration duration) {
        this.maxEvents = maxEvents;
        this.duration = duration;
    }

    public boolean isCountBounded() {
        return duration == null;
    }

    public int maxEvents() {
        return maxEvents;
    }

    public Duration duration() {
        return duration;
    }

    /** Lower-case token used inside Redis keys. */
    public String token() {
        return name().toLowerCase();
    }
}
