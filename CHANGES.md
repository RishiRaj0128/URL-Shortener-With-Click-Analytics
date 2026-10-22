# Project Hardening & Audit Progress: CHANGES.md

This document tracks all changes made per phase, assumptions adopted, and remaining deliverables.

---

## Phase 1: Consistency Fixes (`feature/phase-1-consistency-fixes`)

### PR Summary: Reconcile Snowflake ID & Base62 Length, Robustness, and Truth in Documentation

#### 1. Short Code Length vs. Snowflake ID Encoding
- **Problem**: Documentation and `application.yml` previously claimed "6-character, 56.8B URLs, collision-free" Base62 short codes. However, the system generates 64-bit Snowflake IDs (`SnowflakeIdGenerator`). Shifting the 41-bit millisecond timestamp offset produces 64-bit numbers with magnitudes of $\sim 10^{17}$, which encode to **10 to 11 Base62 characters** without truncation. The 6-character / 56.8B figure is mathematically valid only for sequential integer IDs ($62^6 \approx 56.8 \times 10^9$), not for 64-bit Snowflake IDs.
- **Root Cause**: The repository retained comments and property keys from an earlier sequential auto-increment prototype while implementing a distributed Snowflake ID generator.
- **Resolution**:
  - Confirmed the database column `short_code VARCHAR(20)` accommodates the full 10–11 characters.
  - Kept the 64-bit Snowflake ID design and explicitly preserved the full Base62 encoding without truncation.
  - Added unit test `shouldDemonstrateFullBase62LengthAndProveTruncationCausesCollisions` in [`SnowflakeIdGeneratorTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/util/SnowflakeIdGeneratorTest.java) proving that full Base62 produces 10–11 characters, and demonstrating that truncating to 6 characters induces collisions.
  - Replaced all "hash collisions" wording with **"collision-free by construction"** across `README.md`, `backend/README.md`, `backend/SYSTEM_DESIGN.md`, and `Base62Encoder.java`, documenting why the Cartesian product of timestamp bits, worker node bits, and sequence counters guarantees zero collisions.

#### 2. Snowflake Robustness & Multi-Node Worker Assignment
- **Problem**: Worker ID defaulted to `1` without safe fallback across instances. Backwards clock drift $>5$ms threw an `IllegalStateException` without metric instrumentation.
- **Resolution**:
  - Implemented `SnowflakeIdGenerator.resolveWorkerId(configuredWorkerId, envWorkerId, hostname)` to extract worker IDs dynamically from configuration, environment (`WORKER_NODE_ID`), or container hostname suffixes (e.g. `node-2` -> 2), bounded strictly to `0..1023`.
  - Added metric counters `clockDriftWaitCount` and `clockDriftErrorCount` to `SnowflakeIdGenerator` with accessors `getClockDriftWaitCount()` and `getClockDriftErrorCount()`.
  - Added unit tests verifying wait-and-recover behavior on minor drift ($\le 5$ms) and fail-fast exception with metric recording on major drift ($>5$ms).

#### 3. Documentation & Metric Consistency
- **Resolution**:
  - Updated `README.md`, `backend/README.md`, and `backend/SYSTEM_DESIGN.md` to reflect PostgreSQL and Flyway schema management.
  - Replaced ambiguous latency claims ("sub-ms", "< 3ms across all endpoints") with measured benchmark percentiles from [`bench/results/repeatability_summary.json`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/bench/results/repeatability_summary.json) along with hardware test specifications:
    - **Test Environment**: 12th Gen Intel Core i5-12450H (8 Cores / 12 Logical Processors @ 2.50GHz), 8.0 GB RAM, Windows 11 host with Docker Desktop.
    - **Warm Redis Cache Redirect**: p50 = 1.47–1.49 ms, p95 = 2.74–3.04 ms, p99 = 3.43–3.81 ms.
    - **Cold PostgreSQL Fallback**: p50 = 2.70 ms, p95 = 3.79 ms, p99 = 4.74 ms.
    - **Capacity Knees**: Cold JPA at 100–200 RPS; Warm Redis at 150–200 RPS.
  - Documented that anomaly detection metrics (92.96% accuracy, 100.00% precision, 0.00% FPR) are derived from the synthetic evaluation suite in [`eval/ANOMALY_DETECTION_REPORT.md`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/eval/ANOMALY_DETECTION_REPORT.md).

### Assumptions Made in Phase 1
1. **Full 10–11 Character Length**: Kept full Snowflake Base62 encoding without truncation. Truncation to 6 characters was verified to discard entropy and cause collisions.
2. **PostgreSQL as Source of Truth**: Confirmed PostgreSQL 15+ as the persistent relational database, Flyway for schema migrations, and Redis 7 as the transient cache tier.

---

## Phase 2: Correctness Across Multiple Instances (`feature/phase-2-multi-instance-correctness`)

### PR Summary: Multi-Instance Rate Limiting, Shared Anomaly State, Click Channel Drop Metrics, and HMAC-SHA256 IP Privacy

#### 1. Distributed Rate Limiting via Redis Token Bucket Lua Script
- **Problem**: In-memory `ConcurrentHashMap` with Bucket4j isolated rate-limiting tokens per container/pod. In a multi-node cluster, a user sending requests round-robined across $N$ instances received $N \times$ their rate limit allocation.
- **Resolution**:
  - Implemented an atomic Redis Lua token bucket script in [`RateLimitingService.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/service/RateLimitingService.java).
  - The script models a continuous greedy refill token bucket with key TTL auto-expiration (120 seconds), tracking `tokens` and `last_refill_timestamp` per bucket key.
  - Automatically falls back to in-memory Bucket4j if Redis is disconnected or unavailable, preserving local protection without throwing 500 errors.

