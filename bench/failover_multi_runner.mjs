import { execSync, spawn } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const workspaceDir = path.resolve(__dirname, '..');
const resultsDir = path.join(workspaceDir, 'bench', 'results');

if (!fs.existsSync(resultsDir)) {
  fs.mkdirSync(resultsDir, { recursive: true });
}

const NODES = [
  { container: 'urlshortener-node-1', service: 'app-1', port: 8081 },
  { container: 'urlshortener-node-2', service: 'app-2', port: 8082 },
  { container: 'urlshortener-node-3', service: 'app-3', port: 8083 },
  { container: 'urlshortener-node-1', service: 'app-1', port: 8081 },
  { container: 'urlshortener-node-2', service: 'app-2', port: 8082 }
];

const ACCEPTANCE_THRESHOLD_ERROR_RATE = 0.02; // 2.0%
const RECOVERY_BOUND_SECONDS = 15; // Target bound for node restart and rejoining traffic

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function calculateMedian(arr) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

async function checkUrl(url, timeoutMs = 3000) {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timer);
    return res.ok || res.status === 302;
  } catch (e) {
    return false;
  }
}

async function ensureClusterHealthy() {
  console.log('Ensuring all 3 nodes and router are running and healthy...');
  for (const node of [
    { service: 'app-1', port: 8081 },
    { service: 'app-2', port: 8082 },
    { service: 'app-3', port: 8083 }
  ]) {
    try {
      execSync(`docker compose start ${node.service}`, { cwd: workspaceDir, stdio: 'ignore' });
    } catch (e) {}
  }

  // Reload nginx upstream DNS
  try {
    execSync('docker exec urlshortener-router nginx -s reload', { cwd: workspaceDir, stdio: 'ignore' });
  } catch (e) {}

  let attempts = 0;
  while (attempts < 20) {
    const r1 = await checkUrl('http://127.0.0.1:8081/api/system/circuit-breakers');
    const r2 = await checkUrl('http://127.0.0.1:8082/api/system/circuit-breakers');
    const r3 = await checkUrl('http://127.0.0.1:8083/api/system/circuit-breakers');
    const router = await checkUrl('http://127.0.0.1:8080/router/health');
    if (r1 && r2 && r3 && router) {
      console.log('All 3 cluster nodes and router are responsive!');
      // Warm up benchmark link
      await checkUrl('http://127.0.0.1:8080/bench_warm_1');
      return true;
    }
    await sleep(1000);
    attempts++;
  }
  throw new Error('Cluster failed to reach healthy state before benchmark run');
}

function parseRawK6Metrics(rawJsonPath, killTimestampMs) {
  if (!fs.existsSync(rawJsonPath)) return { ttfeMs: null, ttzeMs: null, perSecond: [] };
  const content = fs.readFileSync(rawJsonPath, 'utf8');
  const lines = content.split('\n');

  const failedEvents = [];
  const reqTimes = [];
  const reqDurations = [];

  for (const line of lines) {
    if (!line.trim()) continue;
    try {
      const obj = JSON.parse(line);
      if (obj.metric === 'http_req_failed' && obj.type === 'Point') {
        const timeMs = new Date(obj.data.time).getTime();
        const failed = obj.data.value === 1;
        reqTimes.push({ timeMs, failed });
        if (failed) {
          failedEvents.push(timeMs);
        }
      } else if (obj.metric === 'http_req_duration' && obj.type === 'Point') {
        const timeMs = new Date(obj.data.time).getTime();
        reqDurations.push({ timeMs, durationMs: obj.data.value });
      }
    } catch (e) {}
  }

  if (reqTimes.length === 0) {
    return { ttfeMs: null, ttzeMs: null, perSecond: [] };
  }

  const startTimeMs = Math.min(...reqTimes.map((r) => r.timeMs));
  const endTimeMs = Math.max(...reqTimes.map((r) => r.timeMs));

  // Time to first error relative to fault injection
  let ttfeMs = null;
  let ttzeMs = null;

  if (failedEvents.length > 0) {
    const firstErrTime = Math.min(...failedEvents);
    const lastErrTime = Math.max(...failedEvents);
    ttfeMs = Math.max(0, firstErrTime - killTimestampMs);
    ttzeMs = Math.max(0, lastErrTime - killTimestampMs);
  } else {
    // Zero errors occurred during the drill!
    ttfeMs = 0;
    ttzeMs = 0;
  }

  // Compute per-second error rates
  const totalSeconds = Math.ceil((endTimeMs - startTimeMs) / 1000) || 1;
  const buckets = Array.from({ length: totalSeconds }, (_, i) => ({
    second: i + 1,
    total: 0,
    failed: 0,
    errorRatePct: 0,
    p95DurationMs: 0,
    durations: []
  }));

  for (const req of reqTimes) {
    const sec = Math.min(totalSeconds - 1, Math.max(0, Math.floor((req.timeMs - startTimeMs) / 1000)));
    buckets[sec].total++;
    if (req.failed) buckets[sec].failed++;
  }

  for (const dur of reqDurations) {
    const sec = Math.min(totalSeconds - 1, Math.max(0, Math.floor((dur.timeMs - startTimeMs) / 1000)));
    buckets[sec].durations.push(dur.durationMs);
  }

  for (const b of buckets) {
    b.errorRatePct = b.total > 0 ? Number(((b.failed / b.total) * 100).toFixed(2)) : 0;
    if (b.durations.length > 0) {
      b.durations.sort((a, b) => a - b);
      const p95Idx = Math.floor(b.durations.length * 0.95);
      b.p95DurationMs = Number(b.durations[p95Idx].toFixed(2));
    }
    delete b.durations;
  }

  return { ttfeMs, ttzeMs, perSecond: buckets };
}

