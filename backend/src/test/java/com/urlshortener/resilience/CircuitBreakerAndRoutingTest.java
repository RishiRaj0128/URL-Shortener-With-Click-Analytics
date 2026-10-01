package com.urlshortener.resilience;

import com.urlshortener.routing.TrafficRouter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 5: Self-Healing Circuit Breaker & Intelligent Routing Tests")
class CircuitBreakerAndRoutingTest {

    @Test
    @DisplayName("Self-Healing Circuit Breaker: Trips to OPEN on failure rate and executes DB fallback fail-fast")
    void shouldOpenCircuitBreakerOnCacheFailuresAndFallbackToDb() {
        // Configure small sliding window of 4 calls with 50% failure rate threshold
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .waitDurationInOpenState(Duration.ofMillis(500))
            .permittedNumberOfCallsInHalfOpenState(2)
            .build();

        CircuitBreaker redisBreaker = CircuitBreaker.of("testRedisBreaker", config);

        AtomicInteger dbFallbackHits = new AtomicInteger(0);
        AtomicInteger redisAttemptHits = new AtomicInteger(0);

        Supplier<String> protectedLookup = () -> {
            Supplier<String> decorated = CircuitBreaker.decorateSupplier(redisBreaker, () -> {
                redisAttemptHits.incrementAndGet();
                throw new RuntimeException("Redis connection timed out (simulated socket timeout)");
            });

            try {
                return decorated.get();
            } catch (Exception ex) {
                // Fallback directly to DB
                dbFallbackHits.incrementAndGet();
                return "https://juspay.in/fallback-from-db";
            }
        };

        assertEquals(CircuitBreaker.State.CLOSED, redisBreaker.getState());

        // Execute 4 failed calls to fill the sliding window
        for (int i = 0; i < 4; i++) {
            String result = protectedLookup.get();
            assertEquals("https://juspay.in/fallback-from-db", result);
        }

        // Breaker should now be tripped to OPEN
        assertEquals(CircuitBreaker.State.OPEN, redisBreaker.getState(),
                "Circuit breaker must trip to OPEN after 100% failure rate");

        // Record how many times Redis was actually touched so far
        int redisCallsBeforeOpen = redisAttemptHits.get();
        assertEquals(4, redisCallsBeforeOpen);

        // Next call in OPEN state should FAIL-FAST without attempting Redis!
        String failFastResult = protectedLookup.get();
        assertEquals("https://juspay.in/fallback-from-db", failFastResult);
        assertEquals(redisCallsBeforeOpen, redisAttemptHits.get(),
                "In OPEN state, CircuitBreaker must short-circuit and avoid calling downstream Redis entirely");
        assertEquals(5, dbFallbackHits.get());
    }

    @Test
    @DisplayName("Intelligent Traffic Routing: Automatically sheds traffic away from unhealthy node to healthy replica")
    void shouldRerouteTrafficAwayFromDyingNode() {
        TrafficRouter router = new TrafficRouter(false);
        router.registerNode("node-alpha", "http://instance-alpha:8080");
        router.registerNode("node-beta", "http://instance-beta:8080");

        // Initially both nodes are healthy
        String initialRoute = router.routeRequest("/abc123");
        assertTrue(initialRoute.startsWith("http://instance-alpha") || initialRoute.startsWith("http://instance-beta"));

        // Simulate node-alpha experiencing a failure (e.g. process crash / out of memory)
        router.recordHealthProbe("node-alpha", false);
        // Second consecutive failure: marks node-alpha UNHEALTHY
        router.recordHealthProbe("node-alpha", false);

        // All subsequent traffic MUST route exclusively to node-beta
        for (int i = 0; i < 20; i++) {
            String target = router.routeRequest("/paymentShortCode" + i);
            assertTrue(target.startsWith("http://instance-beta:8080"),
                    "Traffic must be 100% routed away from unhealthy node-alpha, was: " + target);
        }

        // Simulate node-alpha recovery (passes health probe)
        router.recordHealthProbe("node-alpha", true);

        // Traffic should now resume balanced distribution between both nodes
        boolean reachedAlpha = false;
        for (int i = 0; i < 20; i++) {
            String target = router.routeRequest("/test" + i);
            if (target.startsWith("http://instance-alpha:8080")) {
                reachedAlpha = true;
                break;
            }
        }
        assertTrue(reachedAlpha, "Node-alpha should rejoin active routing pool after recovery");
    }

    @Test
    @DisplayName("Routing Resilience: Throws IllegalStateException when all nodes are unhealthy")
    void shouldThrowWhenAllNodesAreUnhealthy() {
        TrafficRouter router = new TrafficRouter();
        router.setNodeHealthManually("node-primary", false);
        router.setNodeHealthManually("node-replica", false);

        assertThrows(IllegalStateException.class, () -> router.routeRequest("/checkout"));
    }
}
