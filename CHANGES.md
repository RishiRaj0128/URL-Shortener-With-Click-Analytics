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

## Remaining Phases (Pending Approval)

- **Phase 2**: Correctness across multiple instances (Redis-backed Bucket4j distributed rate limiter, shared anomaly detection state, click pipeline durability & drop metrics, HMAC-SHA256 IP hashing with rotatable salt).
- **Phase 3**: Product basics & security (SSRF prevention, private/loopback IP blocking, protocol restrictions, max URL length, link expiration/TTL, custom aliases, soft delete, mandatory non-default JWT secret validation).
- **Phase 4**: Scope cleanup (Repurpose `BanditRouter` to multi-destination A/B link optimization, eliminate banking references, relocate EWMA node scoring to experiments, soften sensationalist wording).
- **Phase 5**: Observability (Micrometer Prometheus meters, provisioned Grafana dashboard in `docker-compose.yml`, alert rules, OpenTelemetry distributed tracing).
- **Phase 6**: Testing & CI (Testcontainers PostgreSQL/Redis integration suite, unit tests for anomaly math and Thompson sampling, GitHub Actions with Trivy, CodeQL, and Dependabot).
- **Phase 7**: Deployment & IaC (Multi-stage non-root Dockerfile, Terraform AWS infrastructure, GitHub Actions deploy workflow with OIDC, `docs/DEPLOY.md`).
- **Phase 8**: Documentation & Portfolio Integrity (`RESUME_FACTS.md`, architecture ADRs, verified `INCIDENTS.md`).
