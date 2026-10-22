import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const experiments = [
  {
    id: 'a',
    name: 'baseline',
    env: {
      APP_QUEUE_PUBLISH_ENABLED: 'true',
      SPRING_REDIS_TIMEOUT: '50ms',
      SPRING_DATA_REDIS_TIMEOUT: '50ms',
      SPRING_CACHE_TYPE: 'redis',
    }
  },
  {
    id: 'b',
    name: 'publish_disabled',
    env: {
      APP_QUEUE_PUBLISH_ENABLED: 'false',
      SPRING_REDIS_TIMEOUT: '50ms',
      SPRING_DATA_REDIS_TIMEOUT: '50ms',
      SPRING_CACHE_TYPE: 'redis',
    }
  },
  {
    id: 'c',
    name: 'timeout_500ms',
    env: {
      APP_QUEUE_PUBLISH_ENABLED: 'true',
      SPRING_REDIS_TIMEOUT: '500ms',
      SPRING_DATA_REDIS_TIMEOUT: '500ms',
      SPRING_CACHE_TYPE: 'redis',
    }
  },
  {
    id: 'd',
    name: 'cache_bypassed',
    env: {
      APP_QUEUE_PUBLISH_ENABLED: 'true',
      SPRING_REDIS_TIMEOUT: '50ms',
      SPRING_DATA_REDIS_TIMEOUT: '50ms',
      SPRING_CACHE_TYPE: 'none',
    }
  }
];

function runCmd(cmd) {
  try {
    return execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (err) {
    return (err.stdout || '') + '\n' + (err.stderr || '');
  }
}

function updateContainerEnv(envObj) {
  // Read docker-compose.yml, update app-1 environment, and docker compose up -d --no-deps app-1
  let dc = fs.readFileSync('docker-compose.yml', 'utf8');

  // We ensure keys exist or replace them in app-1 environment block
  const lines = dc.split('\n');
  let inApp1 = false;
  let inEnv = false;
  const newLines = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim().startsWith('app-1:')) inApp1 = true;
    else if (inApp1 && line.trim().startsWith('app-2:')) inApp1 = false;

    if (inApp1 && line.trim().startsWith('environment:')) {
      inEnv = true;
      newLines.push(line);
      // inject our env keys
      newLines.push(`      APP_QUEUE_PUBLISH_ENABLED: "${envObj.APP_QUEUE_PUBLISH_ENABLED}"`);
      newLines.push(`      SPRING_REDIS_TIMEOUT: "${envObj.SPRING_REDIS_TIMEOUT}"`);
      newLines.push(`      SPRING_DATA_REDIS_TIMEOUT: "${envObj.SPRING_DATA_REDIS_TIMEOUT}"`);
      newLines.push(`      SPRING_CACHE_TYPE: "${envObj.SPRING_CACHE_TYPE}"`);
      continue;
    }

    if (inApp1 && inEnv) {
      if (line.match(/^\s+ports:/) || line.match(/^\s+depends_on:/) || line.match(/^\s+deploy:/)) {
        inEnv = false;
      } else if (line.includes('APP_QUEUE_PUBLISH_ENABLED') ||
                 line.includes('SPRING_REDIS_TIMEOUT') ||
                 line.includes('SPRING_DATA_REDIS_TIMEOUT') ||
                 line.includes('SPRING_CACHE_TYPE')) {
        continue; // skip existing to replace
      }
    }

    newLines.push(line);
  }

  fs.writeFileSync('docker-compose.yml', newLines.join('\n'), 'utf8');

  runCmd('docker compose up -d --no-deps app-1');
  // Copy latest jar and restart
  runCmd('docker cp backend/target/url-shortener-service-1.0.0.jar urlshortener-node-1:/app/app.jar');
  runCmd('docker restart urlshortener-node-1');

  // Wait for health
  console.log('Waiting for app-1 to become healthy...');
  for (let attempt = 0; attempt < 30; attempt++) {
    execSync('powershell -Command "Start-Sleep -Seconds 2"');
    const health = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/health');
    if (health.includes('"status":"UP"')) {
      console.log('app-1 is UP and healthy.');
      break;
    }
  }
  // Prime warmup probe
  runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/bench_warm_1');
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

