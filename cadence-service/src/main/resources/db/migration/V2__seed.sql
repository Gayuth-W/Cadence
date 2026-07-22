-- Development seed data.
--
-- The three accounts below exist so a reviewer can clone the repo, run docker-compose, and see RBAC
-- actually doing something. Their passwords are BCrypt(strength 12) hashes of well-known strings and
-- they are, obviously, worthless outside a laptop. A production deployment should create its first
-- ADMIN through POST /api/v1/users and delete these rows.
--
--   admin    / cadence-admin-2026       ADMIN
--   operator / cadence-operator-2026    OPERATOR
--   viewer   / cadence-viewer-2026      VIEWER

INSERT INTO user_account (id, username, password_hash, enabled, created_at) VALUES
    ('11111111-1111-1111-1111-111111111111', 'admin',
     '$2a$12$A5bkSQg2jOhi0gVCSjjaSOxwnCnu8ZXcK2q6/T299BjIhruIiPu76', TRUE, now()),
    ('22222222-2222-2222-2222-222222222222', 'operator',
     '$2a$12$krh7v6ANfmp6OA3aDql0JeKTwNXUKoW3fHUZHXnpu1B4HGl058B5m', TRUE, now()),
    ('33333333-3333-3333-3333-333333333333', 'viewer',
     '$2a$12$7g1qCZQ.Kv24WK2/Vdo4/On89bMr3aaMX.gJlVJR78BOHzihCthG.', TRUE, now());

INSERT INTO user_role (user_id, role) VALUES
    ('11111111-1111-1111-1111-111111111111', 'ADMIN'),
    ('22222222-2222-2222-2222-222222222222', 'OPERATOR'),
    ('33333333-3333-3333-3333-333333333333', 'VIEWER');

-- ---------------------------------------------------------------------------
-- A service key for the demo application.
--
-- Plaintext:  cad_development_ZGVtby1rZXktZG8tbm90LXVzZS1pbi1wcm9k
-- Stored as:  SHA-256 of that string, hex-encoded. The platform never holds the plaintext; this row
--             exists only so `docker compose up` yields a working demo without a manual key exchange.
-- ---------------------------------------------------------------------------
INSERT INTO api_key (id, name, environment, key_prefix, key_hash, active, created_by, created_at) VALUES
    ('44444444-4444-4444-4444-444444444444', 'demo-app', 'development',
     'cad_developm',
     'a45e541310cf83e3daeb32efbe0bc2a17ead4f920b2959d57149a189b711ca33',
     TRUE, 'admin', now());

INSERT INTO api_key_scope (api_key_id, scope) VALUES
    ('44444444-4444-4444-4444-444444444444', 'FLAGS_READ'),
    ('44444444-4444-4444-4444-444444444444', 'EVENTS_WRITE');

-- ---------------------------------------------------------------------------
-- The flag the demo drives: a new pricing engine, guarded by an error-rate ceiling and a P95 ceiling.
-- Starts OFF at 0%, as every flag must.
-- ---------------------------------------------------------------------------
INSERT INTO feature_flag (
    id, key, description, state, rollout_percentage, environment,
    baseline_config, candidate_config, targeting_rules, health_metrics, rollback_trigger,
    version, created_by, created_at, updated_at
) VALUES (
    '55555555-5555-5555-5555-555555555555',
    'pricing.engine.v2',
    'New pricing engine. Baseline is the legacy calculator; candidate is the rewrite.',
    'OFF', 0, 'development',
    '{"engine":"legacy","cacheTtlSeconds":60}'::jsonb,
    '{"engine":"v2","cacheTtlSeconds":300}'::jsonb,
    '{"rules":[
        {"priority":1,"type":"SEGMENT","values":["internal"],"variant":"CANDIDATE"},
        {"priority":2,"type":"BLOCKLIST","values":["enterprise-tenant-001"],"variant":"BASELINE"}
     ]}'::jsonb,
    '[
        {"name":"error_rate","kind":"ERROR_RATE","direction":"LOWER_IS_BETTER",
         "threshold":0.02,"window":"LAST_100","minSamples":20},
        {"name":"p95_latency","kind":"LATENCY_P95","direction":"LOWER_IS_BETTER",
         "threshold":400.0,"window":"LAST_100","minSamples":20}
     ]'::jsonb,
    '{"autoRollbackEnabled":true,"consecutiveBreaches":5,"minSamples":20,"cooldownMinutes":1}'::jsonb,
    0, 'admin', now(), now()
);
