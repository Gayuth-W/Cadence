package com.cadence.flagservice.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * WebClient is used for the two outbound integrations that must never block a scheduler thread:
 * pulling metrics from an external observability API, and posting Slack alerts during a rollback.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient cadenceWebClient(CadenceProperties properties) {
        int timeoutMs = (int) properties.getExternalMetrics().getTimeout().toMillis();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
