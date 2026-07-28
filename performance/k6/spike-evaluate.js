import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, FLAG_KEY, apiKeyHeaders } from './common/config.js';
import { userId } from './common/auth.js';

// A sudden jump to heavy evaluation traffic, then a drop — tests burst resilience and recovery, the
// realistic shape when a feature flag is flipped on for a launch.
export const options = {
  scenarios: {
    spike_evaluate: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: Number(__ENV.K6_SPIKE_BASE || 20) },
        { duration: '20s', target: Number(__ENV.K6_SPIKE_PEAK || 250) },
        { duration: '30s', target: Number(__ENV.K6_SPIKE_PEAK || 250) },
        { duration: '20s', target: Number(__ENV.K6_SPIKE_BASE || 20) },
        { duration: '20s', target: 0 },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<1500'] },
};

export default function () {
  const body = JSON.stringify({
    flagKey: FLAG_KEY, userId: userId(), country: 'US', segments: [], attributes: {},
  });
  const res = http.post(`${BASE_URL}/sdk/v1/evaluate`, body, apiKeyHeaders());
  check(res, { 'evaluate 200': (r) => r.status === 200 });
}
