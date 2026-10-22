import fs from 'fs';
import path from 'path';

const resultsDir = path.resolve('bench/results');
const retiredDir = path.join(resultsDir, 'retired');

if (!fs.existsSync(retiredDir)) {
  fs.mkdirSync(retiredDir, { recursive: true });
}

// List of mock files to retire
const mockFiles = [
  'circuit_breaker_raw.json', 'circuit_breaker_raw.json.gz', 'circuit_breaker_summary.json',
  'redirect_cold_raw.json', 'redirect_cold_raw.json.gz', 'redirect_cold_summary.json',
  'redirect_warm_raw.json', 'redirect_warm_raw.json.gz', 'redirect_warm_summary.json',
  'redirect_mixed_raw.json', 'redirect_mixed_raw.json.gz', 'redirect_mixed_summary.json',
  'saturation_raw.json', 'saturation_raw.json.gz', 'saturation_summary.json',
  'raw_failover_2s_run_1.json', 'raw_failover_2s_run_1.json.gz', 'summary_failover_2s_run_1.json',
  'raw_failover_2s_run_2.json', 'raw_failover_2s_run_2.json.gz', 'summary_failover_2s_run_2.json',
  'raw_failover_2s_run_3.json', 'raw_failover_2s_run_3.json.gz', 'summary_failover_2s_run_3.json',
  'raw_failover_2s_run_4.json', 'raw_failover_2s_run_4.json.gz', 'summary_failover_2s_run_4.json',
  'raw_failover_2s_run_5.json', 'raw_failover_2s_run_5.json.gz', 'summary_failover_2s_run_5.json',
  'raw_failover_5s_run_1.json', 'raw_failover_5s_run_1.json.gz', 'summary_failover_5s_run_1.json',
  'raw_failover_5s_run_2.json', 'raw_failover_5s_run_2.json.gz', 'summary_failover_5s_run_2.json',
  'raw_failover_5s_run_3.json', 'raw_failover_5s_run_3.json.gz', 'summary_failover_5s_run_3.json',
  'raw_failover_5s_run_4.json', 'raw_failover_5s_run_4.json.gz', 'summary_failover_5s_run_4.json',
  'raw_failover_5s_run_5.json', 'raw_failover_5s_run_5.json.gz', 'summary_failover_5s_run_5.json'
];

for (const f of mockFiles) {
  const src = path.join(resultsDir, f);
  const dst = path.join(retiredDir, f);
  if (fs.existsSync(src)) {
    fs.renameSync(src, dst);
  }
}

console.log('Retired mock files successfully moved to bench/results/retired/');
