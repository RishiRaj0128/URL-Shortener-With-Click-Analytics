package com.urlshortener.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 1.3: Multi-Destination Bandit Router Tests")
class BanditRouterTest {

    private BanditRouter banditRouter;
    private final String routeKey = "paymentCheckout";

    @BeforeEach
    void setUp() {
        banditRouter = new BanditRouter();
        banditRouter.registerDestination(routeKey, "gateway-hdfc", "https://hdfc.bank.in/pay");
        banditRouter.registerDestination(routeKey, "gateway-icici", "https://icici.bank.in/pay");
    }

    @Test
    @DisplayName("Beta sampling produces valid probabilities in (0, 1)")
    void shouldSampleValidBetaValues() {
        for (int i = 0; i < 500; i++) {
            double sample = BanditRouter.sampleBeta(2.0, 5.0);
            assertTrue(sample > 0.0 && sample < 1.0, "Sample " + sample + " must be strictly between 0 and 1");
        }
    }

    @Test
    @DisplayName("Outcome recording updates destination statistics accurately")
    void shouldUpdateStatisticsOnFeedback() {
        banditRouter.recordOutcome(routeKey, "gateway-hdfc", true, 120.0);
        banditRouter.recordOutcome(routeKey, "gateway-hdfc", true, 80.0);
        banditRouter.recordOutcome(routeKey, "gateway-hdfc", false, 300.0);

        List<BanditRouter.DestinationStats> stats = banditRouter.getRouteStats(routeKey);
        BanditRouter.DestinationStats hdfc = stats.stream()
            .filter(s -> s.destinationId().equals("gateway-hdfc"))
            .findFirst()
            .orElseThrow();

        assertEquals(2, hdfc.successes());
        assertEquals(1, hdfc.failures());
        assertEquals(3, hdfc.totalTrials());
        assertEquals(2.0 / 3.0, hdfc.empiricalSuccessRate(), 0.001);
        assertEquals(166.666, hdfc.meanLatencyMs(), 0.1);
        assertEquals(3.0, hdfc.alpha(), 0.001); // 1 + 2
        assertEquals(2.0, hdfc.beta(), 0.001);  // 1 + 1
    }

    @Test
    @DisplayName("Thompson Sampling converges to the higher converting destination arm")
    void shouldConvergeToSuperiorGateway() {
        banditRouter.setStrategy(BanditRouter.Strategy.THOMPSON_SAMPLING);

        // Simulate Gateway HDFC with 90% true success rate, ICICI with 30% true success rate
        // Pre-feed 50 trials each to establish empirical distribution
        for (int i = 0; i < 50; i++) {
            banditRouter.recordOutcome(routeKey, "gateway-hdfc", (i % 10 != 0), 50.0); // 90%
            banditRouter.recordOutcome(routeKey, "gateway-icici", (i % 10 < 3), 150.0); // 30%
        }

        int hdfcSelections = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            Optional<String> chosen = banditRouter.selectDestination(routeKey);
            assertTrue(chosen.isPresent());
            if ("gateway-hdfc".equals(chosen.get())) {
                hdfcSelections++;
            }
        }

        double hdfcRatio = (double) hdfcSelections / trials;
        assertTrue(hdfcRatio > 0.85,
            "Thompson sampling should allocate > 85% traffic to superior gateway, got: " + (hdfcRatio * 100) + "%");
    }

    @Test
    @DisplayName("Epsilon-Greedy exploits best arm (1-epsilon)% of the time")
    void shouldExploitBestArmInEpsilonGreedy() {
        banditRouter.setStrategy(BanditRouter.Strategy.EPSILON_GREEDY);
        banditRouter.setEpsilon(0.10); // 10% explore, 90% exploit

        // HDFC clearly superior
        for (int i = 0; i < 20; i++) {
            banditRouter.recordOutcome(routeKey, "gateway-hdfc", true, 50.0);
            banditRouter.recordOutcome(routeKey, "gateway-icici", false, 50.0);
        }

        int hdfcSelections = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            Optional<String> chosen = banditRouter.selectDestination(routeKey);
            assertTrue(chosen.isPresent());
            if ("gateway-hdfc".equals(chosen.get())) {
                hdfcSelections++;
            }
        }

        // Expected HDFC: 90% exploit + (10% * 0.5 uniform random) = 95%
        double hdfcRatio = (double) hdfcSelections / trials;
        assertTrue(hdfcRatio > 0.88,
            "Epsilon-greedy should allocate ~95% traffic to superior gateway, got: " + (hdfcRatio * 100) + "%");
    }
}
