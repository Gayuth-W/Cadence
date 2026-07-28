package com.cadence.flagservice;

import com.cadence.core.model.FlagState;
import com.cadence.core.metrics.WindowType;
import com.cadence.core.model.VariantName;
import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.domain.AuditRecord;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.repository.FeatureFlagRepository;
import com.cadence.flagservice.metrics.service.MetricIngestionService;
import com.cadence.flagservice.metrics.service.MetricWindowService;
import com.cadence.flagservice.metrics.service.RollbackGuardState;
import com.cadence.flagservice.metrics.service.RollbackWatcherService;
import com.cadence.flagservice.security.CurrentActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of the platform: a degraded candidate is withdrawn without a human.
 *
 * <p>The watcher's timer is disabled in the test profile ({@code cadence.rollback.enabled=false}) and
 * {@link RollbackWatcherService#watch()} is invoked directly instead. Waiting on a 30-second scheduler
 * would make this test slow and racy while testing nothing extra — {@code @Scheduled} is Spring's code,
 * not ours. What is ours is the breach counter, the minimum-sample floor and the evidence trail.
 *
 * <p>The test profile sets {@code consecutive-breaches: 3}, so three watcher passes over an unhealthy
 * window are enough. In production the same code needs 50 passes at 30s ≈ 25 minutes of sustained
 * degradation, which is what stops a single bad deploy minute from withdrawing a good release.
 */
class AutomaticRollbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MetricIngestionService ingestionService;
    @Autowired private RollbackWatcherService watcher;
    @Autowired private FeatureFlagRepository flagRepository;
    @Autowired private AuditRecordRepository auditRepository;
    @Autowired private MetricWindowService windowService;
    @Autowired private RollbackGuardState guardState;

    @Test
    @DisplayName("a candidate breaching its error-rate ceiling is rolled back, attributed to SYSTEM, with evidence")
    void degradedCandidateIsAutomaticallyRolledBack() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "autorollback.error.rate");
        rollout(admin, flagId, 25);

        // Baseline is healthy; the candidate fails 40% of the time, far past its 2% ceiling.
        ingestionService.ingest(TestSupport.healthy("autorollback.error.rate", VariantName.BASELINE, 60, 100));
        ingestionService.ingest(TestSupport.batch("autorollback.error.rate", VariantName.CANDIDATE, 60, 120, 0.40));

        // consecutiveBreaches = 3 in the test profile: two passes must not be enough.
        watcher.watch();
        watcher.watch();
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLING_OUT);

        watcher.watch();
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLED_BACK);

        FeatureFlag flag = flagRepository.findById(UUID.fromString(flagId)).orElseThrow();
        assertThat(flag.getRolloutPercentage()).isZero();
        // The pre-rollback percentage is preserved so a human can see how far it had got.
        assertThat(flag.getPercentageBeforeRollback()).isEqualTo(25);

        List<AuditRecord> rollbacks = auditRepository.findRollbacks(flag.getId());
        assertThat(rollbacks).hasSize(1);
        AuditRecord record = rollbacks.get(0);
        assertThat(record.getAction()).isEqualTo(AuditAction.ROLLBACK_AUTOMATIC);
        assertThat(record.getActor()).isEqualTo(CurrentActor.SYSTEM);

        // Evidence, not just a verdict: the post-mortem must be able to answer "on what basis?"
        var metadata = record.getMetadata();
        assertThat(metadata).containsKeys("breachingMetrics", "candidateErrorRate", "baselineErrorRate",
                "percentageBeforeRollback");
        assertThat(metadata.get("percentageBeforeRollback")).isEqualTo(25);

        // The error-rate metric is the one that breached, and it carries its own observed-vs-threshold pair.
        assertThat(metadata).containsKey("error_rate");
        @SuppressWarnings("unchecked")
        var errorRateEvidence = (java.util.Map<String, Object>) metadata.get("error_rate");
        assertThat(((Number) errorRateEvidence.get("observed")).doubleValue()).isGreaterThan(0.02);
        assertThat(((Number) errorRateEvidence.get("threshold")).doubleValue()).isEqualTo(0.02);

        // The P95 ceiling was never crossed, so it must not appear as a breach.
        assertThat(metadata).doesNotContainKey("p95_latency");
    }

    @Test
    @DisplayName("a healthy candidate is never rolled back, however many times the watcher runs")
    void healthyCandidateSurvives() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "autorollback.healthy");
        rollout(admin, flagId, 50);

        ingestionService.ingest(TestSupport.healthy("autorollback.healthy", VariantName.BASELINE, 60, 100));
        ingestionService.ingest(TestSupport.healthy("autorollback.healthy", VariantName.CANDIDATE, 60, 105));

        for (int i = 0; i < 10; i++) {
            watcher.watch();
        }
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLING_OUT);
        assertThat(auditRepository.findRollbacks(UUID.fromString(flagId))).isEmpty();
    }

    @Test
    @DisplayName("too few samples means no opinion: a tiny unlucky window must not trip a rollback")
    void belowMinSamplesTheWatcherAbstains() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        // minSamples = 5; feed 4 events, all failures. 100% error rate, but not enough evidence.
        String flagId = createFlag(admin, "autorollback.minsamples", 0.02, 400.0, 1, 5);
        rollout(admin, flagId, 10);

        ingestionService.ingest(TestSupport.batch("autorollback.minsamples", VariantName.CANDIDATE, 4, 100, 1.0));

        for (int i = 0; i < 5; i++) {
            watcher.watch();
        }
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLING_OUT);
    }

    @Test
    @DisplayName("a breach counter resets when the candidate recovers, so intermittent blips never accumulate")
    void breachCounterResetsOnRecovery() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "autorollback.flapping");
        rollout(admin, flagId, 25);

        // Two unhealthy passes (threshold is 3)...
        ingestionService.ingest(TestSupport.batch("autorollback.flapping", VariantName.CANDIDATE, 60, 120, 0.40));
        watcher.watch();
        watcher.watch();
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLING_OUT);

        // ...then the candidate recovers. The 100-event window fills with healthy traffic, which both
        // clears the breach counter and pushes the failures out of the window.
        ingestionService.ingest(TestSupport.healthy("autorollback.flapping", VariantName.CANDIDATE, 120, 110));
        watcher.watch();

        // A single later bad pass must start counting from zero again, not from two.
        ingestionService.ingest(TestSupport.batch("autorollback.flapping", VariantName.CANDIDATE, 120, 120, 0.40));
        watcher.watch();
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLING_OUT);
    }

    @Test
    @DisplayName("reset wipes the old candidate's windows, so the fixed one is not judged on its corpse")
    void resetClearsStaleWindows() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String key = "autorollback.reset.windows";
        String flagId = createFlag(admin, key);
        rollout(admin, flagId, 25);

        ingestionService.ingest(TestSupport.batch(key, VariantName.CANDIDATE, 60, 120, 0.40));
        for (int i = 0; i < 3; i++) {
            watcher.watch();
        }
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLED_BACK);
        // The evidence survives the rollback itself — that is what the post-mortem reads.
        assertThat(windowService.stats(key, "candidate", WindowType.LAST_100).sampleCount()).isEqualTo(60);

        reset(admin, flagId);

        // ...and is discarded on reset, once a human has looked at it.
        assertThat(windowService.stats(key, "candidate", WindowType.LAST_100).sampleCount()).isZero();
        assertThat(state(flagId)).isEqualTo(FlagState.OFF);
    }

    @Test
    @DisplayName("reset clears the cooldown, so the fixed candidate rolls out WITH protection, not without it")
    void resetClearsCooldownSoTheWatcherKeepsGuarding() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String key = "autorollback.reset.cooldown";
        // cooldownMinutes defaults to 10 in the helper: long enough that a leftover lock would silence
        // the watcher for the whole test.
        String flagId = createFlag(admin, key);
        rollout(admin, flagId, 25);

        ingestionService.ingest(TestSupport.batch(key, VariantName.CANDIDATE, 60, 120, 0.40));
        for (int i = 0; i < 3; i++) {
            watcher.watch();
        }
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLED_BACK);
        assertThat(guardState.isInCooldown(UUID.fromString(flagId))).isTrue();

        reset(admin, flagId);
        assertThat(guardState.isInCooldown(UUID.fromString(flagId))).isFalse();

        // The "fixed" candidate turns out to be broken too. The watcher must catch it immediately.
        // Before the cooldown was cleared on reset, it would have sat this one out for ten minutes
        // while a known-bad candidate served 25% of production traffic.
        rollout(admin, flagId, 25);
        ingestionService.ingest(TestSupport.batch(key, VariantName.CANDIDATE, 60, 130, 0.45));
        for (int i = 0; i < 3; i++) {
            watcher.watch();
        }
        assertThat(state(flagId)).isEqualTo(FlagState.ROLLED_BACK);
    }

    private void reset(String token, String flagId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/flags/" + flagId + "/reset")
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("reason", "candidate fixed"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    private FlagState state(String flagId) {
        return flagRepository.findById(UUID.fromString(flagId)).orElseThrow().getState();
    }
}
