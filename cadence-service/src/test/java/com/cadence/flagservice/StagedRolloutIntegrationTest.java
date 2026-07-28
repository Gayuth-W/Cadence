package com.cadence.flagservice;

import com.cadence.core.model.FlagState;
import com.cadence.core.model.VariantName;
import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import com.cadence.flagservice.flag.repository.FeatureFlagRepository;
import com.cadence.flagservice.metrics.service.MetricIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staged rollout advances only when the candidate earns it.
 *
 * <p>Driven through HTTP rather than by calling {@code RolloutSchedulerService} directly, because the
 * {@code @PreAuthorize} rules on those methods are part of the behaviour: a VIEWER must not be able to
 * advance a stage, and a service bean invoked from a test method has no security context to check.
 */
class StagedRolloutIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MetricIngestionService ingestionService;
    @Autowired private FeatureFlagRepository flagRepository;
    @Autowired private AuditRecordRepository auditRepository;

    private static final List<Map<String, Object>> STAGES = List.of(
            Map.of("percentage", 5, "durationMinutes", 60),
            Map.of("percentage", 25, "durationMinutes", 60),
            Map.of("percentage", 100, "durationMinutes", 0));

    @Test
    @DisplayName("starting a schedule enters stage one without a canary check: nothing to compare against yet")
    void startEntersFirstStageUngated() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "staged.start.ungated");
        String scheduleId = createSchedule(admin, flagId);

        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/start")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "begin canary"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentPercentage").value(5));

        assertThat(flagRepository.findById(UUID.fromString(flagId)).orElseThrow().getRolloutPercentage())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("a healthy candidate advances through every stage to 100% and the schedule completes")
    void healthyCandidateAdvancesToCompletion() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String key = "staged.healthy.advance";
        String flagId = createFlag(admin, key);
        String scheduleId = createSchedule(admin, flagId);
        start(admin, scheduleId);

        // Equivalent distributions: the gate should find no significant regression.
        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 200, 120));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 200, 120));

        // 5% -> 25%
        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/advance")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.abstained").value(false));
        assertThat(percentage(flagId)).isEqualTo(25);

        // 25% -> 100%, which also completes the schedule.
        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/advance")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        assertThat(percentage(flagId)).isEqualTo(100);
        assertThat(state(flagId)).isEqualTo(FlagState.FULLY_ON);

        mockMvc.perform(get("/api/v1/rollouts/" + scheduleId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Every automated advance is attributed to SYSTEM, with the canary evidence attached.
        var advances = auditRepository.findByFlagIdAndActionAndCreatedAtAfterOrderByCreatedAtDesc(
                UUID.fromString(flagId), AuditAction.STAGE_ADVANCED, java.time.Instant.EPOCH);
        assertThat(advances).isNotEmpty();
        assertThat(advances.get(0).getActor()).isEqualTo("SYSTEM");
        assertThat(advances.get(0).getMetadata()).containsKey("pValue");
    }

    @Test
    @DisplayName("a significantly slower candidate is blocked at the gate and the rollout is paused, not advanced")
    void regressedCandidateIsBlockedByTheCanaryGate() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String key = "staged.blocked.by.canary";
        String flagId = createFlag(admin, key);
        String scheduleId = createSchedule(admin, flagId);
        start(admin, scheduleId);

        // The candidate is more than twice as slow. Note its error rate is fine and its P95 is inside
        // the 400ms ceiling, so the threshold watcher would say nothing — only the statistical
        // comparison against baseline catches this.
        ingestionService.ingest(TestSupport.healthy(key, VariantName.BASELINE, 200, 100));
        ingestionService.ingest(TestSupport.healthy(key, VariantName.CANDIDATE, 200, 260));

        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/advance")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.abstained").value(false));

        // Held at stage one, and frozen rather than withdrawn: the users already on the candidate stay.
        assertThat(percentage(flagId)).isEqualTo(5);
        assertThat(state(flagId)).isEqualTo(FlagState.PAUSED);

        mockMvc.perform(get("/api/v1/rollouts/" + scheduleId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        var blocked = auditRepository.findByFlagIdAndActionAndCreatedAtAfterOrderByCreatedAtDesc(
                UUID.fromString(flagId), AuditAction.STAGE_BLOCKED_BY_CANARY, java.time.Instant.EPOCH);
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).getActor()).isEqualTo("SYSTEM");
        assertThat(blocked.get(0).getReason()).containsIgnoringCase("significantly slower");
    }

    @Test
    @DisplayName("a schedule whose percentages do not strictly increase is rejected")
    void schedulesMustMoveForward() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "staged.backwards.rejected");

        mockMvc.perform(post("/api/v1/rollouts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("flagId", flagId, "stages", List.of(
                                Map.of("percentage", 50, "durationMinutes", 10),
                                Map.of("percentage", 25, "durationMinutes", 10))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a VIEWER cannot advance a stage")
    void viewerCannotAdvance() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String viewer = login("viewer", "cadence-viewer-2026");
        String flagId = createFlag(admin, "staged.viewer.blocked");
        String scheduleId = createSchedule(admin, flagId);
        start(admin, scheduleId);

        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/advance")
                        .header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private String createSchedule(String token, String flagId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/rollouts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("flagId", flagId, "stages", STAGES))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void start(String token, String scheduleId) throws Exception {
        mockMvc.perform(post("/api/v1/rollouts/" + scheduleId + "/start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "start"))))
                .andExpect(status().isOk());
    }

    private int percentage(String flagId) {
        return flagRepository.findById(UUID.fromString(flagId)).orElseThrow().getRolloutPercentage();
    }

    private FlagState state(String flagId) {
        return flagRepository.findById(UUID.fromString(flagId)).orElseThrow().getState();
    }
}
