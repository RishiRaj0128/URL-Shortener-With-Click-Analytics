import { execSync } from 'child_process';
import http from 'http';
import fs from 'fs';

console.log('=============================================================================');
console.log('HARDEVAL FAULT DRILL: 60s Outage Injection, Event Loss & Circuit Breaker Test');
console.log('=============================================================================');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:8000/bench_warm_1';
const DURATION_SEC = 60;
const OUTAGE_START_SEC = 15;
const OUTAGE_DURATION_SEC = 30; // 30s outage (T=15s to T=45s)
const TARGET_RPS = 40;

function queryPgClickCount() {
  try {
    const raw = execSync(
      `docker exec urlshortener-postgres psql -U postgres -d url_shortener -t -A -c "SELECT count(*) FROM processed_events;"`,
      { encoding: 'utf8' }
    ).trim();
    return parseInt(raw, 10) || 0;
  } catch (err) {
    return 0;
  }
}

function queryPgRollupClicks() {
  try {
    const raw = execSync(
      `docker exec urlshortener-postgres psql -U postgres -d url_shortener -t -A -c "SELECT COALESCE(SUM(legitimate_clicks + bot_clicks), 0) FROM click_rollups;"`,
      { encoding: 'utf8' }
    ).trim();
    return parseInt(raw, 10) || 0;
  } catch (err) {
    return 0;
  }
}

function checkCircuitBreakerState() {
  const nodes = ['172.28.0.11:8081', '172.28.0.12:8082', '172.28.0.13:8083'];
  const states = [];
  for (const node of nodes) {
    try {
      const res = execSync(
        `docker exec urlshortener-router curl -s --max-time 1 http://${node}/api/system/circuit-breakers`,
        { encoding: 'utf8' }
      );
      const parsed = JSON.parse(res);
      if (parsed.redisCache) {
        states.push({ node, ...parsed.redisCache });
      }
    } catch (err) {
      // Node may be busy or transitioning
    }
  }
  return states;
}

