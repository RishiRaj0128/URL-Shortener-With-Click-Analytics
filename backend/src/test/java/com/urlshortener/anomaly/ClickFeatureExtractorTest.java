package com.urlshortener.anomaly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 2.1: Click Feature Extractor Tests")
class ClickFeatureExtractorTest {

    private ClickFeatureExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ClickFeatureExtractor();
    }

    @Test
    @DisplayName("Single IP burst yields 0.0 IP entropy and 1.0 concentration")
    void shouldExtractZeroEntropyForSingleIp() {
        List<ClickFeatureExtractor.ClickSample> samples = new ArrayList<>();
        long baseTime = 1700000000000L;

        for (int i = 0; i < 20; i++) {
            samples.add(new ClickFeatureExtractor.ClickSample("bot-ip-hash", "Mozilla/5.0", baseTime + (i * 100)));
        }

        ClickFeatureExtractor.ClickFeatureVector vector = extractor.extractFeatures("promoLink", samples, 0.5);

        assertEquals(20, vector.sampleCount());
        assertEquals(0.0, vector.ipEntropy(), 0.001, "Single IP must produce 0 bits of Shannon entropy");
        assertEquals(1.0, vector.maxIpConcentration(), 0.001);
        assertEquals(20, vector.maxIpVelocity());
        assertEquals(0.0, vector.userAgentEntropy(), 0.001);
    }

    @Test
    @DisplayName("Diverse human traffic yields high IP entropy and Poisson-like inter-arrival CV")
    void shouldExtractHighEntropyForHumanTraffic() {
        List<ClickFeatureExtractor.ClickSample> samples = new ArrayList<>();
        long baseTime = 1700000000000L;

        // 32 unique users with different intervals
        for (int i = 0; i < 32; i++) {
            String ip = "user-ip-" + i;
            String ua = (i % 2 == 0) ? "Chrome" : "Safari";
            // Exponential-like human arrival times
            long interval = (long) (Math.random() * 2000) + 100;
            baseTime += interval;
            samples.add(new ClickFeatureExtractor.ClickSample(ip, ua, baseTime));
        }

        ClickFeatureExtractor.ClickFeatureVector vector = extractor.extractFeatures("viralLink", samples, 1.2);

        // For 32 unique items, max entropy is log2(32) = 5.0 bits
        assertEquals(5.0, vector.ipEntropy(), 0.001, "32 unique IPs must produce 5.0 bits of entropy");
        assertTrue(vector.userAgentEntropy() > 0.90, "Two balanced user-agents should yield close to 1 bit entropy");
        assertTrue(vector.maxIpConcentration() < 0.10, "No single IP should dominate human traffic");
    }

    @Test
    @DisplayName("Fixed-interval automated loop produces near-zero inter-arrival CV")
    void shouldDetectNearZeroCvForMachineLoops() {
        List<ClickFeatureExtractor.ClickSample> samples = new ArrayList<>();
        long baseTime = 1700000000000L;

        // Programmatic loop with exact 50ms intervals
        for (int i = 0; i < 50; i++) {
            samples.add(new ClickFeatureExtractor.ClickSample("bot-" + (i % 5), "BotAgent", baseTime + (i * 50)));
        }

        ClickFeatureExtractor.ClickFeatureVector vector = extractor.extractFeatures("scrapedLink", samples, 0.0);

        assertEquals(0.0, vector.interArrivalJitterCv(), 0.001, "Zero jitter loop must have CV = 0.0");
    }
}
