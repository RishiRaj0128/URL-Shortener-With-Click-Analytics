import http from 'k6/http';
import { check } from 'k6';

/**
 * Cold Cache Miss Saturation Ramp against real PostgreSQL through Nginx router.
 * Evaluates real pre-seeded URLs (cold_key_1 .. cold_key_5000) returning HTTP 302.
 */
export const options = {
  scenarios: {
    cold_saturation_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      stages: [
        { target: 100, duration: '5s' },  // Stage 0: Warmup
        { target: 200, duration: '5s' },  // Step 1: 100 - 200 RPS
        { target: 350, duration: '5s' },  // Step 2: 200 - 350 RPS
        { target: 450, duration: '5s' },  // Step 3: 350 - 450 RPS (Capacity Knee)
        { target: 550, duration: '5s' },  // Step 4: 450 - 550 RPS (Connection pool contention)
      ],
      preAllocatedVUs: 300,
      maxVUs: 1200,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = __ENV.ROUTER_URL || 'http://127.0.0.1:8080';

export default function () {
  const keyIdx = ((__VU * 1000) + __ITER) % 5000 + 1;
  const code = `cold_key_${keyIdx}`;
  
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
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/cold_saturation_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
