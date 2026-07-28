import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, SHARED_THRESHOLDS, bearer } from './common/config.js';
import { loginAsAdmin } from './common/auth.js';

// The console's most frequent authenticated read — a dashboard poll under load.
export const options = {
  scenarios: {
    steady_flag_reads: {
      executor: 'constant-vus',
      vus: Number(__ENV.K6_VUS || 75),
      duration: __ENV.K6_DURATION || '2m',
    },
  },
  thresholds: { ...SHARED_THRESHOLDS, http_req_duration: ['p(95)<500', 'avg<300'] },
};

export function setup() {
  return { token: loginAsAdmin() };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/v1/flags`, bearer(data.token));
  check(res, {
    'GET /flags 200': (r) => r.status === 200,
    'response is an array': (r) => Array.isArray(r.json()),
  });
}
