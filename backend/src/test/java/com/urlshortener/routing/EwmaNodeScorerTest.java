package com.urlshortener.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 1.1: EWMA Node Scorer Tests")
class EwmaNodeScorerTest {

    private EwmaNodeScorer scorer;

    @BeforeEach
    void setUp() {
        // alpha=0.5 for easy deterministic arithmetic in tests
        scorer = new EwmaNodeScorer(0.5, 10.0, 10.0);
    }

    @Test
    @DisplayName("Should correctly smooth latency using EWMA formula")
    void shouldSmoothLatencyWithEwma() {
        String nodeId = "node-1";
        scorer.registerNode(nodeId);

        // Initial default latency is 10.0
        assertEquals(10.0, scorer.getMetricsSnapshot(nodeId).latencyEwma(), 0.001);

        // Observation 1: latency = 30.0, no error
        // New EWMA = 0.5 * 30.0 + 0.5 * 10.0 = 20.0
        scorer.recordObservation(nodeId, 30.0, false);
        assertEquals(20.0, scorer.getMetricsSnapshot(nodeId).latencyEwma(), 0.001);
        assertEquals(0.0, scorer.getMetricsSnapshot(nodeId).errorRateEwma(), 0.001);

        // Observation 2: latency = 40.0
        // New EWMA = 0.5 * 40.0 + 0.5 * 20.0 = 30.0
        scorer.recordObservation(nodeId, 40.0, false);
        assertEquals(30.0, scorer.getMetricsSnapshot(nodeId).latencyEwma(), 0.001);
    }

    @Test
    @DisplayName("Should severely penalize nodes experiencing elevated error rates")
    void shouldPenalizeErrorProneNodes() {
        String healthyNode = "healthy-node";
        String degradedNode = "degraded-node";

        scorer.registerNode(healthyNode);
        scorer.registerNode(degradedNode);

        // Both nodes have same initial latency
        scorer.recordObservation(healthyNode, 20.0, false);
        scorer.recordObservation(degradedNode, 20.0, false);

        // Degraded node experiences consecutive errors
        scorer.recordObservation(degradedNode, 20.0, true);
        scorer.recordObservation(degradedNode, 20.0, true);

        double healthyScore = scorer.computeScore(healthyNode);
        double degradedScore = scorer.computeScore(degradedNode);

        assertTrue(healthyScore > degradedScore * 3.0,
            "Healthy node score (" + healthyScore + ") must be substantially higher than degraded node (" + degradedScore + ")");
    }

    @Test
    @DisplayName("Weighted selection should route disproportionately more traffic to high-scoring nodes")
    void shouldSelectWeightedProportionately() {
        String fastNode = "fast-node";
        String slowNode = "slow-node";

        scorer.registerNode(fastNode);
        scorer.registerNode(slowNode);

        // Fast node: 5ms, 0 errors -> score = 1000 / 5 = 200
        scorer.recordObservation(fastNode, 5.0, false);
        scorer.recordObservation(fastNode, 5.0, false);

        // Slow node: 50ms, 0 errors -> score = 1000 / 50 = 20
        scorer.recordObservation(slowNode, 50.0, false);
        scorer.recordObservation(slowNode, 50.0, false);

        List<String> candidates = List.of(fastNode, slowNode);
        int draws = 2000;
        int fastCount = 0;

        for (int i = 0; i < draws; i++) {
            Optional<String> chosen = scorer.selectNodeWeighted(candidates, null);
            assertTrue(chosen.isPresent());
            if (chosen.get().equals(fastNode)) {
                fastCount++;
            }
        }

        // Expected ratio is ~200 to 20 (approx 90% fast node)
        double fastFraction = (double) fastCount / draws;
        assertTrue(fastFraction > 0.80,
            "Fast node should receive > 80% of traffic, actual: " + (fastFraction * 100) + "%");
    }

    @Test
    @DisplayName("Should correctly calculate alpha from time constant tau: alpha = 1 - exp(-dt / tau)")
    void shouldCalculateAlphaFromTimeConstant() {
        // tau = 5s, dt = 1s -> alpha = 1 - exp(-1/5) = 1 - exp(-0.2) = 1 - 0.81873 = 0.18127
        EwmaNodeScorer tauScorer = EwmaNodeScorer.fromTimeConstant(5.0, 1.0, 10.0, 20.0);
        tauScorer.registerNode("node-tau");

        // Initial = 10.0. Observation = 100.0.
        // Expected new = 0.18127 * 100 + (1 - 0.18127) * 10 = 18.127 + 8.1873 = 26.314
        tauScorer.recordObservation("node-tau", 100.0, false);
        double actual = tauScorer.getMetricsSnapshot("node-tau").latencyEwma();
        assertEquals(26.314, actual, 0.05, "Latency should decay according to time constant tau = 5s");

        // Negative or zero tau must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> EwmaNodeScorer.fromTimeConstant(0.0, 1.0, 10.0, 20.0));
        assertThrows(IllegalArgumentException.class, () -> EwmaNodeScorer.fromTimeConstant(-2.0, 1.0, 10.0, 20.0));
    }
}
