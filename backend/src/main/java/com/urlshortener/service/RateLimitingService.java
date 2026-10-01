package com.urlshortener.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting service using Bucket4j Token Bucket Algorithm.
 * 
 * Token Bucket Architecture:
 * - Refill Strategy: Continuous greedy refill (Refill.greedy).
 *   Tokens are added continuously at a steady rate throughout the window,
 *   rather than in instantaneous bursts at fixed interval boundaries.
 *   This explicitly eliminates the "burst-doubling" vulnerability of fixed-window
 *   counters where a client can send 2x capacity across a window transition edge.
 * - Multi-tier Keying: Discriminated per API key (if present) or client IP.
 * - Storage: In-memory ConcurrentHashMap (single-node) with zero lock contention.
 */
@Service
public class RateLimitingService {
    
    @Value("${app.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;
    
    @Value("${app.rate-limiting.requests-per-minute:60}")
    private int requestsPerMinute;
    
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    /**
     * Check if a request from this client IP or API key is allowed.
     */
    public boolean allowRequest(String identifier) {
        if (!rateLimitingEnabled || identifier == null || identifier.isBlank()) {
            return true;
        }
        
        Bucket bucket = buckets.computeIfAbsent(identifier, this::createBucket);
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
    
    /**
     * Create a new token bucket with continuous greedy refill.
     * Tokens are added continuously at 1 token every (60/requestsPerMinute) seconds.
     */
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
        Bucket bucket = buckets.get(identifier);
        return bucket != null ? (int) bucket.getAvailableTokens() : requestsPerMinute;
    }

    public void reset() {
        buckets.clear();
    }
}

