package com.urlshortener.resilience;

import com.urlshortener.config.CircuitBreakerConfiguration;
import com.urlshortener.entity.Url;
import com.urlshortener.repository.UrlRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proactive Cache Warming Listener.
 * 
 * Solves the "Cache Stampede on Recovery" Problem:
 * When the Redis circuit breaker transitions from HALF_OPEN to CLOSED (Redis recovered),
 * the cache is completely cold. If thousands of requests immediately hit, every single
 * request misses cache and slams PostgreSQL simultaneously (thundering herd).
 * 
 * This listener proactively warms the top hot URLs into Redis before incoming user
 * traffic arrives.
 */
@Component
public class CacheWarmingListener {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmingListener.class);
    private static final Duration WARM_CACHE_TTL = Duration.ofHours(1);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final UrlRepository urlRepository;
    private final StringRedisTemplate redisTemplate;

    private final AtomicLong warmedUrlCount = new AtomicLong(0);
    private final AtomicInteger warmingRunsCount = new AtomicInteger(0);
    private volatile LocalDateTime lastWarmedAt;

    @Autowired
    public CacheWarmingListener(
            CircuitBreakerRegistry circuitBreakerRegistry,
            UrlRepository urlRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.urlRepository = urlRepository;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void registerCircuitBreakerListener() {
        if (circuitBreakerRegistry == null) return;

        CircuitBreaker redisBreaker = circuitBreakerRegistry.circuitBreaker(CircuitBreakerConfiguration.CB_REDIS_CACHE);
        if (redisBreaker != null) {
            redisBreaker.getEventPublisher().onStateTransition(event -> {
                if (event.getStateTransition() == CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED) {
                    log.info("[CACHE WARMER] Detected Redis circuit breaker recovery (HALF_OPEN -> CLOSED). Initiating proactive warming...");
                    warmCache(50);
                }
            });
            log.info("[CACHE WARMER] Registered proactive warming listener on circuit breaker: {}", CircuitBreakerConfiguration.CB_REDIS_CACHE);
        }
    }

    /**
     * Proactively warms the top URLs into Redis.
     * Can be invoked automatically on recovery or manually via operator endpoint.
     */
    public int warmCache(int limit) {
        try {
            List<Url> topUrls = urlRepository.findTop50ByOrderByCreatedAtDesc();
            if (topUrls == null || topUrls.isEmpty()) {
                log.info("[CACHE WARMER] No URLs found in database to warm.");
                return 0;
            }

            int count = 0;
            for (Url url : topUrls) {
                if (count >= limit) break;
                if (redisTemplate != null) {
                    try {
                        String cacheKey = "urls:" + url.getShortCode();
                        redisTemplate.opsForValue().set(cacheKey, url.getOriginalUrl(), WARM_CACHE_TTL);
                    } catch (Exception e) {
                        log.warn("[CACHE WARMER] Failed to write key for shortCode {}: {}", url.getShortCode(), e.getMessage());
                    }
                }
                count++;
                warmedUrlCount.incrementAndGet();
            }

            this.lastWarmedAt = LocalDateTime.now();
            this.warmingRunsCount.incrementAndGet();

            log.info("[CACHE WARMER] Proactive warming complete: pre-warmed {} hot links into Redis.", count);
            return count;
        } catch (Exception e) {
            log.error("[CACHE WARMER] Error during proactive cache warming: {}", e.getMessage(), e);
            return 0;
        }
    }

    public long getWarmedUrlCount() {
        return warmedUrlCount.get();
    }

    public int getWarmingRunsCount() {
        return warmingRunsCount.get();
    }

    public LocalDateTime getLastWarmedAt() {
        return lastWarmedAt;
    }

    public record CacheWarmerStatus(
        long totalWarmedUrls,
        int totalWarmingRuns,
        LocalDateTime lastWarmedAt,
        String targetCircuitBreaker
    ) {}

    public CacheWarmerStatus getStatus() {
        return new CacheWarmerStatus(
            warmedUrlCount.get(),
            warmingRunsCount.get(),
            lastWarmedAt,
            CircuitBreakerConfiguration.CB_REDIS_CACHE
        );
    }
}
