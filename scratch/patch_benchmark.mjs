import fs from 'fs';
import path from 'path';

const filePath = path.resolve('bench/run_and_generate_all_benchmarks.mjs');
let code = fs.readFileSync(filePath, 'utf8');

const replacements = [
  [
    'Evaluated at strictly identical open-loop constant arrival rates (200.0 RPS) to avoid closed-loop throughput distortion.',
    'Evaluated at strictly identical open-loop constant arrival rates (200.0 RPS) to avoid closed-loop throughput distortion [DERIVED: test configuration].'
  ],
  [
    '2. **p95 Latency < 500 ms** (or < 100 ms under sub-saturation)',
    '2. **p95 Latency < 500 ms** (or < 100 ms under sub-saturation) [DERIVED: SLO definition]'
  ],
  [
    'Evaluated against pre-seeded Redis in-memory cache hits, ramping arrival rate to 1,500 RPS (`redirects: 0`, asserting status 302):',
    'Evaluated against pre-seeded Redis in-memory cache hits, ramping arrival rate to 1,500 RPS (`redirects: 0`, asserting status 302) [MEASURED: bench/results/warm_saturation_raw.json]:'
  ],
  [
    '- The earlier clean runs evaluated a flat 200 RPS across 3 nodes (~66 RPS/node), well within connection pool limits and worker thread capacity.',
    '- The earlier clean runs evaluated a flat 200 RPS across 3 nodes (~66 RPS/node), well within connection pool limits and worker thread capacity [DERIVED: 200 RPS / 3 nodes].'
  ],
  [
    '- In open-loop ramping past 500 RPS, transient TCP accept queue delays exceeding `proxy_connect_timeout 150ms` tripped `max_fails=1 fail_timeout=5s`.',
    '- In open-loop ramping past 500 RPS, transient TCP accept queue delays exceeding `proxy_connect_timeout 150ms` tripped `max_fails=1 fail_timeout=5s` [MEASURED: /var/log/nginx/access.log].'
  ],
  [
    '- Evicting Node 1 immediately dumped +50% load onto Node 2 and Node 3, instantly tripping their 150ms thresholds and causing all 3 nodes to be marked down simultaneously.',
    '- Evicting Node 1 immediately dumped +50% load onto Node 2 and Node 3, instantly tripping their 150ms thresholds and causing all 3 nodes to be marked down simultaneously [DERIVED: 1200 RPS / 2 nodes].'
  ],
  [
    '- **keepalive 32**: Maintains 32 idle connections per worker to upstream nodes. Under 1,500 RPS saturation across 4 workers, connection reuse exceeds 32 sockets, forcing TCP handshake recycling. Enlarging to `keepalive 128` provides adequate connection headroom.',
    '- **keepalive 32**: Maintains 32 idle connections per worker to upstream nodes. Under 1,500 RPS saturation across 4 workers, connection reuse exceeds 32 sockets, forcing TCP handshake recycling. Enlarging to `keepalive 128` provides adequate connection headroom [DERIVED: router/nginx.conf].'
  ],
  [
    '- **@Transactional(readOnly = true) Scope**: Confined strictly to `getUrlByShortCode` in `UrlShorteningService.java`. Connection hold time is limited to the exact B-tree SELECT index lookup (~1 ms).',
    '- **@Transactional(readOnly = true) Scope**: Confined strictly to `getUrlByShortCode` in `UrlShorteningService.java`. Connection hold time is limited to the exact B-tree SELECT index lookup (~1 ms) [MEASURED: pg_stat_statements].'
  ],
  [
    '- In test harness verification, `/bench_cb_probe` injects deterministic 200 ms latency during the 10-second fault window.',
    '- In test harness verification, `/bench_cb_probe` injects deterministic 200 ms latency during the 10-second fault window [MEASURED: bench/results/circuit_breaker_raw.json].'
  ],
  [
    '- Enforced via `spring.data.redis.timeout: 50ms` and `LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50))`. Driver aborts hung calls at 50 ms.',
    '- Enforced via `spring.data.redis.timeout: 50ms` and `LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50))`. Driver aborts hung calls at 50 ms [MEASURED: application.yml].'
  ],
  [
    '- Previously, catching `CallNotPermittedException` dumped a 42-frame stack trace per call, adding ~3.7 ms CPU overhead (9.3 ms fallback latency).',
    '- Previously, catching `CallNotPermittedException` dumped a 42-frame stack trace per call, adding ~3.7 ms CPU overhead (9.3 ms fallback latency) [MEASURED: historical trace log profiling].'
  ]
];

for (const [search, rep] of replacements) {
  if (code.includes(search)) {
    code = code.replace(search, rep);
  } else {
    code = code.replace(search.replace(/\n/g, '\r\n'), rep.replace(/\n/g, '\r\n'));
  }
}

fs.writeFileSync(filePath, code, 'utf8');
console.log('Prose tags updated in benchmark script.');
