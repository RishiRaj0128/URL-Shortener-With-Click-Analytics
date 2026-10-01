### End-to-End Measured Benchmark Results (k6)

> **Hardware Specifications & Test Environment:**
> - **CPU**: 12th Gen Intel(R) Core(TM) i5-12450H (8 Cores / 12 Logical Processors @ 2.50GHz)
> - **Memory**: 8.0 GB RAM
> - **Operating System**: Microsoft Windows 11 Home Single Language (x86_64)
> - **Load Profile**: Ramping virtual users (up to 30 VUs) across staged runs via k6
> - **Target**: Local Spring Boot instance over HTTP (end-to-end socket roundtrip)

| Benchmark Scenario | Total Requests | Throughput (RPS) | p50 Latency | p95 Latency | p99 Latency | Error Rate |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Warm Cache Redirect (O(1) In-Memory / Redis Hit)** | 11472 | 707.1 req/s | 1.49 ms | 3.04 ms | 3.81 ms | 0.00% |
| **Cold Cache Redirect (PostgreSQL JPA Miss)** | 2978 | 185.7 req/s | 2.70 ms | 3.79 ms | 4.74 ms | 0.00% |
| **90/10 Zipfian Mixed Traffic Distribution** | 6953 | 433.7 req/s | 1.47 ms | 2.74 ms | 3.43 ms | 0.00% |
| **Redis Slowed with Circuit Breaker (Fail-Fast Fallback)** | 2557 | 182.3 req/s | 1.46 ms | 2.48 ms | 3.09 ms | 0.00% |
