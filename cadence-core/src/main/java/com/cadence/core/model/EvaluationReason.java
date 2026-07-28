package com.cadence.core.model;

/**
 * Why a particular variant was served. Surfaced to callers and stored on outcome events so a
 * degraded rollout can be explained ("were these users bucketed, or allowlisted?").
 */
public enum EvaluationReason {

    /** No flag with this key exists in the environment. */
    FLAG_NOT_FOUND,

    /** Flag state is OFF. */
    FLAG_OFF,

    /** Flag state is ROLLED_BACK. */
    ROLLED_BACK,

    /** Flag state is FULLY_ON: everybody gets the candidate. */
    FULLY_ON,

    /** Flag state is SHADOW: the user sees baseline, the candidate runs in the background. */
    SHADOW_BASELINE,

    /** A targeting rule matched and pinned the variant, bypassing percentage bucketing. */
    TARGETING_MATCH,

    /** The user's stable bucket fell inside the rollout percentage. */
    PERCENTAGE_ROLLOUT,

    /** The user's stable bucket fell outside the rollout percentage. */
    PERCENTAGE_EXCLUDED,

    /** The flag service was unreachable and the circuit breaker served the safe default. */
    ERROR_FALLBACK
}
