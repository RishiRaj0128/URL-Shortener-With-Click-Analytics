import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '3s', target: 10 },  // Ramp-up to 10 VUs
    { duration: '10s', target: 30 }, // Peak at 30 VUs
    { duration: '3s', target: 0 },   // Ramp-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'], // Under 5% error rate allowed
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
// Target 3 warm URLs
const WARM_CODES = ['bench_warm_1', 'bench_warm_2', 'bench_warm_3'];

export function setup() {
  // Pre-warm the cache by making initial calls
  for (const code of WARM_CODES) {
    http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  }
  return { ready: true };
}

export default function () {
  const code = WARM_CODES[Math.floor(Math.random() * WARM_CODES.length)];
  const res = http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  
  check(res, {
    'status is 302 or 200': (r) => r.status === 302 || r.status === 200,
  });

  sleep(0.02); // 20ms pacing
}

export function handleSummary(data) {
  return {
    'bench/results/redirect_warm_summary.json': JSON.stringify(data, null, 2),
  };
}