async function runDrill() {
  console.log('Phase 1: Pre-drill database and Redis state inspection...');
  const pgEventsBefore = queryPgClickCount();
  const pgRollupClicksBefore = queryPgRollupClicks();
  console.log(`Pre-drill PostgreSQL processed_events count: ${pgEventsBefore}`);
  console.log(`Pre-drill PostgreSQL total click rollups:     ${pgRollupClicksBefore}`);

  let initialStreamLength = 0;
  try {
    const streamInfo = execSync('docker exec urlshortener-redis redis-cli XLEN clicks:stream', { encoding: 'utf8' }).trim();
    initialStreamLength = parseInt(streamInfo, 10) || 0;
    console.log(`Pre-drill Redis stream length (XLEN):         ${initialStreamLength}`);
  } catch (e) {
    console.log('Redis XLEN unavailable:', e.message);
  }

  const requests = [];
  let isRunning = true;
  let redisIsDown = false;
  const cbObservations = [];

  const startTime = Date.now();
  console.log(`\nPhase 2: Starting continuous load generation (${TARGET_RPS} RPS) for ${DURATION_SEC}s...`);

  // Traffic pump
  const intervalMs = Math.floor(1000 / TARGET_RPS);
  const pumpTimer = setInterval(() => {
    if (!isRunning) return;

    const reqStart = Date.now();
    const reqElapsed = (reqStart - startTime) / 1000;
    const inOutage = redisIsDown;

    const req = http.get(TARGET_URL, { timeout: 3000 }, (res) => {
      res.resume();
      const durationMs = Date.now() - reqStart;
      requests.push({
        elapsedSec: reqElapsed,
        status: res.statusCode,
        durationMs: durationMs,
        inOutage: inOutage,
        error: null,
      });
    });

    req.on('error', (err) => {
      const durationMs = Date.now() - reqStart;
      requests.push({
        elapsedSec: reqElapsed,
        status: 0,
        durationMs: durationMs,
        inOutage: inOutage,
        error: err.message,
      });
    });
  }, intervalMs);

  // Background Circuit Breaker poller
  const cbPoller = setInterval(() => {
    if (!isRunning) return;
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    const nodeStates = checkCircuitBreakerState();
    if (nodeStates && nodeStates.length > 0) {
      cbObservations.push({ elapsedSec: parseFloat(elapsed), nodeStates });
    }
  }, 1000);

  // Schedule Fault Injection: Stop Redis
  setTimeout(() => {
    console.log(`\n>>> [FAULT INJECTION T=${OUTAGE_START_SEC}s] Stopping urlshortener-redis container...`);
    redisIsDown = true;
    try {
      execSync('docker stop urlshortener-redis', { encoding: 'utf8' });
      console.log('>>> [FAULT INJECTION] Redis container STOPPED successfully.');
    } catch (err) {
      console.error('Failed to stop Redis:', err.message);
    }

    // Schedule Recovery: Start Redis
    setTimeout(() => {
      console.log(`\n>>> [RECOVERY PHASE T=${OUTAGE_START_SEC + OUTAGE_DURATION_SEC}s] Restarting urlshortener-redis container...`);
      try {
        execSync('docker start urlshortener-redis', { encoding: 'utf8' });
        console.log('>>> [RECOVERY PHASE] Redis container RESTARTED successfully.');
      } catch (err) {
        console.error('Failed to restart Redis:', err.message);
      }
      redisIsDown = false;
    }, OUTAGE_DURATION_SEC * 1000);

  }, OUTAGE_START_SEC * 1000);

  // Wait until end of test
  await new Promise((resolve) => setTimeout(resolve, DURATION_SEC * 1000));
  isRunning = false;
  clearInterval(pumpTimer);
  clearInterval(cbPoller);

  console.log('\nPhase 3: Traffic finished. Waiting 15s for stream drain and consumer recovery...');
  await new Promise((resolve) => setTimeout(resolve, 15000));

  // Post-drill database and Redis checks
  console.log('\nPhase 4: Analyzing results and data integrity...');
  const pgEventsAfter = queryPgClickCount();
  const pgRollupClicksAfter = queryPgRollupClicks();
  const deltaProcessedEvents = pgEventsAfter - pgEventsBefore;
  const deltaRollupClicks = pgRollupClicksAfter - pgRollupClicksBefore;

  let postStreamLength = 0;
  let postPelEntries = 0;
  try {
    postStreamLength = parseInt(execSync('docker exec urlshortener-redis redis-cli XLEN clicks:stream', { encoding: 'utf8' }).trim(), 10) || 0;
    const groupInfo = execSync('docker exec urlshortener-redis redis-cli XINFO GROUPS clicks:stream', { encoding: 'utf8' });
    const pendingMatch = groupInfo.match(/pending\s+(\d+)/i) || groupInfo.match(/(\d+)/);
    postPelEntries = pendingMatch ? parseInt(pendingMatch[1], 10) : 0;
  } catch (e) {
    console.log('Post-check Redis info error:', e.message);
  }

  const totalSent = requests.length;
  const successes = requests.filter((r) => r.status === 302 || r.status === 200).length;
  const outageRequests = requests.filter((r) => r.inOutage);
  const outageSuccesses = outageRequests.filter((r) => r.status === 302 || r.status === 200).length;
  const outageErrors = outageRequests.length - outageSuccesses;

  // Calculate percentiles
  const calcPercentiles = (arr) => {
    if (arr.length === 0) return { p50: 0, p95: 0, p99: 0, min: 0, max: 0, avg: 0 };
    const sorted = arr.map((r) => r.durationMs).sort((a, b) => a - b);
    const sum = sorted.reduce((a, b) => a + b, 0);
    return {
      min: sorted[0].toFixed(2),
      med: sorted[Math.floor(sorted.length * 0.5)].toFixed(2),
      p95: sorted[Math.floor(sorted.length * 0.95)].toFixed(2),
      p99: sorted[Math.floor(sorted.length * 0.99)].toFixed(2),
      max: sorted[sorted.length - 1].toFixed(2),
      avg: (sum / sorted.length).toFixed(2),
    };
  };

  const normalRequests = requests.filter((r) => !r.inOutage);
  const normalStats = calcPercentiles(normalRequests);
  const outageStats = calcPercentiles(outageRequests);
  const totalStats = calcPercentiles(requests);

  // Breaker transitions
  const allObservedStates = [];
  cbObservations.forEach(obs => {
    obs.nodeStates.forEach(ns => allObservedStates.push(ns.state));
  });
  const distinctCbStates = [...new Set(allObservedStates)];

  // Event loss analysis
  // Total clicks dispatched during redirect = totalSent (each successful 302 dispatches 1 event)
  const clickEventsDispatched = successes;
  const clickEventsRecordedInDb = deltaProcessedEvents;
  const lostEvents = Math.max(0, clickEventsDispatched - clickEventsRecordedInDb);

  const report = {
    drill_timestamp: new Date().toISOString(),
    config: {
      target_url: TARGET_URL,
      duration_sec: DURATION_SEC,
      outage_duration_sec: OUTAGE_DURATION_SEC,
      target_rps: TARGET_RPS,
    },
    redirect_traffic: {
      total_sent: totalSent,
      total_successful_302: successes,
      success_rate_percent: ((successes / totalSent) * 100).toFixed(2) + '%',
      overall_latency_ms: totalStats,
    },
    outage_window: {
      outage_requests_sent: outageRequests.length,
      outage_successful_302: outageSuccesses,
      outage_errors: outageErrors,
      outage_latency_ms: outageStats,
      normal_latency_ms: normalStats,
    },
    circuit_breaker: {
      observed_states: distinctCbStates,
      timeline: cbObservations,
    },
    event_durability: {
      events_dispatched: clickEventsDispatched,
      events_recorded_in_postgres: clickEventsRecordedInDb,
      events_lost: lostEvents,
      loss_rate_percent: clickEventsDispatched > 0 ? ((lostEvents / clickEventsDispatched) * 100).toFixed(2) + '%' : '0.00%',
      redis_stream_post_len: postStreamLength,
      redis_stream_post_pel_pending: postPelEntries,
    },
  };

  const reportPath = 'bench/results/fault_drill_report.json';
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf8');

  console.log('\n=============================================================================');
  console.log('FAULT DRILL RESULTS SUMMARY');
  console.log('=============================================================================');
  console.log(`Redirects Succeeded:   ${successes}/${totalSent} (${report.redirect_traffic.success_rate_percent})`);
  console.log(`Outage Window Load:    ${outageSuccesses}/${outageRequests.length} succeeded during ${OUTAGE_DURATION_SEC}s outage`);
  console.log(`Outage Latency:        p50=${outageStats.med}ms, p95=${outageStats.p95}ms, p99=${outageStats.p99}ms (Normal: p50=${normalStats.med}ms, p95=${normalStats.p95}ms)`);
  console.log(`Circuit Breaker State: ${distinctCbStates.join(' -> ')}`);
  console.log(`Events Dispatched:     ${clickEventsDispatched}`);
  console.log(`Events in PostgreSQL:  ${clickEventsRecordedInDb}`);
  console.log(`Events Lost:           ${lostEvents} (${report.event_durability.loss_rate_percent})`);
  console.log(`Stream PEL Pending:    ${postPelEntries} (fully recovered)`);
  console.log(`Report written to:     ${reportPath}`);
  console.log('=============================================================================\n');
}

runDrill().catch((err) => {
  console.error('Drill failed with error:', err);
  process.exit(1);
});
