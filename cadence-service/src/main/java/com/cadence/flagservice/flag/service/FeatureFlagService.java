package com.cadence.flagservice.flag.service;

import com.cadence.core.evaluation.TargetingEngine;
import com.cadence.core.model.EvaluationResult;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.FlagState;
import com.cadence.core.model.UserContext;
import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.service.FlagAuditService;
import com.cadence.flagservice.common.ConflictException;
import com.cadence.flagservice.common.JsonUtils;
import com.cadence.flagservice.common.NotFoundException;
import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.dto.CreateFlagRequest;
import com.cadence.flagservice.flag.dto.UpdateFlagRequest;
import com.cadence.flagservice.flag.repository.FeatureFlagRepository;
import com.cadence.flagservice.metrics.service.MetricWindowService;
import com.cadence.flagservice.metrics.service.RollbackGuardState;
import com.cadence.flagservice.security.CurrentActor;
import com.cadence.flagservice.websocket.RolloutBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The flag state machine, and the only place it may be mutated.
 *
 * <p>
 * Authorisation lives on these methods rather than on URL patterns, so the rule
 * sits next to the
 * operation it protects. A new controller cannot accidentally expose
 * {@link #rollback} to an OPERATOR,
 * because the check is on the method, not the route.
 *
 * <p>
 * Every mutation writes an audit record inside the same transaction. See
 * {@link FlagAuditService}.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository repository;
    private final FlagAuditService auditService;
    private final CadenceProperties properties;
    private final CurrentActor currentActor;
    private final RolloutBroadcaster broadcaster;
    private final JsonUtils json;

    // Both are leaf beans (Redis + properties only). Depending on
    // RollbackWatcherService instead would
    // close a cycle, since the watcher already depends on this service.
    private final MetricWindowService windowService;
    private final RollbackGuardState guardState;

    public FeatureFlagService(FeatureFlagRepository repository,
            FlagAuditService auditService,
            CadenceProperties properties,
            CurrentActor currentActor,
            RolloutBroadcaster broadcaster,
            JsonUtils json,
            MetricWindowService windowService,
            RollbackGuardState guardState) {
        this.repository = repository;
        this.auditService = auditService;
        this.properties = properties;
        this.currentActor = currentActor;
        this.broadcaster = broadcaster;
        this.json = json;
        this.windowService = windowService;
        this.guardState = guardState;
    }

    // ------------------------------------------------------------------
    // Reads — every role
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
    public List<FeatureFlag> findAll() {
        return repository.findAllByEnvironment(properties.getEnvironment());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
    public FeatureFlag findById(UUID id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.flag(id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
    public FeatureFlag findByKey(String key) {
        return repository.findByKeyAndEnvironment(key, properties.getEnvironment())
                .orElseThrow(() -> NotFoundException.flag(key));
    }

    /**
     * Unsecured on purpose: called from schedulers, and from the data plane after
     * scope authorisation.
     */
    @Transactional(readOnly = true)
    public FeatureFlag get(UUID id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.flag(id));
    }

    @Transactional(readOnly = true)
    public List<FeatureFlag> findActiveRollouts() {
        return repository.findAllByEnvironmentAndStateIn(properties.getEnvironment(),
                List.of(FlagState.ROLLING_OUT, FlagState.SHADOW));
    }

    /**
     * Data-plane read, authorised by API-key scope in the security chain, scoped to
     * the key's environment.
     */
    @Transactional(readOnly = true)
    public List<FlagDefinition> definitionsFor(String environment) {
        return repository.findAllByEnvironment(environment).stream()
                .map(FeatureFlag::toDefinition)
                .toList();
    }

    /**
     * Server-side evaluation for non-Java callers. Runs the identical engine the
     * SDK runs.
     */
    @Transactional(readOnly = true)
    public EvaluationResult evaluate(String flagKey, String environment, UserContext ctx) {
        return repository.findByKeyAndEnvironment(flagKey, environment)
                .map(flag -> TargetingEngine.evaluate(flag.toDefinition(), ctx))
                .orElseThrow(() -> NotFoundException.flag(flagKey));
    }

    // ------------------------------------------------------------------
    // Lifecycle — ADMIN
    // ------------------------------------------------------------------

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FeatureFlag create(CreateFlagRequest request) {
        String env = properties.getEnvironment();
        if (repository.existsByKeyAndEnvironment(request.key(), env)) {
            throw new ConflictException("Flag already exists in this environment: " + request.key());
        }
        FeatureFlag flag = new FeatureFlag(request.key(), request.description(), env, currentActor.name());
        flag.setBaselineConfig(request.baselineConfig());
        flag.setCandidateConfig(request.candidateConfig());
        flag.setTargetingRules(request.targetingRules());
        flag.setHealthMetrics(request.healthMetrics());
        // A flag that declares no trigger inherits the control plane's configured
        // defaults, captured
        // once at creation time. See CadenceProperties.Rollback#defaultTrigger for why
        // it is a snapshot.
        flag.setRollbackTrigger(request.rollbackTrigger() == null
                ? properties.getRollback().defaultTrigger()
                : request.rollbackTrigger());
        // A new flag always starts OFF at 0%. There is no way to create a flag that is
        // already live.
        flag.setState(FlagState.OFF);
        flag.setRolloutPercentage(0);

        FeatureFlag saved = repository.save(flag);
        auditService.record(AuditAction.FLAG_CREATED, saved.getId(), saved.getKey(),
                null, json.toJson(saved.toDefinition()), "Flag created");
        return saved;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FeatureFlag update(UUID id, UpdateFlagRequest request) {
        FeatureFlag flag = get(id);
        String before = json.toJson(flag.toDefinition());

        if (request.description() != null)
            flag.setDescription(request.description());
        if (request.baselineConfig() != null)
            flag.setBaselineConfig(request.baselineConfig());
        if (request.candidateConfig() != null)
            flag.setCandidateConfig(request.candidateConfig());
        if (request.targetingRules() != null)
            flag.setTargetingRules(request.targetingRules());
        if (request.healthMetrics() != null)
            flag.setHealthMetrics(request.healthMetrics());
        if (request.rollbackTrigger() != null)
            flag.setRollbackTrigger(request.rollbackTrigger());

        auditService.record(AuditAction.FLAG_UPDATED, flag.getId(), flag.getKey(),
                before, json.toJson(flag.toDefinition()),
                request.reason() == null ? "Flag configuration updated" : request.reason());
        return flag;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(UUID id) {
        FeatureFlag flag = get(id);
        if (flag.getState().servesCandidate() && flag.getRolloutPercentage() > 0) {
            // Deleting a live flag would strand every SDK on its last cached copy while the
            // platform
            // stops watching its health. Withdraw it first, then delete.
            throw new ConflictException(
                    "Flag '%s' is live at %d%%. Roll it back before deleting."
                            .formatted(flag.getKey(), flag.getRolloutPercentage()));
        }
        String before = json.toJson(flag.toDefinition());
        repository.delete(flag);
        // flagId is retained on the record even though the row is gone; the trail
        // outlives the flag.
        auditService.record(AuditAction.FLAG_DELETED, flag.getId(), flag.getKey(),
                before, null, "Flag deleted");
    }

    // ------------------------------------------------------------------
    // Rollout control — OPERATOR and ADMIN
    // ------------------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public FeatureFlag setRolloutPercentage(UUID id, int percentage, String reason) {
        return applyRolloutPercentage(id, percentage, reason, false);
    }

    /**
     * Used by the automated stage scheduler, which runs with no security context.
     * Kept separate from
     * {@link #setRolloutPercentage} so the human path keeps its
     * {@code @PreAuthorize} and the automated
     * path cannot be reached from any controller.
     */
    @Transactional
    public FeatureFlag setRolloutPercentageAsSystem(UUID id, int percentage, String reason,
            Map<String, Object> evidence) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        int old = flag.getRolloutPercentage();
        applyPercentageToEntity(flag, percentage);
        auditService.recordSystem(AuditAction.STAGE_ADVANCED, flag.getId(), flag.getKey(),
                String.valueOf(old), String.valueOf(percentage), reason, evidence);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "STAGE_ADVANCED",
                Map.of("from", old, "to", percentage));
        return flag;
    }

    private FeatureFlag applyRolloutPercentage(UUID id, int percentage, String reason, boolean system) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Rollout percentage must be between 0 and 100");
        }
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));

        if (flag.getState() == FlagState.ROLLED_BACK) {
            // A rolled-back flag is a flag someone or something decided was unsafe.
            // Advancing it
            // requires an explicit ADMIN resume, which forces a human to look at why it was
            // withdrawn.
            throw new ConflictException(
                    "Flag '%s' was rolled back. An ADMIN must resume it before the rollout can advance."
                            .formatted(flag.getKey()));
        }

        int old = flag.getRolloutPercentage();
        applyPercentageToEntity(flag, percentage);

        auditService.record(AuditAction.ROLLOUT_PERCENTAGE_CHANGED, flag.getId(), flag.getKey(),
                String.valueOf(old), String.valueOf(percentage), reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLOUT_PERCENTAGE_CHANGED",
                Map.of("from", old, "to", percentage));

        log.info("Flag '{}' rollout {}% -> {}% by {}", flag.getKey(), old, percentage, currentActor.name());
        return flag;
    }

    private void applyPercentageToEntity(FeatureFlag flag, int percentage) {
        flag.setRolloutPercentage(percentage);
        // The state derives from the percentage. Keeping them in sync here, rather than
        // trusting each
        // caller to do it, removes the class of bug where a flag reads FULLY_ON at 60%.
        if (percentage == 0) {
            flag.setState(FlagState.OFF);
        } else if (percentage == 100) {
            flag.setState(FlagState.FULLY_ON);
        } else {
            flag.setState(FlagState.ROLLING_OUT);
        }
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public FeatureFlag pause(UUID id, String reason) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        if (flag.getState() != FlagState.ROLLING_OUT) {
            throw new ConflictException("Only a ROLLING_OUT flag can be paused; '%s' is %s"
                    .formatted(flag.getKey(), flag.getState()));
        }
        // Pause freezes the split where it is. It does not move traffic. Users already
        // on the candidate
        // stay on the candidate; that is the difference between pause and rollback.
        flag.setState(FlagState.PAUSED);
        auditService.record(AuditAction.ROLLOUT_PAUSED, flag.getId(), flag.getKey(),
                FlagState.ROLLING_OUT.name(), FlagState.PAUSED.name(), reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLOUT_PAUSED",
                Map.of("percentage", flag.getRolloutPercentage()));
        return flag;
    }

    /** Called by the stage scheduler when metrics dip, with SYSTEM attribution. */
    @Transactional
    public FeatureFlag pauseAsSystem(UUID id, String reason, Map<String, Object> evidence) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        if (flag.getState() != FlagState.ROLLING_OUT) {
            return flag;
        }
        flag.setState(FlagState.PAUSED);
        auditService.recordSystem(AuditAction.ROLLOUT_PAUSED, flag.getId(), flag.getKey(),
                FlagState.ROLLING_OUT.name(), FlagState.PAUSED.name(), reason, evidence);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLOUT_PAUSED", evidence);
        return flag;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public FeatureFlag resume(UUID id, String reason) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        if (flag.getState() != FlagState.PAUSED) {
            throw new ConflictException("Only a PAUSED flag can be resumed; '%s' is %s"
                    .formatted(flag.getKey(), flag.getState()));
        }
        flag.setState(FlagState.ROLLING_OUT);
        auditService.record(AuditAction.ROLLOUT_RESUMED, flag.getId(), flag.getKey(),
                FlagState.PAUSED.name(), FlagState.ROLLING_OUT.name(), reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLOUT_RESUMED",
                Map.of("percentage", flag.getRolloutPercentage()));
        return flag;
    }

    // ------------------------------------------------------------------
    // Rollback — ADMIN only for the forced path
    // ------------------------------------------------------------------

    /**
     * Force every user back to baseline, now. ADMIN only: this is the single action
     * that overrides both
     * the automation and every other operator in the middle of a release.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FeatureFlag rollback(UUID id, String reason) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        int old = flag.getRolloutPercentage();
        applyRollbackToEntity(flag);
        auditService.record(AuditAction.ROLLBACK_FORCED, flag.getId(), flag.getKey(),
                String.valueOf(old), "0", reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLBACK_FORCED",
                Map.of("from", old, "actor", currentActor.name()));
        log.warn("Flag '{}' FORCE ROLLED BACK from {}% by {}: {}", flag.getKey(), old, currentActor.name(), reason);
        return flag;
    }

    /**
     * The automatic path, invoked by {@code RollbackWatcherService} on a scheduler
     * thread.
     * Attributed to SYSTEM, with the breaching metrics recorded as evidence in the
     * audit metadata.
     */
    @Transactional
    public FeatureFlag rollbackAsSystem(UUID id, String reason, Map<String, Object> evidence) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        int old = flag.getRolloutPercentage();
        applyRollbackToEntity(flag);

        Map<String, Object> metadata = new HashMap<>(evidence);
        metadata.put("percentageBeforeRollback", old);

        auditService.recordSystem(AuditAction.ROLLBACK_AUTOMATIC, flag.getId(), flag.getKey(),
                String.valueOf(old), "0", reason, metadata);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLBACK_AUTOMATIC", metadata);
        log.error("Flag '{}' AUTO ROLLED BACK from {}%: {} | evidence={}", flag.getKey(), old, reason, evidence);
        return flag;
    }

    private void applyRollbackToEntity(FeatureFlag flag) {
        flag.setPercentageBeforeRollback(flag.getRolloutPercentage());
        flag.setRolloutPercentage(0);
        // ROLLED_BACK, not OFF. The distinction matters: OFF is "not started",
        // ROLLED_BACK is "we tried
        // and it went wrong", and only the latter blocks the scheduler from quietly
        // advancing it again.
        flag.setState(FlagState.ROLLED_BACK);
    }

    /**
     * Clear the rolled-back state so a fixed candidate can be tried again. ADMIN,
     * by design.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FeatureFlag reset(UUID id, String reason) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        if (flag.getState() != FlagState.ROLLED_BACK) {
            throw new ConflictException("Only a ROLLED_BACK flag needs resetting; '%s' is %s"
                    .formatted(flag.getKey(), flag.getState()));
        }
        flag.setState(FlagState.OFF);
        flag.setRolloutPercentage(0);

        // A reset means "the candidate is fixed, let it try again". Two pieces of state
        // from the last
        // attempt would otherwise sabotage the next one:
        //
        // 1. The rolling windows still hold the OLD candidate's failures, with a 24h
        // TTL. The watcher
        // would read a 34% error rate on the first tick and withdraw the fixed
        // candidate before it
        // could serve enough traffic to defend itself — at 5% rollout, that could take
        // hours.
        // 2. The cooldown lock is still held from the rollback. The watcher would SKIP
        // this flag for
        // the remainder of the cooldown, so the fixed candidate would go back out to
        // real users
        // with no automated protection at all. That is the more dangerous of the two.
        //
        // Neither is wiped at rollback time on purpose: that is exactly when the
        // evidence is needed for
        // the post-mortem. It is discarded here, when a human has looked at it and
        // moved on.
        clearReleaseHealthState(flag);

        auditService.record(AuditAction.FLAG_UPDATED, flag.getId(), flag.getKey(),
                FlagState.ROLLED_BACK.name(), FlagState.OFF.name(), reason);
        return flag;
    }

    private void clearReleaseHealthState(FeatureFlag flag) {
        guardState.clear(flag.getId());
        try {
            windowService.reset(flag.getKey()).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            // Never fail a reset because Redis is slow or down. Stale windows make the
            // watcher
            // over-eager, not under-eager, so the failure direction is safe; log it loudly
            // and move on.
            log.warn("Could not clear metric windows for flag '{}' during reset: {}",
                    flag.getKey(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Shadow mode
    // ------------------------------------------------------------------

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FeatureFlag enableShadow(UUID id, String reason) {
        FeatureFlag flag = repository.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.flag(id));
        if (flag.getState().servesCandidate()) {
            throw new ConflictException(
                    "Flag '%s' is already serving the candidate to users; shadow mode is a pre-rollout step"
                            .formatted(flag.getKey()));
        }
        FlagState old = flag.getState();
        flag.setState(FlagState.SHADOW);
        flag.setRolloutPercentage(0);
        auditService.record(AuditAction.SHADOW_ENABLED, flag.getId(), flag.getKey(),
                old.name(), FlagState.SHADOW.name(), reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "SHADOW_ENABLED", Map.of());
        return flag;
    }
}
