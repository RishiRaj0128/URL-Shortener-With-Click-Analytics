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
}
