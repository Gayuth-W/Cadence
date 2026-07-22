-- Cadence baseline schema.
-- Hibernate runs with ddl-auto=validate; this file is the single source of truth for the schema.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- Identity: human operators of the control plane
-- ---------------------------------------------------------------------------
CREATE TABLE user_account (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_role (
    user_id UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    role    VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- ---------------------------------------------------------------------------
-- Identity: machine credentials for the SDK data plane
-- ---------------------------------------------------------------------------
CREATE TABLE api_key (
    id           UUID PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    environment  VARCHAR(50)  NOT NULL,
    key_prefix   VARCHAR(20)  NOT NULL UNIQUE,   -- public, non-secret; the lookup index
    key_hash     VARCHAR(64)  NOT NULL,          -- SHA-256 hex of the plaintext key
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_api_key_environment ON api_key (environment);

CREATE TABLE api_key_scope (
    api_key_id UUID        NOT NULL REFERENCES api_key (id) ON DELETE CASCADE,
    scope      VARCHAR(30) NOT NULL,
    PRIMARY KEY (api_key_id, scope)
);

-- ---------------------------------------------------------------------------
-- Flags
-- ---------------------------------------------------------------------------
CREATE TABLE feature_flag (
    id                        UUID PRIMARY KEY,
    key                       VARCHAR(200) NOT NULL,
    description               TEXT,
    state                     VARCHAR(30)  NOT NULL DEFAULT 'OFF',
    rollout_percentage        INTEGER      NOT NULL DEFAULT 0
                                  CHECK (rollout_percentage BETWEEN 0 AND 100),
    environment               VARCHAR(50)  NOT NULL,
    baseline_config           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    candidate_config          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    targeting_rules           JSONB        NOT NULL DEFAULT '{"rules":[]}'::jsonb,
    health_metrics            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    rollback_trigger          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    percentage_before_rollback INTEGER,
    version                   BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking
    created_by                VARCHAR(100) NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Uniqueness is per environment, not global. The same flag key must be able to exist in
-- `staging` and `production` at different rollout percentages; that is the whole point of
-- scoping SDK API keys to an environment. A global UNIQUE(key) would make the second
-- environment's flag uncreatable.
ALTER TABLE feature_flag ADD CONSTRAINT uq_flag_key_environment UNIQUE (key, environment);

CREATE INDEX idx_flag_environment_state ON feature_flag (environment, state);

-- A flag that reads FULLY_ON at 60% is a bug the application layer should never be able to persist.
-- The state machine lives in FeatureFlagService; this constraint makes it unrepresentable in the DB too.
ALTER TABLE feature_flag ADD CONSTRAINT chk_flag_state_percentage CHECK (
    (state = 'OFF'          AND rollout_percentage = 0)   OR
    (state = 'SHADOW'       AND rollout_percentage = 0)   OR
    (state = 'ROLLED_BACK'  AND rollout_percentage = 0)   OR
    (state = 'FULLY_ON'     AND rollout_percentage = 100) OR
    (state = 'ROLLING_OUT'  AND rollout_percentage BETWEEN 1 AND 99) OR
    (state = 'PAUSED'       AND rollout_percentage BETWEEN 0 AND 100)
);

-- ---------------------------------------------------------------------------
-- Audit trail
-- ---------------------------------------------------------------------------
CREATE TABLE audit_record (
    id         UUID PRIMARY KEY,
    flag_id    UUID,                       -- deliberately not a FK: the trail outlives the flag
    flag_key   VARCHAR(200),
    action     VARCHAR(50)  NOT NULL,
    actor      VARCHAR(100) NOT NULL,      -- authenticated username, or 'SYSTEM'
    old_value  TEXT,
    new_value  TEXT,
    reason     TEXT,
    metadata   JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_flag_time ON audit_record (flag_id, created_at DESC);
CREATE INDEX idx_audit_actor     ON audit_record (actor, created_at DESC);
CREATE INDEX idx_audit_action    ON audit_record (action);

-- Immutability, enforced where it actually counts.
--
-- Hibernate's @Immutable and the absence of setters stop the *application* from rewriting history.
-- Neither survives someone with a psql prompt, which is precisely the person an audit log exists to
-- keep honest. This trigger is the only one of the three defences that a DBA cannot simply bypass.
CREATE OR REPLACE FUNCTION audit_record_is_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_record is append-only: % on row % is not permitted', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_record_no_update
    BEFORE UPDATE ON audit_record
    FOR EACH ROW EXECUTE FUNCTION audit_record_is_append_only();

CREATE TRIGGER trg_audit_record_no_delete
    BEFORE DELETE ON audit_record
    FOR EACH ROW EXECUTE FUNCTION audit_record_is_append_only();

-- ---------------------------------------------------------------------------
-- Metric snapshots (Redis holds the live windows; Postgres holds the history)
-- ---------------------------------------------------------------------------
CREATE TABLE metric_snapshot (
    id                    UUID PRIMARY KEY,
    flag_id               UUID         NOT NULL,
    flag_key              VARCHAR(200) NOT NULL,
    variant_key           VARCHAR(40)  NOT NULL,   -- baseline | candidate | candidate-shadow
    window_type           VARCHAR(20)  NOT NULL,
    sample_count          BIGINT       NOT NULL,
    error_count           BIGINT       NOT NULL,
    error_rate            DOUBLE PRECISION NOT NULL,
    mean_latency_ms       DOUBLE PRECISION NOT NULL,
    stddev_latency_ms     DOUBLE PRECISION NOT NULL,
    p50_latency_ms        DOUBLE PRECISION NOT NULL,
    p95_latency_ms        DOUBLE PRECISION NOT NULL,
    p99_latency_ms        DOUBLE PRECISION NOT NULL,
    throughput_per_minute DOUBLE PRECISION NOT NULL,
    trend                 VARCHAR(20)  NOT NULL DEFAULT 'STABLE',
    custom_metrics        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    rollout_percentage    INTEGER      NOT NULL,
    captured_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshot_flag_time    ON metric_snapshot (flag_id, captured_at);
CREATE INDEX idx_snapshot_flag_variant ON metric_snapshot (flag_id, variant_key);

-- ---------------------------------------------------------------------------
-- Staged rollout schedules
-- ---------------------------------------------------------------------------
CREATE TABLE rollout_schedule (
    id                  UUID PRIMARY KEY,
    flag_id             UUID         NOT NULL REFERENCES feature_flag (id) ON DELETE CASCADE,
    flag_key            VARCHAR(200) NOT NULL,
    stages              JSONB        NOT NULL,
    current_stage_index INTEGER      NOT NULL DEFAULT -1,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    started_at          TIMESTAMPTZ,
    next_transition_at  TIMESTAMPTZ,   -- durable deadline: survives restarts, unlike an in-memory timer
    completed_at        TIMESTAMPTZ,
    created_by          VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_schedule_status_next ON rollout_schedule (status, next_transition_at);
CREATE INDEX idx_schedule_flag        ON rollout_schedule (flag_id);
