package com.cadence.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two implementations the flag chooses between, plus the lever the demo pulls to make the
 * candidate misbehave.
 *
 * <p>The baseline is a stable ~50ms calculation that essentially never fails. The candidate is
 * ordinarily <i>faster</i> (~30ms) — which is the point: a canary gate that blocks improvements is
 * worthless, and the demo shows the gate correctly letting a better candidate through.
 *
 * <p>When {@link #degrade()} is called, the candidate starts taking ~800ms and failing ~35% of the
 * time. Both of those cross the thresholds seeded on {@code pricing.engine.v2}, and the rollback
 * watcher withdraws it without anybody touching the console.
 */
@Component
public class PricingEngine {

    private static final Logger log = LoggerFactory.getLogger(PricingEngine.class);

    private final AtomicBoolean degraded = new AtomicBoolean(false);

    /** Legacy calculator. Slow-ish, boring, reliable. */
    public Quote legacy(String sku, int quantity) {
        sleep(45, 60);
        double unit = basePrice(sku);
        return new Quote(sku, quantity, round(unit * quantity), "legacy");
    }

    /**
     * The rewrite. Faster than legacy, and applies a volume discount the legacy engine never had —
     * so its <i>output</i> differs, which is exactly what shadow mode is there to surface before
     * anybody is served it.
     */
    public Quote v2(String sku, int quantity) {
        if (degraded.get()) {
            sleep(700, 950);
            if (ThreadLocalRandom.current().nextDouble() < 0.35) {
                throw new IllegalStateException("Pricing v2: downstream tax service timed out");
            }
        } else {
            sleep(25, 40);
        }
        double unit = basePrice(sku);
        double discount = quantity >= 10 ? 0.9 : 1.0;
        return new Quote(sku, quantity, round(unit * quantity * discount), "v2");
    }

    public void degrade() {
        degraded.set(true);
        log.warn("Pricing v2 candidate is now DEGRADED: ~800ms latency, ~35% error rate");
    }

    public void recover() {
        degraded.set(false);
        log.info("Pricing v2 candidate restored to healthy behaviour");
    }

    public boolean isDegraded() {
        return degraded.get();
    }

    private static double basePrice(String sku) {
        return 10.0 + Math.abs(sku.hashCode() % 90);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void sleep(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Quote(String sku, int quantity, double total, String engine) {
    }
}
