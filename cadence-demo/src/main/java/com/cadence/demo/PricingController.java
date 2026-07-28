package com.cadence.demo;

import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.UserContext;
import com.cadence.sdk.FlagClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.UUID;

/**
 * The integration, in full.
 *
 * <p>Note what {@link #quote} does <b>not</b> do: it does not time anything, does not catch anything,
 * does not report a metric, and does not know that shadow mode exists. It names a flag, describes the
 * user, and hands over two suppliers. The SDK evaluates, executes the right one, times it, catches
 * failures, and reports the outcome event that the rollback watcher will later act on.
 */
@RestController
@CrossOrigin(origins = "http://localhost:4200")
public class PricingController {

    private static final String FLAG = "pricing.engine.v2";

    private final FlagClient flagClient;
    private final PricingEngine engine;
    private final Deque<Map<String, Object>> recentRequests = new ConcurrentLinkedDeque<>();

    public PricingController(FlagClient flagClient, PricingEngine engine) {
        this.flagClient = flagClient;
        this.engine = engine;
    }

    @GetMapping("/quote/{sku}")
    public PricingEngine.Quote quote(@PathVariable String sku,
                                     @RequestParam(defaultValue = "1") int quantity,
                                     @RequestParam String userId,
                                     @RequestParam(required = false) String country,
                                     @RequestParam(required = false) String segment) {

        UserContext ctx = UserContext.builder(userId)
                .country(country)
                .segments(segment == null ? Set.of() : Set.of(segment))
                .build();

        EvaluationResult eval = flagClient.evaluate(FLAG, ctx);
        trackRequest(userId, eval.variant().name());

        return flagClient.run(FLAG, ctx,
                () -> engine.legacy(sku, quantity),   // baseline
                () -> engine.v2(sku, quantity));      // candidate
    }

    /** Which variant would this user get, and why. Handy while watching a rollout advance. */
    @GetMapping("/whoami")
    public EvaluationResult whoami(@RequestParam String userId,
                                   @RequestParam(required = false) String country,
                                   @RequestParam(required = false) String segment) {
        EvaluationResult res = flagClient.evaluate(FLAG, UserContext.builder(userId)
                .country(country)
                .segments(segment == null ? Set.of() : Set.of(segment))
                .build());
        trackRequest(userId, res.variant().name());
        return res;
    }

    private void trackRequest(String userId, String variant) {
        recentRequests.addFirst(Map.of(
            "id", UUID.randomUUID().toString(),
            "userId", userId,
            "variant", variant.substring(0, 1).toUpperCase() + variant.substring(1).toLowerCase()
        ));
        if (recentRequests.size() > 50) {
            recentRequests.removeLast();
        }
    }

    @CrossOrigin(origins = "http://localhost:4200")
    @GetMapping("/requests")
    public List<Map<String, Object>> getRequests() {
        return new ArrayList<>(recentRequests);
    }

    /** The demo's lever: make the candidate slow and error-prone, and watch the platform react. */
    @PostMapping("/chaos/degrade")
    public Map<String, Object> degrade() {
        engine.degrade();
        return Map.of("degraded", true,
                "note", "pricing v2 will now take ~800ms and fail ~35% of the time");
    }

    @PostMapping("/chaos/recover")
    public Map<String, Object> recover() {
        engine.recover();
        return Map.of("degraded", false);
    }

    @GetMapping("/chaos/status")
    public Map<String, Object> status() {
        return Map.of("degraded", engine.isDegraded(), "sdkReady", flagClient.isReady());
    }
}
