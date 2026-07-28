package com.cadence.flagservice.rollout.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tick that drives {@link RolloutSchedulerService#reconcileDueTransitions()}.
 *
 * <p>Kept as a separate bean on purpose. If the {@code @Scheduled} method lived on the service and
 * called its own {@code @Transactional} method, the call would not pass through the Spring proxy and
 * the transaction would never begin — the stage transition would then run without one, and a partial
 * failure could advance the flag while leaving the schedule pointing at the old stage.
 */
@Component
public class RolloutReconciler {

    private static final Logger log = LoggerFactory.getLogger(RolloutReconciler.class);

    private final RolloutSchedulerService schedulerService;

    public RolloutReconciler(RolloutSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Scheduled(fixedDelayString = "${cadence.rollout.reconcile-interval:PT30S}")
    public void tick() {
        try {
            schedulerService.reconcileDueTransitions();
        } catch (Exception e) {
            log.error("Rollout reconciliation tick failed: {}", e.getMessage(), e);
        }
    }
}
