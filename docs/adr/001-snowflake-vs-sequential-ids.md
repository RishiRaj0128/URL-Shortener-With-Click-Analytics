# ADR 001: Snowflake Distributed ID Generation vs Sequential DB Sequences

## Status
Accepted

## Context
A high-throughput URL shortener service must generate unique short identifiers for millions of URLs created across multiple distributed backend application nodes.

We considered two primary approaches:
1. **Centralized Database Sequences (Auto-Increment / PostgreSQL Sequences)**:
   - *Pros*: Monotonically increasing numbers, compact 6-7 character Base62 encodings ($62^6 \approx 56.8 \text{ billion}$).
   - *Cons*: Single point of serialization and lock contention; requires round-trip network I/O to PostgreSQL for every shortened link; exposes global URL volume to external scraping via sequential identifier enumeration; complicates multi-region or active-active deployments.
2. **Distributed 64-bit Snowflake IDs**:
   - *Pros*: Fully autonomous in-memory generation with zero cross-instance synchronization or database round-trips; supports up to 1,024 independent worker nodes; generates up to 4,096 IDs per millisecond per node; temporally ordered; eliminates sequential enumeration attacks.
   - *Cons*: 63-bit positive magnitude requires 10 to 11 Base62 characters ($62^{10} \approx 8.39 \times 10^{17} > 2^{63}-1$). Artificial truncation to 6 or 7 characters results in severe modulo collisions.

## Decision
We adopted Twitter Snowflake ID generation mapped to full-precision 10–11 character Base62 strings:
- **Bit Allocation**: 1 sign bit (0) + 41-bit timestamp (millisecond delta from custom epoch `2024-01-01T00:00:00Z`, providing ~69.7 years lifespan) + 10-bit worker ID (0–1023) + 12-bit sequence counter (0–4095).
- **Base62 Encoding**: Full 64-bit encoding without artificial character truncation. We verified mathematically and via automated tests that any truncation of 64-bit integers below 10 characters immediately triggers hash collisions across distinct timestamps.
- **Worker Node Resolution**: Dynamic resolution hierarchy checking configured property `app.snowflake.worker-id`, environment variable `WORKER_NODE_ID`, hostname hash, and finally a cryptographic random seed fallback.
- **Clock Drift Safety**: If system clock retreats $\le 10\text{ ms}$, the generator sleeps until the previous timestamp is reached. If clock drift exceeds $10\text{ ms}$, generation fails fast with an alert rather than emitting backwards-colliding IDs.

## Consequences
- **Positive**: Link creation fast-path operates with sub-millisecond in-memory speed ($< 1\mu\text{s}$) without database locks.
- **Trade-off**: Generated URLs are 10–11 characters (e.g. `1s2d3f4g5h`) rather than 6 characters. For users needing ultra-short URLs, custom short aliases (e.g. `promo`) are supported with unique constraints and reserved word filtering.
