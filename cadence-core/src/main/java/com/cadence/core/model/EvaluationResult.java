package com.cadence.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The answer to "which variant does this user get, and why".
 *
 * @param flagKey  the flag that was evaluated
 * @param variant  the variant to serve
 * @param config   the variant's configuration payload (arbitrary JSON supplied by whoever created the flag)
 * @param reason   why this variant was chosen; useful in logs and in post-mortem of a bad rollout
 * @param bucket   the user's stable bucket in [0, 10000), or -1 when bucketing was bypassed by a rule
 * @param shadow   true when the flag is in SHADOW state and the caller should also execute the candidate path
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationResult(
        String flagKey,
        VariantName variant,
        Map<String, Object> config,
        EvaluationReason reason,
        int bucket,
        boolean shadow
) {
    public EvaluationResult {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean isCandidate() {
        return variant == VariantName.CANDIDATE;
    }

    /** Read a typed value out of the variant config, falling back when absent or of the wrong type. */
    @SuppressWarnings("unchecked")
    public <T> T configValue(String key, T fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    public static EvaluationResult baseline(String flagKey, Map<String, Object> config, EvaluationReason reason) {
        return new EvaluationResult(flagKey, VariantName.BASELINE, config, reason, -1, false);
    }
}
