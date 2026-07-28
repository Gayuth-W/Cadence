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

    public static class Canary {
        private boolean enabled = true;
        /**
         * Significance level. p below this, in the worse direction, blocks the advance.
         */
        private double alpha = 0.05;
        /**
         * Below this many samples per side, the test has no power and the gate
         * abstains.
         */
        private int minSamples = 30;
        private WindowType window = WindowType.LAST_1H;
        /**
         * Cap on samples pulled per side; Mann-Whitney is O(n log n) and 5k per side is
         * ample.
         */
        private int maxSamples = 5_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getAlpha() {
            return alpha;
        }

        public void setAlpha(double alpha) {
            this.alpha = alpha;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public WindowType getWindow() {
            return window;
        }

        public void setWindow(WindowType window) {
            this.window = window;
        }

        public int getMaxSamples() {
            return maxSamples;
        }

        public void setMaxSamples(int maxSamples) {
            this.maxSamples = maxSamples;
        }
    }

    public static class Metrics {
        /** How often a MetricSnapshot row is written for historical charting. */
        private Duration snapshotInterval = Duration.ofMinutes(5);
        /**
         * Hard cap on events retained per rolling window, protecting Redis memory under
         * a traffic spike.
         */
        private int maxWindowSize = 50_000;
        /** Concurrency ceiling for the virtual-thread ingestion executor. */
        private int ingestionConcurrency = 512;

        public Duration getSnapshotInterval() {
            return snapshotInterval;
        }

        public void setSnapshotInterval(Duration v) {
            this.snapshotInterval = v;
        }

        public int getMaxWindowSize() {
            return maxWindowSize;
        }

        public void setMaxWindowSize(int v) {
            this.maxWindowSize = v;
        }

        public int getIngestionConcurrency() {
            return ingestionConcurrency;
        }

        public void setIngestionConcurrency(int v) {
            this.ingestionConcurrency = v;
        }
    }

    public static class Alerting {
        private boolean enabled = false;
        /** Slack incoming-webhook URL. Read from the environment; never committed. */
        private String slackWebhookUrl;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSlackWebhookUrl() {
            return slackWebhookUrl;
        }

        public void setSlackWebhookUrl(String v) {
            this.slackWebhookUrl = v;
        }
    }

    public static class ExternalMetrics {
        /**
         * When enabled, release health can additionally be pulled from Prometheus via
         * WebClient.
         */
        private boolean enabled = false;
        private String prometheusBaseUrl = "http://localhost:9090";
        private Duration timeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrometheusBaseUrl() {
            return prometheusBaseUrl;
        }

        public void setPrometheusBaseUrl(String v) {
            this.prometheusBaseUrl = v;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Rollback getRollback() {
        return rollback;
    }

    public Canary getCanary() {
        return canary;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Alerting getAlerting() {
        return alerting;
    }

    public ExternalMetrics getExternalMetrics() {
        return externalMetrics;
    }
}
