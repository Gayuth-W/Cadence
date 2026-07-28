package com.cadence.flagservice;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres, real Redis. Nothing here is mocked.
 *
 * <p>A rollback decision is the product of Redis sorted-set semantics, Postgres optimistic locking and
 * Flyway's schema constraints acting together. An in-memory H2 plus a mocked Redis would happily pass a
 * test suite for a platform that could never work: H2 has no {@code jsonb}, and a mocked ZSET cannot
 * reproduce the rank-vs-score distinction the percentile code depends on.
 *
 * <p>Containers are static, so one Postgres and one Redis are shared across every test class in the
 * module rather than being started per class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ------------------------------------------------------------------
    // Helpers. These drive the real HTTP surface rather than calling services directly, because the
    // security filters, the @PreAuthorize checks and the audit actor resolution are all part of what
    // is under test, and none of them run when a service bean is invoked from a test method.
    // ------------------------------------------------------------------

    /** Authenticate against the seeded accounts and return the bearer token. */
    protected String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    /** Create a flag with an error-rate ceiling of 2% and a P95 ceiling of 400ms. Returns its id. */
    protected String createFlag(String adminToken, String key) throws Exception {
        return createFlag(adminToken, key, 0.02, 400.0, 3, 5);
    }

    protected String createFlag(String adminToken, String key, double maxErrorRate, double maxP95Ms,
                                int consecutiveBreaches, int minSamples) throws Exception {
        var healthMetrics = java.util.List.of(
                java.util.Map.of("name", "error_rate", "kind", "ERROR_RATE",
                        "direction", "LOWER_IS_BETTER", "threshold", maxErrorRate,
                        "window", "LAST_100", "minSamples", minSamples),
                java.util.Map.of("name", "p95_latency", "kind", "LATENCY_P95",
                        "direction", "LOWER_IS_BETTER", "threshold", maxP95Ms,
                        "window", "LAST_100", "minSamples", minSamples));

        var request = java.util.Map.of(
                "key", key,
                "description", "integration test flag",
                "baselineConfig", java.util.Map.of("engine", "legacy"),
                "candidateConfig", java.util.Map.of("engine", "v2"),
                "healthMetrics", healthMetrics,
                "rollbackTrigger", java.util.Map.of(
                        "autoRollbackEnabled", true,
                        "consecutiveBreaches", consecutiveBreaches,
                        "minSamples", minSamples,
                        "cooldownMinutes", 10));

        String body = mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    protected void rollout(String token, String flagId, int percentage) throws Exception {
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("percentage", percentage, "reason", "test rollout"))))
                .andExpect(status().isOk());
    }

    /**
     * A well-formed HS256 JWT signed with a key the service does not know. Proves the filter verifies
     * the signature rather than merely parsing the claims — the difference between authentication and
     * decoration.
     */
    protected String tokenSignedWithWrongKey() {
        javax.crypto.SecretKey wrongKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "an-entirely-different-secret-key-of-sufficient-length".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
                .subject("admin")
                .issuer("cadence")
                .claim("roles", java.util.List.of("ADMIN"))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                .signWith(wrongKey)
                .compact();
    }

    @BeforeEach
    void ensureContainersRunning() {
        org.junit.jupiter.api.Assertions.assertTrue(POSTGRES.isRunning());
        org.junit.jupiter.api.Assertions.assertTrue(REDIS.isRunning());
    }
}
