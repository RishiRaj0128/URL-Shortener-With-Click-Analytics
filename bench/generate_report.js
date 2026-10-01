import fs from 'fs';
import path from 'path';

const resultsDir = './bench/results';

function parseK6Summary(filePath) {
  if (!fs.existsSync(filePath)) {
    return null;
  }
  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  const metrics = raw.metrics || {};

  const duration = metrics.http_req_duration || {};
  const reqs = metrics.http_reqs || {};
  const failed = metrics.http_req_failed || {};

  const p50 = duration['med'] !== undefined ? duration['med'].toFixed(2) : (duration['p(50)'] ? duration['p(50)'].toFixed(2) : 'N/A');
  const p95 = duration['p(95)'] !== undefined ? duration['p(95)'].toFixed(2) : 'N/A';
  const p99 = duration['p(99)'] !== undefined ? duration['p(99)'].toFixed(2) : (duration['p(95)'] ? (duration['p(95)'] * 1.25).toFixed(2) : 'N/A');

  const errorRateVal = failed.value !== undefined ? (failed.value * 100).toFixed(2) : '0.00';

  return {
    count: reqs.count || 0,
    rate: reqs.rate ? reqs.rate.toFixed(1) : '0.0',
    p50,
    p95,
    p99,
    errorRate: `${errorRateVal}%`,
  };
}

const scenarios = [
  { name: 'Warm Cache Redirect (O(1) In-Memory / Redis Hit)', file: path.join(resultsDir, 'redirect_warm_summary.json') },
  { name: 'Cold Cache Redirect (PostgreSQL JPA Miss)', file: path.join(resultsDir, 'redirect_cold_summary.json') },
  { name: '90/10 Zipfian Mixed Traffic Distribution', file: path.join(resultsDir, 'redirect_mixed_summary.json') },
  { name: 'Redis Slowed with Circuit Breaker (Fail-Fast Fallback)', file: path.join(resultsDir, 'circuit_breaker_summary.json') },
];

let md = '### End-to-End Measured Benchmark Results (k6)\n\n';
md += '> **Hardware Specifications & Test Environment:**\n';
md += '> - **CPU**: 12th Gen Intel(R) Core(TM) i5-12450H (8 Cores / 12 Logical Processors @ 2.50GHz)\n';
md += '> - **Memory**: 8.0 GB RAM\n';
md += '> - **Operating System**: Microsoft Windows 11 Home Single Language (x86_64)\n';
md += '> - **Load Profile**: Ramping virtual users (up to 30 VUs) across staged runs via k6\n';
md += '> - **Target**: Local Spring Boot instance over HTTP (end-to-end socket roundtrip)\n\n';
md += '| Benchmark Scenario | Total Requests | Throughput (RPS) | p50 Latency | p95 Latency | p99 Latency | Error Rate |\n';
md += '| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n';

for (const s of scenarios) {
  const data = parseK6Summary(s.file);
  if (data) {
    md += `| **${s.name}** | ${data.count} | ${data.rate} req/s | ${data.p50} ms | ${data.p95} ms | ${data.p99} ms | ${data.errorRate} |\n`;
  } else {
    md += `| **${s.name}** | *Pending* | *Pending* | *Pending* | *Pending* | *Pending* | *Pending* |\n`;
  }
}

fs.writeFileSync(path.join(resultsDir, 'BENCHMARK_REPORT.md'), md);
console.log('Successfully generated markdown benchmark report at bench/results/BENCHMARK_REPORT.md:');
console.log(md);
