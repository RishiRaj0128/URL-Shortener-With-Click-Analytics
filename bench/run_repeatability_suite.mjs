import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const rates = [50, 100, 150, 200];
const reps = [1, 2, 3];
const resultsDir = 'bench/results';

if (!fs.existsSync(resultsDir)) {
  fs.mkdirSync(resultsDir, { recursive: true });
}

function getRedisStats() {
  try {
    const rawCmd = execSync('docker exec urlshortener-redis redis-cli info commandstats', { encoding: 'utf8' });
    const rawStats = execSync('docker exec urlshortener-redis redis-cli info stats', { encoding: 'utf8' });
    
    const parseLines = (text) => {
      const out = {};
      text.split('\n').forEach(line => {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) return;
        const [k, v] = trimmed.split(':');
        if (k && v) out[k] = v;
      });
      return out;
    };

    return {
      commandstats: parseLines(rawCmd),
      stats: parseLines(rawStats),
    };
  } catch (err) {
    console.error('Failed to get redis stats:', err.message);
    return null;
  }
}

function getAppPrometheus() {
  try {
    const raw = execSync('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus', { encoding: 'utf8' });
    return raw;
  } catch (err) {
    return '';
  }
}

function extractPromMetric(promText, metricPrefix) {
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

const summaryResults = [];

for (const rate of rates) {
  for (const rep of reps) {
    console.log(`\n======================================================`);
    console.log(`Running Rate: ${rate} RPS, Repetition: ${rep}`);
    console.log(`======================================================`);

    const redisBefore = getRedisStats();
    const promBefore = getAppPrometheus();

    const outputFile = `/workspace/bench/results/rep_${rate}rps_run_${rep}.json`;
    const hostOutputFile = path.resolve(`bench/results/rep_${rate}rps_run_${rep}.json`);

    const k6Cmd = `docker run --rm --network urlshortenerwithclickanalytics_default -v "${process.cwd()}:/workspace" -w /workspace -e RATE=${rate} -e PRE_ALLOCATED_VUS=100 -e MAX_VUS=1000 -e OUTPUT_FILE=${outputFile} grafana/k6:latest run bench/k6_in_network_repeatability.js`;

    console.log(`Executing k6 command: ${k6Cmd}`);
    let k6Output = '';
    try {
      k6Output = execSync(k6Cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    } catch (err) {
      k6Output = (err.stdout || '') + '\n' + (err.stderr || '');
      console.log(`k6 exited with code ${err.status || 1} (threshold check). Capturing metrics anyway.`);
    }

    const redisAfter = getRedisStats();
    const promAfter = getAppPrometheus();

    // Read saved k6 output
    let runData = {};
    if (fs.existsSync(hostOutputFile)) {
      runData = JSON.parse(fs.readFileSync(hostOutputFile, 'utf8'));
    }

    // Calculate Redis command deltas
    const cmdDeltas = {};
    if (redisBefore && redisAfter) {
      for (const cmd in redisAfter.commandstats) {
        const afterStr = redisAfter.commandstats[cmd];
        const beforeStr = redisBefore.commandstats[cmd] || 'calls=0';
        const getCalls = (s) => {
          const match = s.match(/calls=(\d+)/);
          return match ? parseInt(match[1], 10) : 0;
        };
        const delta = getCalls(afterStr) - getCalls(beforeStr);
        if (delta > 0) {
          cmdDeltas[cmd] = delta;
        }
      }
    }

    // Calculate Cache Hit Ratio from Prometheus
    const totalRequestsBefore = extractPromMetric(promBefore, 'http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="REDIRECTION",status="302",uri="/{shortCode}"}');
    const totalRequestsAfter = extractPromMetric(promAfter, 'http_server_requests_seconds_count{error="none",exception="none",method="GET",outcome="REDIRECTION",status="302",uri="/{shortCode}"}');
    const deltaRequests = totalRequestsAfter - totalRequestsBefore;

    const dbBefore = extractPromMetric(promBefore, 'spring_data_repository_invocations_seconds_count{exception="None",method="findByShortCode",repository="UrlRepository",state="SUCCESS"}');
    const dbAfter = extractPromMetric(promAfter, 'spring_data_repository_invocations_seconds_count{exception="None",method="findByShortCode",repository="UrlRepository",state="SUCCESS"}');
    const deltaDb = dbAfter - dbBefore;

    const cacheHits = Math.max(0, deltaRequests - deltaDb);
    const hitRatio = deltaRequests > 0 ? (cacheHits / deltaRequests) : 1.0;

    const measuredDuration = runData.metrics?.measured_req_duration || {};
    const droppedIterations = runData.metrics?.dropped_iterations?.count || 0;

    const resultRecord = {
      rate: rate,
      rep: rep,
      p50: measuredDuration.med || 0,
      p90: measuredDuration['p(90)'] || 0,
      p95: measuredDuration['p(95)'] || 0,
      p99: measuredDuration['p(99)'] || 0,
      avg: measuredDuration.avg || 0,
      min: measuredDuration.min || 0,
      max: measuredDuration.max || 0,
      dropped_iterations: droppedIterations,
      measured_reqs: runData.metrics?.measured_reqs?.count || 0,
      redis_cmd_deltas: cmdDeltas,
      delta_requests: deltaRequests,
      delta_db_fallback: deltaDb,
      cache_hit_ratio: hitRatio,
      output_file: `bench/results/rep_${rate}rps_run_${rep}.json`,
    };

    console.log(`Completed: p50=${resultRecord.p50}ms, p95=${resultRecord.p95}ms, p99=${resultRecord.p99}ms, drops=${droppedIterations}, hitRatio=${(hitRatio*100).toFixed(2)}%`);
    summaryResults.push(resultRecord);

    // Save enriched run data back to file
    runData.redis_cmd_deltas = cmdDeltas;
    runData.cache_hit_ratio = hitRatio;
    runData.delta_db_fallback = deltaDb;
    fs.writeFileSync(hostOutputFile, JSON.stringify(runData, null, 2), 'utf8');
  }
}

fs.writeFileSync('bench/results/repeatability_summary.json', JSON.stringify(summaryResults, null, 2), 'utf8');
console.log('\nAll repeatability runs completed successfully. Summary saved to bench/results/repeatability_summary.json');
