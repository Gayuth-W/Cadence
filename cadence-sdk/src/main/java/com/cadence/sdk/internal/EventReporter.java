package com.cadence.sdk.internal;

import com.cadence.core.event.EventBatch;
import com.cadence.core.event.OutcomeEvent;
import com.cadence.sdk.config.CadenceSdkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers outcome events and ships them to the control plane in batches, entirely off the caller's
 * request path.
 *
 * <p>Three properties this class must never violate:
 * <ul>
 *   <li><b>Zero added latency.</b> {@link #report} does a non-blocking {@code offer()} and returns.
 *       It never does I/O, never blocks, never throws.</li>
 *   <li><b>Bounded memory.</b> When the control plane is down the queue fills; at that point the
 *       oldest events are evicted. Dropping telemetry is strictly better than OOM-killing a caller
 *       that only wanted to check a feature flag.</li>
 *   <li><b>No silent data loss on shutdown.</b> {@link #shutdown} drains one final batch.</li>
 * </ul>
 *
 * <p>Flushing runs on a virtual thread: the work is a single blocking HTTP POST, i.e. exactly the
 * short, I/O-bound task Loom exists for. Pinning a platform thread to wait on it would be waste.
 */
public class EventReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventReporter.class);

    private final FlagApiClient apiClient;
    private final CadenceSdkProperties properties;
    private final BlockingQueue<OutcomeEvent> queue;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService flushExecutor;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private volatile boolean running = true;

    public EventReporter(FlagApiClient apiClient, CadenceSdkProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.queue = new LinkedBlockingQueue<>(properties.getEvents().getMaxBufferSize());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cadence-event-flush-scheduler").factory());
        this.flushExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cadence-event-flush-", 0).factory());
    }

    public void start() {
        if (!properties.getEvents().isEnabled()) {
            log.info("Cadence event reporting disabled; release-health automation will receive no data");
            return;
        }
        long millis = properties.getEvents().getFlushInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Cadence event reporter started (batchSize={}, flushInterval={}ms)",
                properties.getEvents().getBatchSize(), millis);
    }

    /** Hot path. Must stay allocation-light and lock-free enough to be invisible to the caller. */
    public void report(OutcomeEvent event) {
        if (!running || !properties.getEvents().isEnabled()) {
            return;
        }
        if (!queue.offer(event)) {
            // Buffer full: evict the oldest event to make room for the newest. Recent data is what
            // the rollback watcher needs; a 10-minute-old event has no value to it.
            queue.poll();
            queue.offer(event);
            long total = dropped.incrementAndGet();
            if (total % 1000 == 1) {
                log.warn("Cadence event buffer saturated; dropped {} event(s) so far", total);
            }
        }
        if (queue.size() >= properties.getEvents().getBatchSize()) {
            flushExecutor.execute(this::flushQuietly);
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("Cadence event flush failed, {} event(s) still buffered: {}", queue.size(), e.getMessage());
        }
    }

    private void flush() {
        List<OutcomeEvent> batch = new ArrayList<>(properties.getEvents().getBatchSize());
        queue.drainTo(batch, properties.getEvents().getBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        apiClient.publishEvents(new EventBatch(batch));
        published.addAndGet(batch.size());
        log.debug("Cadence published {} event(s)", batch.size());
    }

    public long publishedCount() {
        return published.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int bufferedCount() {
        return queue.size();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            flush(); // best-effort final drain so the last events of a request survive a rolling restart
        } catch (Exception e) {
            log.debug("Final Cadence flush failed on shutdown: {}", e.getMessage());
        }
        flushExecutor.shutdown();
    }
}
