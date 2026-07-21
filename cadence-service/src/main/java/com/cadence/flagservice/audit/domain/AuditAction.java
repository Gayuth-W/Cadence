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
    ROLLOUT_COMPLETED
}
