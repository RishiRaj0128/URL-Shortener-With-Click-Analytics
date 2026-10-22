import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const targetUrl = __ENV.TARGET_URL || 'http://app-1:8081/bench_warm_1';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    step_100: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '30s',
      startTime: '0s',
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
    step_200: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '30s',
      startTime: '30s',
      preAllocatedVUs: 150,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const status302 = new Counter('status_302');
const statusNon302 = new Counter('status_non_302');

export default function () {
  const params = {
    redirects: 0,
    tags: { name: 'redirect_probe' },
  };
  const res = http.get(targetUrl, params);

  if (res.status === 302) {
    status302.add(1);
  } else {
    statusNon302.add(1);
  }

  check(res, {
    'status is 302': (r) => r.status === 302,
  });
}

export function handleSummary(data) {
  const summary = {
    target: targetUrl,
    tool: 'k6',
    k6_location: 'container (grafana/k6)',
    metrics: data.metrics,
  };

  const outputFile = __ENV.OUTPUT_FILE || '/workspace/bench/results/in_network_stepped_ramp.json';
  const result = {};
  result[outputFile] = JSON.stringify(summary, null, 2);
  return result;
}
