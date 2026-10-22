import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Open-loop benchmark for Cache-Miss (Cold Database Path) Redirects.
 * Evaluates constant-arrival-rate at identical RPS to cache-hit (warm),
 * followed by a ramp to identify database connection-pool saturation.
 */
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '200', 10);

export const options = {
  scenarios: {
    constant_rate_comparison: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<150'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  // Query distinct pre-seeded database keys (cold_key_1 through cold_key_5000)
  // Each VU request queries a unique key to exercise the cold DB path returning 302
  const keyIndex = ((__VU * 1000) + __ITER) % 5000 + 1;
  const code = `cold_key_${keyIndex}`;
  
  const res = http.get(`${BASE_URL}/${code}`, { 
    redirects: 0,
    timeout: '3s',
    responseCallback: http.expectedStatuses(302),
  });
  
  check(res, {
    'status is 302': (r) => r.status === 302,
  });
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/redirect_cold_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
