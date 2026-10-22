# Resume Facts: Verifiable Engineering Claims & Artifact References

This document catalogs senior engineering achievements for the **URL Shortener with Click Analytics & Traffic Intelligence** platform. Every claim is strictly backed by committed code paths, automated tests, and reproducible evaluation artifacts. All file links are formatted as relative repository paths. Zero figures are estimated or unverified.

---

## 1. Core Architecture & Distributed Systems

### Bullet 1: Coordination-Free Distributed ID Generation (Twitter Snowflake & Base62)
> *Designed and implemented a distributed, zero-coordination 64-bit Snowflake ID generator mapped to 10–11 character Base62 identifiers, eliminating sequential auto-increment database locks and avoiding multi-node sequence synchronization.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/util/SnowflakeIdGenerator.java`](backend/src/main/java/com/urlshortener/util/SnowflakeIdGenerator.java)
  - Base62 Encoding: [`backend/src/main/java/com/urlshortener/util/Base62Encoder.java`](backend/src/main/java/com/urlshortener/util/Base62Encoder.java)
  - Unit & Collision Tests: [`backend/src/test/java/com/urlshortener/util/SnowflakeIdGeneratorTest.java`](backend/src/test/java/com/urlshortener/util/SnowflakeIdGeneratorTest.java)
  - Architectural Decision Record: [`docs/adr/001-snowflake-vs-sequential-ids.md`](docs/adr/001-snowflake-vs-sequential-ids.md)
- **Verifiable Technical Claims**:
  - **Bit Allocation**: 1 sign bit (0) + 41-bit millisecond timestamp (epoch 2024-01-01, ~69.7 yr lifespan) + 10-bit worker ID (up to 1,024 nodes) + 12-bit sequence counter (up to 4,096 IDs/ms per node).
  - **Mathematical Proof**: 63-bit positive integers require 10–11 characters in Base62 ($62^{10} \approx 8.39 \times 10^{17} > 2^{63}-1$). Artificial truncation to 6 or 7 characters induces modulo collisions across timestamps.
  - **Worker ID Resolution Hierarchy**: `app.snowflake.worker-id` property $\rightarrow$ `WORKER_NODE_ID` env var $\rightarrow$ host IP/name hash $\rightarrow$ secure random seed.
  - **Clock Backward Drift**: Absorbs backward drift $\le 10\text{ ms}$ by sleeping; fails fast with an explicit drift exception if drift exceeds $10\text{ ms}$.
- **Known Trade-offs**:
  - Snowflake IDs are temporally ordered and structured; they do **not** protect against URL enumeration. For obfuscated identifiers, custom short aliases are supported.

---

### Bullet 2: Decoupled Redirect Fast Path & Asynchronous Click Analytics
> *Engineered an HTTP 302 redirect fast path completely decoupled from relational database writes, utilizing Redis cache-aside for resolution and Redis Streams consumer groups for asynchronous click analytics ingestion.*

- **Exact File Paths**:
  - Fast-Path Controller: [`backend/src/main/java/com/urlshortener/controller/RedirectController.java`](backend/src/main/java/com/urlshortener/controller/RedirectController.java)
  - Redis Stream Channel: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java`](backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java)
  - Stream Consumer: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java`](backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java)
  - Architectural Decision Record: [`docs/adr/002-redis-stream-eventual-consistency.md`](docs/adr/002-redis-stream-eventual-consistency.md)
- **Verifiable Technical Claims**:
  - **Zero Blocking DB Writes**: Redirect execution on cache hit requires zero relational database I/O.
  - **Buffer Sizing & Durability Boundary**: `RedisStreamClickChannel` uses a bounded in-memory `ArrayBlockingQueue(10,000)` buffer in front of 4 async publisher threads that write to a capped Redis Stream (`MAXLEN ~ 50,000`). Up to 10,000 in-flight unwritten heap events can be lost on an ungraceful JVM kill (normal flush latency < 2ms).
  - **Consumer Group Semantics**: At-least-once delivery with automatic `XPENDING` and `XCLAIM` recovery, combined with database deduplication on unique `event_id`.
  - **Measured Redirect Latency (k6 Multi-Container Cluster)**:
    - Warm Cache: p50: ~1.48 ms, p95: ~2.89 ms at 100–150 RPS ([`bench/results/repeatability_summary.json`](bench/results/repeatability_summary.json)).
    - Cold DB Fallback: p50: ~2.70 ms, p95: ~3.79 ms on Redis circuit breaker bypass.

---

### Bullet 3: Multi-Instance Distributed Rate Limiting (Redis Lua)
> *Identified through self-audit that local in-memory rate limiting allowed 3x quota leakage across a 3-instance cluster; re-architected rate limiting to an atomic Redis Lua continuous greedy token bucket with in-memory fallback.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/service/RateLimitingService.java`](backend/src/main/java/com/urlshortener/service/RateLimitingService.java)
  - Audit Documentation: [`AUDIT.md#L32`](AUDIT.md#L32)
  - Multi-Instance Verification Test: [`backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java`](backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java)
- **Verifiable Technical Claims**:
  - **Atomic Continuous Replenishment**: Token replenishment is calculated dynamically in Redis Lua: `current_tokens = min(capacity, tokens + delta * rate)`.
  - **Cluster-Wide Enforcement**: Prevents quota multiplication across horizontal replicas without distributed locks.
  - **Degraded Fallback**: If Redis connectivity fails, the service falls back to local in-memory Bucket4j instances to preserve availability.

---

### Bullet 4: Asynchronous Anomaly Detection & Bot Quarantine
> *Built a shared sliding-window anomaly detector using Redis Sorted Sets and Robust Z-Scores (Median Absolute Deviation) executing on the asynchronous ingestion thread to quarantine bot traffic without impacting redirect latency.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/service/AnomalyDetector.java`](backend/src/main/java/com/urlshortener/service/AnomalyDetector.java)
  - Asynchronous Execution: [`backend/src/main/java/com/urlshortener/pipeline/ClickEventProcessor.java`](backend/src/main/java/com/urlshortener/pipeline/ClickEventProcessor.java)
  - Synthetic Evaluation Script: [`eval/generate_synthetic_traffic.mjs`](eval/generate_synthetic_traffic.mjs)
  - Empirical Multi-Seed Report: [`eval/ANOMALY_DETECTION_REPORT.md`](eval/ANOMALY_DETECTION_REPORT.md)
- **Verifiable Metric Numbers (from `eval/ANOMALY_DETECTION_REPORT.md`)**:
  - **Execution Path**: All Redis sorted-set writes and MAD calculations execute strictly in the asynchronous background consumer thread; zero anomaly computation runs on the redirect fast path.
  - **High-Velocity Bot Bursts**: Detected at **80.3 ± 3.1% recall** and **99.8 ± 0.5% precision** across 10 evaluation seeds (43,500 total events).
  - **Legitimate Normal Traffic**: Evaluated at **98.7 ± 0.1% recall**, corresponding to a **1.3 ± 0.1% false-positive rate**.
- **Documented Architectural Limitations**:
  - **Slow-Drip Distributed Botnets**: 0% recall (botnets distributing requests below the per-IP velocity threshold evade detection).
  - **Adversarial Poisson-Jitter Evasion**: 0% recall (attacks varying IP and user-agent with human-like inter-arrival distributions bypass heuristic rules).

---

## 2. Traffic Optimization & Machine Learning

### Bullet 5: Multi-Destination A/B Link Optimization via Bayesian Thompson Sampling
> *Implemented multi-destination landing page optimization using Bayesian Thompson Sampling (Beta-Bernoulli conjugate distributions), dynamically routing traffic toward top-converting variants and reducing cumulative regret by 96.5% in simulation compared to static uniform splitting.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/routing/BanditRouter.java`](backend/src/main/java/com/urlshortener/routing/BanditRouter.java)
  - Multi-Seed Simulation Engine: [`eval/bandit_simulation.mjs`](eval/bandit_simulation.mjs)
  - Artifact Results: [`eval/bandit_results.json`](eval/bandit_results.json)
  - Verification Report: [`eval/BANDIT_REPORT.md`](eval/BANDIT_REPORT.md)
  - Architectural Decision Record: [`docs/adr/003-multi-armed-bandit-ab-optimization.md`](docs/adr/003-multi-armed-bandit-ab-optimization.md)
