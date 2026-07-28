package com.cadence.flagservice.rollout.service;

import com.cadence.core.model.FlagState;
import com.cadence.flagservice.alert.AlertService;
import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.service.FlagAuditService;
import com.cadence.flagservice.common.ConflictException;
import com.cadence.flagservice.common.NotFoundException;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import com.cadence.flagservice.metrics.model.CanaryResult;
import com.cadence.flagservice.metrics.service.CanaryAnalysisService;
import com.cadence.flagservice.security.CurrentActor;
import com.cadence.flagservice.rollout.domain.RolloutSchedule;
import com.cadence.flagservice.rollout.domain.RolloutStage;
import com.cadence.flagservice.rollout.domain.RolloutStatus;
import com.cadence.flagservice.rollout.repository.RolloutScheduleRepository;
import com.cadence.flagservice.websocket.RolloutBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives a flag through its stages on a clock, and refuses to advance a stage the statistics do not
 * support.
 *
 * <h2>The gate</h2>
 * At every transition boundary the scheduler asks {@link CanaryAnalysisService} whether the candidate's
 * latency distribution is significantly worse than the baseline's. If it is, the rollout does not
 * advance: it pauses, an alert fires, and the block is written into the audit trail with the U statistic
 * and p-value attached. A human decides what happens next. The platform never overrules a failing
 * canary on a timer.
 *
 * <h2>Why a durable reconciler rather than in-memory timers</h2>
 * {@code TaskScheduler.schedule(runnable, instant)} is precise and completely amnesiac. A rolling
 * restart in the middle of a four-hour bake would drop every pending transition on the floor, and the
 * flag would sit at 5% until somebody noticed. The deadline lives in Postgres
 * ({@code next_transition_at}); this method polls for expired deadlines. The cost is up to one poll
 * interval of imprecision on a transition that is scheduled hours out. That is a very good trade.
 */
