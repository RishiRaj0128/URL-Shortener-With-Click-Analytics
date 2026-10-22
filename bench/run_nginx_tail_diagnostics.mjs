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

function getTcpRetrans(containerName) {
  try {
    const snmp = runCmd(`docker exec ${containerName} cat /proc/net/snmp`);
    const lines = snmp.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].startsWith('Tcp:')) {
        const header = lines[i].trim().split(/\s+/);
        const values = lines[i + 1]?.trim().split(/\s+/);
        if (values && values[0] === 'Tcp:') {
          const idx = header.indexOf('RetransSegs');
          if (idx !== -1) {
            return parseInt(values[idx], 10);
          }
        }
      }
    }
  } catch (e) {}
  return 0;
}

function getSsStats(containerName) {
  try {
    return runCmd(`docker exec ${containerName} ss -s`);
  } catch (e) {
    return '';
  }
}

function ensureNodesUp() {
  console.log('Ensuring nodes are UP...');
  for (let port of [8081, 8082, 8083]) {
    for (let i = 0; i < 30; i++) {
      const h = runCmd(`docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-${port - 8080}:${port}/actuator/health`);
      if (h.includes('"status":"UP"')) {
        break;
      }
      execSync('powershell -Command "Start-Sleep -Seconds 2"');
    }
  }
  // Prime warmup probe
  runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/bench_warm_1');
  runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-2:8082/bench_warm_1');
  runCmd('docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-3:8083/bench_warm_1');
}

function setNginxUpstreams(upstreamServers) {
  let conf = fs.readFileSync('router/nginx.conf', 'utf8');
  let upstreamBlock = '    upstream backend_nodes {\n        zone backend_nodes 64k;\n';
  for (const s of upstreamServers) {
    upstreamBlock += `        server ${s} max_fails=1 fail_timeout=5s;\n`;
  }
  upstreamBlock += '        keepalive 32;\n    }';

  conf = conf.replace(/upstream backend_nodes\s*\{[\s\S]*?keepalive\s+\d+;\s*\}/, upstreamBlock);
  fs.writeFileSync('router/nginx.conf', conf, 'utf8');

  // Copy to container and reload
  runCmd('docker cp router/nginx.conf urlshortener-router:/etc/nginx/nginx.conf');
  runCmd('docker exec urlshortener-router nginx -s reload');
  execSync('powershell -Command "Start-Sleep -Seconds 1"');
}

const conditions = [
  {
    id: 'a_direct_app1',
    name: 'direct app-1',
    target: 'http://app-1:8081',
    upstreams: ['app-1:8081', 'app-2:8082', 'app-3:8083'] // router config doesn't matter for direct
  },
  {
    id: 'b_nginx_1_upstream',
    name: 'via nginx with only app-1 upstream',
    target: 'http://router:8080',
    upstreams: ['app-1:8081']
  },
  {
    id: 'c_nginx_3_upstreams',
    name: 'via nginx with 3 upstreams',
    target: 'http://router:8080',
    upstreams: ['app-1:8081', 'app-2:8082', 'app-3:8083']
  }
];

const allResults = [];

