package com.urlshortener.service;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickAnomalyLog;
import com.urlshortener.repository.ClickAnomalyLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Track 0.4: Hardened Anomaly Detection & Baseline Guard Tests")
class AnomalyDetectorTest {

    @Mock
    private ClickAnomalyLogRepository anomalyLogRepository;

    private AnomalyDetector anomalyDetector;

    @BeforeEach
    void setUp() {
        anomalyDetector = new AnomalyDetector(anomalyLogRepository);
        anomalyDetector.setVelocityParameters(10, 5); // 5 max requests per 10s window
        anomalyDetector.setZScoreThreshold(3.0);
        anomalyDetector.setMinSpikeVolume(20);
        anomalyDetector.setMinSampleCount(10); // 10 baseline samples for concise testing
        anomalyDetector.setMadFloor(1.0);
        anomalyDetector.setStepChangeRebaselineBuckets(10); // 10 consecutive elevated samples to re-baseline
    }

    @Test
    @DisplayName("Velocity Heuristic: Should allow traffic below threshold and flag rapid bursts from single IP")
    void shouldFlagBotVelocityBursts() {
        String shortCode = "juspayOffer";
        String attackerIp = "203.0.113.42";
        String benignIp = "198.51.100.1";

        // First 5 requests from attacker IP should be marked legitimate
        for (int i = 1; i <= 5; i++) {
            ClickEvent event = new ClickEvent("evt_atk_" + i, shortCode, 1L, LocalDateTime.now(), attackerIp, "bot/1.0", null);
            AnomalyDetector.AnomalyResult result = anomalyDetector.evaluateClickVelocity(event);
            assertFalse(result.isAnomaly(), "Request " + i + " should not be flagged yet");
        }

        // 6th request from attacker IP breaches the threshold
        ClickEvent breachEvent = new ClickEvent("evt_atk_6", shortCode, 1L, LocalDateTime.now(), attackerIp, "bot/1.0", null);
        AnomalyDetector.AnomalyResult breachResult = anomalyDetector.evaluateClickVelocity(breachEvent);

        assertTrue(breachResult.isAnomaly(), "6th request within window must be flagged as an anomaly");
        assertEquals("BOT_BURST_VELOCITY", breachResult.anomalyType());
        assertTrue(breachResult.details().contains("Velocity breach"));

        // Verify audit log entry was saved
        ArgumentCaptor<ClickAnomalyLog> logCaptor = ArgumentCaptor.forClass(ClickAnomalyLog.class);
        verify(anomalyLogRepository, atLeastOnce()).save(logCaptor.capture());
        assertEquals("BOT_BURST_VELOCITY", logCaptor.getValue().getAnomalyType());
        assertEquals(shortCode, logCaptor.getValue().getShortCode());

        // Meanwhile, a different benign IP should NOT be affected
        ClickEvent benignEvent = new ClickEvent("evt_benign_1", shortCode, 1L, LocalDateTime.now(), benignIp, "Mozilla/5.0", null);
        AnomalyDetector.AnomalyResult benignResult = anomalyDetector.evaluateClickVelocity(benignEvent);
        assertFalse(benignResult.isAnomaly(), "Independent benign IP should not be flagged");
    }

    @Test
    @DisplayName("Requirement (a) Cold-Start Guard: Yields zero false flags when sample count < minSampleCount")
    void shouldSuppressVerdictsDuringColdStart() {
        String shortCode = "brandNewLink";
        anomalyDetector.setMinSampleCount(10);

        // Feed only 9 buckets (even wildly fluctuating values)
        long[] noisyInitialTraffic = {10, 250, 5, 400, 2, 600, 15, 800, 1};
        for (int i = 0; i < noisyInitialTraffic.length; i++) {
            AnomalyDetector.AnomalyResult result = anomalyDetector.evaluateBucketZScore(shortCode, noisyInitialTraffic[i]);
            assertFalse(result.isAnomaly(), "Cold-start sample #" + (i + 1) + " must NEVER produce a false anomaly flag");
            assertEquals("INSUFFICIENT_DATA", result.anomalyType());
            assertTrue(result.details().contains("Cold start"));
        }
    }

