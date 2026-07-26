package com.cadence.flagservice.alert;

import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.metrics.model.WindowStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Posts a Slack message when the platform withdraws or freezes a candidate on its own.
 *
 * <p>Two rules this class exists to enforce:
 *
 * <p><b>The webhook URL is never in the source tree.</b> It arrives through
 * {@code cadence.alerting.slack-webhook-url}, which is bound from an environment variable. A Slack
 * webhook is a bearer credential — anyone holding it can post to the channel — and a hard-coded one
 * in a public repository is a credential leak, not a convenience.
 *
 * <p><b>An alert can never abort a rollback.</b> Every send is wrapped and time-boxed. If Slack is
 * down, or the URL is wrong, or the channel was archived, the rollback still happens and the failure
 * is a log line. Notifying humans is strictly less urgent than pulling the bad candidate out of
 * production.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final CadenceProperties properties;

    public AlertService(WebClient cadenceWebClient, CadenceProperties properties) {
        this.webClient = cadenceWebClient;
        this.properties = properties;
    }

    /** Fired by the rollback watcher after an automatic withdrawal. */
    public void automaticRollback(FeatureFlag flag,
                                  int previousPercentage,
                                  List<String> breachingMetrics,
                                  WindowStats candidate,
                                  WindowStats lastStableBaseline) {
        String text = """
                :rotating_light: *Automatic rollback* — `%s`
                Withdrawn from *%d%%* to *0%%* in `%s`.

                *Breached:* %s

                *Candidate now:* error rate %.2f%%, p95 %.0fms, p99 %.0fms (n=%d)
                *Baseline:* error rate %.2f%%, p95 %.0fms, p99 %.0fms (n=%d)
                """.formatted(
                flag.getKey(),
                previousPercentage,
                flag.getEnvironment(),
                String.join(", ", breachingMetrics),
                candidate.errorRate() * 100, candidate.p95LatencyMs(), candidate.p99LatencyMs(), candidate.sampleCount(),
                lastStableBaseline.errorRate() * 100, lastStableBaseline.p95LatencyMs(),
                lastStableBaseline.p99LatencyMs(), lastStableBaseline.sampleCount());

        send(text);
    }

    /** Fired by the stage scheduler when the canary gate blocks an advance. */
    public void canaryBlocked(FeatureFlag flag, String reason) {
        send(":warning: *Rollout paused by canary gate* — `%s` held at *%d%%*.\n%s"
                .formatted(flag.getKey(), flag.getRolloutPercentage(), reason));
    }

    public void rolloutCompleted(FeatureFlag flag) {
        send(":white_check_mark: `%s` reached *100%%* in `%s`."
                .formatted(flag.getKey(), flag.getEnvironment()));
    }

    private void send(String text) {
        CadenceProperties.Alerting cfg = properties.getAlerting();
        if (!cfg.isEnabled() || cfg.getSlackWebhookUrl() == null || cfg.getSlackWebhookUrl().isBlank()) {
            log.info("[alert suppressed, alerting disabled] {}", text.replace('\n', ' '));
            return;
        }
        try {
            webClient.post()
                    .uri(cfg.getSlackWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity()
                    .block(SEND_TIMEOUT);
        } catch (Exception e) {
            log.warn("Slack alert failed (the rollout action itself already succeeded): {}", e.getMessage());
        }
    }

    /** Rendered into the alert body: which metrics breached, with observed vs threshold. */
    public static String describeBreaches(Map<String, double[]> observedVsThreshold) {
        return observedVsThreshold.entrySet().stream()
                .map(e -> "%s = %.4f (limit %.4f)".formatted(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .collect(Collectors.joining("; "));
    }
}
