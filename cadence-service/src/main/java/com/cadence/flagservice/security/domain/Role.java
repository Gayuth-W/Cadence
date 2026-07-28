package com.cadence.flagservice.security.domain;

/**
 * The three roles map exactly onto what the domain permits, not onto a generic user/admin split.
 *
 * <ul>
 *   <li>{@link #VIEWER} — read dashboards, live metrics, snapshots, audit log.</li>
 *   <li>{@link #OPERATOR} — runs rollouts: advance, pause, resume.</li>
 *   <li>{@link #ADMIN} — create/delete flags, force rollback, edit schedules and thresholds, manage users.</li>
 * </ul>
 *
 * <p>Forced rollback is ADMIN-only on purpose. It is the one action that unilaterally overrides the
 * automation and every other operator, and "who forced the 22:14 rollback" should have a short list
 * of possible answers.
 */
public enum Role {
    VIEWER,
    OPERATOR,
    ADMIN;

    /** Spring Security expects the {@code ROLE_} prefix for {@code hasRole()} to match. */
    public String authority() {
        return "ROLE_" + name();
    }
}