- **Verifiable Metric Numbers (from `eval/bandit_results.json`, 50 Monte Carlo Seeds)**:
  - **Test Conditions**: 50 independent seeds, 2,000 visitors/seed (100,000 total simulated interactions per strategy) with preset ground truth conversion rates of 0.85 (Variant A), 0.70 (Variant B), and 0.50 (Variant C).
  - **Optimal Variant Allocation**: Thompson Sampling routed **96.95 ± 2.1%** of traffic to the optimal landing page (1,939 / 2,000 pulls), compared to **33.31 ± 1.12%** for uniform random splitting.
  - **Cumulative Regret**: Capped at **11.7 ± 6.94** under Thompson Sampling vs **332.81 ± 6.61** for uniform splitting (**96.5% regret reduction in simulation**).
  - **Mean Conversion Rate**: Achieved **84.62 ± 0.69%** overall conversion rate vs **68.72 ± 0.98%** for uniform baseline (+15.9% absolute lift in simulation).

---

## 3. Security & Privacy Engineering

### Bullet 6: Multi-Stage SSRF & Redirect Safety Validation
> *Developed a defense-in-depth URL safety validator enforcing HTTP/HTTPS schemes, 2,048-character length bounds, DNS pre-resolution blocking loopback, RFC-1918 private subnets, cloud metadata (`169.254.169.254`), and recursive redirect loops.*

