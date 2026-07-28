package com.cadence.flagservice.common;

import java.time.Instant;
import java.util.Map;

/** RFC-7807-flavoured error body. Deliberately leaks nothing about internals. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, Map.of());
    }
}
