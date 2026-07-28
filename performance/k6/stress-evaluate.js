import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, FLAG_KEY, apiKeyHeaders } from './common/config.js';
import { userId } from './common/auth.js';

// Ramps evaluation load up to find the saturation point and observe how latency grows before any
// failures appear (graceful degradation vs cliff).
export const options = {
  scenarios: {
    ramp_evaluate: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: Number(__ENV.K6_STRESS_T1 || 50) },
        { duration: '2m', target: Number(__ENV.K6_STRESS_T2 || 150) },
        { duration: '1m', target: Number(__ENV.K6_STRESS_T3 || 150) },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<800'] },
};

export default function () {
  const body = JSON.stringify({
    flagKey: FLAG_KEY, userId: userId(), country: 'US', segments: [], attributes: {},
  });
  const res = http.post(`${BASE_URL}/sdk/v1/evaluate`, body, apiKeyHeaders());
  check(res, { 'evaluate 200': (r) => r.status === 200 });
}