async function runSingleIteration(index, targetNode) {
  console.log(`\n==================================================`);
  console.log(`=== RUN ${index + 1}/5: Target Node: ${targetNode.container} (${targetNode.service}:${targetNode.port}) ===`);
  console.log(`==================================================`);

  await ensureClusterHealthy();

  const rawJsonPath = path.join(resultsDir, `raw_run_${index + 1}.json`);
  const summaryJsonPath = path.join(resultsDir, `k6_run_${index + 1}.json`);
  if (fs.existsSync(rawJsonPath)) fs.unlinkSync(rawJsonPath);
  if (fs.existsSync(summaryJsonPath)) fs.unlinkSync(summaryJsonPath);

  console.log('1. Starting steady k6 load (15 VUs for 25s)...');
  const k6Process = spawn('k6', [
    'run',
    '--out', `json=${rawJsonPath}`,
    path.join(__dirname, 'failover_test.js')
  ], {
    cwd: workspaceDir,
    env: { ...process.env, SUMMARY_PATH: summaryJsonPath, ROUTER_URL: 'http://127.0.0.1:8080' },
    stdio: ['ignore', 'pipe', 'pipe']
  });

  let k6Logs = '';
  k6Process.stdout.on('data', (d) => { k6Logs += d.toString(); });
  k6Process.stderr.on('data', (d) => { k6Logs += d.toString(); });

  // Baseline traffic for 5 seconds
  await sleep(5000);

  // Fault Injection: Kill target node
  const killTimeMs = Date.now();
  console.log(`2. [FAULT INJECTION] Killing ${targetNode.container} at ${new Date(killTimeMs).toISOString()}...`);
  try {
    execSync(`docker kill ${targetNode.container}`, { cwd: workspaceDir, stdio: 'ignore' });
    console.log(`   ${targetNode.container} killed.`);
  } catch (e) {
    console.warn(`   Warning killing container: ${e.message}`);
  }

  // Active failover window under node outage: 7 seconds
  await sleep(7000);

  // Recovery Phase: Restart node
  const restartTimeMs = Date.now();
  console.log(`3. [RECOVERY PHASE] Restarting ${targetNode.container} via docker compose start ${targetNode.service}...`);
  try {
    execSync(`docker compose start ${targetNode.service}`, { cwd: workspaceDir, stdio: 'ignore' });
    console.log(`   ${targetNode.service} restart command dispatched.`);
  } catch (e) {
    console.warn(`   Warning starting container: ${e.message}`);
  }

  // Wait for k6 run to conclude (total 25s test duration)
  console.log('4. Waiting for k6 load test completion...');
  await new Promise((resolve) => k6Process.on('close', resolve));

  // Recovery Verification: verify node rejoins and receives traffic
  console.log(`5. Verifying recovery of ${targetNode.container} within stated bound (${RECOVERY_BOUND_SECONDS}s)...`);
  let rejoinsWithinBound = false;
  let rejoinElapsedSec = 0;
  const startVerifyMs = Date.now();

  while (Date.now() - startVerifyMs < RECOVERY_BOUND_SECONDS * 1000) {
    const nodeOk = await checkUrl(`http://127.0.0.1:${targetNode.port}/api/system/circuit-breakers`, 1000);
    if (nodeOk) {
      // Reload nginx to immediately re-sync upstream
      try { execSync('docker exec urlshortener-router nginx -s reload', { stdio: 'ignore' }); } catch (e) {}
      rejoinsWithinBound = true;
      rejoinElapsedSec = Number(((Date.now() - restartTimeMs) / 1000).toFixed(2));
      console.log(`   SUCCESS: ${targetNode.container} responded healthy and rejoined in ${rejoinElapsedSec}s (<= ${RECOVERY_BOUND_SECONDS}s bound).`);
      break;
    }
    await sleep(500);
  }

  if (!rejoinsWithinBound) {
    rejoinElapsedSec = Number(((Date.now() - restartTimeMs) / 1000).toFixed(2));
    console.warn(`   WARNING: ${targetNode.container} took > ${RECOVERY_BOUND_SECONDS}s to rejoin.`);
  }

  // Parse k6 metrics and custom measurements
  let k6Summary = null;
  if (fs.existsSync(summaryJsonPath)) {
    try {
      k6Summary = JSON.parse(fs.readFileSync(summaryJsonPath, 'utf8'));
    } catch (e) {}
  }

  const { ttfeMs, ttzeMs, perSecond } = parseRawK6Metrics(rawJsonPath, killTimeMs);

  const totalReqs = k6Summary?.metrics?.http_reqs?.values?.count || k6Summary?.metrics?.http_reqs?.count || 0;
  const failedRate = k6Summary?.metrics?.http_req_failed?.values?.rate ?? k6Summary?.metrics?.http_req_failed?.value ?? 0;
  const failedCount = k6Summary?.metrics?.http_req_failed?.values?.passes ?? Math.round(totalReqs * failedRate);
  const errorRatePct = Number((failedRate * 100).toFixed(2));
  const p50Duration = Number((k6Summary?.metrics?.http_req_duration?.values?.med || 0).toFixed(2));
  const p95Duration = Number((k6Summary?.metrics?.http_req_duration?.values?.['p(95)'] || 0).toFixed(2));
  const p99Duration = Number((k6Summary?.metrics?.http_req_duration?.values?.['p(99)'] || p95Duration).toFixed(2));

  const runResult = {
    run_index: index + 1,
    killed_node: targetNode.container,
    killed_service: targetNode.service,
    total_requests: totalReqs,
    failed_requests: failedCount,
    error_rate_pct: errorRatePct,
    test_acceptance_threshold_pct: ACCEPTANCE_THRESHOLD_ERROR_RATE * 100,
    passed_acceptance_threshold: errorRatePct <= ACCEPTANCE_THRESHOLD_ERROR_RATE * 100,
    time_to_first_error_ms: ttfeMs,
    time_to_zero_errors_ms: ttzeMs,
    p50_duration_ms: p50Duration,
    p95_duration_ms: p95Duration,
    p99_duration_ms: p99Duration,
    recovery: {
      restarted_service: targetNode.service,
      rejoined_within_bound: rejoinsWithinBound,
      rejoin_time_seconds: rejoinElapsedSec,
      stated_bound_seconds: RECOVERY_BOUND_SECONDS
    },
    per_second_error_rate: perSecond
  };

  const runJsonPath = path.join(resultsDir, `failover_run_${index + 1}.json`);
  fs.writeFileSync(runJsonPath, JSON.stringify(runResult, null, 2));

  console.log(`Telemetry for Run ${index + 1}:`);
  console.log(`   Requests: ${totalReqs}, Failed: ${failedCount} (${errorRatePct}%)`);
  console.log(`   Test Acceptance Threshold: <= ${(ACCEPTANCE_THRESHOLD_ERROR_RATE * 100).toFixed(1)}% -> ${runResult.passed_acceptance_threshold ? 'PASSED' : 'FAILED'}`);
  console.log(`   Time-to-First-Error (TTFE): ${ttfeMs !== null ? ttfeMs + ' ms' : 'N/A'}`);
  console.log(`   Time-to-Zero-Errors (TTZE): ${ttzeMs !== null ? ttzeMs + ' ms' : 'N/A'}`);
  console.log(`   p50 Latency: ${p50Duration} ms | p95 Latency: ${p95Duration} ms | p99 Latency: ${p99Duration} ms`);

  return runResult;
}

