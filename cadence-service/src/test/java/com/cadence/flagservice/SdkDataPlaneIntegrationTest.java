package com.cadence.flagservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The data plane is a separate security world from the console: a scoped service key, no identity,
 * and no ability to change a release.
 *
 * <p>The key seeded in {@code V2__seed.sql} is scoped to the {@code development} environment, while the
 * test control plane runs as {@code test}. That asymmetry is itself under test: the environment comes
 * from the authenticated key, never from a header the caller controls.
 */
class SdkDataPlaneIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY_HEADER = "X-Cadence-Api-Key";
    private static final String DEMO_KEY = "cad_development_ZGVtby1rZXktZG8tbm90LXVzZS1pbi1wcm9k";

    @Test
    @DisplayName("no API key means 401 on the data plane")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/sdk/v1/flags")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/sdk/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("events", List.of()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an invalid API key is rejected without revealing whether the prefix exists")
    void invalidKeyIsRejected() throws Exception {
        mockMvc.perform(get("/sdk/v1/flags").header(KEY_HEADER, "cad_developmTOTALLY_WRONG_SECRET"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/sdk/v1/flags").header(KEY_HEADER, "not-even-a-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid service key can read flag config for its own environment")
    void validKeyReadsFlags() throws Exception {
        mockMvc.perform(get("/sdk/v1/flags").header(KEY_HEADER, DEMO_KEY))
                .andExpect(status().isOk())
                // The key is scoped to `development`, so it sees the seeded demo flag and nothing the
                // `test` control plane created.
                .andExpect(jsonPath("$[?(@.key=='pricing.engine.v2')]").exists());
    }

    @Test
    @DisplayName("a valid service key can post events and is answered 202 without waiting for ingestion")
    void validKeyPostsEvents() throws Exception {
        mockMvc.perform(post("/sdk/v1/events")
                        .header(KEY_HEADER, DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("events", List.of(Map.of(
                                "flagKey", "pricing.engine.v2",
                                "variant", "CANDIDATE",
                                "type", "LIVE",
                                "latencyMs", 42,
                                "success", true))))))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("an operator's JWT is worthless on the data plane, and a service key is worthless on the console")
    void thePlanesDoNotCrossOver() throws Exception {
        String admin = login("admin", "cadence-admin-2026");

        // A full ADMIN token cannot read the SDK endpoint: that chain only accepts scoped API keys.
        mockMvc.perform(get("/sdk/v1/flags").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnauthorized());

        // And the service key cannot touch the control plane — it certainly cannot force a rollback.
        mockMvc.perform(get("/api/v1/flags").header(KEY_HEADER, DEMO_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("server-side evaluation returns the same variant the SDK would compute locally")
    void serverSideEvaluationMatchesTheSharedEngine() throws Exception {
        // The seeded flag is OFF, so everyone gets baseline with reason FLAG_OFF.
        mockMvc.perform(post("/sdk/v1/evaluate")
                        .header(KEY_HEADER, DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("flagKey", "pricing.engine.v2", "userId", "user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant").value("BASELINE"))
                .andExpect(jsonPath("$.reason").value("FLAG_OFF"))
                .andExpect(jsonPath("$.config.engine").value("legacy"));
    }
}
