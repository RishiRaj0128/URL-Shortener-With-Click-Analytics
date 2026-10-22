import fs from 'fs';
import path from 'path';

let code = fs.readFileSync('bench/run_and_generate_all_benchmarks.mjs', 'utf8');

// Update generateReport to scrub retired numbers and update Section 1, 5, 6
const oldSection1 = `## 1. Open-Loop Arrival Rate Benchmarks (Section C)

Evaluated at strictly identical open-loop constant arrival rates (200.0 RPS) to avoid closed-loop throughput distortion [DERIVED: test configuration].

| Benchmark Scenario | Total Requests | Target RPS | Measured RPS | Measured p50 | Measured p95 | Measured p99 | Max Latency | Error Rate | Cache Hit Ratio | Provenance Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Warm Cache Hit (O(1) Redis/Memory)** | \${warmMetrics.count.toLocaleString()} | 200.0 RPS | **\${warmMetrics.measuredRps} RPS** | **\${warmMetrics.p50} ms** | **\${warmMetrics.p95} ms** | **\${warmMetrics.p99} ms** | **\${warmMetrics.max} ms** | **\${warmMetrics.errorRatePct}%** | **100.0%** (3 pre-seeded keys) | [MEASURED: bench/results/redirect_warm_raw.json] |
| **Cold Cache Miss (Database JPA Path)** | \${coldMetrics.count.toLocaleString()} | 200.0 RPS | **\${coldMetrics.measuredRps} RPS** | **\${coldMetrics.p50} ms** | **\${coldMetrics.p95} ms** | **\${coldMetrics.p99} ms** | **\${coldMetrics.max} ms** | **\${coldMetrics.errorRatePct}%** | **0.0%** (unique random keys) | [MEASURED: bench/results/redirect_cold_raw.json] |
| **Zipfian 80/20 Mixed Traffic** | \${mixedMetrics.count.toLocaleString()} | 200.0 RPS | **\${mixedMetrics.measuredRps} RPS** | **\${mixedMetrics.p50} ms** | **\${mixedMetrics.p95} ms** | **\${mixedMetrics.p99} ms** | **\${mixedMetrics.max} ms** | **\${mixedMetrics.errorRatePct}%** | **79.8%** (Pareto head) | [MEASURED: bench/results/redirect_mixed_raw.json] |
| **Circuit Breaker CLOSED (Degraded Redis Probe)** | \${cbClosedMetrics.count.toLocaleString()} | 50.0 RPS | **\${cbClosedMetrics.measuredRps} RPS** | **\${cbClosedMetrics.p50} ms** | **\${cbClosedMetrics.p95} ms** | **\${cbClosedMetrics.p99} ms** | **\${cbClosedMetrics.max} ms** | **\${cbClosedMetrics.errorRatePct}%** | Injected 200ms Redis latency | [MEASURED: bench/results/circuit_breaker_raw.json] |
| **Circuit Breaker OPEN (Fail-Fast Bypass)** | \${cbOpenMetrics.count.toLocaleString()} | 50.0 RPS | **\${cbOpenMetrics.measuredRps} RPS** | **\${cbOpenMetrics.p50} ms** | **\${cbOpenMetrics.p95} ms** | **\${cbOpenMetrics.p99} ms** | **\${cbOpenMetrics.max} ms** | **\${cbOpenMetrics.errorRatePct}%** | Redis bypassed (fail-fast) | [MEASURED: bench/results/circuit_breaker_raw.json] |`;

const newSection1 = `## 1. Production Cluster Steady-State Baselines & Mock Retirement Audit

> [!IMPORTANT]
> **RETIREMENT NOTICE**: Historical Section C benchmarks (\`redirect_warm_raw.json\`, \`redirect_cold_raw.json\`, \`redirect_mixed_raw.json\`, \`circuit_breaker_raw.json\`) targeted a local test-harness mock (\`127.0.0.1:8089\`) rather than live cluster services, and \`redirect_cold_raw.json\` had 100% 404 responses. Per provenance audit, they have been formally moved to [\`bench/results/retired/RETIRED_MANIFEST.md\`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/bench/results/retired/RETIRED_MANIFEST.md) and all claims derived from them are retired.

### Verified Cluster Steady-State Baseline (Nginx Ingress -> Live Cluster)
Evaluated across 5 independent open-loop runs against pre-seeded keys at 200.0 RPS arrival rate through Nginx ingress, returning strictly **HTTP 302 Found** (asserting status 302, redirects: 0) [MEASURED: raw_run_1..5.json]:

| Run Index | Total Requests | Target RPS | Measured RPS | Measured p50 | Measured p95 | Measured p99 | Max Latency | Error Rate | Status Histogram | Provenance Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Run 1** | 2,016 | 200.0 RPS | **198.2 RPS** | **90.54 ms** | **376.13 ms** | **924.99 ms** | **2,493.10 ms** | **0.00%** | 302: 2016 (100.0%) | [MEASURED: bench/results/raw_run_1.json] |
| **Run 2** | 3,255 | 200.0 RPS | **199.1 RPS** | **31.90 ms** | **242.85 ms** | **421.17 ms** | **1,680.61 ms** | **0.00%** | 302: 3255 (100.0%) | [MEASURED: bench/results/raw_run_2.json] |
| **Run 3** | 3,555 | 200.0 RPS | **199.8 RPS** | **27.46 ms** | **204.51 ms** | **321.08 ms** | **568.89 ms** | **0.00%** | 302: 3555 (100.0%) | [MEASURED: bench/results/raw_run_3.json] |
| **Run 4** | 3,232 | 200.0 RPS | **199.3 RPS** | **29.73 ms** | **229.72 ms** | **323.44 ms** | **800.37 ms** | **0.00%** | 302: 3232 (100.0%) | [MEASURED: bench/results/raw_run_4.json] |
| **Run 5** | 3,134 | 200.0 RPS | **199.4 RPS** | **31.16 ms** | **247.88 ms** | **359.69 ms** | **712.03 ms** | **0.00%** | 302: 3134 (100.0%) | [MEASURED: bench/results/raw_run_5.json] |`;

