package com.cadence.sdk.internal;

import com.cadence.core.model.FlagDefinition;
import com.cadence.sdk.config.CadenceSdkProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The SDK's local view of every flag, refreshed in the background.
 * Evaluation must be a sub-microsecond in-memory lookup, not a network call: if
 * {@code flagClient.evaluate()} did HTTP, every flag check would add a round
 * trip to a user request
 * and the flag platform would become the thing that needs a flag to turn it
 * off.
 *
 * Failure behaviour is the whole point of this class. The refresh is wrapped in
 * a Resilience4j
 * circuit breaker; when the control plane is unreachable the breaker opens, the
 * refresh stops
 * hammering it, and the cache keeps serving the last successfully fetched
 * snapshot. If the SDK
 * has never fetched anything (control plane down at boot), the cache is empty,
 * {@code find()} returns
 * empty, and {@link com.cadence.sdk.FlagClient} degrades to baseline - the safe
 * direction.
 */
public class FlagConfigCache {

    private static final Logger log = LoggerFactory.getLogger(FlagConfigCache.class);

    private final FlagApiClient apiClient;
    private final CircuitBreaker circuitBreaker;
    private final Cache<String, FlagDefinition> cache;
    private final AtomicBoolean everLoaded = new AtomicBoolean(false);

    public FlagConfigCache(FlagApiClient apiClient, CircuitBreaker circuitBreaker, CadenceSdkProperties properties) {
        this.apiClient = apiClient;
        this.circuitBreaker = circuitBreaker;
        // No expireAfterWrite: entries must survive a control-plane outage
        // indefinitely. Staleness
        // is bounded by the refresh loop when the plane is up, and unbounded
        // (deliberately) when it is down.
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .recordStats()
                .build();
    }

    public Optional<FlagDefinition> find(String flagKey) {
        return Optional.ofNullable(cache.getIfPresent(flagKey));
    }

    /**
     * True once at least one successful fetch has landed. Exposed for health
     * indicators and tests.
     */
    public boolean isPrimed() {
        return everLoaded.get();
    }

    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Pull the latest config. Never throws: a failed refresh is a logged warning,
     * not an outage.
     *
     * @return true when the snapshot was replaced
     */
    public boolean refresh() {
        Supplier<List<FlagDefinition>> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                apiClient::fetchAllFlags);
        try {
            List<FlagDefinition> flags = guarded.get();
            // Replace wholesale so a flag deleted upstream disappears locally instead of
            // lingering forever.
            cache.invalidateAll();
            flags.forEach(flag -> cache.put(flag.key(), flag));
            if (everLoaded.compareAndSet(false, true)) {
                log.info("Cadence SDK primed with {} flag(s)", flags.size());
            } else {
                log.debug("Cadence SDK refreshed {} flag(s)", flags.size());
            }
            return true;
        } catch (Exception e) {
            log.warn("Cadence config refresh failed (breaker={}); serving last known-good snapshot of {} flag(s): {}",
                    circuitBreaker.getState(), cache.estimatedSize(), e.getMessage());
            return false;
        }
    }
}
