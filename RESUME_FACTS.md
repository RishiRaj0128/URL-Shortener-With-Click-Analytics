# Resume Facts: Verifiable Engineering Claims & Artifact References

This document provides a catalog of senior engineering claims for the **URL Shortener with Click Analytics & Traffic Intelligence** platform. Every claim is strictly backed by committed code paths, automated tests, and reproducible evaluation artifacts. Zero figures are estimated or unverified.

---

## 1. Core Architecture & Distributed Systems

### Bullet 1: Distributed ID Generation via Twitter Snowflake & Base62
> *Designed and implemented a distributed, zero-coordination 64-bit Snowflake ID generator mapped to 10–11 character Base62 identifiers, eliminating sequential auto-increment database locks and protecting against URL enumeration scraping.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/util/SnowflakeIdGenerator.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/SnowflakeIdGenerator.java)
  - Base62 Encoding: [`backend/src/main/java/com/urlshortener/util/Base62Encoder.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/Base62Encoder.java)
  - Unit & Collision Tests: [`backend/src/test/java/com/urlshortener/util/SnowflakeIdGeneratorTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/util/SnowflakeIdGeneratorTest.java)
  - Architectural Decision: [`docs/adr/001-snowflake-vs-sequential-ids.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/adr/001-snowflake-vs-sequential-ids.md)
- **Verifiable Technical Claims**:
  - Bit layout: 1 sign bit + 41-bit millisecond timestamp (epoch 2024-01-01, ~69.7 yr lifespan) + 10-bit worker ID (up to 1,024 nodes) + 12-bit sequence counter (up to 4,096 IDs/ms per node).
  - Mathematical collision proof: 63-bit integers require 10–11 characters in Base62 ($62^{10} \approx 8.39 \times 10^{17} > 2^{63}-1$). Truncating to 6 or 7 characters creates severe modulo collisions.
  - Worker ID resolution hierarchy: `app.snowflake.worker-id` property $\rightarrow$ `WORKER_NODE_ID` env var $\rightarrow$ host IP/name hash $\rightarrow$ secure random seed.
  - Clock backward drift tolerance: Sleeps for backward drift $\le 10\text{ ms}$; fails fast with an explicit drift exception if drift exceeds $10\text{ ms}$.

---

### Bullet 2: Non-Blocking Redirect Fast Path with Redis Cache & Async Ingestion
> *Engineered an ultra-low latency HTTP 302 redirect fast path completely decoupled from relational database writes, utilizing Redis caching and Redis Streams consumer groups for asynchronous click analytics ingestion.*

- **Exact File Paths**:
  - Fast-Path Controller: [`backend/src/main/java/com/urlshortener/controller/RedirectController.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/controller/RedirectController.java)
  - Redis Stream Channel: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java)
  - Stream Consumer: [`backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickConsumer.java)
  - Architectural Decision: [`docs/adr/002-redis-stream-eventual-consistency.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/adr/002-redis-stream-eventual-consistency.md)
- **Verifiable Technical Claims**:
  - Redirect execution requires zero blocking relational database calls on cache hit.
  - Redis Stream consumer group (`click_analytics_group`) provides at-least-once delivery with automatic `XPENDING` and `XCLAIM` recovery.
  - Bounded 50,000-event in-memory ring buffer (`InMemoryClickEventChannel`) serves as a backpressure safety net when Redis is temporarily partitioned.

---

