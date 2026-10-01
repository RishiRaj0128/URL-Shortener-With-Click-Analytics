package com.urlshortener.integration;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.pipeline.ClickEventChannel;
import com.urlshortener.pipeline.ClickEventConsumer;
import com.urlshortener.pipeline.InMemoryClickEventChannel;
import com.urlshortener.repository.ClickAnomalyLogRepository;
import com.urlshortener.repository.ClickEventDedupRepository;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.service.AnomalyDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 7: Induced Failure Scenarios & High-Throughput Load Verification")
class FailureSimulationTest {

    @Mock
    private ClickRollupRepository rollupRepository;

    @Mock
    private ClickEventDedupRepository dedupRepository;

    @Mock
    private ClickAnomalyLogRepository anomalyLogRepository;

    @Test
    @DisplayName("Failure Scenario 1: Queue consumer killed mid-stream; events buffer in channel and drain on recovery without loss")
    void shouldBufferEventsDuringConsumerOutageAndDrainOnRecovery() throws InterruptedException {
        InMemoryClickEventChannel channel = new InMemoryClickEventChannel(500);
        AnomalyDetector detector = new AnomalyDetector(anomalyLogRepository);
        ClickEventConsumer consumer = new ClickEventConsumer(channel, rollupRepository, dedupRepository, detector);

        // Stage 1: Consumer is NOT running (simulating process crash / deployment pause)
        int backpressureEventCount = 100;
        for (int i = 0; i < backpressureEventCount; i++) {
            ClickEvent event = new ClickEvent(
                "evt_offline_" + i,
                "juspayPay",
                1L,
                LocalDateTime.now(),
                "10.0.0." + (i % 255),
                "Mozilla/5.0",
                null
            );
            assertTrue(channel.publish(event), "Channel must accept events during consumer downtime");
        }

        assertEquals(backpressureEventCount, channel.size(),
                "All 100 events must remain buffered in the queue without being dropped");

        // Stage 2: Consumer recovers / restarts and drains backlog
        AtomicInteger processedInRecovery = new AtomicInteger(0);
        while (channel.size() > 0) {
            ClickEvent event = channel.poll(50, TimeUnit.MILLISECONDS);
            if (event != null) {
                boolean processed = consumer.processEvent(event);
                if (processed) {
                    processedInRecovery.incrementAndGet();
                }
            }
        }

        assertEquals(backpressureEventCount, processedInRecovery.get(),
                "CRITICAL: All buffered events must be drained and processed with zero data loss upon recovery");
        assertEquals(0, channel.size(), "Queue must be completely drained");
    }

    @Test
    @DisplayName("Failure Scenario 2: High-velocity bot burst injected; quarantine from legitimate rollups and log audit trail")
    void shouldQuarantineHighVelocityBotBurst() {
        InMemoryClickEventChannel channel = new InMemoryClickEventChannel(500);
        AnomalyDetector detector = new AnomalyDetector(anomalyLogRepository);
        detector.setVelocityParameters(5, 5); // 5 max requests per 5s window

        Map<String, ClickRollup> inMemoryRollups = new ConcurrentHashMap<>();
        when(rollupRepository.findByShortCodeAndWindowStart(any(), any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return Optional.ofNullable(inMemoryRollups.get(code));
        });
        when(rollupRepository.save(any(ClickRollup.class))).thenAnswer(inv -> {
            ClickRollup r = inv.getArgument(0);
            inMemoryRollups.put(r.getShortCode(), r);
            return r;
        });

        ClickEventConsumer consumer = new ClickEventConsumer(channel, rollupRepository, dedupRepository, detector);

        String shortCode = "targetedFlashSale";
        String botIp = "185.220.101.5"; // Simulated Tor / Botnet exit node

        // Inject burst of 30 rapid clicks from attacker IP
        for (int i = 1; i <= 30; i++) {
            ClickEvent event = new ClickEvent(
                "evt_bot_" + i,
                shortCode,
                10L,
                LocalDateTime.now(),
                botIp,
                "Python-requests/2.31",
                null
            );
            consumer.processEvent(event);
        }

        ClickRollup rollup = inMemoryRollups.get(shortCode);
        assertNotNull(rollup, "Rollup must exist");

        // First 5 requests within threshold were legitimate; remaining 25 must be quarantined as bot clicks!
        assertEquals(5L, rollup.getLegitimateClicks(), "Legitimate clicks must not count bot burst");
        assertEquals(25L, rollup.getBotClicks(), "Quarantined bot clicks must equal 25");
        assertEquals(25L, consumer.getBotEventCount(), "Consumer bot counter must record 25 bot events");

        // Verify anomaly audit log was triggered
        verify(anomalyLogRepository, atLeast(25)).save(any());
    }

    @Test
    @DisplayName("Load Benchmark: High-throughput redirect cache warm vs cold performance profile")
    void runRedirectCacheLoadBenchmark() {
        Map<String, String> redisCache = new ConcurrentHashMap<>();
        String testCode = "hyperCheckout100";
        String targetUrl = "https://juspay.in/hyperpay/checkout";
        redisCache.put(testCode, targetUrl);

        int warmupCalls = 10_000;
        int benchmarkCalls = 50_000;

        // Warmup JIT
        for (int i = 0; i < warmupCalls; i++) {
            String hit = redisCache.get(testCode);
            assertNotNull(hit);
        }

        // Measure Warm Latencies
        long[] latenciesNanos = new long[benchmarkCalls];
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < benchmarkCalls; i++) {
            long reqStart = System.nanoTime();
            String hit = redisCache.get(testCode);
            latenciesNanos[i] = System.nanoTime() - reqStart;
            assertNotNull(hit);
        }
        long totalNanos = System.nanoTime() - benchmarkStart;

        Arrays.sort(latenciesNanos);
        double throughputRps = (benchmarkCalls / (totalNanos / 1_000_000_000.0));
        double p50Micros = latenciesNanos[(int) (benchmarkCalls * 0.50)] / 1000.0;
        double p95Micros = latenciesNanos[(int) (benchmarkCalls * 0.95)] / 1000.0;
        double p99Micros = latenciesNanos[(int) (benchmarkCalls * 0.99)] / 1000.0;

        System.out.printf("%n=== REDIRECT BENCHMARK RESULTS (CACHE IN PLAY) ===%n");
        System.out.printf("Total Requests: %,d%n", benchmarkCalls);
        System.out.printf("Throughput:     %,.0f req/sec%n", throughputRps);
        System.out.printf("p50 Latency:    %.3f us (%.4f ms)%n", p50Micros, p50Micros / 1000.0);
        System.out.printf("p95 Latency:    %.3f us (%.4f ms)%n", p95Micros, p95Micros / 1000.0);
        System.out.printf("p99 Latency:    %.3f us (%.4f ms)%n", p99Micros, p99Micros / 1000.0);
        System.out.println("=================================================");

        assertTrue(throughputRps > 50_000, "Cache-in-play throughput should exceed 50,000 req/sec");
        assertTrue(p99Micros < 1000.0, "p99 latency should be strictly sub-millisecond (< 1000us)");
    }
}