code = code.replace(oldSection1, newSection1);

// Update Section 5 (Circuit breaker)
const oldSection5 = `## 5. Circuit Breaker Diagnostics (Resilience4j)

- **Fault Injection Methodology**:
  - In production drills, Redis latency is injected via Toxiproxy latency toxics or Redis command \`CLIENT PAUSE <ms>\` (e.g. \`CLIENT PAUSE 5000\`).
  - In test harness verification, \`/bench_cb_probe\` injects deterministic 200 ms latency during the 10-second fault window.
- **Redis Driver Command Timeout Verification**:
  - Enforced via \`spring.data.redis.timeout: 50ms\` and \`LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50))\`. Driver aborts hung calls at 50 ms.
- **Time-to-Open & Slowed Requests Measurement**:
  - From timestamps in [\`bench/results/circuit_breaker_raw.json\`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/bench/results/circuit_breaker_raw.json), exactly **10 slow requests** (the sliding window size) execute before Resilience4j transitions from CLOSED to OPEN in **~200 ms** [MEASURED: bench/results/circuit_breaker_raw.json].
- **Fallback Stack-Trace Suppression Re-measurement**:
  - Previously, catching \`CallNotPermittedException\` dumped a 42-frame stack trace per call, adding ~3.7 ms CPU overhead (9.3 ms fallback latency).
  - With custom \`CacheErrorHandler\` and suppressed logging implemented in \`CacheConfiguration.java\` and \`GlobalExceptionHandler.java\`, re-measured fail-fast bypass latency drops to **1.62 ms** (p95: **2.85 ms**), completely restoring sub-2ms bypass performance [MEASURED: bench/results/circuit_breaker_raw.json].`;

const newSection5 = `## 5. Circuit Breaker Diagnostics (Resilience4j)

- **Fault Injection Methodology**:
  - Production drills require Toxiproxy between the live application container and Redis container.
- **Driver Command Timeout Configuration**:
  - Configured via \`spring.data.redis.timeout: 50ms\` and \`LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50))\`.
- **Status of Historical Claims**:
  - Previous claims citing \`circuit_breaker_raw.json\` (200 ms injected latency, 10 slow requests to open, 9.30 ms / 1.62 ms fallback latency, stack-trace suppression) evaluated a mock HTTP route on port 8089 rather than live Lettuce/Redis sockets.
  - Per provenance audit, those numbers have been formally **RETIRED** to [\`bench/results/retired/RETIRED_MANIFEST.md\`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/bench/results/retired/RETIRED_MANIFEST.md).
  - Live production Toxiproxy measurement is currently **NOT RUN** (all breaker latency claims removed).`;

code = code.replace(oldSection5, newSection5);

// Update Section 6 (Failover mock notice)
const oldSection6Heading = `## 6. Failover Telemetry: 2s vs 5s Client Timeout Comparison

Generated across 10 distinct, fully independent k6 executions (5 runs per configuration):`;

const newSection6Heading = `## 6. Failover Telemetry & Rejoin Audit Notice

> [!IMPORTANT]
> **RETIREMENT NOTICE**: The historical 10-run failover drill dataset (\`raw_failover_2s_run_*.json\`, \`raw_failover_5s_run_*.json\`) evaluated the local Node mock server endpoint \`/reset_drill_clock\` on port 8089 rather than live container restarts. Earlier rejoin claims (1.8-3.4s and 27-28s) and the 66,000-request zero-error count are formally **RETIRED** to [\`bench/results/retired/RETIRED_MANIFEST.md\`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/bench/results/retired/RETIRED_MANIFEST.md). Live container failover re-measurement requires drill runs exceeding full container boot time (33.8s) + 30s.

Below is the historical structural analysis of the failover retry mechanics:`;

code = code.replace(oldSection6Heading, newSection6Heading);

fs.writeFileSync('bench/run_and_generate_all_benchmarks.mjs', code);
console.log('Successfully patched generateReport in bench/run_and_generate_all_benchmarks.mjs');
