package com.cadence.flagservice.security.dto;

import com.cadence.flagservice.security.domain.ApiKey;
import com.cadence.flagservice.security.domain.ApiKeyScope;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param plaintextKey populated only in the response to the creation call, null everywhere else.
 *                     The platform cannot show it again because it does not store it.
 */
public record ApiKeyResponse(
        UUID id,
        String name,
        String environment,
        String keyPrefix,
        Set<ApiKeyScope> scopes,
        boolean active,
        String createdBy,
        Instant createdAt,
        Instant lastUsedAt,
        String plaintextKey
) {
    public static ApiKeyResponse from(ApiKey key) {
        return of(key, null);
    }

    public static ApiKeyResponse of(ApiKey key, String plaintextKey) {
        return new ApiKeyResponse(key.getId(), key.getName(), key.getEnvironment(), key.getKeyPrefix(),
                key.getScopes(), key.isActive(), key.getCreatedBy(), key.getCreatedAt(),
                key.getLastUsedAt(), plaintextKey);
    }
}
