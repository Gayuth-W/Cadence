package com.cadence.flagservice.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable line of the accountability trail: who changed what, when, from what to what, and why.
 *
 * <p>Immutability is enforced in three places, because one is not enough:
 * <ul>
 *   <li>{@link Immutable} — Hibernate will not issue an UPDATE for this entity.</li>
 *   <li>No setters — application code physically cannot mutate a persisted record.</li>
 *   <li>A Postgres trigger (see {@code V1__baseline.sql}) that raises on UPDATE or DELETE — the only
 *       one of the three that survives someone opening psql.</li>
 * </ul>
 *
 * <p>{@code actor} is the authenticated username for human actions and {@code SYSTEM} for the
 * platform's own automation. This is the field that answers "who forced the 22:14 rollback, and why".
 */
@Entity
@Table(name = "audit_record")
@Immutable
public class AuditRecord {

    @Id
    @GeneratedValue
    private UUID id;

    /** Nullable: a flag can be deleted, and the audit line for its deletion must outlive it. */
    @Column(name = "flag_id")
    private UUID flagId;

    /** Denormalised on purpose, so the trail is still readable after the flag row is gone. */
    @Column(name = "flag_key", length = 200)
    private String flagKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    /** Authenticated username, or {@code SYSTEM}. */
    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** Free text: the operator's justification, or the watcher's description of the breach. */
    @Column(columnDefinition = "text")
    private String reason;

    /**
     * Structured evidence. For an automatic rollback this carries the breaching metric, its observed
     * value, its threshold, and the diff against the last stable snapshot. For a canary decision it
     * carries the U statistic and the p-value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditRecord() {
    }

    private AuditRecord(Builder builder) {
        this.flagId = builder.flagId;
        this.flagKey = builder.flagKey;
        this.action = builder.action;
        this.actor = builder.actor;
        this.oldValue = builder.oldValue;
        this.newValue = builder.newValue;
        this.reason = builder.reason;
        this.metadata = builder.metadata;
        this.createdAt = Instant.now();
    }

    public static Builder builder(AuditAction action, String actor) {
        return new Builder(action, actor);
    }

    public UUID getId() { return id; }
    public UUID getFlagId() { return flagId; }
    public String getFlagKey() { return flagKey; }
    public AuditAction getAction() { return action; }
    public String getActor() { return actor; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getReason() { return reason; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }

    public static final class Builder {
        private final AuditAction action;
        private final String actor;
        private UUID flagId;
        private String flagKey;
        private String oldValue;
        private String newValue;
        private String reason;
        private Map<String, Object> metadata = Map.of();

        private Builder(AuditAction action, String actor) {
            this.action = action;
            this.actor = actor;
        }

        public Builder flag(UUID flagId, String flagKey) {
            this.flagId = flagId;
            this.flagKey = flagKey;
            return this;
        }

        public Builder oldValue(String oldValue) {
            this.oldValue = oldValue;
            return this;
        }

        public Builder newValue(String newValue) {
            this.newValue = newValue;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : metadata;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(this);
        }
    }
}
