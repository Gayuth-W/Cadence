package com.cadence.flagservice.metrics.service;

import com.cadence.flagservice.config.CadenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * The rollback watcher's two pieces of per-flag memory: how many consecutive evaluations have
 * breached, and whether the flag is inside a post-rollback cooldown.
 *
 * <p>Extracted from {@code RollbackWatcherService} rather than left as private helpers, because two
 * callers need it and they cannot depend on each other. The watcher <i>writes</i> this state on every
 * tick; {@code FeatureFlagService#reset} must <i>clear</i> it when an operator declares a candidate
 * fixed. Injecting the watcher into the flag service would close a bean cycle
 * ({@code watcher -> flagService -> watcher}) and Spring would refuse to start.
 *
 * <p>Both keys live in Redis rather than Postgres on purpose: they are cheap, they are worthless the
 * moment the process forgets them, and losing them costs at most one extra watcher tick of patience.
 */
@Component
public class RollbackGuardState {

    private static final Logger log = LoggerFactory.getLogger(RollbackGuardState.class);

    private final StringRedisTemplate redis;
    private final CadenceProperties properties;

    public RollbackGuardState(StringRedisTemplate redis, CadenceProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** @return the new consecutive-breach count for this flag. */
    public long recordBreach(UUID flagId, Duration ttl) {
        Long count = redis.opsForValue().increment(breachKey(flagId));
        // TTL well beyond the time needed to accumulate the required streak, so a flag that breaches
        // once and is then forgotten does not leave a counter in Redis forever.
        redis.expire(breachKey(flagId), ttl);
        return count == null ? 1L : count;
    }

    /** A healthy evaluation. This is what makes the counter mean "consecutive" rather than "total". */
    public void clearBreaches(UUID flagId) {
        redis.delete(breachKey(flagId));
    }

    public boolean isInCooldown(UUID flagId) {
        return Boolean.TRUE.equals(redis.hasKey(cooldownKey(flagId)));
    }

    /** Anti-flap lock: after a rollback the watcher leaves this flag alone for {@code duration}. */
    public void enterCooldown(UUID flagId, Duration duration) {
        redis.opsForValue().set(cooldownKey(flagId), "1", duration);
    }

    /**
     * Forget everything about this flag: the breach streak and the cooldown lock.
     *
     * <p>Called when an ADMIN resets a rolled-back flag. Leaving the cooldown key in place would mean
     * the watcher silently skips the flag for the remainder of the cooldown — so the freshly fixed
     * candidate would roll back out to real users with <i>no</i> automated protection at all, which is
     * the exact opposite of what a reset should mean.
     */
    public void clear(UUID flagId) {
        try {
            redis.delete(breachKey(flagId));
            redis.delete(cooldownKey(flagId));
        } catch (Exception e) {
            // A reset must not fail because Redis is unreachable. The worst case is that the watcher
            // stays in cooldown a little longer, which is the safe direction: it withholds automation,
            // it does not withhold protection from a rollback an operator can still force by hand.
            log.warn("Could not clear rollback guard state for flag {}: {}", flagId, e.getMessage());
        }
    }

    String breachKey(UUID flagId) {
        return "cadence:%s:breach:%s".formatted(properties.getEnvironment(), flagId);
    }

    String cooldownKey(UUID flagId) {
        return "cadence:%s:cooldown:%s".formatted(properties.getEnvironment(), flagId);
    }
}
