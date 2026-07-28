package com.cadence.flagservice.metrics.service;

import com.cadence.core.event.EventBatch;
import com.cadence.core.event.OutcomeEvent;
import com.cadence.flagservice.config.AsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The high-volume, I/O-bound end of the platform: turning a batch of outcome events into rolling
 * window updates.
 *
 * <h2>Why virtual threads here specifically</h2>
 * Each event fans out into roughly a dozen Redis writes across three windows. The work is essentially
 * all waiting. On a fixed platform-thread pool, a Redis hiccup during a traffic spike backs the queue
 * up, the pool saturates, and {@code POST /sdk/v1/events} starts timing out — at which point the
 * platform is blind exactly when a rollout is going wrong. Virtual threads let ingestion hold thousands
 * of concurrent in-flight fan-outs for the cost of their continuations, with the
 * {@code SimpleAsyncTaskExecutor} concurrency limit providing back-pressure against Redis rather than
 * against the caller.
 *
 * <p>{@code @Async} means {@code POST /sdk/v1/events} returns 202 as soon as the payload is parsed;
 * the SDK is fire-and-forget and never waits for this.
 */
@Service
public class MetricIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MetricIngestionService.class);
    /** Events further in the future than this are clock skew, not data. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final MetricWindowService windowService;
    private final Counter ingestedCounter;
    private final Counter rejectedCounter;
    private final Timer ingestionTimer;

    public MetricIngestionService(MetricWindowService windowService, MeterRegistry meterRegistry) {
        this.windowService = windowService;
        this.ingestedCounter = Counter.builder("cadence.events.ingested")
                .description("Outcome events written into rolling windows")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("cadence.events.rejected")
                .description("Outcome events discarded as malformed or implausibly timestamped")
                .register(meterRegistry);
        this.ingestionTimer = Timer.builder("cadence.events.ingestion")
                .description("Wall time to fan a batch into Redis")
                .register(meterRegistry);
    }

    @Async(AsyncConfig.INGESTION_EXECUTOR)
    public void ingestAsync(EventBatch batch) {
        ingest(batch);
    }

    /** Synchronous entry point, used by tests and by the demo seeder. */
    public void ingest(EventBatch batch) {
        Timer.Sample sample = Timer.start();
        try {
            // Group by (flag, variant) so eviction runs once per group instead of once per event.
            Map<String, List<OutcomeEvent>> grouped = new LinkedHashMap<>();
            for (OutcomeEvent event : batch.events()) {
                if (!isPlausible(event)) {
                    rejectedCounter.increment();
                    continue;
                }
                grouped.computeIfAbsent(event.flagKey() + "\u0000" + event.variantKey(), k -> new java.util.ArrayList<>())
                        .add(event);
            }

            grouped.forEach((groupKey, events) -> {
                String[] parts = groupKey.split("\u0000", 2);
                String flagKey = parts[0];
                String variantKey = parts[1];

                Flux.fromIterable(events)
                        .flatMap(windowService::record, 32)
                        .then(windowService.evict(flagKey, variantKey))
                        .block(Duration.ofSeconds(10));

                ingestedCounter.increment(events.size());
            });

            log.debug("Ingested {} event(s) across {} flag/variant group(s)", batch.size(), grouped.size());
        } catch (Exception e) {
            // Losing a batch of telemetry is bad. Letting it propagate out of an @Async method, where the
            // only listener is an uncaught-exception handler, would be indistinguishable from losing it
            // silently — so it is logged loudly here with the batch size that was lost.
            log.error("Failed to ingest a batch of {} event(s): {}", batch.size(), e.getMessage(), e);
        } finally {
            sample.stop(ingestionTimer);
        }
    }

    /**
     * An SDK is an untrusted client. It can be misconfigured, or its clock can be wrong, and a single
     * event stamped a year in the future would sit in the 24h window forever, permanently skewing every
     * percentile the rollback watcher reads.
     */
    private boolean isPlausible(OutcomeEvent event) {
        if (event.flagKey() == null || event.flagKey().isBlank() || event.variant() == null) {
            return false;
        }
        if (event.latencyMs() < 0) {
            return false;
        }
        Instant now = Instant.now();
        if (event.timestamp().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return false;
        }
        // Older than the widest window: it can contribute nothing and would be evicted immediately anyway.
        return !event.timestamp().isBefore(now.minus(Duration.ofHours(24)));
    }
}
