// Shared configuration for the Cadence k6 suite.
// Every value can be overridden with a K6_* environment variable.

export const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080'; // control plane
export const DEMO_URL = __ENV.K6_DEMO_URL || 'http://localhost:8081'; // demo app

export const ADMIN_USERNAME = __ENV.K6_ADMIN_USERNAME || 'admin';
export const ADMIN_PASSWORD = __ENV.K6_ADMIN_PASSWORD || 'cadence-admin-2026';

// The seeded development service key (scopes: FLAGS_READ + EVENTS_WRITE).
export const API_KEY = __ENV.K6_API_KEY || 'cad_development_ZGVtby1rZXktZG8tbm90LXVzZS1pbi1wcm9k';
export const FLAG_KEY = __ENV.K6_FLAG_KEY || 'pricing.engine.v2';

export function bearer(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

export function apiKeyHeaders() {
  return { headers: { 'X-Cadence-Api-Key': API_KEY, 'Content-Type': 'application/json' } };
}

// Default pass/fail gates. Individual scripts tighten or relax these per workload.
export const SHARED_THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500'],
};
