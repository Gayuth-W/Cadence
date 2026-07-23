package com.cadence.flagservice.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the name to stamp on an audit record.
 *
 * <p>This tiny class is what turns the audit table from decoration into an accountability trail. Every
 * mutation asks it "who is doing this", and it answers either with the authenticated username from the
 * JWT, or with {@link #SYSTEM} when the actor is the platform's own automation (the rollback watcher,
 * the stage scheduler) running outside any HTTP request.
 */
@Component
public class CurrentActor {

    /** The actor recorded when the platform acts on its own: auto-rollback, automated stage advance. */
    public static final String SYSTEM = "SYSTEM";

    /**
     * @return the authenticated username, or {@link #SYSTEM} when running on a scheduler thread with
     *         no security context.
     */
    public String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return SYSTEM;
        }
        return auth.getName();
    }

    /** True when the current actor is a real, authenticated human rather than the platform itself. */
    public boolean isHuman() {
        return !SYSTEM.equals(name());
    }
}
