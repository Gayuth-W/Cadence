package com.cadence.sdk.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Runs the candidate implementation alongside the baseline during dark launch.
 *
 * <p>Virtual threads make this affordable: a shadow execution is a whole extra call to the candidate
 * path (a DB query, an HTTP hop, a model inference) that produces nothing the user sees. On platform
 * threads, shadowing a busy endpoint would double the pool pressure and eventually starve the real
 * traffic. On virtual threads each shadow is a cheap, parked continuation.
 *
 * <p>A semaphore still bounds concurrency. Virtual threads are cheap, but the <i>downstream</i>
 * resources the candidate touches — a connection pool, a rate-limited API — are not. Shadow traffic
 * must never be the reason production baseline traffic queues for a connection, so when the limit is
 * reached the shadow run is skipped rather than queued.
 */
public class ShadowExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShadowExecutor.class);

    private final ExecutorService executor;
    private final Semaphore concurrencyLimit;

    public ShadowExecutor(int maxConcurrentShadows) {
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cadence-shadow-", 0).factory());
        this.concurrencyLimit = new Semaphore(maxConcurrentShadows);
    }

    /**
     * Execute {@code candidate} off the request path and hand the outcome to {@code onComplete}.
     * Never propagates an exception to the caller: a shadow failure is the finding, not an incident.
     */
    public void runShadow(Supplier<Object> candidate, ShadowCallback onComplete) {
        Objects.requireNonNull(candidate);
        if (!concurrencyLimit.tryAcquire()) {
            log.debug("Shadow execution skipped: concurrency limit reached");
            return;
        }
        executor.execute(() -> {
            long start = System.nanoTime();
            try {
                Object result = candidate.get();
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                onComplete.accept(result, latencyMs, null);
            } catch (Throwable t) {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                // This is precisely what shadow mode exists to surface: the candidate blew up,
                // and not one user noticed.
                log.debug("Shadow candidate threw {}", t.getClass().getSimpleName());
                onComplete.accept(null, latencyMs, t);
            } finally {
                concurrencyLimit.release();
            }
        });
    }

    @FunctionalInterface
    public interface ShadowCallback {
        void accept(Object result, long latencyMs, Throwable error);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
