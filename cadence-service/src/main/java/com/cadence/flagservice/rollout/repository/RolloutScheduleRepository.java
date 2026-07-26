package com.cadence.flagservice.rollout.repository;

import com.cadence.flagservice.rollout.domain.RolloutSchedule;
import com.cadence.flagservice.rollout.domain.RolloutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RolloutScheduleRepository extends JpaRepository<RolloutSchedule, UUID> {

    List<RolloutSchedule> findByFlagIdOrderByCreatedAtDesc(UUID flagId);

    Optional<RolloutSchedule> findFirstByFlagIdAndStatusIn(UUID flagId, List<RolloutStatus> statuses);

    /** The reconciler's query: every running schedule whose stage has expired. */
    List<RolloutSchedule> findByStatusAndNextTransitionAtBefore(RolloutStatus status, Instant now);
}
