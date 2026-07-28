package com.cadence.core.event;

/** Whether an outcome event came from real user-facing traffic or from a dark-launch execution. */
public enum EventType {

    /** The variant's result was actually returned to a user. */
    LIVE,

    /** The candidate ran in the background under SHADOW state; its result was discarded. */
    SHADOW
}
