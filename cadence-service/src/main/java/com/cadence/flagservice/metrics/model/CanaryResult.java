package com.cadence.flagservice.metrics.model;

import java.util.Map;

/**
 * The verdict of one Mann-Whitney U comparison of candidate against baseline.
 *
 * @param passed          true when the rollout may advance
 * @param abstained       true when there were too few samples to say anything; the gate then defers
 *                        to the operator rather than guessing
 * @param uStatistic      the U statistic
 * @param pValue          asymptotic two-sided p-value, or NaN when abstaining
 * @param candidateSamples number of candidate latency observations used
 * @param baselineSamples  number of baseline latency observations used
 * @param candidateMedian  median candidate latency, ms
 * @param baselineMedian   median baseline latency, ms
 * @param reason           human-readable explanation, written into the audit record
 */
public record CanaryResult(
        boolean passed,
        boolean abstained,
        double uStatistic,
        double pValue,
        int candidateSamples,
        int baselineSamples,
        double candidateMedian,
        double baselineMedian,
        String reason
) {
    public static CanaryResult abstain(int candidateSamples, int baselineSamples, String reason) {
        return new CanaryResult(true, true, Double.NaN, Double.NaN,
                candidateSamples, baselineSamples, Double.NaN, Double.NaN, reason);
    }

    /** Flattened for the audit record's {@code metadata} column. */
    public Map<String, Object> asEvidence() {
        return Map.of(
                "canaryPassed", passed,
                "canaryAbstained", abstained,
                "uStatistic", Double.isNaN(uStatistic) ? "n/a" : uStatistic,
                "pValue", Double.isNaN(pValue) ? "n/a" : pValue,
                "candidateSamples", candidateSamples,
                "baselineSamples", baselineSamples,
                "candidateMedianMs", Double.isNaN(candidateMedian) ? "n/a" : candidateMedian,
                "baselineMedianMs", Double.isNaN(baselineMedian) ? "n/a" : baselineMedian,
                "reason", reason);
    }
}
