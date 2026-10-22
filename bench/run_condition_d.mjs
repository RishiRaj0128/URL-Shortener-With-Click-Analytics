import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

function runCmd(cmd) {
  try {
    return execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (err) {
    return (err.stdout || '') + '\n' + (err.stderr || '');
  }
}

function parseGcLogs(logs) {
  let longestPause = 0.0;
  const pauseRegex = /Pause\s+[A-Za-z\s()]+\s+(\d+\.\d+)ms/g;
  let match;
  while ((match = pauseRegex.exec(logs)) !== null) {
    const val = parseFloat(match[1]);
    if (val > longestPause) longestPause = val;
  }
  return longestPause;
}

function getHikariMetrics() {
  const active = parseFloat(runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/metrics/hikaricp.connections.active').match(/"value":\s*(\d+(\.\d+)?)/)?.[1] || '0');
  const pending = parseFloat(runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/metrics/hikaricp.connections.pending').match(/"value":\s*(\d+(\.\d+)?)/)?.[1] || '0');
  return { active, pending };
}

function getPrometheusMetric(promText, metricPrefix) {
  const lines = promText.split('\n');
  for (const line of lines) {
    if (line.startsWith(metricPrefix)) {
      const parts = line.trim().split(/\s+/);
      const val = parseFloat(parts[parts.length - 1]);
      if (!isNaN(val)) return val;
    }
  }
  return 0;
}

async function main() {
  console.log('Checking app-1 health...');
  let healthy = false;
  for (let i = 0; i < 30; i++) {
    const health = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/health');
    if (health.includes('"status":"UP"')) {
      console.log('app-1 is UP!');
      healthy = true;
      break;
    }
    console.log(`Waiting for app-1 to become UP (attempt ${i + 1}/30)...`);
    await new Promise(r => setTimeout(r, 2000));
  }

  if (!healthy) {
    console.error('app-1 failed to become healthy!');
    process.exit(1);
  }

  // Warmup curl
  runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/bench_warm_1');

  console.log('Recording baselines...');
  const logsBefore = runCmd('docker logs urlshortener-node-1');
  const promBefore = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus');

  const outputFile = '/workspace/bench/results/discrim_d_raw.json';
  const hostOutputFile = path.resolve('bench/results/discrim_d_raw.json');

  const k6Cmd = `docker run --rm --network urlshortenerwithclickanalytics_default -v "${process.cwd()}:/workspace" -w /workspace -e RATE=200 -e PRE_ALLOCATED_VUS=100 -e MAX_VUS=1000 -e OUTPUT_FILE=${outputFile} grafana/k6:latest run bench/k6_in_network_repeatability.js`;

  console.log('Running k6 condition (d) at 200 RPS...');
  runCmd(k6Cmd);

  const logsAfter = runCmd('docker logs urlshortener-node-1');
  const newLogs = logsAfter.substring(logsBefore.length);
  const promAfter = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus');

  const longestGcPause = parseGcLogs(newLogs);
  const timeoutMatches = newLogs.match(/Redis command timed out/g) || [];
  const lettuceTimeouts = timeoutMatches.length;

  const hikari = getHikariMetrics();

  let k6Data = {};
  if (fs.existsSync(hostOutputFile)) {
    k6Data = JSON.parse(fs.readFileSync(hostOutputFile, 'utf8'));
  }
  const measured = k6Data.metrics?.measured_req_duration || {};
  const drops = k6Data.metrics?.dropped_iterations?.count || 0;

  const record = {
    experiment: 'd',
    name: 'cache_bypassed',
    config: {
      APP_QUEUE_PUBLISH_ENABLED: "true",
      SPRING_REDIS_TIMEOUT: "50ms",
      SPRING_DATA_REDIS_TIMEOUT: "50ms",
      SPRING_CACHE_TYPE: "none"
    },
    p50: measured.med || 0,
    p90: measured['p(90)'] || 0,
    p95: measured['p(95)'] || 0,
    p99: measured['p(99)'] || 0,
    avg: measured.avg || 0,
    dropped_iterations: drops,
    longest_gc_pause_ms: longestGcPause,
    lettuce_command_timeouts: lettuceTimeouts,
    hikari_active: hikari.active,
    hikari_pending: hikari.pending,
    cache_hit_ratio: 0.0,
    raw_file: 'bench/results/discrim_d_raw.json'
  };

  console.log('Condition (d) result:', record);

  // Update discriminating_summary.json
  const summaryPath = path.resolve('bench/results/discriminating_summary.json');
  let summary = [];
  if (fs.existsSync(summaryPath)) {
    summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
  }
  const idx = summary.findIndex(s => s.experiment === 'd');
  if (idx >= 0) {
    summary[idx] = record;
  } else {
    summary.push(record);
  }
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8');
  console.log('Updated bench/results/discriminating_summary.json');
}

main().catch(console.error);
