package com.urlshortener.resilience;

import com.urlshortener.config.CircuitBreakerConfiguration;
import com.urlshortener.entity.Url;
import com.urlshortener.repository.UrlRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Track 3.2: Proactive Cache Warming on Self-Healing Recovery Tests")
class CacheWarmingListenerTest {

    private UrlRepository urlRepository;
    private CircuitBreakerRegistry registry;
    private CacheWarmingListener warmingListener;
    private CircuitBreaker redisBreaker;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        CircuitBreakerConfiguration config = new CircuitBreakerConfiguration();
        registry = config.circuitBreakerRegistry();
        redisBreaker = registry.circuitBreaker(CircuitBreakerConfiguration.CB_REDIS_CACHE);

        warmingListener = new CacheWarmingListener(registry, urlRepository, null);
        warmingListener.registerCircuitBreakerListener();
    }

    @Test
    @DisplayName("Manual cache warming fetches top URLs and pre-populates cache")
    void shouldWarmTopUrls() {
        Url u1 = new Url();
        u1.setShortCode("promo1");
        u1.setOriginalUrl("https://juspay.in/promo1");

        Url u2 = new Url();
        u2.setShortCode("promo2");
        u2.setOriginalUrl("https://juspay.in/promo2");

        when(urlRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(u1, u2));

        int warmed = warmingListener.warmCache(50);

        assertEquals(2, warmed);
        assertEquals(2, warmingListener.getWarmedUrlCount());
        assertEquals(1, warmingListener.getWarmingRunsCount());
        assertNotNull(warmingListener.getLastWarmedAt());
    }

    @Test
    @DisplayName("Circuit breaker transition from HALF_OPEN to CLOSED triggers proactive warming")
    void shouldTriggerWarmingOnCircuitBreakerHealing() {
        Url u1 = new Url();
        u1.setShortCode("healedPromo");
        u1.setOriginalUrl("https://juspay.in/healed");

        when(urlRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(u1));

        // Simulate state transition: CLOSED -> OPEN -> HALF_OPEN -> CLOSED
        redisBreaker.transitionToOpenState();
        redisBreaker.transitionToHalfOpenState();
        redisBreaker.transitionToClosedState();

        assertEquals(1, warmingListener.getWarmingRunsCount(),
            "Healing transition (HALF_OPEN -> CLOSED) must automatically trigger cache warming");
        assertEquals(1, warmingListener.getWarmedUrlCount());
    }
}
