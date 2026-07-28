import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, ADMIN_USERNAME, ADMIN_PASSWORD } from './config.js';

// Cadence has no public self-registration (user creation is ADMIN-only), so this signs in with the
// seeded admin account and fails loudly if the control plane is unreachable or the seed is missing —
// rather than silently running an unauthenticated load test.
export function loginAsAdmin() {
  const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username: ADMIN_USERNAME,
    password: ADMIN_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });

  const ok = check(res, {
    'login is 200': (r) => r.status === 200,
    'login returns a token': (r) => !!r.json('token'),
  });
  if (!ok) {
    fail(`Admin login failed (status ${res.status}). Is the stack up on ${BASE_URL} and seeded?`);
  }
  return res.json('token');
}

// A distinct userId per VU+iteration so murmur3 bucketing spreads evaluations across both variants.
export function userId() {
  return `k6-${__VU}-${__ITER}`;
}