### Bullet 3: Multi-Instance Distributed Rate Limiting via Redis Lua
> *Implemented a multi-instance continuous greedy token bucket rate limiter in Redis Lua with seamless in-memory fallback, enforcing per-IP throughput constraints across distributed application clusters.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/service/RateLimitingService.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/service/RateLimitingService.java)
  - Multi-Instance Verification: [`backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java)
- **Verifiable Technical Claims**:
  - Continuous fractional token replenishment calculated dynamically in atomic Lua script: `current_tokens = min(capacity, tokens + delta * rate)`.
  - Avoids token race conditions across clustered nodes without requiring distributed locks.
  - Graceful fallback: If Redis becomes unreachable, switches seamlessly to local `ConcurrentHashMap` bucket without dropping client traffic.

---

### Bullet 4: Cluster Sliding-Window Anomaly Detection & Bot Quarantine
> *Built a shared multi-instance anomaly detection engine leveraging Redis Sorted Sets and Robust Z-Scores (Median Absolute Deviation) to detect and quarantine burst bot attacks in real time without dropping legitimate traffic.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/service/AnomalyDetector.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/service/AnomalyDetector.java)
  - Cluster Verification Test: [`backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java)
  - Synthetic Evaluation Script: [`eval/generate_synthetic_traffic.mjs`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/generate_synthetic_traffic.mjs)
  - Verification Report: [`eval/ANOMALY_DETECTION_REPORT.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/ANOMALY_DETECTION_REPORT.md)
- **Verifiable Technical Claims**:
  - Redis sorted sets record microsecond epoch scores per hashed client IP; expired entries pruned via `ZREMRANGEBYSCORE`.
  - Velocity tripwire flags burst traffic exceeding 5 requests per 5-second window.
  - Robust Z-score uses Median Absolute Deviation ($MAD = \text{median}(|x_i - \tilde{x}|)$) rather than standard deviation to prevent outlier-induced model poisoning.
  - Flagged requests are diverted to `click_anomaly_logs` with zero loss of legitimate click counts.

---

## 2. Machine Learning & Traffic Optimization

### Bullet 5: Multi-Destination A/B Link Optimization via Bayesian Thompson Sampling
> *Implemented multi-destination landing page optimization using Bayesian Thompson Sampling (Beta-Bernoulli conjugate distributions), autonomously routing traffic toward top-converting variants while reducing opportunity cost (regret) by ~75% compared to static A/B splits.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/routing/BanditRouter.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/routing/BanditRouter.java)
  - Simulation Engine: [`eval/bandit_simulation.mjs`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/bandit_simulation.mjs)
  - Artifact Results: [`eval/bandit_results.json`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/bandit_results.json)
  - Verification Report: [`eval/BANDIT_REPORT.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/BANDIT_REPORT.md)
  - Architectural Decision: [`docs/adr/003-multi-armed-bandit-ab-optimization.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/adr/003-multi-armed-bandit-ab-optimization.md)
- **Verifiable Metric Numbers (from `eval/bandit_results.json`)**:
  - **Optimal Variant Allocation**: 93.4% of total traffic allocated to the optimal landing page (85% conversion probability) over 2,000 trials, compared to 33.3% under uniform allocation.
  - **Conversion Efficiency**: Achieved 82.95% overall conversion rate (1,659 successes) vs 51.65% under static uniform allocation (+31.3% lift).
  - **Opportunity Loss (Regret)**: Cumulative regret capped at 23.4 vs 93.8 for uniform split (75.1% reduction in wasted traffic).

---

## 3. Security, Privacy & Compliance

### Bullet 6: Enterprise SSRF & Phishing Defense in Depth
> *Developed a multi-stage URL safety validation engine preventing Server-Side Request Forgery (SSRF) across loopback, RFC-1918 private subnets, and cloud instance metadata endpoints (`169.254.169.254`), with recursive redirect loop blocking and reserved keyword isolation.*

- **Exact File Paths**:
  - Validator: [`backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/UrlSafetyValidator.java)
  - Security Test Suite (42 unit tests): [`backend/src/test/java/com/urlshortener/util/UrlSafetyValidatorTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/util/UrlSafetyValidatorTest.java)
  - Security Documentation: [`docs/SECURITY.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/SECURITY.md)
  - Architectural Decision: [`docs/adr/004-ssrf-defense-in-depth.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/adr/004-ssrf-defense-in-depth.md)
- **Verifiable Technical Claims**:
  - Enforces HTTP/HTTPS protocol allowlist and 2,048 character limit.
  - DNS pre-resolution blocks `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254`, and carrier NAT `100.64.0.0/10`.
  - Defends against self-referencing redirect loops and protects system endpoints (`/actuator`, `/api`, `/health`).

### Bullet 7: Cryptographic IP Pseudonymization (HMAC-SHA256)
> *Upgraded client IP address privacy to rotatable HMAC-SHA256 hashing with salt length validation ($\ge 32$ chars), ensuring GDPR/privacy compliance while preserving deterministic fraud velocity detection.*

- **Exact File Paths**:
  - Implementation: [`backend/src/main/java/com/urlshortener/util/IpHashUtil.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/IpHashUtil.java)
  - Tests: [`backend/src/test/java/com/urlshortener/util/IpHashUtilTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/util/IpHashUtilTest.java)
- **Verifiable Technical Claims**:
  - Raw client IP addresses are never persisted to disk or logged.
  - Enforces $\ge 32$ character minimum salt on application boot; rejects empty or default salts.

---

## 4. Observability & Telemetry

### Bullet 8: Distributed Tracing & End-to-End Context Propagation
> *Implemented W3C TraceContext distributed tracing across synchronous HTTP boundaries and asynchronous Redis Stream queue consumers, binding trace IDs to MDC logging for unified log correlation from edge ingress to database rollups.*

- **Exact File Paths**:
  - Filter: [`backend/src/main/java/com/urlshortener/security/TraceContextFilter.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/security/TraceContextFilter.java)
  - Prometheus Metrics & Alerts: [`infra/prometheus/prometheus.yml`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/infra/prometheus/prometheus.yml), [`infra/prometheus/alert_rules.yml`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/infra/prometheus/alert_rules.yml)
  - Grafana Dashboard: [`infra/grafana/dashboards/url_shortener_dashboard.json`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/infra/grafana/dashboards/url_shortener_dashboard.json)
  - Observability Tests: [`backend/src/test/java/com/urlshortener/service/ObservabilityAndTracingTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/service/ObservabilityAndTracingTest.java)
- **Verifiable Technical Claims**:
  - Parses W3C `traceparent` and legacy `X-Trace-Id` headers; injects 32-character hex trace ID on missing incoming context.
  - Injects `traceId` into `ClickEvent` queue payloads, ensuring consumer worker threads re-bind the identical MDC context during async batch persistence.
  - Micrometer timers and counters instrument p50/p95/p99 redirect latencies, HTTP status codes, queue depth, and circuit breaker trip states.

---

## 5. Deployment, IaC & DevOps

### Bullet 9: Multi-AZ AWS Infrastructure as Code (Terraform) & OIDC CI/CD
> *Authored production-ready Terraform 1.5+ configurations provisioning a 3-tier AWS topology (VPC, ALB, ECS Fargate, Multi-AZ RDS PostgreSQL, and ElastiCache Redis) paired with zero-static-credentials GitHub Actions deployment via AWS OIDC federation.*

- **Exact File Paths**:
  - Terraform Configs: [`infra/terraform/`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/infra/terraform/)
  - Multi-Stage Dockerfile: [`backend/Dockerfile`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/Dockerfile)
  - OIDC Deployment Workflow: [`.github/workflows/deploy.yml`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/.github/workflows/deploy.yml)
  - Integration Test Suite: [`backend/src/test/java/com/urlshortener/integration/ClusterIntegrationTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/integration/ClusterIntegrationTest.java)
  - Deployment Runbook: [`docs/DEPLOY.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/docs/DEPLOY.md)
- **Verifiable Technical Claims**:
  - Non-root container runtime (`appuser:appgroup`, UID 10001) with JVM container memory sizing (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`).
  - Zero static AWS keys: GitHub Actions uses short-lived STS tokens exchanged via OIDC role assumption.
  - Zero-downtime rolling ECS updates (`minimum_healthy_percent = 100`, `maximum_percent = 200`) gated by `/actuator/health` probes on ALB target group.