@Service
public class RolloutSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(RolloutSchedulerService.class);

    private final RolloutScheduleRepository repository;
    private final FeatureFlagService flagService;
    private final CanaryAnalysisService canaryService;
    private final FlagAuditService auditService;
    private final AlertService alertService;
    private final RolloutBroadcaster broadcaster;
    private final CurrentActor currentActor;

    public RolloutSchedulerService(RolloutScheduleRepository repository,
                                   FeatureFlagService flagService,
                                   CanaryAnalysisService canaryService,
                                   FlagAuditService auditService,
                                   AlertService alertService,
                                   RolloutBroadcaster broadcaster,
                                   CurrentActor currentActor) {
        this.repository = repository;
        this.flagService = flagService;
        this.canaryService = canaryService;
        this.auditService = auditService;
        this.alertService = alertService;
        this.broadcaster = broadcaster;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------------
    // Schedule management
    // ------------------------------------------------------------------

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RolloutSchedule create(UUID flagId, List<RolloutStage> stages) {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("A schedule needs at least one stage");
        }
        for (int i = 1; i < stages.size(); i++) {
            if (stages.get(i).percentage() <= stages.get(i - 1).percentage()) {
                // A schedule that goes backwards would re-bucket users out of the candidate, which is
                // a rollback wearing a schedule's clothes. Rollbacks have their own, audited path.
                throw new IllegalArgumentException(
                        "Stage percentages must strictly increase; stage %d (%d%%) is not above stage %d (%d%%)"
                                .formatted(i, stages.get(i).percentage(), i - 1, stages.get(i - 1).percentage()));
            }
        }

        FeatureFlag flag = flagService.findById(flagId);
        repository.findFirstByFlagIdAndStatusIn(flagId, List.of(RolloutStatus.PENDING, RolloutStatus.RUNNING))
                .ifPresent(existing -> {
                    throw new ConflictException("Flag '%s' already has an active schedule".formatted(flag.getKey()));
                });

        RolloutSchedule schedule = new RolloutSchedule(flag.getId(), flag.getKey(), stages, currentActor.name());
        RolloutSchedule saved = repository.save(schedule);

        auditService.record(AuditAction.SCHEDULE_CREATED, flag.getId(), flag.getKey(),
                null, stages.toString(), "Staged rollout schedule created");
        return saved;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public RolloutSchedule start(UUID scheduleId, String reason) {
        RolloutSchedule schedule = get(scheduleId);
        if (schedule.getStatus() != RolloutStatus.PENDING) {
            throw new ConflictException("Schedule is %s, not PENDING".formatted(schedule.getStatus()));
        }
        FeatureFlag flag = flagService.findById(schedule.getFlagId());
        if (flag.getState() == FlagState.ROLLED_BACK) {
            throw new ConflictException("Flag '%s' was rolled back; reset it before starting a schedule"
                    .formatted(flag.getKey()));
        }

        RolloutStage first = schedule.getStages().get(0);
        schedule.setCurrentStageIndex(0);
        schedule.setStatus(RolloutStatus.RUNNING);
        schedule.setStartedAt(Instant.now());
        schedule.setNextTransitionAt(Instant.now().plus(Duration.ofMinutes(first.durationMinutes())));

        // The first stage is entered without a canary check. There is nothing to compare against yet:
        // no user has ever seen the candidate.
        flagService.setRolloutPercentageAsSystem(flag.getId(), first.percentage(),
                "Schedule started: entering stage 1 at %d%%".formatted(first.percentage()),
                Map.of("scheduleId", schedule.getId().toString(), "stage", 1));

        auditService.record(AuditAction.SCHEDULE_STARTED, flag.getId(), flag.getKey(),
                null, String.valueOf(first.percentage()), reason);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "SCHEDULE_STARTED",
                Map.of("stage", 1, "percentage", first.percentage()));
        return schedule;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public RolloutSchedule pause(UUID scheduleId, String reason) {
        RolloutSchedule schedule = get(scheduleId);
        if (schedule.getStatus() != RolloutStatus.RUNNING) {
            throw new ConflictException("Only a RUNNING schedule can be paused");
        }
        schedule.setStatus(RolloutStatus.PAUSED);
        schedule.setNextTransitionAt(null);
        flagService.pause(schedule.getFlagId(), reason);
        return schedule;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public RolloutSchedule resume(UUID scheduleId, String reason) {
        RolloutSchedule schedule = get(scheduleId);
        if (schedule.getStatus() != RolloutStatus.PAUSED) {
            throw new ConflictException("Only a PAUSED schedule can be resumed");
        }
        RolloutStage current = schedule.currentStage();
        schedule.setStatus(RolloutStatus.RUNNING);
        schedule.setNextTransitionAt(Instant.now().plus(
                Duration.ofMinutes(current == null ? 0 : current.durationMinutes())));
        flagService.resume(schedule.getFlagId(), reason);
        return schedule;
    }

    /** Advance now, without waiting for the clock. The canary gate still applies. */
    @Transactional
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public CanaryResult advanceNow(UUID scheduleId) {
        RolloutSchedule schedule = get(scheduleId);
        if (schedule.getStatus() != RolloutStatus.RUNNING) {
            throw new ConflictException("Only a RUNNING schedule can be advanced");
        }
        return attemptTransition(schedule);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
    public RolloutSchedule find(UUID scheduleId) {
        return get(scheduleId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
    public List<RolloutSchedule> findByFlag(UUID flagId) {
        return repository.findByFlagIdOrderByCreatedAtDesc(flagId);
    }

    // ------------------------------------------------------------------
    // The reconciler
    // ------------------------------------------------------------------

    /**
     * Picks up any stage whose hold time has elapsed. Runs on a virtual thread.
     *
     * <p>Not annotated {@code @Scheduled} here: see {@link RolloutReconciler}, which owns the tick so
     * that this transactional service method is invoked through the Spring proxy rather than
     * self-invoked (a self-call would bypass {@code @Transactional} entirely — a classic and silent bug).
     */
    @Transactional
    public void reconcileDueTransitions() {
        List<RolloutSchedule> due =
                repository.findByStatusAndNextTransitionAtBefore(RolloutStatus.RUNNING, Instant.now());
        for (RolloutSchedule schedule : due) {
            try {
                attemptTransition(schedule);
            } catch (Exception e) {
                log.error("Stage transition failed for schedule {} (flag '{}'): {}",
                        schedule.getId(), schedule.getFlagKey(), e.getMessage(), e);
                // Back off one interval so a permanently broken schedule does not spin every tick.
                schedule.setNextTransitionAt(Instant.now().plus(Duration.ofMinutes(1)));
            }
        }
    }

    private CanaryResult attemptTransition(RolloutSchedule schedule) {
        FeatureFlag flag = flagService.get(schedule.getFlagId());

        // The watcher may have withdrawn the candidate underneath us. A schedule must never resurrect
        // a rolled-back flag; that decision belongs to a human.
        if (flag.getState() == FlagState.ROLLED_BACK) {
            schedule.setStatus(RolloutStatus.ABORTED);
            schedule.setNextTransitionAt(null);
            log.warn("Schedule {} aborted: flag '{}' was rolled back", schedule.getId(), flag.getKey());
            return CanaryResult.abstain(0, 0, "Flag was rolled back; schedule aborted");
        }

        if (!schedule.hasNextStage()) {
            return complete(schedule, flag);
        }

        CanaryResult canary = canaryService.analyse(flag);
        if (!canary.passed()) {
            blockOnCanary(schedule, flag, canary);
            return canary;
        }

        RolloutStage next = schedule.nextStage();
        int stageNumber = schedule.getCurrentStageIndex() + 2; // 1-based, and we are moving to the next

        Map<String, Object> evidence = new HashMap<>(canary.asEvidence());
        evidence.put("scheduleId", schedule.getId().toString());
        evidence.put("stage", stageNumber);

        flagService.setRolloutPercentageAsSystem(flag.getId(), next.percentage(),
                "Canary gate passed; advancing to stage %d at %d%%".formatted(stageNumber, next.percentage()),
                evidence);

        schedule.setCurrentStageIndex(schedule.getCurrentStageIndex() + 1);
        schedule.setNextTransitionAt(Instant.now().plus(Duration.ofMinutes(next.durationMinutes())));

        log.info("Schedule {} advanced flag '{}' to {}% (stage {}), next check at {}",
                schedule.getId(), flag.getKey(), next.percentage(), stageNumber, schedule.getNextTransitionAt());

        if (next.percentage() == 100) {
            complete(schedule, flag);
        }
        return canary;
    }

    private void blockOnCanary(RolloutSchedule schedule, FeatureFlag flag, CanaryResult canary) {
        schedule.setStatus(RolloutStatus.PAUSED);
        schedule.setNextTransitionAt(null);

        flagService.pauseAsSystem(flag.getId(),
                "Stage transition blocked by canary analysis", canary.asEvidence());

        auditService.recordSystem(AuditAction.STAGE_BLOCKED_BY_CANARY, flag.getId(), flag.getKey(),
                String.valueOf(flag.getRolloutPercentage()), String.valueOf(flag.getRolloutPercentage()),
                canary.reason(), canary.asEvidence());

        alertService.canaryBlocked(flag, canary.reason());
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "STAGE_BLOCKED_BY_CANARY", canary.asEvidence());

        log.warn("Canary gate blocked advance of '{}' at {}%: {}",
                flag.getKey(), flag.getRolloutPercentage(), canary.reason());
    }

    private CanaryResult complete(RolloutSchedule schedule, FeatureFlag flag) {
        schedule.setStatus(RolloutStatus.COMPLETED);
        schedule.setCompletedAt(Instant.now());
        schedule.setNextTransitionAt(null);

        auditService.recordSystem(AuditAction.ROLLOUT_COMPLETED, flag.getId(), flag.getKey(),
                String.valueOf(flag.getRolloutPercentage()), "100",
                "All stages completed", Map.of("scheduleId", schedule.getId().toString()));

        alertService.rolloutCompleted(flag);
        broadcaster.flagChanged(flag.getId(), flag.getKey(), "ROLLOUT_COMPLETED", Map.of());
        log.info("Schedule {} completed: flag '{}' is fully rolled out", schedule.getId(), flag.getKey());
        return CanaryResult.abstain(0, 0, "Rollout complete");
    }

    private RolloutSchedule get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rollout schedule not found: " + id));
    }
}
