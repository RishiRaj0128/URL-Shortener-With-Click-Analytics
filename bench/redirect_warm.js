import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Open-loop benchmark for Cache-Hit Redirects.
 * Evaluates constant-arrival-rate at identical RPS to DB-miss (cold),
 * followed by a ramp to identify the saturation cliff.
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
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<50'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const WARM_CODES = ['bench_warm_1', 'bench_warm_2', 'bench_warm_3'];

export function setup() {
  for (const code of WARM_CODES) {
    http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  }
  return { ready: true };
}

export default function () {
  const code = WARM_CODES[Math.floor(Math.random() * WARM_CODES.length)];
  const res = http.get(`${BASE_URL}/${code}`, { 
    redirects: 0,
    timeout: '3s'
  });
  
  check(res, {
    'status is 302 or 200': (r) => r.status === 302 || r.status === 200,
  });
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/redirect_warm_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