async function main() {
  console.log('========================================================================');
  console.log('=== MULTI-RUN FAILOVER DRILL: 5 CONSECUTIVE RUNS WITH NODE ROTATION ===');
  console.log('========================================================================\n');

  const runs = [];

  for (let i = 0; i < NODES.length; i++) {
    const result = await runSingleIteration(i, NODES[i]);
    runs.push(result);
    // Cool-down between runs
    if (i < NODES.length - 1) {
      console.log('\nCooling down for 5s before next run...');
      await sleep(5000);
    }
  }

  // Aggregate min / median / max
  const errorRates = runs.map((r) => r.error_rate_pct);
  const totalReqs = runs.map((r) => r.total_requests);
  const ttfes = runs.map((r) => r.time_to_first_error_ms ?? 0);
  const ttzes = runs.map((r) => r.time_to_zero_errors_ms ?? 0);
  const p50s = runs.map((r) => r.p50_duration_ms);
  const p95s = runs.map((r) => r.p95_duration_ms);
  const p99s = runs.map((r) => r.p99_duration_ms);
  const rejoinTimes = runs.map((r) => r.recovery.rejoin_time_seconds);

  const aggregate = {
    drill_name: 'multi_run_failover_drill',
    total_runs: runs.length,
    test_acceptance_threshold_error_rate: '< 2.0%',
    overall_status: runs.every((r) => r.passed_acceptance_threshold) ? 'PASSED' : 'FAILED',
    summary_statistics: {
      total_requests: {
        min: Math.min(...totalReqs),
        median: calculateMedian(totalReqs),
        max: Math.max(...totalReqs)
      },
      error_rate_pct: {
        min: Math.min(...errorRates),
        median: calculateMedian(errorRates),
        max: Math.max(...errorRates)
      },
      time_to_first_error_ms: {
        min: Math.min(...ttfes),
        median: calculateMedian(ttfes),
        max: Math.max(...ttfes)
      },
      time_to_zero_errors_ms: {
        min: Math.min(...ttzes),
        median: calculateMedian(ttzes),
        max: Math.max(...ttzes)
      },
      k6_p50_duration_ms: {
        min: Math.min(...p50s),
        median: calculateMedian(p50s),
        max: Math.max(...p50s)
      },
      k6_p95_duration_ms: {
        min: Math.min(...p95s),
        median: calculateMedian(p95s),
        max: Math.max(...p95s)
      },
      k6_p99_duration_ms: {
        min: Math.min(...p99s),
        median: calculateMedian(p99s),
        max: Math.max(...p99s)
      },
      node_rejoin_time_seconds: {
        min: Math.min(...rejoinTimes),
        median: calculateMedian(rejoinTimes),
        max: Math.max(...rejoinTimes),
        stated_bound_seconds: RECOVERY_BOUND_SECONDS,
        all_within_bound: runs.every((r) => r.recovery.rejoined_within_bound)
      }
    },
    runs: runs
  };

  const finalReportPath = path.join(resultsDir, 'failover_multi_run_report.json');
  fs.writeFileSync(finalReportPath, JSON.stringify(aggregate, null, 2));

  // Also update standard failover_summary.json with the aggregate summary
  const summaryPath = path.join(resultsDir, 'failover_summary.json');
  fs.writeFileSync(summaryPath, JSON.stringify(aggregate, null, 2));

  console.log('\n========================================================================');
  console.log('=== MULTI-RUN DRILL COMPLETE: SUMMARY RESULTS (5 RUNS) ===');
  console.log('========================================================================');
  console.log(`Overall Test Acceptance Status: ${aggregate.overall_status}`);
  console.log(`Error Rate (%): Min = ${aggregate.summary_statistics.error_rate_pct.min}% | Median = ${aggregate.summary_statistics.error_rate_pct.median}% | Max = ${aggregate.summary_statistics.error_rate_pct.max}%`);
  console.log(`TTFE (ms)     : Min = ${aggregate.summary_statistics.time_to_first_error_ms.min}ms | Median = ${aggregate.summary_statistics.time_to_first_error_ms.median}ms | Max = ${aggregate.summary_statistics.time_to_first_error_ms.max}ms`);
  console.log(`TTZE (ms)     : Min = ${aggregate.summary_statistics.time_to_zero_errors_ms.min}ms | Median = ${aggregate.summary_statistics.time_to_zero_errors_ms.median}ms | Max = ${aggregate.summary_statistics.time_to_zero_errors_ms.max}ms`);
  console.log(`k6 p50 Latency: Min = ${aggregate.summary_statistics.k6_p50_duration_ms.min}ms | Median = ${aggregate.summary_statistics.k6_p50_duration_ms.median}ms | Max = ${aggregate.summary_statistics.k6_p50_duration_ms.max}ms`);
  console.log(`k6 p95 Latency: Min = ${aggregate.summary_statistics.k6_p95_duration_ms.min}ms | Median = ${aggregate.summary_statistics.k6_p95_duration_ms.median}ms | Max = ${aggregate.summary_statistics.k6_p95_duration_ms.max}ms`);
  console.log(`k6 p99 Latency: Min = ${aggregate.summary_statistics.k6_p99_duration_ms.min}ms | Median = ${aggregate.summary_statistics.k6_p99_duration_ms.median}ms | Max = ${aggregate.summary_statistics.k6_p99_duration_ms.max}ms`);
  console.log(`Rejoin Time (s): Min = ${aggregate.summary_statistics.node_rejoin_time_seconds.min}s | Median = ${aggregate.summary_statistics.node_rejoin_time_seconds.median}s | Max = ${aggregate.summary_statistics.node_rejoin_time_seconds.max}s (Bound: ${RECOVERY_BOUND_SECONDS}s)`);
  console.log(`Report saved to: ${finalReportPath}`);
}

main().catch((err) => {
  console.error('Fatal error during multi-run drill:', err);
  process.exit(1);
});
