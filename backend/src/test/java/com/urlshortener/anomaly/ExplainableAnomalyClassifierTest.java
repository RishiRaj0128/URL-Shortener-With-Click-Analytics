package com.urlshortener.anomaly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 2.2: Explainable Anomaly Classifier Tests")
class ExplainableAnomalyClassifierTest {

    private ExplainableAnomalyClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ExplainableAnomalyClassifier();
    }

    @Test
    @DisplayName("Single IP burst classified as BOT_BURST_VELOCITY with explicit attribution factors")
    void shouldClassifyBotBurstVelocity() {
        // High velocity (30 reqs from single IP), 0 entropy
        ClickFeatureExtractor.ClickFeatureVector burstVector = new ClickFeatureExtractor.ClickFeatureVector(
            "shortPromo",
            30,
            0.0,
            0.0,
            0.05,
            1.0,
            30,
            2.5
        );

        ExplainableAnomalyClassifier.ClassificationResult result = classifier.classify(burstVector);

        assertTrue(result.isAnomaly());
        assertEquals("BOT_BURST_VELOCITY", result.anomalyType());
        assertTrue(result.anomalyScore() >= 0.70);
        assertFalse(result.explainabilityFactors().isEmpty());
        assertTrue(result.explainabilityFactors().get(0).contains("IP Velocity Tripwire"));
    }

    @Test
    @DisplayName("Distributed botnet with machine timing classified as DISTRIBUTED_BOTNET_ENTROPY_COLLAPSE")
    void shouldClassifyDistributedBotnet() {
        // 50 requests across 20 IPs, but identical User-Agent (entropy = 0) and zero jitter (CV = 0.02)
        ClickFeatureExtractor.ClickFeatureVector botnetVector = new ClickFeatureExtractor.ClickFeatureVector(
            "checkoutPay",
            50,
            4.2, // diverse IPs
            0.0, // identical UA across all IPs
            0.02, // machine timer loop
            0.10,
            5,
            1.8
        );

        ExplainableAnomalyClassifier.ClassificationResult result = classifier.classify(botnetVector);

        assertTrue(result.isAnomaly());
        assertEquals("DISTRIBUTED_BOTNET_ENTROPY_COLLAPSE", result.anomalyType());
        assertTrue(result.anomalyScore() >= 0.65);
        boolean containsMachineTiming = result.explainabilityFactors().stream()
            .anyMatch(f -> f.contains("Machine Timing Detected") || f.contains("User-Agent Entropy Collapse"));
        assertTrue(containsMachineTiming, "Attribution factors must clearly articulate why botnet was flagged");
    }

    @Test
    @DisplayName("Legitimate viral traffic classified as LEGITIMATE or STATISTICAL_VOLUME_SPIKE without bot false-positive")
    void shouldDistinguishLegitimateHumanSpikes() {
        // High volume (Z=4.5), but diverse human IPs (entropy = 4.8), diverse UAs (entropy = 2.1), human Poisson jitter (CV = 1.1)
        ClickFeatureExtractor.ClickFeatureVector viralVector = new ClickFeatureExtractor.ClickFeatureVector(
            "viralLaunch",
            100,
            4.8,
            2.1,
            1.1, // normal human Poisson distribution
            0.05,
            5,
            4.5
        );

        ExplainableAnomalyClassifier.ClassificationResult result = classifier.classify(viralVector);

        // Volume spike is noted, but it is NOT falsely accused of being a bot velocity/botnet attack!
        assertEquals("STATISTICAL_VOLUME_SPIKE", result.anomalyType());
        assertNotEquals("BOT_BURST_VELOCITY", result.anomalyType());
        assertNotEquals("DISTRIBUTED_BOTNET_ENTROPY_COLLAPSE", result.anomalyType());
    }

    @Test
    @DisplayName("Normal human traffic receives low score (< 0.40) and is labeled LEGITIMATE")
    void shouldPassNormalHumanTrafficCleanly() {
        ClickFeatureExtractor.ClickFeatureVector normalVector = new ClickFeatureExtractor.ClickFeatureVector(
            "docLink",
            20,
            3.8,
            1.5,
            0.95,
            0.08,
            2,
            0.8
        );

        ExplainableAnomalyClassifier.ClassificationResult result = classifier.classify(normalVector);

        assertFalse(result.isAnomaly());
        assertEquals("LEGITIMATE", result.anomalyType());
        assertTrue(result.anomalyScore() < 0.40);
        assertTrue(result.explainabilityFactors().get(0).contains("Human behavioral distribution verified"));
    }
}
