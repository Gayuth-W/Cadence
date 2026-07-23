package com.cadence.flagservice.security.domain;

/**
 * What a service key is allowed to do. Both scopes together are still strictly less power than a
 * VIEWER token: a key can read flag config and write events, and can never read the audit log or
 * see another environment's flags.
 */
public enum ApiKeyScope {
    FLAGS_READ,
    EVENTS_WRITE;

    public String authority() {
        return "SCOPE_" + name();
    }
}
