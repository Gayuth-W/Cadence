package com.cadence.flagservice.metrics.external;

import com.cadence.flagservice.config.CadenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional secondary source of release health: an instant query against an existing Prometheus.
 *
 * <p>The SDK's own outcome events are the primary signal because they are the only thing that knows
 * <i>which variant</i> served a given request. Prometheus rarely does. But a team that already exports
 * a meaningful SLI (checkout success rate, queue depth, saturation) should be able to gate a rollout on
 * it without re-instrumenting, so this client lets a custom metric be pulled rather than pushed.
 *
 * <p>Disabled by default. WebClient, not RestClient, because this runs from the scheduler and a slow
 * Prometheus must not hold a thread while the watcher's other flags wait.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.external-metrics", name = "enabled", havingValue = "true")
public class PrometheusMetricClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricClient.class);

    private final WebClient webClient;
    private final CadenceProperties properties;

    public PrometheusMetricClient(WebClient cadenceWebClient, CadenceProperties properties) {
        this.webClient = cadenceWebClient;
        this.properties = properties;
    }

    /**
     * Run an instant PromQL query and return the first scalar sample.
     *
     * @return empty when Prometheus is unreachable, the query matched nothing, or the result is not a
     *         scalar. Empty always means "unknown" and never contributes to a rollback decision.
     */
    @SuppressWarnings("unchecked")
    public Optional<Double> instantQuery(String promQl) {
        CadenceProperties.ExternalMetrics cfg = properties.getExternalMetrics();
        String uri = UriComponentsBuilder.fromHttpUrl(cfg.getPrometheusBaseUrl())
                .path("/api/v1/query")
                .queryParam("query", promQl)
                .build()
                .toUriString();
        try {
            Map<String, Object> body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(cfg.getTimeout());

            if (body == null || !"success".equals(body.get("status"))) {
                return Optional.empty();
            }
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
            if (results == null || results.isEmpty()) {
                return Optional.empty();
            }
            // Prometheus returns value as [ <unixTime>, "<sampleValue>" ].
            List<Object> value = (List<Object>) results.get(0).get("value");
            return Optional.of(Double.parseDouble(String.valueOf(value.get(1))));
        } catch (Exception e) {
            log.warn("Prometheus query failed ({}), treating as unknown: {}", promQl, e.getMessage());
            return Optional.empty();
        }
    }
}
