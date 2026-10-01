package com.urlshortener.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Distributed-safe 64-bit Snowflake ID Generator.
 *
 * Bit Allocation (64 bits total):
 * - 1 sign bit: Always 0 (ensures non-negative signed 64-bit integer)
 * - 41 timestamp bits: Milliseconds since custom epoch (provides ~69.7 years of span)
 * - 10 worker node bits: Supports 1024 concurrent worker nodes (0 - 1023)
 * - 12 sequence bits: Supports 4096 IDs per millisecond per worker node (up to ~4.096M IDs/sec/node)
 *
 * Guarantees:
 * - Zero coordination: Nodes generate IDs autonomously without centralized locks or DB sequences
 * - Zero collisions: Unique across worker nodes and monotonic per millisecond
 * - Pure Base62 compatibility: Produces compact, URL-safe alphanumeric short codes
 * - Clock skew tolerance: Handles minor backwards NTP drift with spin-wait; rejects major drift
 */
@Component
public class SnowflakeIdGenerator {

    // Epoch: 2026-01-01T00:00:00Z (in milliseconds)
    public static final long DEFAULT_CUSTOM_EPOCH = 1767225600000L;

    public static final int WORKER_ID_BITS = 10;
    public static final int SEQUENCE_BITS = 12;

    public static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;   // 1023
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;     // 4095

    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;              // 12
    private static final int TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 22

    private final long workerId;
    private final long customEpoch;
    private final Clock clock;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this(1L, DEFAULT_CUSTOM_EPOCH, Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SnowflakeIdGenerator(@Value("${app.snowflake.worker-id:1}") long workerId) {
        this(workerId, DEFAULT_CUSTOM_EPOCH, Clock.systemUTC());
    }

    public SnowflakeIdGenerator(long workerId, long customEpoch) {
        this(workerId, customEpoch, Clock.systemUTC());
    }

    public SnowflakeIdGenerator(long workerId, long customEpoch, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                String.format("Worker ID must be between 0 and %d, got: %d", MAX_WORKER_ID, workerId)
            );
        }
        if (customEpoch <= 0) {
            throw new IllegalArgumentException("Custom epoch must be positive");
        }
        this.workerId = workerId;
        this.customEpoch = customEpoch;
        this.clock = clock;
    }

    /**
     * Generate the next unique Snowflake ID. Thread-safe.
     */
    public synchronized long nextId() {
        long currentTimestamp = getCurrentTimeMillis();

        if (currentTimestamp < lastTimestamp) {
            long drift = lastTimestamp - currentTimestamp;
            // Handle minor NTP clock backwards jitter (up to 5ms) by waiting
            if (drift <= 5) {
                try {
                    Thread.sleep(drift + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                currentTimestamp = getCurrentTimeMillis();
            }
            if (currentTimestamp < lastTimestamp) {
                throw new IllegalStateException(
                    String.format("Clock moved backwards. Refusing to generate ID for %d ms", lastTimestamp - currentTimestamp)
                );
            }
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted in this millisecond; spin until next millisecond
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - customEpoch) << TIMESTAMP_LEFT_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimeMillis();
        }
        return timestamp;
    }

    private long getCurrentTimeMillis() {
        return clock.millis();
    }

    public long getWorkerId() {
        return workerId;
    }

    public long getCustomEpoch() {
        return customEpoch;
    }

    /**
     * Decomposes a Snowflake ID into its constituent parts for audit/verification.
     */
    public static SnowflakeDecomposition decompose(long snowflakeId, long customEpoch) {
        long sequence = snowflakeId & MAX_SEQUENCE;
        long workerId = (snowflakeId >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
        long timestampOffset = snowflakeId >> TIMESTAMP_LEFT_SHIFT;
        long timestamp = timestampOffset + customEpoch;

        return new SnowflakeDecomposition(snowflakeId, timestamp, workerId, sequence, Instant.ofEpochMilli(timestamp));
    }

    public record SnowflakeDecomposition(
        long id,
        long timestampMillis,
        long workerId,
        long sequence,
        Instant instant
    ) {}
}
