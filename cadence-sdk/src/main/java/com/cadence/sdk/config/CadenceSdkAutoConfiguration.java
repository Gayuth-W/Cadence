package com.cadence.sdk.config;

import com.cadence.sdk.FlagClient;
import com.cadence.sdk.internal.EventReporter;
import com.cadence.sdk.internal.FlagApiClient;
import com.cadence.sdk.internal.FlagConfigCache;
import com.cadence.sdk.internal.ShadowExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * Makes the SDK a genuine drop-in: add the dependency, set two properties,
 * inject {@link FlagClient}.
 * Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>
 * The circuit breaker is built programmatically rather than with
 * {@code @CircuitBreaker}. A library
 * that relies on annotation-driven AOP forces its AOP configuration onto every
 * consumer and silently
 * does nothing if the consumer's proxying is set up differently. Decorating the
 * supplier directly
 * works identically in a Spring app, in a plain unit test, and inside a virtual
 * thread.
 */
@AutoConfiguration
@EnableConfigurationProperties(CadenceSdkProperties.class)
@ConditionalOnProperty(prefix = "cadence.sdk", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class CadenceSdkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CadenceSdkAutoConfiguration.class);
    public static final String BREAKER_NAME = "cadence-flag-service";

    @Bean
    @ConditionalOnMissingBean
    public FlagApiClient cadenceFlagApiClient(CadenceSdkProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("cadence.sdk.api-key is not set. The control plane will reject config fetches "
                    + "and every flag will degrade to baseline.");
        }
        return new FlagApiClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "cadenceCircuitBreaker")
    public CircuitBreaker cadenceCircuitBreaker(CadenceSdkProperties properties) {
        CadenceSdkProperties.CircuitBreaker cfg = properties.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.getFailureRateThreshold())
                .slidingWindowSize(cfg.getSlidingWindowSize())
                .waitDurationInOpenState(cfg.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cfg.getPermittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker(BREAKER_NAME);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlagConfigCache cadenceFlagConfigCache(FlagApiClient apiClient,
            CircuitBreaker breaker,
            CadenceSdkProperties properties) {
        return new FlagConfigCache(apiClient, breaker, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlagClient flagClient(FlagConfigCache cache,
            EventReporter reporter,
            ShadowExecutor shadowExecutor,
            CadenceSdkProperties properties) {
        return new FlagClient(cache, reporter, shadowExecutor, properties.isEnabled());
    }
}
