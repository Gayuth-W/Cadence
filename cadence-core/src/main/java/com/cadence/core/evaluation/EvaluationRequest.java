package com.cadence.core.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Body of the server-side {@code POST /sdk/v1/evaluate} endpoint, for callers that cannot run the
 * Java SDK (a Python service, a curl in the demo script, another language entirely). Java callers
 * should prefer the SDK's local evaluation, which costs a Caffeine lookup instead of a network hop.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationRequest(
        String flagKey,
        String userId,
        String country,
        List<String> segments,
        Map<String, String> attributes
) {
    public EvaluationRequest {
        segments = segments == null ? List.of() : List.copyOf(segments);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
