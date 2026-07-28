package com.cadence.core.evaluation;

import com.cadence.core.model.EvaluationReason;
import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.FlagState;
import com.cadence.core.model.Operator;
import com.cadence.core.model.TargetingRule;
import com.cadence.core.model.TargetingRules;
import com.cadence.core.model.UserContext;
import com.cadence.core.model.VariantName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TargetingEngineTest {

    private static FlagDefinition flag(FlagState state, int percentage, TargetingRules rules) {
        return new FlagDefinition(UUID.randomUUID(), "checkout.v2", state, percentage,
                Map.of("v", "old"), Map.of("v", "new"), rules, List.of(), null, 1L, Instant.now());
    }

    @Test
    void offServesBaseline() {
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.OFF, 0, TargetingRules.empty()), UserContext.of("u1"));
        assertThat(result.variant()).isEqualTo(VariantName.BASELINE);
        assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_OFF);
    }

    @Test
    void fullyOnServesCandidateToEveryone() {
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.FULLY_ON, 100, TargetingRules.empty()), UserContext.of("u1"));
        assertThat(result.isCandidate()).isTrue();
    }

    @Test
    void shadowServesBaselineButFlagsTheCandidateForBackgroundExecution() {
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.SHADOW, 0, TargetingRules.empty()), UserContext.of("u1"));
        assertThat(result.variant()).isEqualTo(VariantName.BASELINE);
        assertThat(result.shadow()).isTrue();
        assertThat(result.reason()).isEqualTo(EvaluationReason.SHADOW_BASELINE);
    }

    /**
     * "Rolled back" must mean nobody, including the allowlist. Honouring targeting on a withdrawn
     * candidate would keep serving broken code to precisely the people who complained first.
     */
    @Test
    void rolledBackIgnoresTheAllowlist() {
        TargetingRules rules = TargetingRules.of(TargetingRule.allowlist(1, List.of("vip")));
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.ROLLED_BACK, 0, rules), UserContext.of("vip"));
        assertThat(result.variant()).isEqualTo(VariantName.BASELINE);
        assertThat(result.reason()).isEqualTo(EvaluationReason.ROLLED_BACK);
    }

    @Test
    void targetingRuleBeatsPercentageBucketing() {
        TargetingRules rules = TargetingRules.of(TargetingRule.allowlist(1, List.of("vip")));
        // 0% rollout: bucketing alone could never select the candidate.
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.ROLLING_OUT, 1, rules), UserContext.of("vip"));
        assertThat(result.isCandidate()).isTrue();
        assertThat(result.reason()).isEqualTo(EvaluationReason.TARGETING_MATCH);
    }

    @Test
    void lowerPriorityRuleWinsWhenBothMatch() {
        TargetingRules rules = new TargetingRules(List.of(
                TargetingRule.blocklist(1, List.of("u1")),                          // priority 1 -> BASELINE
                TargetingRule.allowlist(2, List.of("u1"))));                        // priority 2 -> CANDIDATE
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.ROLLING_OUT, 50, rules), UserContext.of("u1"));
        assertThat(result.variant()).isEqualTo(VariantName.BASELINE);
    }

    @Test
    void segmentAndCountryAndAttributeRulesMatch() {
        UserContext ctx = UserContext.builder("u1")
                .country("lk")
                .segment("internal")
                .attribute("tenant", "acme")
                .build();

        assertThat(TargetingEngine.evaluate(flag(FlagState.ROLLING_OUT, 0,
                TargetingRules.of(TargetingRule.segment(1, List.of("internal"), VariantName.CANDIDATE))), ctx)
                .isCandidate()).isTrue();

        assertThat(TargetingEngine.evaluate(flag(FlagState.ROLLING_OUT, 0,
                TargetingRules.of(TargetingRule.country(1, List.of("LK"), VariantName.CANDIDATE))), ctx)
                .isCandidate()).as("country is normalised to upper case by the builder").isTrue();

        assertThat(TargetingEngine.evaluate(flag(FlagState.ROLLING_OUT, 0,
                TargetingRules.of(TargetingRule.attribute(1, "tenant", Operator.IN, List.of("acme"),
                        VariantName.CANDIDATE))), ctx).isCandidate()).isTrue();
    }

    /** A typo in an operator's regex must degrade to "no match", never to a 500 in the caller. */
    @Test
    void malformedRegexDoesNotThrow() {
        TargetingRules rules = TargetingRules.of(TargetingRule.attribute(
                1, "plan", Operator.REGEX, List.of("[unclosed"), VariantName.CANDIDATE));
        UserContext ctx = UserContext.builder("u1").attribute("plan", "pro").build();

        EvaluationResult result = TargetingEngine.evaluate(flag(FlagState.ROLLING_OUT, 0, rules), ctx);
        assertThat(result.variant()).isEqualTo(VariantName.BASELINE);
    }

    @Test
    void percentageBucketingIsReportedWithTheBucket() {
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.ROLLING_OUT, 50, TargetingRules.empty()), UserContext.of("u1"));
        assertThat(result.bucket()).isBetween(0, 9_999);
        assertThat(result.reason()).isIn(EvaluationReason.PERCENTAGE_ROLLOUT, EvaluationReason.PERCENTAGE_EXCLUDED);
    }

    @Test
    void variantConfigTravelsWithTheDecision() {
        EvaluationResult result = TargetingEngine.evaluate(
                flag(FlagState.FULLY_ON, 100, TargetingRules.empty()), UserContext.of("u1"));
        assertThat(result.<String>configValue("v", "?")).isEqualTo("new");
    }
}
