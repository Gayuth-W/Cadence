package com.cadence.flagservice.security.dto;

import com.cadence.flagservice.security.domain.ApiKeyScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateApiKeyRequest(
        @NotBlank String name,
        @NotBlank String environment,
        @NotEmpty Set<ApiKeyScope> scopes
) {
}
