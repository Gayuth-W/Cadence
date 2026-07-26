package com.cadence.flagservice.rollout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An automated, staged rollout plan for one flag.
 *
 * <p>{@code nextTransitionAt} is persisted rather than held in an in-memory timer. A control plane
 * that scheduled its stage transitions only with {@code TaskScheduler.schedule(...)} would silently
 * forget every in-flight rollout on restart, leaving flags parked at 5% forever with nobody watching.
 * Storing the deadline in Postgres means the reconciler picks the rollout back up on the next tick
 * after a deploy, a crash, or a pod eviction.
 */
@Entity
@Table(name = "rollout_schedule", indexes = {
        @Index(name = "idx_schedule_status_next", columnList = "status, next_transition_at"),
        @Index(name = "idx_schedule_flag", columnList = "flag_id")
})
public class RolloutSchedule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "flag_id", nullable = false)
    private UUID flagId;

    @Column(name = "flag_key", nullable = false, length = 200)
    private String flagKey;

    /** Ordered stages, stored as a jsonb array. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<RolloutStage> stages = List.of();

    @Column(name = "current_stage_index", nullable = false)
    private int currentStageIndex = -1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RolloutStatus status = RolloutStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    /** When the reconciler should next consider advancing. Null unless RUNNING. */
    @Column(name = "next_transition_at")
    private Instant nextTransitionAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected RolloutSchedule() {
    }

    public RolloutSchedule(UUID flagId, String flagKey, List<RolloutStage> stages, String createdBy) {
        this.flagId = flagId;
        this.flagKey = flagKey;
        this.stages = List.copyOf(stages);
        this.createdBy = createdBy;
    }

    /** The stage currently being served, or null before the schedule starts. */
    public RolloutStage currentStage() {
        return currentStageIndex >= 0 && currentStageIndex < stages.size()
                ? stages.get(currentStageIndex) : null;
    }

    public boolean hasNextStage() {
        return currentStageIndex + 1 < stages.size();
    }

    public RolloutStage nextStage() {
        return hasNextStage() ? stages.get(currentStageIndex + 1) : null;
    }

    public UUID getId() { return id; }
    public UUID getFlagId() { return flagId; }
    public String getFlagKey() { return flagKey; }
    public List<RolloutStage> getStages() { return stages; }
    public void setStages(List<RolloutStage> stages) { this.stages = List.copyOf(stages); }
    public int getCurrentStageIndex() { return currentStageIndex; }
    public void setCurrentStageIndex(int i) { this.currentStageIndex = i; }
    public RolloutStatus getStatus() { return status; }
    public void setStatus(RolloutStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getNextTransitionAt() { return nextTransitionAt; }
    public void setNextTransitionAt(Instant at) { this.nextTransitionAt = at; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant at) { this.completedAt = at; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
