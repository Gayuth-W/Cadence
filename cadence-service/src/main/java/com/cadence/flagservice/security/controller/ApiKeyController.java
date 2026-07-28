package com.cadence.flagservice.security.controller;

import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.security.service.ApiKeyService;
import com.cadence.flagservice.security.dto.ApiKeyResponse;
import com.cadence.flagservice.security.dto.CreateApiKeyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Issue and revoke the service keys the SDK authenticates with. ADMIN only. */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final CadenceProperties properties;

    public ApiKeyController(ApiKeyService apiKeyService, CadenceProperties properties) {
        this.apiKeyService = apiKeyService;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Mint a service key. The plaintext is returned once and never again.")
    public ApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request,
                                 Authentication authentication) {
        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                request.name(), request.environment(), request.scopes(), authentication.getName());
        return ApiKeyResponse.of(issued.record(), issued.plaintext());
    }

    @GetMapping
    public List<ApiKeyResponse> list(@RequestParam(required = false) String environment) {
        String env = environment == null ? properties.getEnvironment() : environment;
        return apiKeyService.list(env).stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a key. The record is retained so the audit trail stays resolvable.")
    public void revoke(@PathVariable UUID id) {
        apiKeyService.revoke(id);
    }
}
