package com.cadence.core.model;

/** The dimension a {@link TargetingRule} matches on. */
public enum RuleType {

    /** Explicit user IDs that always receive the rule's variant. Highest signal, evaluate first. */
    ALLOWLIST,

    /** Explicit user IDs that are pinned to the rule's variant regardless of rollout percentage. */
    BLOCKLIST,

    /** Matches when {@code UserContext.segments} intersects the rule values (e.g. "internal", "beta"). */
    SEGMENT,

    /** Matches on the ISO 3166-1 alpha-2 country code of the user context. */
    COUNTRY,

    /** Matches on an arbitrary string attribute of the user context (e.g. tenant, plan, inputType). */
    ATTRIBUTE
}
