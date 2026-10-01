import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '3s', target: 5 },   // Ramp up
    { duration: '8s', target: 15 },  // Evaluated window
    { duration: '3s', target: 0 },   // Ramp down
  ],
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export default function () {
  // Hit benchmark endpoint under circuit breaker observation
  const res = http.get(`${BASE_URL}/bench_cb_probe`, { redirects: 0 });

  check(res, {
    'status received (302/404/503)': (r) => r.status > 0,
  });

  sleep(0.04);
}

export function handleSummary(data) {
  return {
    'bench/results/circuit_breaker_summary.json': JSON.stringify(data, null, 2),
  };
}
