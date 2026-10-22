package com.urlshortener.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 1: Distributed-Safe Snowflake ID Generator Tests")
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("Should generate positive IDs with correct bit decomposition")
    void shouldGeneratePositiveIdWithCorrectBitDecomposition() {
        long workerId = 42L;
        long customEpoch = 1767225600000L; // 2026-01-01T00:00:00Z
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(workerId, customEpoch);

        long id = generator.nextId();
        assertTrue(id > 0, "Generated ID must be strictly positive");

        SnowflakeIdGenerator.SnowflakeDecomposition decomp = SnowflakeIdGenerator.decompose(id, customEpoch);
        assertEquals(workerId, decomp.workerId(), "Worker ID must match bitfield");
        assertEquals(0L, decomp.sequence(), "First sequence in ms should be 0");
        assertTrue(decomp.timestampMillis() >= customEpoch, "Timestamp must be >= custom epoch");
    }

    @Test
    @DisplayName("Should generate strictly monotonic IDs within the same millisecond")
    void shouldGenerateMonotonicIdsWithinSameMillisecond() {
        Instant fixedInstant = Instant.parse("2026-03-01T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        long workerId = 7L;
        long customEpoch = 1767225600000L;

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(workerId, customEpoch, fixedClock);

        long prevId = -1;
        for (int i = 0; i < 1000; i++) {
            long currentId = generator.nextId();
            assertTrue(currentId > prevId, "Successive IDs in same ms must strictly increase");
            SnowflakeIdGenerator.SnowflakeDecomposition decomp = SnowflakeIdGenerator.decompose(currentId, customEpoch);
            assertEquals(i, decomp.sequence(), "Sequence must increment monotonically");
            assertEquals(workerId, decomp.workerId());
            prevId = currentId;
        }
    }

    @Test
    @DisplayName("Zero-Collision Concurrency Test: 50,000 IDs across 16 concurrent workers")
    void shouldHaveZeroCollisionsAcrossConcurrentWorkers() throws InterruptedException, ExecutionException {
        int threadCount = 16;
        int idsPerThread = 3500;
        int totalExpectedIds = threadCount * idsPerThread; // 56,000 IDs total

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        Set<Long> generatedIds = Collections.synchronizedSet(new HashSet<>(totalExpectedIds));
        Set<String> generatedShortCodes = Collections.synchronizedSet(new HashSet<>(totalExpectedIds));

        // Create 16 generators with distinct worker IDs
        SnowflakeIdGenerator[] generators = new SnowflakeIdGenerator[threadCount];
        for (int i = 0; i < threadCount; i++) {
            generators[i] = new SnowflakeIdGenerator(i, SnowflakeIdGenerator.DEFAULT_CUSTOM_EPOCH);
        }

        Future<?>[] futures = new Future<?>[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int workerIndex = t;
            futures[t] = executor.submit(() -> {
                try {
                    startLatch.await(); // Simultaneous start
                    SnowflakeIdGenerator gen = generators[workerIndex];
                    for (int i = 0; i < idsPerThread; i++) {
                        long id = gen.nextId();
                        String code = Base62Encoder.encode(id);
                        generatedIds.add(id);
                        generatedShortCodes.add(code);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown(); // Fire all threads simultaneously
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(totalExpectedIds, generatedIds.size(),
                "CRITICAL: Collisions detected in 64-bit Snowflake IDs!");
        assertEquals(totalExpectedIds, generatedShortCodes.size(),
                "CRITICAL: Collisions detected in Base62 short codes!");
    }

    @Test
    @DisplayName("Base62 pure function bi-directional encoding/decoding symmetry")
    void shouldMaintainBiDirectionalBase62Symmetry() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(99L);
        for (int i = 0; i < 5000; i++) {
            long originalId = generator.nextId();
            String encoded = Base62Encoder.encode(originalId);
            assertNotNull(encoded);
            assertFalse(encoded.isEmpty());

            long decoded = Base62Encoder.decode(encoded);
            assertEquals(originalId, decoded, "Decoded Base62 must match original Snowflake ID exactly");
        }
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on negative worker ID or worker ID exceeding 1023")
    void shouldRejectInvalidWorkerIds() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }

    @Test
    @DisplayName("Length & Truncation Collision Proof: Snowflake Base62 produces 10-11 chars; truncating to 6 causes collisions")
    void shouldDemonstrateFullBase62LengthAndProveTruncationCausesCollisions() {
        // Epoch: 2026-01-01T00:00:00Z. Simulated time 10 months into epoch:
        Instant fixedInstant = Instant.parse("2026-10-22T12:00:00Z");
        Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, SnowflakeIdGenerator.DEFAULT_CUSTOM_EPOCH, clock);

        long id = generator.nextId();
        String fullBase62 = Base62Encoder.encode(id);

        // Explicit length assertion: 64-bit Snowflake encodes to 10-11 chars, NOT 6 chars!
        assertTrue(fullBase62.length() >= 10 && fullBase62.length() <= 11,
                "Snowflake Base62 must be 10-11 characters, got length: " + fullBase62.length() + " (" + fullBase62 + ")");

        // Now demonstrate why a 6-character truncation destroys collision-freedom:
        // Generate IDs with different sequences within the same millisecond or adjacent ms:
        Set<String> truncatedCodes = new HashSet<>();
        boolean collisionFound = false;

        for (int i = 0; i < 2000; i++) {
            long currentId = generator.nextId();
            String code = Base62Encoder.encode(currentId);
            // Prefix truncation (taking first 6 chars):
            String truncated = code.substring(0, 6);
            if (!truncatedCodes.add(truncated)) {
                collisionFound = true;
                break;
            }
        }

        assertTrue(collisionFound,
                "Truncating 64-bit Snowflake Base62 to 6 characters MUST cause collisions (prefix shared across time window)");
    }

    @Test
    @DisplayName("Clock Drift Metric: Minor drift <= 5ms waits and increments wait metric")
    void shouldIncrementWaitMetricOnMinorDrift() {
        AtomicBoolean clockMovedBack = new AtomicBoolean(false);
        Instant t0 = Instant.parse("2026-10-22T12:00:00.100Z");
        Instant tMinus3 = Instant.parse("2026-10-22T12:00:00.097Z"); // 3ms backward drift
        Instant tRecovered = Instant.parse("2026-10-22T12:00:00.105Z");

        Clock customClock = new Clock() {
            private int callCount = 0;
            @Override
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override
            public Instant instant() {
                callCount++;
                if (callCount == 1) return t0;
                if (callCount == 2) return tMinus3; // drift backwards by 3ms
                return tRecovered;
            }
        };

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(2L, SnowflakeIdGenerator.DEFAULT_CUSTOM_EPOCH, customClock);
        long id1 = generator.nextId();
        long id2 = generator.nextId();

        assertTrue(id2 > id1, "Monotonic ID progression after drift recovery");
        assertTrue(generator.getClockDriftWaitCount() > 0, "Wait metric must increment on minor drift");
        assertEquals(0, generator.getClockDriftErrorCount(), "No error metric on recovered drift");
    }

    @Test
    @DisplayName("Clock Drift Metric: Major drift > 5ms throws exception and increments error metric")
    void shouldIncrementErrorMetricOnMajorDrift() {
        Instant t0 = Instant.parse("2026-10-22T12:00:00.100Z");
        Instant tMinus50 = Instant.parse("2026-10-22T12:00:00.050Z"); // 50ms backward drift

        Clock customClock = new Clock() {
            private int callCount = 0;
            @Override
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override
            public Instant instant() {
                callCount++;
                if (callCount == 1) return t0;
                return tMinus50; // drift backwards by 50ms
            }
        };

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3L, SnowflakeIdGenerator.DEFAULT_CUSTOM_EPOCH, customClock);
        generator.nextId(); // First ID establishes lastTimestamp

        assertThrows(IllegalStateException.class, generator::nextId, "Must fail fast on major backward clock drift");
        assertEquals(1, generator.getClockDriftErrorCount(), "Error metric must increment on unrecoverable clock drift");
    }

    @Test
    @DisplayName("Worker ID Resolution: Safely extracts worker ID from config, env, or hostname")
    void shouldResolveWorkerIdSafely() {
        // Explicit property
        assertEquals(5L, SnowflakeIdGenerator.resolveWorkerId("5", null, null));
        // Fallback to env var
        assertEquals(8L, SnowflakeIdGenerator.resolveWorkerId(null, "8", null));
        // Fallback to hostname suffix (e.g., k8s statefulset or docker container: app-2)
        assertEquals(2L, SnowflakeIdGenerator.resolveWorkerId(null, null, "urlshortener-node-2"));
        // Default to 1 if none provided
        assertEquals(1L, SnowflakeIdGenerator.resolveWorkerId(null, null, "unknown-host"));
    }
}

