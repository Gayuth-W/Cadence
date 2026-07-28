package com.cadence.flagservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Redis carries the rolling metric windows (sorted sets), the rollback breach counters, and the
 * anti-flap cooldown locks. It is deliberately not the source of truth for anything: losing Redis
 * costs the platform its in-flight windows, not its flags, users or audit trail.
 */
@Configuration
public class RedisConfig {

    /**
     * Reactive template for the sorted-set window operations. Lettuce is non-blocking, so a single
     * connection multiplexes the fan-out of a whole event batch without a connection per event.
     */
    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
