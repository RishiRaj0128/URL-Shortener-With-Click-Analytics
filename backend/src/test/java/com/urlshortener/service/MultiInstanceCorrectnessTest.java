package com.urlshortener.service;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.repository.ClickAnomalyLogRepository;
import com.urlshortener.util.IpHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 2: Multi-Instance Correctness Tests (Rate Limiting, Split Anomaly, HMAC IP Privacy)")
class MultiInstanceCorrectnessTest {

    @Mock
    private ClickAnomalyLogRepository anomalyLogRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    // Simulated Redis storage shared across multiple instances
    private final Map<String, Double> redisTokenBuckets = new ConcurrentHashMap<>();
    private final Map<String, Long> redisTokenTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> redisZSets = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        redisTokenBuckets.clear();
        redisTokenTimestamps.clear();
        redisZSets.clear();
    }

    @Test
    @DisplayName("Task 2(a): Distributed Rate Limiting - Limit holds across multiple service instances sharing Redis")
    void shouldEnforceRateLimitAcrossMultipleServiceInstancesSharingRedis() {
        int limitRpm = 10;
        String clientIp = "203.0.113.42";

        // Mock Redis script execution to simulate atomic Redis token bucket
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                List<String> keys = invocation.getArgument(1);
                String key = keys.get(0);
                double capacity = Double.parseDouble(invocation.getArgument(2).toString());
                double refillRate = Double.parseDouble(invocation.getArgument(3).toString());
                long now = Long.parseLong(invocation.getArgument(4).toString());
                double requested = Double.parseDouble(invocation.getArgument(5).toString());

                double tokens = redisTokenBuckets.getOrDefault(key, capacity);
                long lastUpdated = redisTokenTimestamps.getOrDefault(key, now);

                double elapsedSec = Math.max(0, (now - lastUpdated) / 1000.0);
                tokens = Math.min(capacity, tokens + elapsedSec * refillRate);

                if (tokens >= requested) {
                    tokens -= requested;
                    redisTokenBuckets.put(key, tokens);
                    redisTokenTimestamps.put(key, now);
                    return 1L;
                } else {
                    redisTokenBuckets.put(key, tokens);
                    redisTokenTimestamps.put(key, now);
                    return 0L;
                }
            });

        // Simulate Instance 1 (Node 1)
        RateLimitingService instance1 = new RateLimitingService(redisTemplate);
        ReflectionTestUtils.setField(instance1, "rateLimitingEnabled", true);
        ReflectionTestUtils.setField(instance1, "requestsPerMinute", limitRpm);

        // Simulate Instance 2 (Node 2)
        RateLimitingService instance2 = new RateLimitingService(redisTemplate);
        ReflectionTestUtils.setField(instance2, "rateLimitingEnabled", true);
        ReflectionTestUtils.setField(instance2, "requestsPerMinute", limitRpm);

        // Send 6 requests to Instance 1
        for (int i = 0; i < 6; i++) {
            assertTrue(instance1.allowRequest(clientIp, null), "Instance 1 should allow request " + (i + 1));
        }

        // Send 4 requests to Instance 2 (total 10 reaches capacity)
        for (int i = 0; i < 4; i++) {
            assertTrue(instance2.allowRequest(clientIp, null), "Instance 2 should allow request " + (i + 7));
        }

        // 11th request across the cluster MUST be rejected by BOTH instances
        assertFalse(instance1.allowRequest(clientIp, null),
                "Instance 1 must reject request once cluster-wide limit of 10 is reached");
        assertFalse(instance2.allowRequest(clientIp, null),
                "Instance 2 must reject request once cluster-wide limit of 10 is reached");
    }

    @Test
    @DisplayName("Task 2(b): Split-Burst Anomaly - Burst split across two nodes is detected via shared Redis state")
    void shouldDetectSplitBurstVelocityAttackAcrossMultipleInstances() {
        int maxAllowedInWindow = 10;
        String clientIp = "198.51.100.99";
        String shortCode = "splitBurstLink";

        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        // Mock Redis sorted set operations to simulate sliding window across cluster
        when(zSetOperations.removeRangeByScore(anyString(), anyDouble(), anyDouble()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(0);
                double maxScore = inv.getArgument(2);
                Map<String, Double> set = redisZSets.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                int removed = 0;
                Iterator<Map.Entry<String, Double>> it = set.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue() <= maxScore) {
                        it.remove();
                        removed++;
                    }
                }
                return (long) removed;
            });

        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = inv.getArgument(1);
                double score = inv.getArgument(2);
                redisZSets.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(val, score);
                return true;
            });

        when(zSetOperations.size(anyString()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(0);
                return (long) redisZSets.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).size();
            });

        // Node A Anomaly Detector
        AnomalyDetector nodeA = new AnomalyDetector(anomalyLogRepository, null, null, redisTemplate);
        ReflectionTestUtils.setField(nodeA, "velocityWindowSeconds", 10);
        ReflectionTestUtils.setField(nodeA, "velocityMaxRequests", maxAllowedInWindow);

        // Node B Anomaly Detector
        AnomalyDetector nodeB = new AnomalyDetector(anomalyLogRepository, null, null, redisTemplate);
        ReflectionTestUtils.setField(nodeB, "velocityWindowSeconds", 10);
        ReflectionTestUtils.setField(nodeB, "velocityMaxRequests", maxAllowedInWindow);

        // Node A receives 6 requests (below threshold 10)
        for (int i = 0; i < 6; i++) {
            ClickEvent evt = new ClickEvent("evt_a_" + i, shortCode, 1L, LocalDateTime.now(), clientIp, "UA", "ref");
            AnomalyDetector.AnomalyResult res = nodeA.evaluateClickVelocity(evt);
            assertFalse(res.isAnomaly(), "Node A request " + (i + 1) + " should not be flagged yet");
        }

        // Node B receives 4 requests (total 10, exactly at threshold)
        for (int i = 0; i < 4; i++) {
            ClickEvent evt = new ClickEvent("evt_b_" + i, shortCode, 1L, LocalDateTime.now(), clientIp, "UA", "ref");
            AnomalyDetector.AnomalyResult res = nodeB.evaluateClickVelocity(evt);
            assertFalse(res.isAnomaly(), "Node B request " + (i + 1) + " should not be flagged yet");
        }

        // 11th request arriving at Node B breaches cluster velocity (11 > 10)
        ClickEvent breachEvt = new ClickEvent("evt_b_breach", shortCode, 1L, LocalDateTime.now(), clientIp, "UA", "ref");
        AnomalyDetector.AnomalyResult breachResult = nodeB.evaluateClickVelocity(breachEvt);

        assertTrue(breachResult.isAnomaly(), "Split burst MUST be detected when total across nodes exceeds threshold");
        assertEquals("BOT_BURST_VELOCITY", breachResult.anomalyType());
    }

    @Test
    @DisplayName("Task 2(d): IP Privacy - HMAC-SHA256 replaces unsalted SHA-256 with rotatable salt")
    void shouldComputeHmacSha256AndSupportSaltRotation() {
        String ip = "192.0.2.146";
        String secret = "test-secret-key-for-hmac-sha256-verification-256bits";
        String saltV1 = "salt-v1-2026-q1";
        String saltV2 = "salt-v2-2026-q2";

        // 1. Same IP, same secret, same salt -> strictly deterministic HMAC
        String hashV1a = IpHashUtil.hashIp(ip, secret, saltV1);
        String hashV1b = IpHashUtil.hashIp(ip, secret, saltV1);
        assertNotNull(hashV1a);
        assertEquals(64, hashV1a.length(), "HMAC-SHA256 must produce 64 hex characters");
        assertEquals(hashV1a, hashV1b, "HMAC must be deterministic for identical IP and secret/salt");

        // 2. Different IPs with same secret/salt -> distinct hashes
        String hashDifferentIp = IpHashUtil.hashIp("192.0.2.147", secret, saltV1);
        assertNotEquals(hashV1a, hashDifferentIp, "Different IPs must produce distinct hashes");

        // 3. Salt rotation -> same IP yields completely different hash after salt rotation
        String hashV2 = IpHashUtil.hashIp(ip, secret, saltV2);
        assertNotEquals(hashV1a, hashV2, "Rotating salt must change the computed IP hash (prevents rainbow table reversal)");

        // 4. Verify Component level active secret and salt configuration
        IpHashUtil ipHashUtil = new IpHashUtil(secret, saltV1);
        assertEquals(hashV1a, IpHashUtil.hashIp(ip), "Static hashIp() must use active secret and salt");
    }
}
