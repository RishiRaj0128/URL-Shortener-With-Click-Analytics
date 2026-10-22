import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';

const targetUrl = __ENV.TARGET_URL || 'http://app-1:8081/bench_warm_1';
const rate = parseInt(__ENV.RATE || '50', 10);
const preAllocatedVUs = parseInt(__ENV.PRE_ALLOCATED_VUS || '100', 10);
const maxVUs = parseInt(__ENV.MAX_VUS || '1000', 10);

const measuredReqDuration = new Trend('measured_req_duration');
const measuredStatus302 = new Counter('measured_status_302');
const measuredStatusNon302 = new Counter('measured_status_non_302');
const measuredReqs = new Counter('measured_reqs');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: '15s',
      startTime: '0s',
      preAllocatedVUs: preAllocatedVUs,
      maxVUs: maxVUs,
    },
    measurement: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: '60s',
      startTime: '15s',
      preAllocatedVUs: preAllocatedVUs,
      maxVUs: maxVUs,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const isMeasurement = (exec.scenario.name === 'measurement');
  const params = {
    redirects: 0,
    tags: { name: isMeasurement ? 'probe_measurement' : 'probe_warmup' },
  };

  const res = http.get(targetUrl, params);

  if (isMeasurement) {
    measuredReqs.add(1);
    measuredReqDuration.add(res.timings.duration);
    if (res.status === 302) {
      measuredStatus302.add(1);
    } else {
      measuredStatusNon302.add(1);
    }
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
    rate: rate,
    warmup_duration: '15s (discarded)',
    measurement_duration: '60s',
    metrics: {
      measured_req_duration: data.metrics.measured_req_duration ? data.metrics.measured_req_duration.values : null,
      measured_reqs: data.metrics.measured_reqs ? data.metrics.measured_reqs.values : null,
      measured_status_302: data.metrics.measured_status_302 ? data.metrics.measured_status_302.values : null,
      measured_status_non_302: data.metrics.measured_status_non_302 ? data.metrics.measured_status_non_302.values : null,
      dropped_iterations: data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values : null,
      iterations: data.metrics.iterations ? data.metrics.iterations.values : null,
    },
    raw_summary: data,
  };

  const outputFile = __ENV.OUTPUT_FILE || `/workspace/bench/results/rep_${rate}rps.json`;
  const result = {};
  result[outputFile] = JSON.stringify(summary, null, 2);
  return result;
}
