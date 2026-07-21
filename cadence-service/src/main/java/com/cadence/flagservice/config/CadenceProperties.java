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

    public static class Rollback {
        private boolean enabled = true;
        /** How often the watcher evaluates every active flag. */
        private Duration checkInterval = Duration.ofSeconds(30);
        /** Default breach count when a flag does not override it. */
        private int consecutiveBreaches = 50;
        private int minSamples = 20;
        private Duration cooldown = Duration.ofMinutes(10);

        /**
         * The trigger stamped onto a new flag that does not declare one of its own.
         *
         * <p>
         * The watcher always reads the <i>flag's</i> trigger, never these properties
         * directly — a
         * rollout's safety envelope belongs to the rollout, not to whatever the control
         * plane happened
         * to be configured with when the watcher woke up. These values are therefore
         * defaults at
         * creation time, and changing them never silently retunes a flag that is
         * already in flight.
         */
        public com.cadence.core.metrics.RollbackTrigger defaultTrigger() {
            return new com.cadence.core.metrics.RollbackTrigger(
                    enabled, consecutiveBreaches, minSamples, (int) Math.max(1, cooldown.toMinutes()));
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(Duration checkInterval) {
            this.checkInterval = checkInterval;
        }

        public int getConsecutiveBreaches() {
            return consecutiveBreaches;
        }

        public void setConsecutiveBreaches(int v) {
            this.consecutiveBreaches = v;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int v) {
            this.minSamples = v;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }
    }
}
