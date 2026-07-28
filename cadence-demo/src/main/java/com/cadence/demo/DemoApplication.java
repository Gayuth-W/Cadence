package com.cadence.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small pricing service that consumes the Cadence SDK exactly the way a real application would.
 *
 * <p>There is no Cadence configuration class here, no bean wiring, no metrics plumbing. The SDK's
 * auto-configuration supplies {@code FlagClient}; two properties in {@code application.yml} point it
 * at the control plane. That is the entire integration.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
