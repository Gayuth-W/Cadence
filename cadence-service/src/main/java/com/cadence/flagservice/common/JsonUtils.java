package com.cadence.flagservice.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Serialises audit before/after snapshots without letting a serialisation hiccup abort a mutation. */
@Component
public class JsonUtils {

    private final ObjectMapper mapper;

    public JsonUtils(@Qualifier("internalObjectMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // An unserialisable audit payload must not roll back the operation being audited.
            return "{\"_serializationError\":\"" + e.getOriginalMessage() + "\"}";
        }
    }
}
