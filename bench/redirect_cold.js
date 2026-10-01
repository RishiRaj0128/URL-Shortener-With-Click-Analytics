import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '3s', target: 5 },   // Ramp-up to 5 VUs
    { duration: '10s', target: 20 }, // Peak at 20 VUs
    { duration: '3s', target: 0 },   // Ramp-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'], // Server errors must stay under 5%
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  // Generate random short codes to exercise database cache-miss lookup path
  const randomSuffix = Math.floor(Math.random() * 1000000);
  const code = `cold_bench_${randomSuffix}`;
  
  const res = http.get(`${BASE_URL}/${code}`, { 
    redirects: 0,
    responseCallback: http.expectedStatuses(404, 302),
  });
  
  check(res, {
    'status is 404 or 302': (r) => r.status === 404 || r.status === 302,
  });

  sleep(0.05); // 50ms pacing
}

export function handleSummary(data) {
  return {
    'bench/results/redirect_cold_summary.json': JSON.stringify(data, null, 2),
  };
}
