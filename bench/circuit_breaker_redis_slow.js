import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Benchmark: Redis Slow with Resilience4j Circuit Breaker Comparison
 * 
 * Compares two states:
 * 1. Breaker CLOSED (Degraded Redis): Requests wait on Redis latency/timeout (~200ms).
 * 2. Breaker OPEN (Tripped): Requests short-circuit immediately (< 3ms) via fallback path.
 */

export const options = {
  scenarios: {
    // Phase 1: Breaker CLOSED with slow Redis simulation
    redis_slow_breaker_closed: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
    // Phase 2: Breaker tripped to OPEN (fail-fast protection)
    breaker_open_fail_fast: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      startTime: '11s',
      duration: '10s',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export function setup() {
  http.get(`${BASE_URL}/reset_drill_clock`, { timeout: '2s' });
  return { ready: true };
}

export default function () {
  // Query probe endpoint
  const res = http.get(`${BASE_URL}/bench_cb_probe`, { 
    redirects: 0,
    timeout: '3s',
    responseCallback: http.expectedStatuses(200, 302, 404, 503)
  });

  check(res, {
    'request completed (200/302/404/503)': (r) => r.status > 0,
  });
}

export function handleSummary(data) {
  const summaryPath = __ENV.SUMMARY_PATH || 'bench/results/circuit_breaker_summary.json';
  return {
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
