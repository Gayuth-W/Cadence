package com.cadence.flagservice.metrics.service;

import com.cadence.core.event.OutcomeEvent;
import com.cadence.core.metrics.WindowType;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.metrics.model.WindowStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolling metric windows on Redis sorted sets.
 *
 * <h2>Key layout</h2>
 * For each {@code flag:variant} pair and each {@link WindowType} the service maintains four kinds of
 * sorted set, all sharing an identical member string so that evicting an event from one evicts it
 * from all:
 * <pre>
 *   cadence:{env}:m:{flagKey}:{variant}:idx:{window}          score = timestamp   (the index)
 *   cadence:{env}:m:{flagKey}:{variant}:lat:{window}          score = latency ms  (percentiles)
 *   cadence:{env}:m:{flagKey}:{variant}:err:{window}          score = timestamp   (only failures)
 *   cadence:{env}:m:{flagKey}:{variant}:cst:{name}:{window}   score = value       (custom metrics)
 * </pre>
 *
 * <h2>Why two sorted sets instead of one</h2>
 * A single ZSET cannot answer both questions. Scored by timestamp, it evicts correctly by age but
 * cannot serve a percentile. Scored by latency, it gives P95 in {@code O(log n)} via a rank query but
 * {@code ZREMRANGEBYRANK} would then evict the <i>fastest</i> requests rather than the oldest ones —
 * silently biasing every percentile upward over time. The index set decides <i>what</i> is in the
 * window; the latency set answers <i>how slow</i> the window is.
 *
 * <p>Error rate is a third set rather than a scan of the index, because {@code ZCARD err / ZCARD idx}
 * is two O(1) commands, whereas deriving it from members means transferring the whole window.
 */
@Service
public class MetricWindowService {

