package com.urlshortener.anomaly;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Real-Time Feature Extraction Engine for Click Traffic.
 * 
 * Computes multidimensional feature vectors across a sliding evaluation window:
 * - Shannon Entropy of Client IPs (H(IP))
 * - Shannon Entropy of User Agents (H(UA))
 * - Inter-Arrival Time Coefficient of Variation (CV = sigma / mu)
 * - Maximum IP Concentration Ratio (max(c_i) / N)
 * - Peak IP Velocity
 */
@Component
public class ClickFeatureExtractor {

    public record ClickSample(
        String ipHash,
        String userAgent,
        long timestampMs
    ) {}

    public record ClickFeatureVector(
        String shortCode,
        long sampleCount,
        double ipEntropy,
        double userAgentEntropy,
        double interArrivalJitterCv,
        double maxIpConcentration,
        long maxIpVelocity,
        double robustZScore
    ) {}

    /**
     * Extracts a multidimensional ClickFeatureVector from a window of raw click samples.
     */
    public ClickFeatureVector extractFeatures(String shortCode, List<ClickSample> samples, double robustZScore) {
        if (samples == null || samples.isEmpty()) {
            return new ClickFeatureVector(shortCode, 0, 0.0, 0.0, 1.0, 0.0, 0, robustZScore);
        }

        long n = samples.size();
        Map<String, Long> ipCounts = new HashMap<>();
        Map<String, Long> uaCounts = new HashMap<>();
        List<Long> timestamps = new ArrayList<>(samples.size());

        for (ClickSample s : samples) {
            ipCounts.merge(s.ipHash() != null ? s.ipHash() : "unknown", 1L, Long::sum);
            uaCounts.merge(s.userAgent() != null ? s.userAgent() : "unknown", 1L, Long::sum);
            timestamps.add(s.timestampMs());
        }

        // 1. Shannon Entropy of IPs: H(X) = -sum( p(x) * log2(p(x)) )
        double ipEntropy = computeShannonEntropy(ipCounts.values(), n);

        // 2. Shannon Entropy of User Agents
        double uaEntropy = computeShannonEntropy(uaCounts.values(), n);

        // 3. Maximum IP Concentration and Peak Velocity
        long maxIpHits = 0;
        for (Long count : ipCounts.values()) {
            if (count > maxIpHits) {
                maxIpHits = count;
            }
        }
        double maxIpConcentration = (double) maxIpHits / n;

        // 4. Inter-Arrival Time Coefficient of Variation (CV = stddev / mean)
        double interArrivalCv = computeInterArrivalCv(timestamps);

        return new ClickFeatureVector(
            shortCode,
            n,
            ipEntropy,
            uaEntropy,
            interArrivalCv,
            maxIpConcentration,
            maxIpHits,
            robustZScore
        );
    }

    private double computeShannonEntropy(Collection<Long> counts, long total) {
        if (total <= 1 || counts.isEmpty()) {
            return 0.0;
        }

        double entropy = 0.0;
        for (Long c : counts) {
            if (c > 0) {
                double p = (double) c / total;
                entropy -= p * (Math.log(p) / Math.log(2.0));
            }
        }
        return Math.max(0.0, entropy);
    }

    private double computeInterArrivalCv(List<Long> timestamps) {
        if (timestamps.size() < 3) {
            return 1.0; // Default neutral human CV for small windows
        }

        List<Long> sorted = new ArrayList<>(timestamps);
        Collections.sort(sorted);

        List<Double> intervals = new ArrayList<>(sorted.size() - 1);
        double sum = 0.0;
        for (int i = 1; i < sorted.size(); i++) {
            double delta = Math.max(0.0, sorted.get(i) - sorted.get(i - 1));
            intervals.add(delta);
            sum += delta;
        }

        double mean = sum / intervals.size();
        if (mean <= 0.001) {
            return 0.0; // Simultaneous burst (0 jitter)
        }

        double varianceSum = 0.0;
        for (Double dt : intervals) {
            double diff = dt - mean;
            varianceSum += (diff * diff);
        }
        double stdDev = Math.sqrt(varianceSum / intervals.size());

        return stdDev / mean;
    }
}
