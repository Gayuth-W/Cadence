package com.cadence.core.metrics;

/**
 * The release-health signals the platform can gate a rollout on.
 *
 * <p>Latency percentiles are separate kinds rather than a single "latency" kind with a parameter,
 * because a rollout usually wants different thresholds for the median and the tail: a candidate that
 * keeps P50 flat while doubling P99 is exactly the regression a naive mean would hide.
 */
public enum MetricKind {

    /** Share of outcome events with {@code success = false}, in [0, 1]. */
    ERROR_RATE,

    LATENCY_P50,
    LATENCY_P95,
    LATENCY_P99,

    /** Mean latency in milliseconds. */
    LATENCY_MEAN,

    /** Events per minute observed in the window. */
    THROUGHPUT,

    /** Any app-reported numeric dimension: conversion rate, checkout completion, click-through. */
    CUSTOM;

    public boolean isLatency() {
        return this == LATENCY_P50 || this == LATENCY_P95 || this == LATENCY_P99 || this == LATENCY_MEAN;
    }
}
