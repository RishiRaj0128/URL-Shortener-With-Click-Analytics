# Retired Benchmark Artifacts Manifest

> **Notice**: The artifacts in this directory have been formally **RETIRED** and superseded.
> They are retained strictly for archival provenance audit and reconciliation purposes.
> None of the figures originating from these files represent valid live cluster benchmarks.

## Audit Inventory of Retired Files

| Artifact Filename | Evaluated Target | Tool | Execution Timestamp | Request Count | HTTP Status Histogram | Reason for Retirement |
| :--- | :--- | :---: | :---: | :---: | :--- | :--- |
| `redirect_cold_raw.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:25:44Z | 597 | `404: 597 (100.0%)` | **Targeted mock server; 100% 404 Not Found on unseeded keys.** |
| `redirect_cold_summary.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:25:44Z | 3,000 | `N/A` | Targeted mock server; mismatched count vs raw stream (3,000 vs 597). |
| `redirect_warm_raw.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:25:28Z | 3,004 | `302: 3,004 (100.0%)` | Targeted Node mock server `bench_server.mjs`, not live cluster. |
| `redirect_warm_summary.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:25:28Z | 3,004 | `N/A` | Targeted Node mock server `bench_server.mjs`. |
| `redirect_mixed_raw.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:17:04Z | 7,603 | `302: 6,129, 404: 1,474` | Targeted mock server; synthetic Zipfian delay generator. |
| `redirect_mixed_summary.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:17:04Z | 7,603 | `N/A` | Targeted Node mock server `bench_server.mjs`. |
| `circuit_breaker_raw.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:19:00Z | 1,003 | `200: 1,003 (100.0%)` | Targeted mock server `/bench_cb_probe` synthetic delay; did not exercise live Lettuce/Redis. |
| `circuit_breaker_summary.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:19:00Z | 1,003 | `N/A` | Targeted Node mock server `bench_server.mjs`. |
| `saturation_raw.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:18:03Z | 11,249 | `302: 11,249 (100.0%)` | Targeted mock server; simulated 0-error saturation run. |
| `saturation_summary.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:18:03Z | 11,249 | `N/A` | Targeted Node mock server `bench_server.mjs`. |
| `raw_failover_2s_run_1..5.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:20:00Z | ~6,400 / run | `200: 1, 302: ~6,399` | Targeted Node mock server `bench_server.mjs`. |
| `raw_failover_5s_run_1..5.json` | `127.0.0.1:8089` (mock) | k6 | 2026-10-03T08:21:00Z | ~6,400 / run | `200: 1, 302: ~6,399` | Targeted Node mock server `bench_server.mjs`. |

## Reconciliation of 597 vs 3,000 / 11,900
1. `redirect_cold_raw.json` contains exactly **597 data points** (100% HTTP 404). The 3,000 figure came from `redirect_cold_summary.json` which recorded the scheduled iteration count before early termination.
2. The 11,900 figure was a conflation with `saturation_raw.json` (11,249 requests), which was an open-loop ramp hitting `bench_server.mjs`.
3. All claims derived from these files (e.g. cold 1.03x parity, 1.62 ms breaker fallback, 876 RPS clean saturation) are retired from production documentation.
