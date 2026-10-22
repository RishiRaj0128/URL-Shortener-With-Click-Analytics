import http from 'k6/http';
import { check } from 'k6';

/**
 * Open-loop saturation ramp benchmark.
 * Ramps arrival rate from 100 RPS to 1000 RPS to identify the saturation knee.
 */
export const options = {
  scenarios: {
    warm_saturation_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      stages: [
        { target: 100, duration: '5s' },   // Stage 0: Warm-up phase (unmeasured baseline)
        { target: 250, duration: '5s' },   // Step 1: Linear range
        { target: 500, duration: '5s' },   // Step 2: Optimal range
        { target: 750, duration: '5s' },   // Step 3: High throughput
        { target: 1000, duration: '5s' },  // Step 4: Saturation approach
        { target: 1250, duration: '5s' },  // Step 5: Knee search
        { target: 1500, duration: '5s' },  // Step 6: Saturation knee limit
        { target: 0, duration: '2s' },     // Cooldown
      ],
      preAllocatedVUs: 500,
      maxVUs: 3000,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const WARM_CODES = ['bench_warm_1', 'bench_warm_2', 'bench_warm_3'];

export default function () {
  const code = WARM_CODES[Math.floor(Math.random() * WARM_CODES.length)];
  const res = http.get(`${BASE_URL}/${code}`, {
    redirects: 0,
    timeout: '3s',
  });
  check(res, {
    'status is 302': (r) => r.status === 302,
  });
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/saturation_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
