package com.cadence.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;

/**
 * The full targeting policy for a flag: an ordered set of rules serialised as a single
 * {@code jsonb} column. Kept as its own type (rather than a bare list) so the JSON shape
 * stays {@code {"rules":[...]}} and remains extensible without a schema migration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TargetingRules(List<TargetingRule> rules) {

    private static final TargetingRules EMPTY = new TargetingRules(List.of());

    public TargetingRules {
        rules = rules == null
                ? List.of()
                : rules.stream().sorted(Comparator.comparingInt(TargetingRule::priority)).toList();
    }

    public static TargetingRules empty() {
        return EMPTY;
    }

    public static TargetingRules of(TargetingRule... rules) {
        return new TargetingRules(List.of(rules));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }
}
