package com.cadence.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything a consuming application configures under {@code cadence.sdk.*}.
 *
 * <pre>
 * cadence:
 *   sdk:
 *     enabled: true
 *     base-url: http://flag-service:8080
 *     api-key: cad_live_xxxxxxxx      # per-environment service key, NOT a user JWT
 *     environment: production
 * </pre>
 */
@ConfigurationProperties(prefix = "cadence.sdk")
public class CadenceSdkProperties {

    /**
     * Master switch. When false, every evaluate() returns baseline and no HTTP
     * calls are made.
     */
    private boolean enabled = true;

    /** Base URL of the Cadence control plane, without a trailing slash. */
    private String baseUrl = "http://localhost:8080";

    /**
     * The per-environment service API key, scoped to {@code flags:read} +
     * {@code events:write}.
     * This is machine-to-machine credential material and is never a user JWT — the
     * SDK has no
     * business holding a human's identity.
     */
    private String apiKey;

    /** Logical environment name, echoed on outcome events. */
    private String environment = "default";

    /**
     * How often the local flag-config cache is refreshed from the control plane.
     */
    private Duration refreshInterval = Duration.ofSeconds(15);

    /**
     * HTTP connect/read timeout for config fetches and event flushes. Kept short:
     * this is off the request path.
     */
    private Duration requestTimeout = Duration.ofSeconds(3);

    private final Events events = new Events();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public static class Events {
        /**
         * Turn off to evaluate flags without reporting any release-health data.
         * Rollback automation then has no input.
         */
        private boolean enabled = true;
        /** Events are flushed when the buffer reaches this size... */
        private int batchSize = 100;
        /** ...or when this interval elapses, whichever comes first. */
        private Duration flushInterval = Duration.ofSeconds(2);
        /**
         * Hard cap on the in-memory buffer. When the control plane is down and the
         * buffer fills,
         * the oldest events are dropped rather than growing the heap without bound.
         * Losing telemetry
         * is acceptable; OOM-killing the caller is not.
         */
        private int maxBufferSize = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getFlushInterval() {
            return flushInterval;
        }

        public void setFlushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
        }

        public int getMaxBufferSize() {
            return maxBufferSize;
        }

        public void setMaxBufferSize(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
        }
    }

    public static class CircuitBreaker {
        /** Percentage of failed config fetches that opens the breaker. */
        private float failureRateThreshold = 50f;
        /** Calls sampled before the rate is evaluated. */
        private int slidingWindowSize = 10;
        /** How long the breaker stays open before probing the control plane again. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        private int permittedCallsInHalfOpenState = 3;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float v) {
            this.failureRateThreshold = v;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int v) {
            this.slidingWindowSize = v;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration v) {
            this.waitDurationInOpenState = v;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int v) {
            this.permittedCallsInHalfOpenState = v;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Events getEvents() {
        return events;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
