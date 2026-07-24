package com.cadence.flagservice.audit.domain;

/**
 * Every mutation the platform can perform on a flag, and therefore every line
 * the audit log can contain.
 */
public enum AuditAction {
    FLAG_CREATED,
    FLAG_UPDATED,
    FLAG_DELETED,

    ROLLOUT_PERCENTAGE_CHANGED,
    ROLLOUT_PAUSED,
    ROLLOUT_RESUMED,
    ROLLOUT_COMPLETED,

    /** An ADMIN pressed the button. */
    ROLLBACK_FORCED,

    /**
     * The watcher decided on its own. Actor is SYSTEM; the metric evidence is in
     * the metadata.
     */
    ROLLBACK_AUTOMATIC,

    SHADOW_ENABLED,

    SCHEDULE_CREATED,
    SCHEDULE_STARTED,
    STAGE_ADVANCED,

    /** A stage transition was blocked by the Mann-Whitney canary gate. */
    STAGE_BLOCKED_BY_CANARY
}
