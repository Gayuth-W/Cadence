package com.cadence.flagservice;

import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.repository.FeatureFlagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access-control matrix, exercised through real HTTP with real JWTs, plus the audit attribution
 * that makes the matrix worth having.
 */
class SecurityAndAuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FeatureFlagRepository flagRepository;

    @Autowired
    private AuditRecordRepository auditRepository;

    @Test
    @DisplayName("no token is 401, not 403: the caller is unauthenticated, not merely unauthorised")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/flags"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a malformed or forged bearer token is rejected")
    void malformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/flags").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        // A structurally valid JWT signed with the wrong key must not be accepted.
        mockMvc.perform(get("/api/v1/flags").header("Authorization", "Bearer " + tokenSignedWithWrongKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login with a bad password is 401 and never reveals whether the user exists")
    void badCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "no-such-user", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                // Identical message: the response cannot be used to enumerate usernames.
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    @DisplayName("VIEWER can read flags but cannot create one")
    void viewerIsReadOnly() throws Exception {
        String viewer = login("viewer", "cadence-viewer-2026");

        mockMvc.perform(get("/api/v1/flags").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", "Bearer " + viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "viewer.should.not.create"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPERATOR can advance a rollout but cannot force a rollback; ADMIN can do both")
    void operatorCannotForceRollback() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String operator = login("operator", "cadence-operator-2026");
        String flagId = createFlag(admin, "rbac.rollback.check");

        // OPERATOR advances: allowed.
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollout")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("percentage", 10, "reason", "canary"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLING_OUT"))
                .andExpect(jsonPath("$.rolloutPercentage").value(10));

        // OPERATOR force-rollback: forbidden. This is the one action reserved for ADMIN.
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollback")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "operator tries to force"))))
                .andExpect(status().isForbidden());

        // ADMIN force-rollback: allowed.
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollback")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "checkout errors spiking"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rolloutPercentage").value(0));
    }

    @Test
    @DisplayName("a rolled-back flag refuses to advance until an ADMIN resets it")
    void rolledBackFlagRefusesToAdvance() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String operator = login("operator", "cadence-operator-2026");
        String flagId = createFlag(admin, "rbac.rolledback.guard");

        rollout(operator, flagId, 10);
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollback")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "withdraw"))))
                .andExpect(status().isOk());

        // 409, not a silent re-advance.
        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollout")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("percentage", 25, "reason", "try again"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/flags/" + flagId + "/reset")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "candidate fixed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OFF"));
    }

    @Test
    @DisplayName("every rollout change is written to the audit log attributed to the human who made it")
    @Transactional
    void auditCapturesTheRealActor() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String operator = login("operator", "cadence-operator-2026");
        String flagId = createFlag(admin, "audit.actor.check");
        rollout(operator, flagId, 15);

        FeatureFlag flag = flagRepository.findById(java.util.UUID.fromString(flagId)).orElseThrow();

        var records = auditRepository.findByFlagIdOrderByCreatedAtDesc(
                flag.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();

        assertThat(records).hasSize(2);
        assertThat(records.get(0).getAction()).isEqualTo(AuditAction.ROLLOUT_PERCENTAGE_CHANGED);
        // The actor is taken from the security context, never from the request body.
        assertThat(records.get(0).getActor()).isEqualTo("operator");
        assertThat(records.get(0).getOldValue()).isEqualTo("0");
        assertThat(records.get(0).getNewValue()).isEqualTo("15");
        assertThat(records.get(0).getReason()).isNotBlank();

        assertThat(records.get(1).getAction()).isEqualTo(AuditAction.FLAG_CREATED);
        assertThat(records.get(1).getActor()).isEqualTo("admin");
    }

    @Test
    @DisplayName("a rollout change with no reason is rejected: an unexplained release change is not auditable")
    void reasonIsMandatory() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        String flagId = createFlag(admin, "audit.reason.required");

        mockMvc.perform(post("/api/v1/flags/" + flagId + "/rollout")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("percentage", 10, "reason", "  ")))) // blank
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").exists());
    }

    @Test
    @DisplayName("the audit log has no write endpoint")
    void auditIsReadOnlyOverHttp() throws Exception {
        String admin = login("admin", "cadence-admin-2026");
        mockMvc.perform(post("/api/v1/audit")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }
}
