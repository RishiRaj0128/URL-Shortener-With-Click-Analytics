package com.urlshortener.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Instance Rate Limiting Service.
 * 
 * Token Bucket Architecture:
 * - Distributed Storage: Uses Redis atomic Token Bucket script via StringRedisTemplate across all cluster nodes.
 *   Guarantees that a client IP or API key has the exact same rate limit (e.g., 60 RPM) enforced across
 *   all app instances, preventing the "3x limit" multi-node vulnerability.
 * - Refill Strategy: Continuous greedy refill (Refill.greedy).
 *   Tokens refill steadily throughout the window, eliminating the burst-doubling vulnerability of fixed windows.
 * - Resilience Fallback: If Redis is unavailable or unconfigured, falls back seamlessly to an in-memory
 *   Bucket4j ConcurrentHashMap token bucket with zero downtime.
 */
@Service
public class RateLimitingService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingService.class);

    private static final String LUA_TOKEN_BUCKET_SCRIPT =
        "local key = KEYS[1]\n" +
        "local capacity = tonumber(ARGV[1])\n" +
        "local refill_rate = tonumber(ARGV[2])\n" +
        "local now = tonumber(ARGV[3])\n" +
        "local requested = tonumber(ARGV[4])\n" +
        "\n" +
        "local data = redis.call('HMGET', key, 'tokens', 'last_updated')\n" +
        "local tokens = tonumber(data[1])\n" +
        "local last_updated = tonumber(data[2])\n" +
        "\n" +
        "if not tokens then\n" +
        "    tokens = capacity\n" +
        "    last_updated = now\n" +
        "else\n" +
        "    local elapsed = math.max(0, (now - last_updated) / 1000.0)\n" +
        "    tokens = math.min(capacity, tokens + elapsed * refill_rate)\n" +
        "    last_updated = now\n" +
        "end\n" +
        "\n" +
        "if tokens >= requested then\n" +
        "    tokens = tokens - requested\n" +
        "    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_updated', tostring(last_updated))\n" +
        "    redis.call('EXPIRE', key, 120)\n" +
        "    return 1\n" +
        "else\n" +
        "    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_updated', tostring(last_updated))\n" +
        "    redis.call('EXPIRE', key, 120)\n" +
        "    return 0\n" +
        "end\n";

    private final RedisScript<Long> tokenBucketScript = new DefaultRedisScript<>(LUA_TOKEN_BUCKET_SCRIPT, Long.class);

    @Value("${app.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled = true;

    @Value("${app.rate-limiting.requests-per-minute:60}")
    private int requestsPerMinute = 60;

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Bucket> localBuckets = new ConcurrentHashMap<>();

    public RateLimitingService() {
        this(null);
    }

    @Autowired
    public RateLimitingService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check if a request from this identifier (IP or key) is allowed.
     */
    public boolean allowRequest(String identifier) {
        if (!rateLimitingEnabled || identifier == null || identifier.isBlank()) {
            return true;
        }

        // 1. Attempt distributed Redis Token Bucket first
        if (redisTemplate != null) {
            try {
                String redisKey = "ratelimit:" + identifier;
                double refillRatePerSec = (double) requestsPerMinute / 60.0;
                long now = System.currentTimeMillis();

                Long result = redisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(requestsPerMinute),
                    String.valueOf(refillRatePerSec),
                    String.valueOf(now),
                    "1"
                );

                if (result != null) {
                    return result == 1L;
                }
            } catch (Exception e) {
                log.warn("Redis distributed rate limiter error, falling back to local Bucket4j: {}", e.getMessage());
            }
        }

        // 2. Fallback to in-memory Bucket4j
        Bucket bucket = localBuckets.computeIfAbsent(identifier, this::createBucket);
        return bucket.tryConsume(1);
    }

    /**
     * Check if request is allowed, prioritizing API key over IP.
     */
    public boolean allowRequest(String clientIp, String apiKey) {
        if (!rateLimitingEnabled) {
            return true;
        }
        String key = (apiKey != null && !apiKey.isBlank()) ? "key:" + apiKey : "ip:" + clientIp;
        return allowRequest(key);
    }

    private Bucket createBucket(String key) {
        Bandwidth limit = Bandwidth.classic(
            requestsPerMinute,
            Refill.greedy(requestsPerMinute, Duration.ofMinutes(1))
        );
        return Bucket4j.builder()
            .addLimit(limit)
            .build();
    }

    public int getAvailableTokens(String identifier) {
        Bucket bucket = localBuckets.get(identifier);
        return bucket != null ? (int) bucket.getAvailableTokens() : requestsPerMinute;
    }

    public void reset() {
        localBuckets.clear();
    }
}
