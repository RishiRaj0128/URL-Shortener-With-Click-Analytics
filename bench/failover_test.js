import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    failover_steady_traffic: {
      executor: 'constant-vus',
      vus: 15,
      duration: '25s',
    },
  },
  thresholds: {
    // Test acceptance threshold: error rate must remain strictly below 2.0% during the node kill drill
    http_req_failed: ['rate<0.02'], 
    http_req_duration: ['p(95)<150'], // 95% of requests under 150ms even with upstream retry handoff
  },
};

const BASE_URL = __ENV.ROUTER_URL || 'http://127.0.0.1:8080';

export default function () {
  const res = http.get(`${BASE_URL}/bench_warm_1`, { 
    redirects: 0,
    timeout: '5s'
  });

  check(res, {
    'status is 302 or 200': (r) => r.status === 302 || r.status === 200,
  });

  sleep(0.05); // 50ms inter-request pacing
}

export function handleSummary(data) {
  const outputPath = __ENV.SUMMARY_PATH || 'bench/results/failover_summary.json';
  return {
    [outputPath]: JSON.stringify(data, null, 2),
  };
}