- **Exact File Paths**:
  - Validator: [`backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java`](backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java)
  - Security Test Suite (42 unit tests): [`backend/src/test/java/com/urlshortener/util/UrlSafetyValidatorTest.java`](backend/src/test/java/com/urlshortener/util/UrlSafetyValidatorTest.java)
  - Security Documentation: [`docs/SECURITY.md`](docs/SECURITY.md)
  - Architectural Decision Record: [`docs/adr/004-ssrf-defense-in-depth.md`](docs/adr/004-ssrf-defense-in-depth.md)
- **Verifiable Technical Claims**:
  - DNS pre-resolution checks all resolved IP addresses against prohibited CIDRs: `127.0.0.0/8`, `::1`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254`, and carrier-grade NAT `100.64.0.0/10`.
  - Self-referencing check blocks redirect loops pointing back to `app.base-url`.
- **Documented Limitation**:
  - **DNS Rebinding**: Validate-at-create-time checks are subject to TOCTOU (time-of-check to time-of-use) DNS rebinding attacks if the attacker changes the DNS record post-creation. Comprehensive mitigation requires egress firewall proxy enforcement or validation on redirect resolution.

---

### Bullet 7: Privacy-Preserving IP Pseudonymization (HMAC-SHA256)
> *Upgraded client IP address privacy from plain unsalted SHA-256 to rotatable HMAC-SHA256 with key length validation ($\ge 32$ chars), protecting visitor IP addresses against offline rainbow table inversion.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/util/IpHashUtil.java`](backend/src/main/java/com/urlshortener/util/IpHashUtil.java)
  - Unit Tests: [`backend/src/test/java/com/urlshortener/util/IpHashUtilTest.java`](backend/src/test/java/com/urlshortener/util/IpHashUtilTest.java)
- **Verifiable Technical Claims**:
  - Rejects missing, empty, or default salts under 32 characters on startup.
  - Raw client IP addresses are never persisted into application databases.
