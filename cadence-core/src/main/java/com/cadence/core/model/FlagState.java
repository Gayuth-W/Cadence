package com.cadence.core.model;

/**
 * Lifecycle of a progressive-delivery flag.
 *
 * <p>The state machine is deliberately small; every transition is audited.
 * <pre>
 *   OFF ──────► SHADOW ──────► ROLLING_OUT ──────► FULLY_ON
 *                                 │  ▲
 *                                 │  └── resume
 *                                 ▼
 *                              PAUSED
 *                                 │
 *                                 ▼
 *                           ROLLED_BACK
 * </pre>
 */
public enum FlagState {

    /** Everyone gets baseline. The candidate path is never executed. */
    OFF,

    /**
     * Dark launch. Every user-facing response is baseline, but the candidate is executed
     * in parallel on a virtual thread and its latency/errors/output-diff are recorded.
     * Zero user impact.
     */
    SHADOW,

    /** A percentage of traffic receives the candidate. Health is actively watched. */
    ROLLING_OUT,

    /** Rollout is frozen at the current percentage. Traffic split is unchanged. */
    PAUSED,

    /** 100% of traffic receives the candidate. */
    FULLY_ON,

    /**
     * The candidate was withdrawn (manually by an ADMIN, or automatically by the
     * rollback watcher). All traffic is served baseline and the flag will not advance
     * until an ADMIN explicitly restarts it.
     */
    ROLLED_BACK;

    /** True when the candidate may be served to at least some real users. */
    public boolean servesCandidate() {
        return this == ROLLING_OUT || this == PAUSED || this == FULLY_ON;
    }

    /** True when the rollout automation (scheduler, canary gate, watcher) should act on this flag. */
    public boolean isActiveRollout() {
        return this == ROLLING_OUT || this == SHADOW;
    }
}
