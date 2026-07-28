package com.cadence.flagservice.flag.domain;

import com.cadence.core.metrics.HealthMetricSpec;
import com.cadence.core.metrics.RollbackTrigger;
import com.cadence.core.model.FlagDefinition;
import com.cadence.core.model.FlagState;
import com.cadence.core.model.TargetingRules;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A progressive-delivery flag. Not a boolean.
 *
 * <p>Beyond on/off it carries the four things that make a rollout <i>safe</i> rather than merely
 * gradual: the current percentage, the release-health thresholds the candidate must respect, the
 * conditions under which the platform withdraws it automatically, and both variant configurations
 * so the platform always knows what "the old version" was.
 *
 * <p>{@link Version} is load-bearing. The rollout scheduler, the rollback watcher and a human operator
 * can all try to change {@code rolloutPercentage} at the same instant. Without optimistic locking, an
 * automatic rollback to 0% and a manual advance to 25% interleave into a lost update, and the flag
 * ends at 25% while the audit log says it was rolled back. With it, the loser gets a 409.
 */
@Entity
@Table(name = "feature_flag", uniqueConstraints = @UniqueConstraint(
        name = "uq_flag_key_environment", columnNames = {"key", "environment"}))
public class FeatureFlag {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9._-]{3,200}$",
            message = "Flag key must be 3-200 chars of letters, digits, dot, underscore or hyphen")
    @Column(nullable = false, length = 200)
    private String key;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FlagState state = FlagState.OFF;

    @Min(0)
    @Max(100)
    @Column(name = "rollout_percentage", nullable = false)
    private int rolloutPercentage = 0;

    @Column(nullable = false, length = 50)
    private String environment;

    /** What to serve when the flag is off, the user is outside the bucket, or the flag was rolled back. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "baseline_config", columnDefinition = "jsonb")
    private Map<String, Object> baselineConfig = Map.of();

    /** The new version being rolled out. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_config", columnDefinition = "jsonb")
    private Map<String, Object> candidateConfig = Map.of();

    /** Ordered targeting policy, evaluated before percentage bucketing. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "targeting_rules", columnDefinition = "jsonb")
    private TargetingRules targetingRules = TargetingRules.empty();

    /** The signals that decide whether the candidate is healthy, each with a direction and a threshold. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "health_metrics", columnDefinition = "jsonb")
    private List<HealthMetricSpec> healthMetrics = List.of();

    /** When the platform may withdraw the candidate without a human. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rollback_trigger", columnDefinition = "jsonb")
    private RollbackTrigger rollbackTrigger = RollbackTrigger.defaults();

    /** Set to the percentage the flag held at the moment of a rollback, so it can be resumed knowingly. */
    @Column(name = "percentage_before_rollback")
    private Integer percentageBeforeRollback;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected FeatureFlag() {
    }

    public FeatureFlag(String key, String description, String environment, String createdBy) {
        this.key = key;
        this.description = description;
        this.environment = environment;
        this.createdBy = createdBy;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Project to the wire shape the SDK and the shared {@code TargetingEngine} consume. */
    public FlagDefinition toDefinition() {
        return new FlagDefinition(id, key, state, rolloutPercentage, baselineConfig, candidateConfig,
                targetingRules, healthMetrics, rollbackTrigger, version, updatedAt);
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public FlagState getState() { return state; }
    public void setState(FlagState state) { this.state = state; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public void setRolloutPercentage(int rolloutPercentage) { this.rolloutPercentage = rolloutPercentage; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Map<String, Object> getBaselineConfig() { return baselineConfig; }
    public void setBaselineConfig(Map<String, Object> c) { this.baselineConfig = c == null ? Map.of() : c; }
    public Map<String, Object> getCandidateConfig() { return candidateConfig; }
    public void setCandidateConfig(Map<String, Object> c) { this.candidateConfig = c == null ? Map.of() : c; }
    public TargetingRules getTargetingRules() { return targetingRules; }
    public void setTargetingRules(TargetingRules r) { this.targetingRules = r == null ? TargetingRules.empty() : r; }
    public List<HealthMetricSpec> getHealthMetrics() { return healthMetrics; }
    public void setHealthMetrics(List<HealthMetricSpec> m) { this.healthMetrics = m == null ? List.of() : m; }
    public RollbackTrigger getRollbackTrigger() { return rollbackTrigger; }
    public void setRollbackTrigger(RollbackTrigger t) { this.rollbackTrigger = t == null ? RollbackTrigger.defaults() : t; }
    public Integer getPercentageBeforeRollback() { return percentageBeforeRollback; }
    public void setPercentageBeforeRollback(Integer p) { this.percentageBeforeRollback = p; }
    public long getVersion() { return version; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
