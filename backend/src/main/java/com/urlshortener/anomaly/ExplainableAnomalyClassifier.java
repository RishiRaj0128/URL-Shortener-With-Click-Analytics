package com.urlshortener.anomaly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Explainable Multi-Dimensional Anomaly Classifier.
 * 
 * Replaces black-box opaque models with an explainable ensemble:
 * - Computes normalized anomaly score in [0.0, 1.0].
 * - Distinguishes between Single-IP Bot Bursts, Distributed Machine Botnets, and Legitimate Viral Surges.
 * - Produces human-readable attribution factors for audit and security operations.
 */
@Component
public class ExplainableAnomalyClassifier {

    private static final Logger log = LoggerFactory.getLogger(ExplainableAnomalyClassifier.class);

    public record ClassificationResult(
        boolean isAnomaly,
        String anomalyType,
        double anomalyScore,
        List<String> explainabilityFactors,
        ClickFeatureExtractor.ClickFeatureVector featureVector
    ) {}

    private double anomalyThreshold = 0.60;
    private int velocityThreshold = 15;
    private double zScoreThreshold = 3.0;

    public void setAnomalyThreshold(double threshold) {
        this.anomalyThreshold = threshold;
    }

    public void setVelocityThreshold(int threshold) {
        this.velocityThreshold = threshold;
    }

    public void setZScoreThreshold(double threshold) {
        this.zScoreThreshold = threshold;
    }

    /**
     * Evaluates a feature vector and returns an explainable classification verdict.
     */
    public ClassificationResult classify(ClickFeatureExtractor.ClickFeatureVector vector) {
        List<String> factors = new ArrayList<>();
        double score = 0.0;
        String detectedType = null;

        // Factor 1: Single-IP Request Velocity & Concentration
        if (vector.maxIpVelocity() >= velocityThreshold) {
            double velocityFactor = Math.min(1.0, (double) vector.maxIpVelocity() / (velocityThreshold * 2.0));
            score = Math.max(score, 0.70 + (0.30 * velocityFactor));
            factors.add(String.format("[CRITICAL] IP Velocity Tripwire: %d requests in window (threshold: %d, concentration: %.1f%%)",
                vector.maxIpVelocity(), velocityThreshold, vector.maxIpConcentration() * 100.0));
            detectedType = "BOT_BURST_VELOCITY";
        } else if (vector.maxIpConcentration() > 0.80 && vector.sampleCount() >= 10) {
            score = Math.max(score, 0.65);
            factors.add(String.format("[HIGH] High Single-IP Concentration: %.1f%% of traffic from single origin",
                vector.maxIpConcentration() * 100.0));
            detectedType = "BOT_BURST_VELOCITY";
        }

        // Factor 2: Inter-Arrival Jitter (Programmatic Scrapers vs Human Poisson Arrivals)
        // Humans typically exhibit CV >= 0.6; fixed-loop cron scripts / loops exhibit CV < 0.15
        if (vector.sampleCount() >= 10 && vector.interArrivalJitterCv() < 0.15) {
            double jitterPenalty = (0.15 - vector.interArrivalJitterCv()) / 0.15;
            score = Math.max(score, 0.60 + (0.35 * jitterPenalty));
            factors.add(String.format("[CRITICAL] Machine Timing Detected: Inter-arrival CV=%.3f (human baseline >= 0.60) indicates programmatic script loop",
                vector.interArrivalJitterCv()));
            if (detectedType == null) {
                detectedType = "DISTRIBUTED_BOTNET_ENTROPY_COLLAPSE";
            }
        }

        // Factor 3: User Agent Entropy Collapse
        // Distributed botnets often hit with diverse IPs but zero or near-zero UA variation
        if (vector.sampleCount() >= 15 && vector.userAgentEntropy() < 0.10 && vector.ipEntropy() > 2.0) {
            score = Math.max(score, 0.75);
            factors.add(String.format("[HIGH] User-Agent Entropy Collapse: H(UA)=%.2f bits across %d unique IPs (identical automated client fingerprint)",
                vector.userAgentEntropy(), vector.sampleCount()));
            detectedType = "DISTRIBUTED_BOTNET_ENTROPY_COLLAPSE";
        }

        // Factor 4: Robust Z-Score Volume Surge
        if (vector.robustZScore() >= zScoreThreshold && vector.sampleCount() >= 20) {
            double zScoreFactor = Math.min(1.0, (vector.robustZScore() - zScoreThreshold) / 5.0);
            score = Math.max(score, 0.65 + (0.30 * zScoreFactor));
            factors.add(String.format("[MEDIUM] Robust Volume Surge: Z=%.2f >= %.2f threshold",
                vector.robustZScore(), zScoreThreshold));
            if (detectedType == null) {
                detectedType = "STATISTICAL_VOLUME_SPIKE";
            }
        }

        boolean isAnomaly = (score >= anomalyThreshold);
        if (!isAnomaly) {
            factors.add(String.format("[NORMAL] Human behavioral distribution verified: H(IP)=%.2f bits, Inter-arrival CV=%.2f, Z=%.2f",
                vector.ipEntropy(), vector.interArrivalJitterCv(), vector.robustZScore()));
            detectedType = "LEGITIMATE";
        }

        log.debug("Classification verdict for shortCode {}: isAnomaly={}, type={}, score={:.2f}",
            vector.shortCode(), isAnomaly, detectedType, score);

        return new ClassificationResult(
            isAnomaly,
            detectedType,
            Math.min(1.0, score),
            factors,
            vector
        );
    }
}