const summary = [];

for (const exp of experiments) {
  console.log(`\n======================================================`);
  console.log(`Running Discriminating Experiment (${exp.id}): ${exp.name}`);
  console.log(`Config:`, exp.env);
  console.log(`======================================================`);

  updateContainerEnv(exp.env);

  // Record baseline log size / time
  const logsBefore = runCmd('docker logs urlshortener-node-1');
  const promBefore = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus');

  const outputFile = `/workspace/bench/results/discrim_${exp.id}_raw.json`;
  const hostOutputFile = path.resolve(`bench/results/discrim_${exp.id}_raw.json`);

  const k6Cmd = `docker run --rm --network urlshortenerwithclickanalytics_default -v "${process.cwd()}:/workspace" -w /workspace -e RATE=200 -e PRE_ALLOCATED_VUS=100 -e MAX_VUS=1000 -e OUTPUT_FILE=${outputFile} grafana/k6:latest run bench/k6_in_network_repeatability.js`;

  console.log(`Running k6 200 RPS test...`);
  runCmd(k6Cmd);

  // Capture telemetry after test
  const logsAfter = runCmd('docker logs urlshortener-node-1');
  const newLogs = logsAfter.substring(logsBefore.length);
  const promAfter = runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus');

  const longestGcPause = parseGcLogs(newLogs);
  const timeoutMatches = newLogs.match(/Redis command timed out/g) || [];
  const lettuceTimeouts = timeoutMatches.length;

  const hikari = getHikariMetrics();

  // Cache hit ratio
  const totalReqsBefore = getPrometheusMetric(promBefore, 'http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="REDIRECTION",status="302",uri="/{shortCode}"}');
  const totalReqsAfter = getPrometheusMetric(promAfter, 'http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="REDIRECTION",status="302",uri="/{shortCode}"}');
  const deltaReqs = totalReqsAfter - totalReqsBefore;

  const dbBefore = getPrometheusMetric(promBefore, 'spring_data_repository_invocations_seconds_count{exception="None",method="findByShortCode",repository="UrlRepository",state="SUCCESS"}');
  const dbAfter = getPrometheusMetric(promAfter, 'spring_data_repository_invocations_seconds_count{exception="None",method="findByShortCode",repository="UrlRepository",state="SUCCESS"}');
  const deltaDb = dbAfter - dbBefore;

  const hitRatio = exp.env.SPRING_CACHE_TYPE === 'none' ? 0.0 : (deltaReqs > 0 ? Math.max(0, deltaReqs - deltaDb) / deltaReqs : 1.0);

  // Read k6 results
  let k6Data = {};
  if (fs.existsSync(hostOutputFile)) {
    k6Data = JSON.parse(fs.readFileSync(hostOutputFile, 'utf8'));
  }
  const measured = k6Data.metrics?.measured_req_duration || {};
  const drops = k6Data.metrics?.dropped_iterations?.count || 0;

  const record = {
    experiment: exp.id,
    name: exp.name,
    config: exp.env,
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
    cache_hit_ratio: hitRatio,
    raw_file: `bench/results/discrim_${exp.id}_raw.json`,
  };

  console.log(`Experiment ${exp.id} (${exp.name}) completed:`);
  console.log(`  p50=${record.p50}ms, p95=${record.p95}ms, p99=${record.p99}ms, drops=${drops}`);
  console.log(`  longest_gc=${longestGcPause}ms, timeouts=${lettuceTimeouts}, hikari_active=${hikari.active}, hitRatio=${(hitRatio*100).toFixed(1)}%`);

  summary.push(record);
}

fs.writeFileSync('bench/results/discriminating_summary.json', JSON.stringify(summary, null, 2), 'utf8');
console.log('\nAll discriminating experiments completed. Saved to bench/results/discriminating_summary.json');