    @Test
    @DisplayName("Requirement (b) Poisoning Resistance: Sustained attack ramp does NOT learn itself into normal")
    void shouldResistBaselinePoisoningUnderSustainedAttackRamp() {
        String shortCode = "promoPaymentPage";
        anomalyDetector.setMinSampleCount(10);

        // Step 1: Establish steady baseline traffic of ~50 clicks across 10 buckets
        long[] baseline = {50, 51, 49, 50, 52, 48, 50, 51, 49, 50};
        for (long count : baseline) {
            AnomalyDetector.AnomalyResult res = anomalyDetector.evaluateBucketZScore(shortCode, count);
            assertFalse(res.isAnomaly());
        }

        // Step 2: Inject a sustained attack ramp: 150, 180, 210, 250, 300, 350
        // Because flagged buckets are quarantined from the baseline, median remains ~50 and MAD remains tight.
        long[] attackRamp = {150, 180, 210, 250, 300, 350};
        for (long attackVolume : attackRamp) {
            AnomalyDetector.AnomalyResult attackRes = anomalyDetector.evaluateBucketZScore(shortCode, attackVolume);
            assertTrue(attackRes.isAnomaly(), 
                "Volume of " + attackVolume + " MUST be flagged as an anomaly; attack cannot poison normal baseline");
            assertEquals("STATISTICAL_VOLUME_SPIKE", attackRes.anomalyType());
        }
    }

    @Test
    @DisplayName("Requirement (c) Division-by-Zero Floor: Constant flat traffic series handles MAD=0 with floor")
    void shouldHandleConstantFlatSeriesWithoutDivisionByZero() {
        String shortCode = "flatTrafficLink";
        anomalyDetector.setMinSampleCount(10);
        anomalyDetector.setMadFloor(1.0);

        // Feed identical click counts: MAD would mathematically be 0
        for (int i = 0; i < 15; i++) {
            AnomalyDetector.AnomalyResult res = anomalyDetector.evaluateBucketZScore(shortCode, 50L);
            assertFalse(res.isAnomaly());
            assertFalse(Double.isNaN(res.zScore()), "Z-score must never be NaN");
            assertFalse(Double.isInfinite(res.zScore()), "Z-score must never be Infinite");
            assertEquals(0.0, res.zScore(), 0.001);
        }
    }

    @Test
    @DisplayName("Requirement (d) Step-Change Re-baselining: Legitimate sustained step change is re-baselined after window")
    void shouldRebaselineAfterSustainedStepChange() {
        String shortCode = "viralCampaignHit";
        int rebaselineThreshold = 10;
        anomalyDetector.setMinSampleCount(10);
        anomalyDetector.setStepChangeRebaselineBuckets(rebaselineThreshold);

        // Step 1: Initial baseline around 20 clicks
        for (int i = 0; i < 10; i++) {
            anomalyDetector.evaluateBucketZScore(shortCode, 20L);
        }

        // Step 2: Traffic permanently jumps to 200 clicks/min (e.g. TV advertisement / Superbowl launch)
        // First 9 buckets are flagged as spikes
        for (int i = 1; i < rebaselineThreshold; i++) {
            AnomalyDetector.AnomalyResult res = anomalyDetector.evaluateBucketZScore(shortCode, 200L);
            assertTrue(res.isAnomaly(), "Bucket #" + i + " should initially be treated as spike");
        }

        // At exactly bucket #10 of continuous elevated volume, system transitions and re-baselines
        AnomalyDetector.AnomalyResult rebaselineResult = anomalyDetector.evaluateBucketZScore(shortCode, 200L);
        assertFalse(rebaselineResult.isAnomaly(), "At threshold, the sustained step-change must re-baseline to normal");
        assertTrue(rebaselineResult.details().contains("re-baselined"));

        // Subsequent traffic at 200 is now accepted as the new normal
        AnomalyDetector.AnomalyResult newNormalResult = anomalyDetector.evaluateBucketZScore(shortCode, 200L);
        assertFalse(newNormalResult.isAnomaly(), "Post-rebaseline traffic at 200 must not trigger false alarms");
        
        // Small normal variation around new normal (e.g. 202) is also within bounds
        AnomalyDetector.AnomalyResult slightVariationResult = anomalyDetector.evaluateBucketZScore(shortCode, 202L);
        assertFalse(slightVariationResult.isAnomaly(), "Slight variation around new normal should not trigger anomaly");
    }
}
