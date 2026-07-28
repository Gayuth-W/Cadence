package com.cadence.flagservice.security.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A per-environment machine credential for the SDK's data plane.
 *
 * <p>Stored as a SHA-256 hash, never in plaintext: a leaked database backup must not hand an attacker
 * working keys. The plaintext is returned exactly once, at creation time, and is unrecoverable
 * afterwards.
 *
 * <p>The {@code prefix} (first 12 chars, e.g. {@code cad_live_9f2a}) is stored in the clear so an
 * operator can identify a key in the UI and in logs without the platform ever holding the secret.
 * It is also the lookup index: without it, verifying a key would mean bcrypt-comparing against every
 * row on every SDK request.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String environment;

    /** Public, non-secret identifier of the key. Indexed. */
    @Column(name = "key_prefix", nullable = false, unique = true, length = 20)
    private String keyPrefix;

    /** SHA-256 of the full plaintext key, hex-encoded. */
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "api_key_scope", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "scope", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {
    }

    public ApiKey(String name, String environment, String keyPrefix, String keyHash,
                  Set<ApiKeyScope> scopes, String createdBy) {
        this.name = name;
        this.environment = environment;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.scopes = EnumSet.copyOf(scopes);
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEnvironment() { return environment; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public Set<ApiKeyScope> getScopes() { return scopes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
