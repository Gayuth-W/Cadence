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
import com.cadence.flagservice.metrics.service.RollbackGuardState;
import com.cadence.flagservice.security.CurrentActor;
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
    private final JsonUtils json;
    private final RollbackGuardState guardState;

    public FeatureFlagService(FeatureFlagRepository repository,
            FlagAuditService auditService,
            CadenceProperties properties,
            CurrentActor currentActor,
            JsonUtils json,
            RollbackGuardState guardState) {
        this.repository = repository;
        this.auditService = auditService;
        this.properties = properties;
        this.currentActor = currentActor;
        this.json = json;
        this.guardState = guardState;
    }

    // Reads — every role

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

    // Lifecycle — ADMIN

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
}
