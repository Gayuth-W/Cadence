import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, FLAG_KEY, SHARED_THRESHOLDS, apiKeyHeaders } from './common/config.js';
import { userId } from './common/auth.js';

// THE platform hot path. In production every gated call resolves a variant — server-side here, or in
// the in-process SDK. This measures how many evaluations/second the control plane serves, and at what
// latency, under steady concurrent load.
export const options = {
  scenarios: {
    steady_evaluate: {
      executor: 'constant-vus',
      vus: Number(__ENV.K6_VUS || 75),
      duration: __ENV.K6_DURATION || '2m',
    },
  },
  thresholds: { ...SHARED_THRESHOLDS, http_req_duration: ['p(95)<500', 'avg<200'] },
};

export default function () {
  const body = JSON.stringify({
    flagKey: FLAG_KEY, userId: userId(), country: 'US', segments: [], attributes: {},
  });
  const res = http.post(`${BASE_URL}/sdk/v1/evaluate`, body, apiKeyHeaders());
  check(res, {
    'evaluate 200': (r) => r.status === 200,
    'variant is BASELINE or CANDIDATE': (r) => r.json('variant') === 'BASELINE' || r.json('variant') === 'CANDIDATE',
  });
}