#### 2. Shared Cluster Anomaly Detection State
- **Problem**: Per-instance `ArrayDeque` in `AnomalyDetector` allowed bot burst velocity attacks to evade detection when requests were split across multiple backend nodes (split-burst anomaly evasion).
- **Resolution**:
  - Upgraded [`AnomalyDetector.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/service/AnomalyDetector.java) to track sliding-window request timestamps using a shared Redis Sorted Set (`ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`) keyed by `anomaly:burst:{shortCode}:{ipHash}`.
  - Sets a 60-second key TTL so stale burst keys are purged automatically.
  - Automatically falls back to local in-memory sliding window deque if Redis is not active.

#### 3. HMAC-SHA256 IP Hashing with Rotatable Salt
- **Problem**: Previously used plain SHA-256 (`MessageDigest.getInstance("SHA-256")`). Because the IPv4 address space is small ($2^{32} \approx 4.29 \times 10^9$), unsalted or statically salted SHA-256 hashes are easily reversed via precomputed rainbow tables, violating GDPR/privacy standards.
- **Resolution**:
  - Refactored [`IpHashUtil.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/util/IpHashUtil.java) to use `HmacSHA256` keyed with a configurable secret (`app.security.ip-hash-secret` / `IP_HASH_SECRET`).
  - Added support for rotatable date/epoch salts (`app.security.ip-hash-salt` / `IP_HASH_SALT`), allowing automated salt rotation to protect historical IP hashes.
  - Updated all unit tests in [`IpHashUtilTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/util/IpHashUtilTest.java).

#### 4. Click Pipeline Durability & Metric Gauges
- **Problem**: When the Redis Stream click ingestion channel or fallback executor is saturated, dropped events were logged but not exposed to Prometheus/Micrometer.
- **Resolution**:
  - Added Micrometer metric gauges (`click.events.dropped`, `click.events.published`) in [`RedisStreamClickChannel.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/pipeline/RedisStreamClickChannel.java).
  - Maintained backward-compatible constructor for standalone/mocked testing.

#### 5. Verification Suite
- Added [`MultiInstanceCorrectnessTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/service/MultiInstanceCorrectnessTest.java) explicitly simulating multi-node deployments:
  - Tested distributed rate limiting across simulated nodes sharing a single Redis backend.
  - Tested split-burst anomaly evasion detection where 11 requests split 6 + 5 across Node A and Node B trigger a `BOT_BURST_VELOCITY` anomaly breach on the shared Redis state.
  - Tested HMAC-SHA256 cryptographic separation with distinct secrets and salts.

### Assumptions Made in Phase 2
1. **Redis Availability**: Redis is the shared coordination layer in production for distributed rate limiting and anomaly burst tracking. In-memory fallbacks ensure service continuity during Redis degradation or local non-Docker development.
2. **IP Hash Privacy**: HMAC-SHA256 with key/salt rotation satisfies GDPR compliance for anonymized traffic analytics.

---

## Remaining Phases (Pending Approval)

- **Phase 3**: Product basics & security (SSRF prevention, private/loopback IP blocking, protocol restrictions, max URL length, link expiration/TTL, custom aliases, soft delete, mandatory non-default JWT secret validation).
- **Phase 4**: Scope cleanup (Repurpose `BanditRouter` to multi-destination A/B link optimization, eliminate banking references, relocate EWMA node scoring to experiments, soften sensationalist wording).
- **Phase 5**: Observability (Micrometer Prometheus meters, provisioned Grafana dashboard in `docker-compose.yml`, alert rules, OpenTelemetry distributed tracing).
- **Phase 6**: Testing & CI (Testcontainers PostgreSQL/Redis integration suite, unit tests for anomaly math and Thompson sampling, GitHub Actions with Trivy, CodeQL, and Dependabot).
- **Phase 7**: Deployment & IaC (Multi-stage non-root Dockerfile, Terraform AWS infrastructure, GitHub Actions deploy workflow with OIDC, `docs/DEPLOY.md`).
- **Phase 8**: Documentation & Portfolio Integrity (`RESUME_FACTS.md`, architecture ADRs, verified `INCIDENTS.md`).

