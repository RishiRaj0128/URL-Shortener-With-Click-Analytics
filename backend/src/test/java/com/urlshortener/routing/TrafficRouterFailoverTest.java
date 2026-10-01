package com.urlshortener.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 1.2: Gradual Failover & Slow-Start Warm-up Tests")
class TrafficRouterFailoverTest {

    private TrafficRouter router;

    @BeforeEach
    void setUp() {
        router = new TrafficRouter(false);
        router.registerNode("node-alpha", "http://alpha:8080");
        router.registerNode("node-beta", "http://beta:8080");
    }

    @Test
    @DisplayName("Gradual Failover: 1 failure decays weight to 50%, 2 failures quarantines to 0%")
    void shouldGraduallyDecayTrafficOnDegradation() {
        // Initial state: fully healthy, 100% weight modifier
        assertEquals(1.00, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Probe 1 fails: partial degradation -> decay weight to 50%
        router.recordHealthProbe("node-alpha", false);
        assertEquals(0.50, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Probe 2 fails: consecutive failures >= 2 -> node marked unhealthy, 0% weight
        router.recordHealthProbe("node-alpha", false);
        assertEquals(0.00, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        List<TrafficRouter.NodeInstance> status = router.getClusterStatus();
        TrafficRouter.NodeInstance alpha = status.stream().filter(n -> n.nodeId().equals("node-alpha")).findFirst().orElseThrow();
        assertFalse(alpha.isHealthy());
    }

    @Test
    @DisplayName("Slow-Start Ramp: Recovered node enters warm-up stages (10% -> 25% -> 50% -> 100%)")
    void shouldRampUpRecoveredNodeProgressively() {
        // Force node-alpha into unhealthy state
        router.recordHealthProbe("node-alpha", false);
        router.recordHealthProbe("node-alpha", false);
        assertEquals(0.00, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Node passes probe 1: enters warmup step 1 (10% traffic cap)
        router.recordHealthProbe("node-alpha", true);
        assertEquals(1, router.getWarmupStep("node-alpha"));
        assertEquals(0.10, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Probe 2 passes: advances to warmup step 2 (25% traffic cap)
        router.recordHealthProbe("node-alpha", true);
        assertEquals(2, router.getWarmupStep("node-alpha"));
        assertEquals(0.25, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Probe 3 passes: advances to warmup step 3 (50% traffic cap)
        router.recordHealthProbe("node-alpha", true);
        assertEquals(3, router.getWarmupStep("node-alpha"));
        assertEquals(0.50, router.getEffectiveWeightModifier("node-alpha"), 0.001);

        // Probe 4 passes: reaches full recovery (100% traffic)
        router.recordHealthProbe("node-alpha", true);
        assertEquals(4, router.getWarmupStep("node-alpha"));
        assertEquals(1.00, router.getEffectiveWeightModifier("node-alpha"), 0.001);
    }

    @Test
    @DisplayName("Should successfully route traffic between nodes in EWMA and Round-Robin modes")
    void shouldRouteInBothModes() {
        router.setRoutingMode(TrafficRouter.RoutingMode.EWMA_WEIGHTED);
        String route1 = router.routeRequest("/test");
        assertNotNull(route1);
        assertTrue(route1.startsWith("http://alpha") || route1.startsWith("http://beta"));

        router.setRoutingMode(TrafficRouter.RoutingMode.ROUND_ROBIN);
        String route2 = router.routeRequest("/test");
        assertNotNull(route2);
    }
}
