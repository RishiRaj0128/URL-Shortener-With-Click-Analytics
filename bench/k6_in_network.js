import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const targetUrl = __ENV.TARGET_URL || 'http://app-1:8081/bench_warm_1';
const rate = parseInt(__ENV.RATE || '50', 10);
const duration = __ENV.DURATION || '60s';
const preAllocatedVUs = parseInt(__ENV.PRE_ALLOCATED_VUS || '50', 10);
const maxVUs = parseInt(__ENV.MAX_VUS || '300', 10);

const status302 = new Counter('status_302');
const status200 = new Counter('status_200');
const status404 = new Counter('status_404');
const status500 = new Counter('status_500');
const status502 = new Counter('status_502');
const status503 = new Counter('status_503');
const statusOther = new Counter('status_other');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    constant_rate: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      preAllocatedVUs: preAllocatedVUs,
      maxVUs: maxVUs,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const params = {
    redirects: 0,
    tags: { name: 'redirect_probe' },
  };
  const res = http.get(targetUrl, params);

  if (res.status === 302) {
    status302.add(1);
  } else if (res.status === 200) {
    status200.add(1);
  } else if (res.status === 404) {
    status404.add(1);
  } else if (res.status === 500) {
    status500.add(1);
  } else if (res.status === 502) {
    status502.add(1);
  } else if (res.status === 503) {
    status503.add(1);
  } else {
    statusOther.add(1);
  }

  check(res, {
    'status is 302': (r) => r.status === 302,
  });
}

export function handleSummary(data) {
  const getCount = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const statusHistogram = {
    '302': getCount('status_302'),
    '200': getCount('status_200'),
    '404': getCount('status_404'),
    '500': getCount('status_500'),
    '502': getCount('status_502'),
    '503': getCount('status_503'),
    'other': getCount('status_other'),
  };

  const summary = {
    target: targetUrl,
    tool: 'k6',
    k6_location: 'container (grafana/k6)',
    rate: rate,
    duration: duration,
    metrics: {
      http_req_duration: data.metrics.http_req_duration ? data.metrics.http_req_duration.values : null,
      http_reqs: data.metrics.http_reqs ? data.metrics.http_reqs.values : null,
      http_req_failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values : null,
      dropped_iterations: data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values : null,
      iterations: data.metrics.iterations ? data.metrics.iterations.values : null,
      vus: data.metrics.vus ? data.metrics.vus.values : null,
      vus_max: data.metrics.vus_max ? data.metrics.vus_max.values : null,
    },
    status_histogram: statusHistogram,
    raw_summary: data,
  };

  const outputFile = __ENV.OUTPUT_FILE || `/workspace/bench/results/in_network_${rate}rps.json`;
  const result = {};
  result[outputFile] = JSON.stringify(summary, null, 2);
  return result;
}

