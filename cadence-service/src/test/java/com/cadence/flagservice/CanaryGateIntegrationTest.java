package com.cadence.flagservice;

import com.cadence.core.model.VariantName;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.repository.FeatureFlagRepository;
import com.cadence.flagservice.metrics.model.CanaryResult;
import com.cadence.flagservice.metrics.service.CanaryAnalysisService;
import com.cadence.flagservice.metrics.service.MetricIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statistical gate that separates a real regression from noise.
 *
 * <p>Mann-Whitney U is used rather than a t-test because latency distributions are not normal — they
 * are right-skewed with a long tail, and the mean of a handful of slow tail requests can swamp a t-test
 * while the rank-based U statistic barely notices. A rank test also needs no assumption about variance.
 *
 * <p>The gate is deliberately one-sided in effect: a statistically significant difference where the
 * candidate is <i>faster</i> must not block the rollout. Rolling back an improvement because it was
 * significantly different is the classic failure of a naive two-sided gate.
 */
class CanaryGateIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MetricIngestionService ingestionService;
    @Autowired private CanaryAnalysisService canaryService;
    @Autowired private FeatureFlagRepository flagRepository;

    @Test
    @DisplayName("a candidate that is significantly slower than baseline is blocked")
    void significantlySlowerCandidateIsBlocked() throws Exception {
        String key = "canary.slower.candidate";
        FeatureFlag flag = seedFlag(key);

        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 200, 100));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 200, 260));

        CanaryResult result = canaryService.analyse(flag);

        assertThat(result.abstained()).isFalse();
        assertThat(result.passed()).isFalse();
        assertThat(result.pValue()).isLessThan(0.05);
        assertThat(result.candidateMedian()).isGreaterThan(result.baselineMedian());
        assertThat(result.candidateSamples()).isEqualTo(200);
        assertThat(result.baselineSamples()).isEqualTo(200);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("a candidate drawn from the same distribution as baseline passes")
    void statisticallyIndistinguishableCandidatePasses() throws Exception {
        String key = "canary.equivalent.candidate";
        FeatureFlag flag = seedFlag(key);

        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 200, 120));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 200, 120));

        CanaryResult result = canaryService.analyse(flag);

        assertThat(result.abstained()).isFalse();
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("a significantly FASTER candidate passes: the gate blocks regressions, not improvements")
    void significantlyFasterCandidatePasses() throws Exception {
        String key = "canary.faster.candidate";
        FeatureFlag flag = seedFlag(key);

        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 200, 300));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 200, 110));

        CanaryResult result = canaryService.analyse(flag);

        // The difference is real...
        assertThat(result.pValue()).isLessThan(0.05);
        assertThat(result.candidateMedian()).isLessThan(result.baselineMedian());
        // ...but it is an improvement, so the rollout advances.
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("below the minimum sample size the gate abstains rather than guessing")
    void tooFewSamplesAbstains() throws Exception {
        String key = "canary.insufficient.samples";
        FeatureFlag flag = seedFlag(key);

        // Test profile sets canary.min-samples = 10.
        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 5, 100));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 5, 900));

        CanaryResult result = canaryService.analyse(flag);

        assertThat(result.abstained()).isTrue();
        // Abstaining passes: with no data, the gate defers to the operator rather than blocking a
        // release on a sample of five. It says so in the reason.
        assertThat(result.passed()).isTrue();
        assertThat(Double.isNaN(result.pValue())).isTrue();
        assertThat(result.reason()).containsIgnoringCase("sample");
    }

    private FeatureFlag seedFlag(String key) throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, key);
        rollout(admin, flagId, 50);
        return flagRepository.findById(UUID.fromString(flagId)).orElseThrow();
    }
}
