package com.cadence.core.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The payload of {@code POST /sdk/v1/events}. Events are batched by the SDK rather than sent
 * one-per-request: at the volumes this pipeline is built for, per-event HTTP round trips would cost
 * far more than the ingestion work itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventBatch(List<OutcomeEvent> events) {

    public EventBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