async function main() {
  ensureNodesUp();

  for (const cond of conditions) {
    console.log(`\n======================================================`);
    console.log(`Condition: ${cond.name} (${cond.id}) -> Target: ${cond.target}`);
    console.log(`======================================================`);

    setNginxUpstreams(cond.upstreams);

    const conditionReps = [];

    for (let rep = 1; rep <= 3; rep++) {
      console.log(`\n--- Repetition ${rep}/3 for ${cond.id} ---`);

      // Truncate nginx logs
      runCmd('docker exec urlshortener-router sh -c "truncate -s 0 /var/log/nginx/access.log /var/log/nginx/error.log"');

      const tcpRetransBefore = getTcpRetrans('urlshortener-router');
      const ssBefore = getSsStats('urlshortener-router');

      const outputFile = `/workspace/bench/results/nginx_tail_${cond.id}_rep_${rep}.json`;
      const hostOutputFile = path.resolve(`bench/results/nginx_tail_${cond.id}_rep_${rep}.json`);

      const k6Cmd = `docker run --rm --network urlshortenerwithclickanalytics_default -v "${process.cwd()}:/workspace" -w /workspace -e BASE_URL=${cond.target} -e RATE=100 -e PRE_ALLOCATED_VUS=50 -e MAX_VUS=500 -e OUTPUT_FILE=${outputFile} grafana/k6:latest run bench/k6_in_network_repeatability.js`;

      console.log(`Running k6 100 RPS test...`);
      runCmd(k6Cmd);

      const tcpRetransAfter = getTcpRetrans('urlshortener-router');
      const ssAfter = getSsStats('urlshortener-router');
      const retransDelta = tcpRetransAfter - tcpRetransBefore;

      const accessLog = runCmd('docker exec urlshortener-router cat /var/log/nginx/access.log');
      const errorLog = runCmd('docker exec urlshortener-router cat /var/log/nginx/error.log');

      // Parse access log for requests with rt >= 1.0s
      const lines = accessLog.split('\n').filter(l => l.trim().length > 0);
      const slowReqs = [];
      const slowHist = { '1s-2s': 0, '2s-3s': 0, '3s+': 0 };
      const commonUpstreams = {};
      const commonPids = {};
      let maxRt = 0;
      let maxUct = 0;

      for (const line of lines) {
        // format: rt=0.002 uct="0.001" uht="0.002" urt="0.002" us="302" uaddr="172.28.0.5:8081" pid=24
        const rtMatch = line.match(/\brt=([0-9.]+)/);
        const uctMatch = line.match(/\buct="([^"]+)"/);
        const urtMatch = line.match(/\burt="([^"]+)"/);
        const uaddrMatch = line.match(/\buaddr="([^"]+)"/);
        const pidMatch = line.match(/\bpid=([0-9]+)/);
        const statusMatch = line.match(/"\s+([0-9]{3})\s+/);

        const rt = rtMatch ? parseFloat(rtMatch[1]) : 0;
        const uct = uctMatch ? uctMatch[1] : '';
        const urt = urtMatch ? urtMatch[1] : '';
        const uaddr = uaddrMatch ? uaddrMatch[1] : '';
        const pid = pidMatch ? pidMatch[1] : '';
        const status = statusMatch ? statusMatch[1] : '';

        if (rt > maxRt) maxRt = rt;
        const uctNum = parseFloat(uct) || 0;
        if (uctNum > maxUct) maxUct = uctNum;

        if (rt >= 1.0) {
          if (rt >= 3.0) slowHist['3s+']++;
          else if (rt >= 2.0) slowHist['2s-3s']++;
          else slowHist['1s-2s']++;

          commonUpstreams[uaddr] = (commonUpstreams[uaddr] || 0) + 1;
          commonPids[pid] = (commonPids[pid] || 0) + 1;

          slowReqs.push({ rt, uct, urt, uaddr, pid, status, line: line.substring(0, 150) });
        }
      }

      // Check error log for ejection events
      const ejections = (errorLog.match(/no live upstreams|connect\(\) failed|upstream timed out/g) || []).length;

      let k6Data = {};
      if (fs.existsSync(hostOutputFile)) {
        k6Data = JSON.parse(fs.readFileSync(hostOutputFile, 'utf8'));
      }
      const measured = k6Data.metrics?.measured_req_duration || {};
      const drops = k6Data.metrics?.dropped_iterations?.count || 0;

      const repRecord = {
        rep,
        p50: measured.med || 0,
        p90: measured['p(90)'] || 0,
        p95: measured['p(95)'] || 0,
        p99: measured['p(99)'] || 0,
        avg: measured.avg || 0,
        dropped_iterations: drops,
        tcp_retrans_delta: retransDelta,
        nginx_total_requests: lines.length,
        slow_requests_count: slowReqs.length,
        slow_histogram: slowHist,
        common_upstreams: commonUpstreams,
        common_pids: commonPids,
        ejection_events: ejections,
        max_rt_sec: maxRt,
        max_uct_sec: maxUct,
        raw_k6_file: `bench/results/nginx_tail_${cond.id}_rep_${rep}.json`,
        slow_sample: slowReqs.slice(0, 5)
      };

      console.log(`Rep ${rep}: p50=${repRecord.p50}ms, p95=${repRecord.p95}ms, p99=${repRecord.p99}ms, drops=${drops}`);
      console.log(`  slow(>=1s)=${slowReqs.length}, retransDelta=${retransDelta}, ejections=${ejections}, maxRt=${maxRt}s, maxUct=${maxUct}s`);

      conditionReps.push(repRecord);
    }

    allResults.push({
      condition_id: cond.id,
      condition_name: cond.name,
      target: cond.target,
      repetitions: conditionReps
    });
  }

  // Restore 3 upstreams
  setNginxUpstreams(['app-1:8081', 'app-2:8082', 'app-3:8083']);

  fs.writeFileSync('bench/results/nginx_tail_summary.json', JSON.stringify(allResults, null, 2), 'utf8');
  console.log('\nAll Nginx tail diagnostics completed. Summary written to bench/results/nginx_tail_summary.json');
}

main().catch(console.error);
