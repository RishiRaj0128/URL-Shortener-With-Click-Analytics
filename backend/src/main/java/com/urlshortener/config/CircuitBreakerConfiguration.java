package com.urlshortener.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resilience4j Circuit Breaker Configuration for Downstream Dependencies.
 * 
 * Self-Healing System Strategy:
 * - redisCache: Guards against Redis connection hangs/latency spikes.
 *   If Redis latency or error rate exceeds 50% across 10 calls, circuit opens
 *   and fails fast to PostgreSQL fallback without stalling HTTP threads.
 * - geolocation: Guards against external HTTP 3rd-party latency.
 * - Health Observability: States (CLOSED, OPEN, HALF_OPEN) are exposed via REST API
 *   for live dashboard monitoring.
 */
@Configuration
public class CircuitBreakerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerConfiguration.class);

    public static final String CB_REDIS_CACHE = "redisCache";
    public static final String CB_GEOLOCATION = "geolocation";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofMillis(200))
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowSize(10)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);

        // Pre-register named breakers
        CircuitBreaker redisBreaker = registry.circuitBreaker(CB_REDIS_CACHE);
        redisBreaker.getEventPublisher()
            .onStateTransition(event -> log.warn("[CIRCUIT BREAKER] {} state transitioned: {}",
                CB_REDIS_CACHE, event.getStateTransition()));

        CircuitBreaker geoBreaker = registry.circuitBreaker(CB_GEOLOCATION);
        geoBreaker.getEventPublisher()
            .onStateTransition(event -> log.warn("[CIRCUIT BREAKER] {} state transitioned: {}",
                CB_GEOLOCATION, event.getStateTransition()));

        return registry;
    }

    @Bean(name = "redisCircuitBreaker")
    public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(CB_REDIS_CACHE);
    }

    @Bean(name = "geoCircuitBreaker")
    public CircuitBreaker geoCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(CB_GEOLOCATION);
    }
}
