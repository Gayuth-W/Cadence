package com.cadence.flagservice.metrics.controller;

import com.cadence.core.metrics.WindowType;
import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.domain.AuditRecord;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import com.cadence.flagservice.metrics.dto.FlagAnalytics;
import com.cadence.flagservice.metrics.dto.MetricSnapshotResponse;
import com.cadence.flagservice.metrics.model.CanaryResult;
import com.cadence.flagservice.metrics.model.WindowStats;
import com.cadence.flagservice.metrics.repository.MetricSnapshotRepository;
import com.cadence.flagservice.metrics.service.CanaryAnalysisService;
import com.cadence.flagservice.metrics.service.MetricWindowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only release-health surface for the dashboard, plus a CSV export for the people who will
 * inevitably want the numbers in a spreadsheet during a post-mortem.
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics")
@PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
public class MetricController {

    private static final List<String> VARIANTS = List.of("baseline", "candidate", "candidate-shadow");

    private final FeatureFlagService flagService;
    private final MetricWindowService windowService;
    private final CanaryAnalysisService canaryService;
    private final MetricSnapshotRepository snapshotRepository;
    private final AuditRecordRepository auditRepository;

    public MetricController(FeatureFlagService flagService,
                            MetricWindowService windowService,
                            CanaryAnalysisService canaryService,
                            MetricSnapshotRepository snapshotRepository,
                            AuditRecordRepository auditRepository) {
        this.flagService = flagService;
        this.windowService = windowService;
        this.canaryService = canaryService;
        this.snapshotRepository = snapshotRepository;
        this.auditRepository = auditRepository;
    }

    /** Live window statistics, straight from Redis. This is what the watcher is looking at right now. */
    @GetMapping("/flags/{flagId}")
    @Operation(summary = "Live release health for both variants")
    public Map<String, WindowStats> live(@PathVariable UUID flagId,
                                         @RequestParam(defaultValue = "LAST_100") WindowType window) {
        FeatureFlag flag = flagService.findById(flagId);
        Map<String, WindowStats> result = new LinkedHashMap<>();
        for (String variant : VARIANTS) {
            result.put(variant, windowService.stats(flag.getKey(), variant, window));
        }
        return result;
    }

    /** A dry run of the canary gate. Answers "would this rollout advance right now, and why not". */
    @GetMapping("/flags/{flagId}/canary")
    @Operation(summary = "Run the Mann-Whitney canary comparison without advancing anything")
    public CanaryResult canary(@PathVariable UUID flagId) {
        return canaryService.analyse(flagService.findById(flagId));
    }

    @GetMapping("/flags/{flagId}/snapshots")
    @Operation(summary = "Historical snapshots, for charting")
    public List<MetricSnapshotResponse> snapshots(
            @PathVariable UUID flagId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        Instant start = from == null ? Instant.now().minus(Duration.ofHours(24)) : from;
        Instant end = to == null ? Instant.now() : to;
        return snapshotRepository.findByFlagIdAndCapturedAtBetweenOrderByCapturedAtAsc(flagId, start, end)
                .stream().map(MetricSnapshotResponse::from).toList();
    }

    @GetMapping(value = "/flags/{flagId}/snapshots.csv", produces = "text/csv")
    @Operation(summary = "Export the snapshot history as CSV")
    public ResponseEntity<String> exportCsv(@PathVariable UUID flagId) {
        FeatureFlag flag = flagService.findById(flagId);
        List<MetricSnapshotResponse> rows = snapshotRepository
                .findByFlagIdAndCapturedAtBetweenOrderByCapturedAtAsc(
                        flagId, Instant.now().minus(Duration.ofDays(30)), Instant.now())
                .stream().map(MetricSnapshotResponse::from).toList();

        StringBuilder csv = new StringBuilder(
                "captured_at,flag_key,variant,rollout_percentage,samples,errors,error_rate,"
                + "mean_ms,stddev_ms,p50_ms,p95_ms,p99_ms,throughput_per_min,trend\n");
        for (MetricSnapshotResponse r : rows) {
            csv.append("%s,%s,%s,%d,%d,%d,%.6f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s%n".formatted(
                    r.capturedAt(), r.flagKey(), r.variantKey(), r.rolloutPercentage(),
                    r.sampleCount(), r.errorCount(), r.errorRate(), r.meanLatencyMs(),
                    r.stdDevLatencyMs(), r.p50LatencyMs(), r.p95LatencyMs(), r.p99LatencyMs(),
                    r.throughputPerMinute(), r.trend()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s-metrics.csv\"".formatted(flag.getKey()))
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    /** The retrospective: how often this flag was withdrawn, by whom, and what it cost. */
    @GetMapping("/flags/{flagId}/analytics")
    @Operation(summary = "Rollback history, causes, exposure per stage, and time to full rollout")
    public FlagAnalytics analytics(@PathVariable UUID flagId) {
        FeatureFlag flag = flagService.findById(flagId);
        List<AuditRecord> rollbacks = auditRepository.findRollbacks(flagId);

        int automatic = (int) rollbacks.stream()
                .filter(r -> r.getAction() == AuditAction.ROLLBACK_AUTOMATIC).count();

        List<MetricSnapshotResponse> candidateHistory = snapshotRepository
                .findByFlagIdAndVariantKeyOrderByCapturedAtDesc(flagId, "candidate")
                .stream().map(MetricSnapshotResponse::from).toList();

        // Distinct percentages the flag has actually served, with the sample count observed at each.
        // A far better proxy for "how many users were exposed" than the percentage on its own.
        Map<String, Integer> exposure = new LinkedHashMap<>();
        candidateHistory.forEach(s ->
                exposure.merge(s.rolloutPercentage() + "%", (int) s.sampleCount(), Integer::sum));

        Instant firstRollout = candidateHistory.isEmpty()
                ? null : candidateHistory.get(candidateHistory.size() - 1).capturedAt();
        Instant completed = flag.getState() == com.cadence.core.model.FlagState.FULLY_ON
                ? flag.getUpdatedAt() : null;

        String timeToFull = (firstRollout != null && completed != null)
                ? Duration.between(firstRollout, completed).toString()
                : null;

        return new FlagAnalytics(
                flagId,
                flag.getKey(),
                rollbacks.size(),
                automatic,
                rollbacks.stream().map(AuditRecord::getReason).toList(),
                firstRollout,
                completed,
                timeToFull,
                exposure);
    }

    /**
     * Wipe the rolling windows for a flag. Used after a rollback has been investigated and the
     * candidate fixed, so the next attempt is not judged against the corpse of the previous one.
     */
    @DeleteMapping("/flags/{flagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Clear all rolling metric windows for this flag. ADMIN.")
    public ResponseEntity<Void> resetWindows(@PathVariable UUID flagId) {
        FeatureFlag flag = flagService.findById(flagId);
        windowService.reset(flag.getKey()).block(Duration.ofSeconds(10));
        return ResponseEntity.noContent().build();
    }
}
