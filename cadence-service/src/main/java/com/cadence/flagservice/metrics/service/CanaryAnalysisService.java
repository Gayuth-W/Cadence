package com.cadence.flagservice.metrics.service;

import com.cadence.core.metrics.Direction;
import com.cadence.core.metrics.WindowType;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.metrics.model.CanaryResult;
import org.apache.commons.math3.stat.inference.MannWhitneyUTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The statistical gate that stands between one rollout stage and the next.
 *
 * <h2>Why Mann-Whitney U and not a t-test</h2>
 * Latency distributions are not normal. They are right-skewed, heavy-tailed, and frequently
 * multi-modal (cache hit vs cache miss). A Student's t-test assumes normality and compares means, so a
 * handful of 8-second timeouts drag the candidate's mean up and trip a false alarm, while a candidate
 * that is uniformly 40ms slower at every percentile can hide inside the variance. Mann-Whitney U is a
 * rank test: it asks "if I draw one request from each variant, how often is the candidate slower?" —
 * which is exactly the question a release engineer is actually asking, and it is distribution-free.
 *
 * <h2>Why the gate is one-sided</h2>
 * {@code mannWhitneyUTest} returns a two-sided p-value: it is small when the distributions differ in
 * <i>either</i> direction. Blocking on {@code p < alpha} alone would block a candidate that is
 * significantly <b>faster</b> than baseline, which is absurd. So the gate blocks only when the
 * difference is both statistically significant <i>and</i> in the worse direction, judged by comparing
 * medians (a rank statistic, consistent with the test itself, unlike the mean).
 *
 * <h2>Why it abstains</h2>
 * With fewer than ~30 observations per side the test has almost no power; the honest answer is "we do
 * not know yet", not "looks fine". Abstaining returns {@code passed = true} but flags
 * {@code abstained = true}, and the caller records that in the audit trail so nobody later mistakes an
 * uninformed advance for a validated one.
 */
@Service
public class CanaryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CanaryAnalysisService.class);

    private static final String BASELINE = "baseline";
    private static final String CANDIDATE = "candidate";

    private final MetricWindowService windowService;
    private final CadenceProperties properties;
    private final MannWhitneyUTest test = new MannWhitneyUTest();

    public CanaryAnalysisService(MetricWindowService windowService, CadenceProperties properties) {
        this.windowService = windowService;
        this.properties = properties;
    }

    /** Compare the candidate's latency distribution against the baseline's over the configured window. */
    public CanaryResult analyse(FeatureFlag flag) {
        CadenceProperties.Canary cfg = properties.getCanary();
        if (!cfg.isEnabled()) {
            return CanaryResult.abstain(0, 0, "Canary analysis is disabled");
        }

        WindowType window = cfg.getWindow();
        int cap = cfg.getMaxSamples();

        List<Double> candidate = windowService.latencySamples(flag.getKey(), CANDIDATE, window, cap);
        List<Double> baseline = windowService.latencySamples(flag.getKey(), BASELINE, window, cap);

        if (candidate.size() < cfg.getMinSamples() || baseline.size() < cfg.getMinSamples()) {
            return CanaryResult.abstain(candidate.size(), baseline.size(),
                    "Insufficient samples for a meaningful test (need %d per side, have candidate=%d baseline=%d)"
                            .formatted(cfg.getMinSamples(), candidate.size(), baseline.size()));
        }

        double[] c = toArray(candidate);
        double[] b = toArray(baseline);

        double u = test.mannWhitneyU(c, b);
        double p = test.mannWhitneyUTest(c, b);

        // The samples come back from Redis in ascending score order, so the median is a direct index.
        double candidateMedian = median(candidate);
        double baselineMedian = median(baseline);

        boolean significant = p < cfg.getAlpha();
        boolean worse = Direction.LOWER_IS_BETTER.isWorse(candidateMedian, baselineMedian);
        boolean passed = !(significant && worse);

        String reason = passed
                ? significant
                    ? "Candidate differs significantly (p=%.4f) but is faster: median %.1fms vs %.1fms"
                        .formatted(p, candidateMedian, baselineMedian)
                    : "No significant latency difference (p=%.4f >= alpha=%.2f)".formatted(p, cfg.getAlpha())
                : "Candidate is significantly slower (p=%.4f < alpha=%.2f): median %.1fms vs baseline %.1fms"
                        .formatted(p, cfg.getAlpha(), candidateMedian, baselineMedian);

        log.info("Canary '{}': passed={} U={} p={} candidate(n={},med={}) baseline(n={},med={})",
                flag.getKey(), passed, u, p, c.length, candidateMedian, b.length, baselineMedian);

        return new CanaryResult(passed, false, u, p, c.length, b.length, candidateMedian, baselineMedian, reason);
    }

    private static double[] toArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /** @param ascending values already sorted ascending (Redis ZSET rank order) */
    private static double median(List<Double> ascending) {
        int n = ascending.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1
                ? ascending.get(n / 2)
                : (ascending.get(n / 2 - 1) + ascending.get(n / 2)) / 2.0;
    }
}
