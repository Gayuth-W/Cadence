package com.cadence.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One clause of a flag's targeting policy. Rules are stored as JSON on the flag entity and
 * evaluated in ascending {@code priority} order; the first match wins and short-circuits
 * percentage bucketing entirely.
 *
 * <p>Examples:
 * <pre>
 *   {"priority":1,"type":"ALLOWLIST","values":["user-42"],"variant":"CANDIDATE"}
 *   {"priority":2,"type":"SEGMENT","values":["internal"],"variant":"CANDIDATE"}
 *   {"priority":3,"type":"COUNTRY","values":["LK","SG"],"variant":"CANDIDATE"}
 *   {"priority":4,"type":"ATTRIBUTE","attribute":"tenant","operator":"IN","values":["acme"],"variant":"CANDIDATE"}
 * </pre>
 *
 * @param priority lower runs first
 * @param type     which dimension to match on
 * @param attribute attribute key, only meaningful for {@link RuleType#ATTRIBUTE}
 * @param operator comparison for ATTRIBUTE rules; defaults to {@link Operator#IN}
 * @param values   the values to match against (user IDs, segments, country codes, ...)
 * @param variant  the variant to pin when this rule matches
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TargetingRule(
        int priority,
        RuleType type,
        String attribute,
        Operator operator,
        List<String> values,
        VariantName variant
) {
    public TargetingRule {
        values = values == null ? List.of() : List.copyOf(values);
        operator = operator == null ? Operator.IN : operator;
        variant = variant == null ? VariantName.CANDIDATE : variant;
    }

    public static TargetingRule allowlist(int priority, List<String> userIds) {
        return new TargetingRule(priority, RuleType.ALLOWLIST, null, Operator.IN, userIds, VariantName.CANDIDATE);
    }

    public static TargetingRule blocklist(int priority, List<String> userIds) {
        return new TargetingRule(priority, RuleType.BLOCKLIST, null, Operator.IN, userIds, VariantName.BASELINE);
    }

    public static TargetingRule segment(int priority, List<String> segments, VariantName variant) {
        return new TargetingRule(priority, RuleType.SEGMENT, null, Operator.IN, segments, variant);
    }

    public static TargetingRule country(int priority, List<String> isoCodes, VariantName variant) {
        return new TargetingRule(priority, RuleType.COUNTRY, null, Operator.IN, isoCodes, variant);
    }

    public static TargetingRule attribute(int priority, String key, Operator op, List<String> values, VariantName variant) {
        return new TargetingRule(priority, RuleType.ATTRIBUTE, key, op, values, variant);
    }
}
