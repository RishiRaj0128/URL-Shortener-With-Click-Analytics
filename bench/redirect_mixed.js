import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '3s', target: 10 },
    { duration: '10s', target: 25 },
    { duration: '3s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

// 90/10 Zipfian distribution: 3 hot keys get 90% traffic, 27 cold keys get 10% traffic
const HOT_KEYS = ['bench_warm_1', 'bench_warm_2', 'bench_warm_3'];
const COLD_KEYS = Array.from({ length: 27 }, (_, i) => `bench_longtail_${i}`);

export default function () {
  const isHot = Math.random() < 0.90;
  const code = isHot
    ? HOT_KEYS[Math.floor(Math.random() * HOT_KEYS.length)]
    : COLD_KEYS[Math.floor(Math.random() * COLD_KEYS.length)];

  const res = http.get(`${BASE_URL}/${code}`, { 
    redirects: 0,
    responseCallback: http.expectedStatuses(200, 302, 404),
  });

  check(res, {
    'status is valid (302/404/200)': (r) => r.status === 302 || r.status === 404 || r.status === 200,
  });

  sleep(0.03); // 30ms pacing
}

export function handleSummary(data) {
  return {
    'bench/results/redirect_mixed_summary.json': JSON.stringify(data, null, 2),
  };
}
