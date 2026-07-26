package com.cadence.flagservice.flag.controller;

import com.cadence.core.evaluation.EvaluationRequest;
import com.cadence.core.event.EventBatch;
import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.UserContext;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import com.cadence.flagservice.metrics.service.MetricIngestionService;
import com.cadence.flagservice.security.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;

/**
 * The SDK data plane. Authenticated by service API key, authorised by scope
 * (see {@code SecurityConfig}),
 * and capable of exactly two things: reading flag config and writing outcome
 * events.
 *
 * <p>
 * Note what is <i>not</i> here. No rollout, no pause, no rollback, no audit
 * read. An application pod
 * holding a leaked key can learn which flags exist and lie about metrics; it
 * cannot change a release.
 */
@RestController
@RequestMapping("/sdk/v1")
@Tag(name = "SDK data plane")
@SecurityRequirement(name = "apiKey")
public class SdkController {

    private final FeatureFlagService flagService;
    private final MetricIngestionService ingestionService;
    private final CadenceProperties properties;

    public SdkController(FeatureFlagService flagService,
            MetricIngestionService ingestionService,
            CadenceProperties properties) {
        this.flagService = flagService;
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    /**
     * The whole flag config for the key's environment. Polled by the SDK every 15s
     * and cached in
     * Caffeine; evaluation itself never touches the network.
     */
    @GetMapping("/flags")
    @Operation(summary = "Flag configuration for the environment this API key is scoped to")
    public List<FlagDefinition> flags(HttpServletRequest request) {
        return flagService.definitionsFor(environmentOf(request));
    }

    /** Server-side evaluation for callers that cannot run the Java SDK. */
    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate one flag for one user context")
    public EvaluationResult evaluate(@Valid @RequestBody EvaluationRequest request, HttpServletRequest http) {
        UserContext ctx = UserContext.builder(request.userId())
                .country(request.country())
                .segments(new HashSet<>(request.segments()))
                .attributes(request.attributes())
                .build();
        return flagService.evaluate(request.flagKey(), environmentOf(http), ctx);
    }

    /**
     * Ingest a batch of outcome events.
     *
     * <p>
     * Returns {@code 202 Accepted} the moment the payload is deserialised. The
     * actual fan-out into
     * Redis windows happens on a virtual thread, so a slow Redis never becomes
     * latency in a caller's
     * fire-and-forget flush, and a burst of a hundred thousand events never
     * exhausts a thread pool.
     */
    @PostMapping("/events")
    @Operation(summary = "Report outcome events. Accepted asynchronously; never blocks the caller.")
    public ResponseEntity<Void> events(@RequestBody EventBatch batch) {
        if (!batch.isEmpty()) {
            ingestionService.ingestAsync(batch);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * The environment comes from the authenticated key, not from a request
     * parameter. A staging key
     * must not be able to read production flag config by changing a header.
     */
    private String environmentOf(HttpServletRequest request) {
        Object env = request.getAttribute(ApiKeyAuthenticationFilter.RequestAttributes.ENVIRONMENT);
        return env instanceof String s ? s : properties.getEnvironment();
    }
}
