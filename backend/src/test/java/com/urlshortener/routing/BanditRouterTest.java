package com.urlshortener.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Multi-Destination A/B Bandit Router Tests")
class BanditRouterTest {

    private BanditRouter banditRouter;
    private final String routeKey = "landingPageTest";

    @BeforeEach
    void setUp() {
        banditRouter = new BanditRouter();
        banditRouter.registerDestination(routeKey, "variant-landing-v1", "https://example.com/lander-v1");
        banditRouter.registerDestination(routeKey, "variant-landing-v2", "https://example.com/lander-v2");
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
    @DisplayName("Conversion recording updates variant statistics accurately")
    void shouldUpdateStatisticsOnFeedback() {
        banditRouter.recordConversion(routeKey, "variant-landing-v1", true, 120.0);
        banditRouter.recordConversion(routeKey, "variant-landing-v1", true, 80.0);
        banditRouter.recordConversion(routeKey, "variant-landing-v1", false, 300.0);

        List<BanditRouter.DestinationStats> stats = banditRouter.getRouteStats(routeKey);
        BanditRouter.DestinationStats v1 = stats.stream()
            .filter(s -> s.destinationId().equals("variant-landing-v1"))
            .findFirst()
            .orElseThrow();

        assertEquals(2, v1.successes());
        assertEquals(1, v1.failures());
        assertEquals(3, v1.totalTrials());
        assertEquals(2.0 / 3.0, v1.empiricalSuccessRate(), 0.001);
        assertEquals(166.666, v1.meanLatencyMs(), 0.1);
        assertEquals(3.0, v1.alpha(), 0.001); // 1 + 2
        assertEquals(2.0, v1.beta(), 0.001);  // 1 + 1
    }

    @Test
    @DisplayName("Should enforce maximum limit of 5 destination variants per campaign")
    void shouldEnforceMaxDestinationLimit() {
        String campaignKey = "limitedCampaign";
        BanditRouter router = new BanditRouter();
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            assertDoesNotThrow(() -> router.registerDestination(campaignKey, "v" + id, "https://example.com/v" + id));
        }

        // Registering a 6th variant must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
            router.registerDestination(campaignKey, "v6", "https://example.com/v6")
        );
    }

    @Test
    @DisplayName("Thompson Sampling converges to the higher converting destination arm")
    void shouldConvergeToSuperiorVariant() {
        banditRouter.setStrategy(BanditRouter.Strategy.THOMPSON_SAMPLING);

        // Simulate Variant V1 with 90% true conversion rate, V2 with 30% true conversion rate
        for (int i = 0; i < 50; i++) {
            banditRouter.recordConversion(routeKey, "variant-landing-v1", (i % 10 != 0), 50.0); // 90%
            banditRouter.recordConversion(routeKey, "variant-landing-v2", (i % 10 < 3), 150.0); // 30%
        }

        int v1Selections = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            Optional<String> chosen = banditRouter.selectDestination(routeKey);
            assertTrue(chosen.isPresent());
            if ("variant-landing-v1".equals(chosen.get())) {
                v1Selections++;
            }
        }

        double v1Ratio = (double) v1Selections / trials;
        assertTrue(v1Ratio > 0.85,
            "Thompson sampling should allocate > 85% traffic to superior variant, got: " + (v1Ratio * 100) + "%");
    }

    @Test
    @DisplayName("Epsilon-Greedy exploits best arm (1-epsilon)% of the time")
    void shouldExploitBestArmInEpsilonGreedy() {
        banditRouter.setStrategy(BanditRouter.Strategy.EPSILON_GREEDY);
        banditRouter.setEpsilon(0.10); // 10% explore, 90% exploit

        // Variant V1 clearly superior
        for (int i = 0; i < 20; i++) {
            banditRouter.recordConversion(routeKey, "variant-landing-v1", true, 50.0);
            banditRouter.recordConversion(routeKey, "variant-landing-v2", false, 50.0);
        }

        int v1Selections = 0;
        int trials = 1000;

        for (int i = 0; i < trials; i++) {
            Optional<String> chosen = banditRouter.selectDestination(routeKey);
            assertTrue(chosen.isPresent());
            if ("variant-landing-v1".equals(chosen.get())) {
                v1Selections++;
            }
        }

        // Expected V1: 90% exploit + (10% * 0.5 uniform random) = 95%
        double v1Ratio = (double) v1Selections / trials;
        assertTrue(v1Ratio > 0.88,
            "Epsilon-greedy should allocate ~95% traffic to superior variant, got: " + (v1Ratio * 100) + "%");
    }
}
