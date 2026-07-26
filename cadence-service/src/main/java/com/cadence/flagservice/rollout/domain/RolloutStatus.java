package com.cadence.flagservice.rollout.domain;

/** Lifecycle of a schedule, which is distinct from the lifecycle of the flag it drives. */
public enum RolloutStatus {

    /** Created but not started; no traffic is moving. */
    PENDING,

    /** Advancing through stages on the clock, gated by canary analysis at every transition. */
    RUNNING,

    /** Held: either an operator paused it, or the canary gate blocked a transition. */
    PAUSED,

    /** Every stage completed; the flag reached 100%. */
    COMPLETED,

    /** The flag was rolled back underneath the schedule. The schedule stops and does not resume. */
    ABORTED
}
