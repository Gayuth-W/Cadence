package com.cadence.flagservice.config;

import com.cadence.core.metrics.WindowType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Every knob of the control plane, bound from {@code cadence.*}. */
@ConfigurationProperties(prefix = "cadence")
public class CadenceProperties {

    /**
     * Logical environment this control plane serves. Flags and API keys are scoped
     * to it.
     */
    private String environment = "default";

    private final Jwt jwt = new Jwt();
    private final Rollback rollback = new Rollback();
    private final Canary canary = new Canary();
    private final Metrics metrics = new Metrics();
    private final Alerting alerting = new Alerting();
    private final ExternalMetrics externalMetrics = new ExternalMetrics();

    public static class Jwt {
        /**
         * HMAC-SHA256 signing secret. Must be at least 32 bytes. There is no default: a
         * flag control
         * plane that ships with a hard-coded signing key is a control plane anyone can
         * forge an
         * ADMIN token for, so the application refuses to start without one.
         */
        private String secret;
        private Duration expiration = Duration.ofHours(8);
        private String issuer = "cadence";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getExpiration() {
            return expiration;
        }

        public void setExpiration(Duration expiration) {
            this.expiration = expiration;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }
}
