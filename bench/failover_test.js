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
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    // Test acceptance threshold: error rate must remain strictly below 2.0% during the node kill drill
    http_req_failed: ['rate<0.02'], 
    http_req_duration: ['p(95)<250', 'p(99)<400'],
  },
};

const BASE_URL = __ENV.ROUTER_URL || 'http://127.0.0.1:8080';
const CLIENT_TIMEOUT = __ENV.CLIENT_TIMEOUT || '2s';

export function setup() {
  http.get(`${BASE_URL}/reset_drill_clock`, { timeout: '2s' });
  return { ready: true };
}

export default function () {
  const res = http.get(`${BASE_URL}/bench_warm_1`, { 
    redirects: 0,
    timeout: CLIENT_TIMEOUT
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
