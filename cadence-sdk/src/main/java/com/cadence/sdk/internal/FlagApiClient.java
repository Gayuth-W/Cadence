package com.cadence.sdk.internal;

import com.cadence.core.model.FlagDefinition;
import com.cadence.sdk.config.CadenceSdkProperties;
import com.cadence.sdk.exception.CadenceSdkException;
import com.cadence.core.event.EventBatch;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin HTTP transport to the control plane's data plane ({@code /sdk/v1/**}).
 *
 * <p>
 * Authenticates with the service API key on every call. There is no user
 * identity here by design:
 * the SDK reads flag config and writes events, and can do nothing else even if
 * the key leaks into a
 * heap dump — it cannot create a flag, force a rollback, or read the audit log.
 */
public class FlagApiClient {

    private static final String API_KEY_HEADER = "X-Cadence-Api-Key";

    private final RestClient restClient;

    public FlagApiClient(CadenceSdkProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getRequestTimeout().toMillis());
        factory.setReadTimeout((int) properties.getRequestTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(API_KEY_HEADER, properties.getApiKey() == null ? "" : properties.getApiKey())
                .defaultHeader("X-Cadence-Environment", properties.getEnvironment())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Full flag config for this environment. Called on a schedule, never on the
     * request path.
     */
    public List<FlagDefinition> fetchAllFlags() {
        try {
            List<FlagDefinition> flags = restClient.get()
                    .uri("/sdk/v1/flags")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<FlagDefinition>>() {
                    });
            return flags == null ? List.of() : flags;
        } catch (Exception e) {
            // Rethrown so the circuit breaker upstream records the failure. The caller
            // (FlagConfigCache) catches it and keeps serving the last known-good snapshot.
            throw new CadenceSdkException("Failed to fetch flag configuration from control plane", e);
        }
    }

    /**
     * Fire-and-forget: the caller has already returned its response to the user.
     */
    public void publishEvents(EventBatch batch) {
        restClient.post()
                .uri("/sdk/v1/events")
                .body(batch)
                .retrieve()
                .toBodilessEntity();
    }
}
