package com.cadence.flagservice;

import com.cadence.flagservice.config.CadenceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cadence — a progressive delivery control plane.
 *
 * <p>The closed loop this service implements:
 * <pre>
 *   staged rollout → real-time release-health metrics → statistical canary gate → automatic rollback
 * </pre>
 * gated by role-based access control and backed by an actor-attributed, immutable audit trail.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(CadenceProperties.class)
public class CadenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CadenceApplication.class, args);
    }
}
