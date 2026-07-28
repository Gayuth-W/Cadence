package com.cadence.flagservice.security.dto;

import java.time.Instant;
import java.util.Set;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        String username,
        Set<String> roles
) {
}
