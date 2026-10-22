import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Mixed Zipfian Distribution Benchmark.
 * Simulates real-world power law access:
 * Top 10% hottest URLs receive ~80% of click traffic (Cache Hits),
 * Long-tail 90% receive remaining 20% of traffic (Cache Misses).
 */
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '250', 10);

export const options = {
  scenarios: {
    mixed_zipfian_traffic: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
    ramp_saturation: {
      executor: 'ramping-arrival-rate',
      startRate: TARGET_RPS,
      timeUnit: '1s',
      startTime: '21s',
      stages: [
        { target: TARGET_RPS * 2, duration: '10s' },
        { target: 0, duration: '3s' },
      ],
      preAllocatedVUs: 40,
      maxVUs: 200,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<80'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const HOT_KEYS = ['bench_warm_1', 'bench_warm_2', 'bench_warm_3'];
const COLD_KEYS = Array.from({ length: 50 }, (_, i) => `bench_longtail_${i}`);

export function setup() {
  for (const code of HOT_KEYS) {
    http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  }
  return { ready: true };
}

export default function () {
  // Zipfian 80/20 distribution
  const isHot = Math.random() < 0.80;
  const code = isHot
    ? HOT_KEYS[Math.floor(Math.random() * HOT_KEYS.length)]
    : COLD_KEYS[Math.floor(Math.random() * COLD_KEYS.length)];

  const res = http.get(`${BASE_URL}/${code}`, { 
    redirects: 0,
    timeout: '3s',
    responseCallback: http.expectedStatuses(200, 302, 404),
  });

  check(res, {
    'status is valid (302/404/200)': (r) => r.status === 302 || r.status === 404 || r.status === 200,
  });
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/redirect_mixed_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