- **Documented Limitations**:
  - **Ingress Server Logging**: Upstream reverse proxies (Nginx / ALB) record client IP addresses in standard HTTP access logs unless custom log formatting or log scrubbing is explicitly configured.
  - **Salt Rotation Reset**: Rotating the HMAC secret salt invalidates historical distinct-visitor linkage, causing recurring visitors to appear as new visitors.

---

## 4. Observability & Telemetry

### Bullet 8: Trace-ID Propagation & MDC Log Correlation
> *Implemented distributed trace-ID extraction and propagation (W3C `traceparent` and `X-Trace-Id`) across HTTP request filters and Redis Stream queue payloads, binding trace IDs to MDC for end-to-end log correlation.*

- **Exact File Paths**:
  - Trace Context Filter: [`backend/src/main/java/com/urlshortener/security/TraceContextFilter.java`](backend/src/main/java/com/urlshortener/security/TraceContextFilter.java)
  - Redis Stream Propagation: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java`](backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java)
  - Consumer MDC Binding: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java`](backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java)
  - Observability Tests: [`backend/src/test/java/com/urlshortener/service/ObservabilityAndTracingTest.java`](backend/src/test/java/com/urlshortener/service/ObservabilityAndTracingTest.java)
- **Verifiable Technical Claims**:
  - Injects trace ID into HTTP response headers (`X-Trace-Id`) and log context (`%X{traceId:-system}`).
  - Serializes `traceId` inside `ClickEvent` queue payload so background consumer worker threads re-bind the identical trace ID in MDC during batch database persistence.
  - Micrometer timers and counters expose redirect latencies (`p50`, `p95`, `p99`), status codes (`302`, `410`), and stream queue depth (`click.stream.queue_depth`) to Prometheus.

---

## 5. Infrastructure, CI/CD & Deployment

### Bullet 9: Terraform-Defined AWS Topology & OIDC Continuous Deployment
> *Authored modular Terraform 1.5+ configurations defining a 3-tier AWS topology (VPC, ALB, ECS Fargate, Multi-AZ RDS PostgreSQL, and ElastiCache Redis) paired with zero-static-credentials GitHub Actions deployment via AWS OIDC.*

- **Exact File Paths**:
  - Terraform Configurations: [`infra/terraform/`](infra/terraform/)
  - Multi-Stage Hardened Dockerfile: [`backend/Dockerfile`](backend/Dockerfile)
  - GitHub Actions Deploy Workflow: [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)
  - Deployment Runbook: [`docs/DEPLOY.md`](docs/DEPLOY.md)
  - Integration Test Suite: [`backend/src/test/java/com/urlshortener/integration/ClusterIntegrationTest.java`](backend/src/test/java/com/urlshortener/integration/ClusterIntegrationTest.java)
