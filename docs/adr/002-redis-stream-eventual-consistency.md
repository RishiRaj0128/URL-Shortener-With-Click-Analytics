# ADR 002: Redis Streams for Asynchronous Eventual Consistency vs Synchronous DB Writes

## Status
Accepted

## Context
When an end-user navigates to a shortened URL (e.g. `GET /{shortCode}`), the system must:
1. Resolve the destination URL and issue an immediate HTTP 302 Found redirect.
2. Track granular click intelligence (timestamp, hashed IP, User-Agent, referer, anomaly flags).

If click tracking executes synchronously on the HTTP redirect path:
- Database disk writes introduce unpredictable latency spikes (10ms–150ms+ under load).
- If PostgreSQL encounters lock contention, pool exhaustion, or downtime, user redirects fail, directly degrading customer experience.
- Throughput is capped by database write IOPS.

## Decision
We separated the **Redirect Fast Path** from the **Analytics Persistence Path** using Redis Streams and an asynchronous consumer group architecture:

1. **Redirect Fast Path**:
   - Resolves destination URL from L1 local caffeine cache or L2 Redis cache.
   - Evaluates expiration (`expires_at`) and soft-delete (`is_deleted`) status in memory.
   - Publishes an immutable `ClickEvent` into Redis Stream `stream:click:events` via non-blocking write.
   - Immediately returns HTTP 302 with `Location` header.
   - Total fast-path p95 latency is preserved under 1ms.

2. **Analytics Ingestion Path (Asynchronous)**:
   - Dedicated worker threads participate in a Redis Consumer Group (`click_analytics_group`).
   - Batches events using `XREADGROUP` with automatic ACK and pending-list recovery (`XPENDING` / `XCLAIM`) to guarantee at-least-once processing.
   - Aggregates metrics into hourly time-bucketed rollups (`click_rollups`) with batch upserts, drastically reducing database write frequency.
   - Uses sliding-window Redis sorted sets and SHA-256 event fingerprinting to deduplicate replayed messages.

3. **Fault Tolerance & Graceful Degradation**:
   - If Redis becomes unavailable, a bounded in-memory ring buffer (`InMemoryClickEventChannel`) absorbs traffic surges up to 50,000 events without blocking redirect execution.

## Consequences
- **Positive**: Redirect latency is decoupled from database performance and database outages. The system handles sudden 10x traffic spikes without dropping redirect requests.
- **Trade-off**: Click analytics dashboards reflect data with sub-second eventual consistency (~100ms–500ms delay) rather than immediate synchronous consistency. This trade-off is optimal for analytics workloads.
