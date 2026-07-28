import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, FLAG_KEY, apiKeyHeaders } from './common/config.js';
import { userId } from './common/auth.js';

// The write hot path: SDKs report outcome events here, and the service fans them into Redis windows
// on virtual threads. Ingestion acknowledges asynchronously (202) the moment the payload validates,
// so this measures write throughput and acknowledge latency under concurrent producers.
export const options = {
  scenarios: {
    write_events: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.K6_W1 || 10) },
        { duration: '1m', target: Number(__ENV.K6_W2 || 30) },
        { duration: '30s', target: Number(__ENV.K6_W3 || 60) },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<800'] },
};

export default function () {
  const variant = (__ITER % 2 === 0) ? 'CANDIDATE' : 'BASELINE';
  const body = JSON.stringify({
    events: [{
      flagKey: FLAG_KEY,
      variant,
      type: 'LIVE',
      latencyMs: 20 + Math.floor(Math.random() * 60),
      success: true,
      customMetrics: {},
      userId: userId(),
    }],
  });
  const res = http.post(`${BASE_URL}/sdk/v1/events`, body, apiKeyHeaders());
  check(res, { 'events accepted (202)': (r) => r.status === 202 });
}