- **Verifiable Technical Claims**:
  - **Multi-Stage Container Image**: Built with `eclipse-temurin:25-jre-noble`, executing as unprivileged user `appuser:appgroup` (UID 10001) with JVM container cgroup memory constraints (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`).
  - **Zero Long-Lived AWS Keys**: GitHub Actions exchanges short-lived tokens via AWS STS OIDC role assumption.
  - **Rolling Update Strategy**: Configured with `minimum_healthy_percent = 100` and `maximum_percent = 200` gated by ALB `/actuator/health` check checks.
  - **Testcontainers Integration Suite**: `ClusterIntegrationTest` tests PostgreSQL and Redis containers with `disabledWithoutDocker = true` fallback for bare developer machines.

---

## 6. Reconciliation of Legacy & Unverified Metrics

| Legacy Resume Claim | Status | Reason & Truth in Code |
| :--- | :---: | :--- |
| **"1M collision-free codes generated"** | **UNVERIFIED / PROJECTION** | Represents a database sizing projection in `backend/SYSTEM_DESIGN.md` ($10^6 \times 1\text{KB} \approx 1\text{GB}$), not a recorded benchmark artifact. Unit test verifies $10,000$ consecutive codes without collisions. |
| **"5,000 events/sec throughput"** | **UNVERIFIED / RETIRED** | Synthetic projection from single-node in-memory microbenchmarks. Multi-container load tests were capped at 200 RPS across 3 nodes in `bench/results/repeatability_summary.json`. |
| **"~65% latency reduction via caching"** | **CLARIFIED** | In-process microbenchmarks show cache-aside hit speedups ($< 0.1\text{ms}$ vs $2.8\text{ms}$ cold DB fallback), but end-to-end network latency across Nginx measures p50: ~1.48 ms (warm) vs p50: ~2.70 ms (cold), an empirical reduction of ~45%. |
| **"100% availability through 30s outage"** | **CLARIFIED** | Tested via `FailureSimulationTest.java` and `INCIDENTS.md#INC-001`. Circuit breaker bypasses Redis in $< 3\text{ms}$, but Toxiproxy multi-minute live cluster test was not completed. |
| **"92% bot detection accuracy"** | **REPLACED BY ARTIFACT** | Replaced by multi-seed evaluation in `eval/ANOMALY_DETECTION_REPORT.md`: **80.3 ± 3.1% recall** on bot bursts with **99.8 ± 0.5% precision** and **1.3 ± 0.1% false-positive rate** on normal traffic. |

---

## 7. Ready-to-Use Resume Bullet Sets

### Resume Draft A (Backend & Systems Focus, 4 Bullets)

> **URL Shortener with Click Analytics & Traffic Intelligence** | *Java 25, Spring Boot 3, PostgreSQL, Redis, Docker, Terraform, GitHub Actions, Prometheus, Grafana*
> - Engineered an HTTP 302 redirect path using Redis cache-aside (p95: 2.89 ms at 150 RPS across 3 container replicas behind Nginx) with Resilience4j circuit breaker fail-fast fallback to PostgreSQL during cache degradation.
> - Decoupled click analytics from the redirect path using Redis Streams consumer groups (at-least-once, idempotent upserts) and a bounded publisher buffer (`ArrayBlockingQueue(10,000)`); instrumented queue depth and drop metrics to Prometheus and Grafana.
> - Identified through self-audit that rate limiting and anomaly detection were stored in local memory (permitting 3x quota leakage); re-architected both to Redis (Lua token bucket, sorted-set sliding windows); MAD-based detector achieved 80.3% recall on synthetic bot bursts at a 1.3% false-positive rate across 10 evaluation seeds.
> - Hardened and deployed: multi-stage non-root Dockerfile, SSRF-safe URL validation, HMAC-SHA256 IP pseudonymization, Testcontainers integration tests, and Terraform-defined AWS infrastructure (ECS Fargate, Multi-AZ RDS PostgreSQL, ElastiCache Redis) with OIDC-based CI/CD deploys.

### Resume Draft B (Product & Analytics Focus, 4 Bullets)

> **URL Shortener with Click Analytics & Traffic Intelligence** | *Java 25, Spring Boot 3, PostgreSQL, Redis, React, Vite, Recharts, Docker, Terraform*
> - Built a high-throughput URL shortener with non-blocking redirects (Redis cache-aside, Resilience4j failover), decoupling downstream analytics ingestion via Redis Streams and hourly time-bucketed rollup aggregations.
> - Implemented multi-destination A/B landing page optimization using Bayesian Thompson Sampling (Beta-Bernoulli conjugate distributions), allocating 96.9% of traffic to the optimal variant and reducing cumulative regret by 96.5% in simulation over 50 Monte Carlo seeds.
> - Resolved multi-node concurrency vulnerabilities uncovered in self-audit by moving token bucket rate limiting and sliding-window bot detection to Redis (Lua scripts, sorted sets), quarantining bot bursts (80.3% recall, 1.3% false-positive rate) into audit tables.
> - Built full-stack platform: React analytics dashboard (real-time click time-series, browser/device breakdown via Recharts), SSRF validation blocking private subnets/metadata, and Terraform-defined AWS infrastructure (ECS Fargate, RDS, ElastiCache).
