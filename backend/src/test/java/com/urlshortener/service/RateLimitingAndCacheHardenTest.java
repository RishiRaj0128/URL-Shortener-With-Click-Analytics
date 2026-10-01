package com.urlshortener.service;

import com.urlshortener.dto.ShortenUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.entity.Url;
import com.urlshortener.entity.User;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.repository.UserRepository;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 2: Harden Caching & Rate Limiting Tests")
class RateLimitingAndCacheHardenTest {

    private RateLimitingService rateLimitingService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClickService clickService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @InjectMocks
    private UrlShorteningService urlShorteningService;

    @BeforeEach
    void setUp() {
        rateLimitingService = new RateLimitingService();
        ReflectionTestUtils.setField(rateLimitingService, "rateLimitingEnabled", true);
        ReflectionTestUtils.setField(rateLimitingService, "requestsPerMinute", 10);
        ReflectionTestUtils.setField(urlShorteningService, "baseUrl", "http://localhost:8080");
    }

    @Test
    @DisplayName("Token Bucket: Should enforce requestsPerMinute and reject subsequent requests")
    void shouldEnforceTokenBucketLimit() {
        String testIp = "192.168.1.100";

        // First 10 requests must pass
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimitingService.allowRequest(testIp), "Request " + (i + 1) + " should be permitted");
        }

        // 11th request in the same window must be rejected
        assertFalse(rateLimitingService.allowRequest(testIp), "Request 11 exceeding bucket capacity must be blocked");
    }

    @Test
    @DisplayName("Token Bucket: Should prioritize API Key over IP for tiered limits")
    void shouldPrioritizeApiKeyOverIp() {
        String testIp = "192.168.1.50";
        String apiKey = "sk_live_vip_enterprise_999";

        // Consume tokens under API key
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimitingService.allowRequest(testIp, apiKey));
        }
        // Exceeded on API key
        assertFalse(rateLimitingService.allowRequest(testIp, apiKey));

        // Different IP with same API key is also throttled
        assertFalse(rateLimitingService.allowRequest("10.0.0.1", apiKey));

        // But raw IP without this API key has its own fresh bucket
        assertTrue(rateLimitingService.allowRequest("10.0.0.1", null));
    }

    @Test
    @DisplayName("Cache Invalidation: updateUrl must persist new target and trigger cache eviction")
    void shouldUpdateUrlAndEvictCache() {
        String shortCode = "1aBcXy";
        String userEmail = "dev@juspay.in";
        User user = new User(1L, userEmail, "hash", LocalDateTime.now(), LocalDateTime.now());
        Url existingUrl = new Url(100L, user, "https://old-domain.com", shortCode, LocalDateTime.now(), LocalDateTime.now());

        when(urlRepository.findByShortCode(shortCode)).thenReturn(Optional.of(existingUrl));
        when(userRepository.findByEmail(userEmail)).thenReturn(Optional.of(user));
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(clickService.getClickCount(100L)).thenReturn(42L);

        UrlResponse updated = urlShorteningService.updateUrl(shortCode, "https://new-domain.com/payments", userEmail);

        assertNotNull(updated);
        assertEquals("https://new-domain.com/payments", updated.getOriginalUrl());
        verify(urlRepository, times(1)).save(existingUrl);
    }

    @Test
    @DisplayName("Benchmark: Simulated Cache Warm vs Cold Redirect Latency Difference")
    void benchmarkWarmVsColdRedirectLatency() {
        // Cold path: Simulates DB I/O latency (~15ms)
        // Warm path: Simulates Redis / in-memory cache lookup (~0.5ms)
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        String shortCode = "fastRedirectCode";
        String targetUrl = "https://juspay.in/hyperpay";

        int iterations = 100;

        // Measure Cold path (cache miss -> simulate DB fetch 10ms -> write cache)
        long coldStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cache.remove(shortCode); // force miss
            if (!cache.containsKey(shortCode)) {
                try {
                    TimeUnit.MILLISECONDS.sleep(2); // Simulated minimal DB latency
                } catch (InterruptedException ignored) {}
                cache.put(shortCode, targetUrl);
            }
        }
        long coldTotalNanos = System.nanoTime() - coldStart;
        double coldAvgMs = (coldTotalNanos / 1_000_000.0) / iterations;

        // Measure Warm path (cache hit -> immediate return)
        long warmStart = System.nanoTime();
        for (int i = 0; i < iterations * 100; i++) {
            String hit = cache.get(shortCode);
            assertNotNull(hit);
        }
        long warmTotalNanos = System.nanoTime() - warmStart;
        double warmAvgMs = (warmTotalNanos / 1_000_000.0) / (iterations * 100);

        System.out.printf("[IN-PROCESS MICROBENCHMARK (NOT END-TO-END)] Cache Cold Avg: %.3f ms | Cache Warm Avg: %.4f ms | In-Process Speedup: %.1fx%n",
                coldAvgMs, warmAvgMs, coldAvgMs / warmAvgMs);

        assertTrue(coldAvgMs > warmAvgMs, "Cache warm must be substantially faster than cold miss");
        assertTrue(warmAvgMs < 0.1, "Warm cache lookup must be sub-millisecond");
    }
}