    private static final Logger log = LoggerFactory.getLogger(MetricWindowService.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveStringRedisTemplate redis;
    private final CadenceProperties properties;

    public MetricWindowService(ReactiveStringRedisTemplate redis, CadenceProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Write path
    // ------------------------------------------------------------------

    /**
     * Fan one event into every window. Returns a cold {@link Mono}; the caller decides when to subscribe.
     * Lettuce multiplexes all of these commands onto one connection, so an event costs one round trip's
     * worth of latency, not a dozen.
     */
    public Mono<Void> record(OutcomeEvent event) {
        String member = newMember(event.timestamp().toEpochMilli());
        long ts = event.timestamp().toEpochMilli();
        double latency = event.latencyMs();

        List<Mono<?>> writes = new ArrayList<>();
        for (WindowType window : WindowType.values()) {
            String idx = idxKey(event.flagKey(), event.variantKey(), window);
            String lat = latKey(event.flagKey(), event.variantKey(), window);

            writes.add(redis.opsForZSet().add(idx, member, ts));
            writes.add(redis.opsForZSet().add(lat, member, latency));
            writes.add(redis.expire(idx, ttlFor(window)));
            writes.add(redis.expire(lat, ttlFor(window)));

            if (!event.success()) {
                String err = errKey(event.flagKey(), event.variantKey(), window);
                writes.add(redis.opsForZSet().add(err, member, ts));
                writes.add(redis.expire(err, ttlFor(window)));
            }

            for (Map.Entry<String, Double> custom : event.customMetrics().entrySet()) {
                String cst = cstKey(event.flagKey(), event.variantKey(), custom.getKey(), window);
                writes.add(redis.opsForZSet().add(cst, member, custom.getValue()));
                writes.add(redis.expire(cst, ttlFor(window)));
            }
        }
        if (!event.customMetrics().isEmpty()) {
            writes.add(redis.opsForSet().add(
                    cstNamesKey(event.flagKey(), event.variantKey()),
                    event.customMetrics().keySet().toArray(new String[0])));
        }

        return Flux.merge(writes).then();
    }

    /**
     * Trim every window for this pair back inside its bound.
     *
     * <p>Called once per ingested batch rather than once per event: eviction is the expensive part
     * (it must remove the same members from up to four sets), and doing it 100 times for a batch of 100
     * events would make ingestion cost more than the work it is recording.
     */
    public Mono<Void> evict(String flagKey, String variantKey) {
        return Flux.fromArray(WindowType.values())
                .flatMap(window -> evictWindow(flagKey, variantKey, window))
                .then();
    }

    private Mono<Void> evictWindow(String flagKey, String variantKey, WindowType window) {
        String idx = idxKey(flagKey, variantKey, window);

        Mono<List<String>> expired = window.isCountBounded()
                ? evictByCount(idx, window)
                : evictByAge(idx, window);

        return expired.flatMap(members -> {
            if (members.isEmpty()) {
                return Mono.empty();
            }
            Object[] toRemove = members.toArray();
            List<Mono<?>> removals = new ArrayList<>();
            removals.add(redis.opsForZSet().remove(idx, toRemove));
            removals.add(redis.opsForZSet().remove(latKey(flagKey, variantKey, window), toRemove));
            removals.add(redis.opsForZSet().remove(errKey(flagKey, variantKey, window), toRemove));

            return customNames(flagKey, variantKey)
                    .flatMapMany(Flux::fromIterable)
                    .map(name -> redis.opsForZSet().remove(cstKey(flagKey, variantKey, name, window), toRemove))
                    .collectList()
                    .flatMap(customRemovals -> {
                        removals.addAll(customRemovals);
                        return Flux.merge(removals).then();
                    });
        }).then();
    }

    /** Oldest-first by rank; the index is scored by timestamp so rank 0 really is the oldest event. */
    private Mono<List<String>> evictByCount(String idxKey, WindowType window) {
        int max = Math.min(window.maxEvents(), properties.getMetrics().getMaxWindowSize());
        return redis.opsForZSet().size(idxKey)
                .flatMap(size -> size <= max
                        ? Mono.just(List.<String>of())
                        : redis.opsForZSet().range(idxKey, Range.closed(0L, size - max - 1)).collectList());
    }

    private Mono<List<String>> evictByAge(String idxKey, WindowType window) {
        double cutoff = System.currentTimeMillis() - window.duration().toMillis();
        Mono<List<String>> byAge = redis.opsForZSet()
                .rangeByScore(idxKey, Range.closed(Double.NEGATIVE_INFINITY, cutoff))
                .collectList();

        // A traffic spike must not be allowed to grow a 24h window without bound. Age eviction alone
        // would happily hold ten million members for a day.
        int hardCap = properties.getMetrics().getMaxWindowSize();
        return byAge.flatMap(aged -> redis.opsForZSet().size(idxKey).flatMap(size -> {
            if (size - aged.size() <= hardCap) {
                return Mono.just(aged);
            }
            long overflow = size - aged.size() - hardCap;
            return redis.opsForZSet().range(idxKey, Range.closed((long) aged.size(), aged.size() + overflow - 1))
                    .collectList()
                    .map(extra -> {
                        List<String> all = new ArrayList<>(aged);
                        all.addAll(extra);
                        return all;
                    });
        }));
    }

    // ------------------------------------------------------------------
    // Read path
    // ------------------------------------------------------------------

    /** Blocking read, safe to call from the virtual-thread scheduler where the watcher and snapshotter run. */
    public WindowStats stats(String flagKey, String variantKey, WindowType window) {
        try {
            WindowStats stats = statsReactive(flagKey, variantKey, window).block(BLOCK_TIMEOUT);
            return stats == null ? WindowStats.empty(variantKey, window) : stats;
        } catch (Exception e) {
            // Redis being unreachable must not throw a scheduler tick into a retry storm. An empty window
            // is honest: the platform genuinely does not know the candidate's health right now, and
            // "unknown" never trips a rollback (see minSamples).
            log.warn("Window stats unavailable for {}:{} {}: {}", flagKey, variantKey, window, e.getMessage());
            return WindowStats.empty(variantKey, window);
        }
    }

    public Mono<WindowStats> statsReactive(String flagKey, String variantKey, WindowType window) {
        String idx = idxKey(flagKey, variantKey, window);
        String lat = latKey(flagKey, variantKey, window);
        String err = errKey(flagKey, variantKey, window);

        Mono<Long> total = redis.opsForZSet().size(idx).defaultIfEmpty(0L);
        Mono<Long> errors = redis.opsForZSet().size(err).defaultIfEmpty(0L);
        Mono<List<Double>> latencies = redis.opsForZSet()
                .rangeWithScores(lat, Range.closed(0L, -1L))
                .map(ZSetOperations.TypedTuple::getScore)
                .collectList();

        return Mono.zip(total, errors, latencies).flatMap(tuple -> {
            long samples = tuple.getT1();
            long errorCount = tuple.getT2();
            List<Double> sortedLatencies = tuple.getT3(); // ZSET range returns ascending score order

            if (samples == 0) {
                return Mono.just(WindowStats.empty(variantKey, window));
            }

            double mean = sortedLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = sortedLatencies.stream()
                    .mapToDouble(v -> (v - mean) * (v - mean))
                    .average().orElse(0);

            double throughput = window.isCountBounded()
                    ? throughputFromSpan(sortedLatencies.size(), window)
                    : samples / (double) window.duration().toMinutes();

            return customMeans(flagKey, variantKey, window).map(customs -> new WindowStats(
                    variantKey,
                    window,
                    samples,
                    errorCount,
                    (double) errorCount / samples,
                    mean,
                    Math.sqrt(variance),
                    percentile(sortedLatencies, 0.50),
                    percentile(sortedLatencies, 0.95),
                    percentile(sortedLatencies, 0.99),
                    throughput,
                    customs));
        }).defaultIfEmpty(WindowStats.empty(variantKey, window));
    }

    /** Raw latency samples for the Mann-Whitney canary gate. Ascending; rank order is all the test needs. */
    public List<Double> latencySamples(String flagKey, String variantKey, WindowType window, int limit) {
        try {
            List<Double> samples = redis.opsForZSet()
                    .rangeWithScores(latKey(flagKey, variantKey, window), Range.closed(0L, (long) limit - 1))
                    .map(ZSetOperations.TypedTuple::getScore)
                    .collectList()
                    .block(BLOCK_TIMEOUT);
            return samples == null ? List.of() : samples;
        } catch (Exception e) {
            log.warn("Latency samples unavailable for {}:{}: {}", flagKey, variantKey, e.getMessage());
            return List.of();
        }
    }

    private Mono<Map<String, Double>> customMeans(String flagKey, String variantKey, WindowType window) {
        return customNames(flagKey, variantKey).flatMap(names -> {
            if (names.isEmpty()) {
                return Mono.just(Map.of());
            }
            return Flux.fromIterable(names)
                    .flatMap(name -> redis.opsForZSet()
                            .rangeWithScores(cstKey(flagKey, variantKey, name, window), Range.closed(0L, -1L))
                            .map(ZSetOperations.TypedTuple::getScore)
                            .collectList()
                            .map(values -> Map.entry(name,
                                    values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN))))
                    .collectList()
                    .map(entries -> {
                        Map<String, Double> result = new HashMap<>();
                        entries.forEach(e -> result.put(e.getKey(), e.getValue()));
                        return result;
                    });
        });
    }

    private Mono<Set<String>> customNames(String flagKey, String variantKey) {
        return redis.opsForSet().members(cstNamesKey(flagKey, variantKey)).collect(java.util.stream.Collectors.toSet());
    }

    /** Wipe every window for a flag. Called after a rollback so the next attempt starts from clean data. */
    public Mono<Void> reset(String flagKey) {
        return redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(prefix() + ":m:" + flagKey + ":*").count(1000).build())
                .collectList()
                .flatMap(keys -> keys.isEmpty() ? Mono.empty() : redis.delete(Flux.fromIterable(keys)).then());
    }

