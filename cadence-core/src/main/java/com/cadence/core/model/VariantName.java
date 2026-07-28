package com.cadence.core.model;

/**
 * The two sides of every progressive delivery: the known-good current version and the
 * new version under evaluation. Canary analysis is always {@code CANDIDATE} vs {@code BASELINE}.
 */
public enum VariantName {

    /** The current stable version. Served when the flag is off, paused below the bucket, or rolled back. */
    BASELINE,

    /** The new version being rolled out. */
    CANDIDATE;

    /**
     * Redis window namespace for this variant. Shadow executions of the candidate are kept in a
     * separate namespace so that dark-launch traffic never pollutes live release-health windows.
     */
    public String windowKey(boolean shadow) {
        String base = name().toLowerCase();
        return shadow ? base + "-shadow" : base;
    }
}