    // ------------------------------------------------------------------

    /**
     * Nearest-rank percentile on an ascending list. Chosen over linear interpolation because the input
     * is already the complete population of the window, not a sample of it: there is nothing to
     * interpolate between, and the nearest-rank value is an actual observed latency rather than a
     * synthetic one that never happened.
     */
    static double percentile(List<Double> ascending, double p) {
        if (ascending.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * ascending.size()) - 1;
        return ascending.get(Math.max(0, Math.min(rank, ascending.size() - 1)));
    }

    private double throughputFromSpan(int samples, WindowType window) {
        // The count-bounded window has no fixed duration, so per-minute throughput is not meaningful.
        // Reporting 0 is better than reporting a number that means nothing.
        return 0;
    }

    private Duration ttlFor(WindowType window) {
        // Keys outlive their window by a wide margin so an idle flag's data does not vanish between
        // two watcher ticks, but a deleted flag's keys do eventually disappear on their own.
        return window.isCountBounded() ? Duration.ofHours(24) : window.duration().multipliedBy(2);
    }

    private static String newMember(long timestampMillis) {
        return timestampMillis + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private String prefix() {
        return "cadence:" + properties.getEnvironment();
    }

    private String base(String flagKey, String variantKey) {
        return prefix() + ":m:" + flagKey + ":" + variantKey;
    }

    String idxKey(String flagKey, String variantKey, WindowType w) {
        return base(flagKey, variantKey) + ":idx:" + w.token();
    }

    String latKey(String flagKey, String variantKey, WindowType w) {
        return base(flagKey, variantKey) + ":lat:" + w.token();
    }

    String errKey(String flagKey, String variantKey, WindowType w) {
        return base(flagKey, variantKey) + ":err:" + w.token();
    }

    String cstKey(String flagKey, String variantKey, String name, WindowType w) {
        return base(flagKey, variantKey) + ":cst:" + name + ":" + w.token();
    }

    String cstNamesKey(String flagKey, String variantKey) {
        return base(flagKey, variantKey) + ":cstnames";
    }
}
