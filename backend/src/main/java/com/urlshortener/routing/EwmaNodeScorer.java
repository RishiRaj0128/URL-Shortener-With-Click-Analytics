package com.urlshortener.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponentially Weighted Moving Average (EWMA) Node Scorer.
 * 
 * Features:
 * - Continuously tracks EWMA of latency and error rates per downstream node.
 * - Computes dynamic routing score that prioritizes fast, low-error instances.
 * - Heavy penalty multiplier on elevated error rates.
 * - Supports Weighted Random Selection (roulette wheel selection).
 */
@Component
public class EwmaNodeScorer {

    private static final Logger log = LoggerFactory.getLogger(EwmaNodeScorer.class);

    // Alpha smoothing factor derived from time constant tau: alpha = 1 - exp(-dt / tau)
    // where tau is the characteristic time constant (e.g. tau = 4.48s for alpha = 0.2 at dt = 1s)
    private final double alpha;
    private final double defaultLatencyMs;
    private final double errorPenaltyMultiplier;

    private final Map<String, NodeMetrics> nodeMetrics = new ConcurrentHashMap<>();

    public EwmaNodeScorer() {
        this(0.2, 10.0, 20.0);
    }

    public EwmaNodeScorer(double alpha, double defaultLatencyMs, double errorPenaltyMultiplier) {
        if (alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("Alpha smoothing factor must be in range (0.0, 1.0]");
        }
        this.alpha = alpha;
        this.defaultLatencyMs = defaultLatencyMs;
        this.errorPenaltyMultiplier = errorPenaltyMultiplier;
    }

    /**
     * Factory constructor specifying characteristic time constant tau and observation interval dt.
     * alpha = 1.0 - Math.exp(-dtSec / tauSec)
     */
    public static EwmaNodeScorer fromTimeConstant(double tauSec, double dtSec, double defaultLatencyMs, double errorPenaltyMultiplier) {
        if (tauSec <= 0.0) throw new IllegalArgumentException("Time constant tau must be positive");
        double calculatedAlpha = 1.0 - Math.exp(-dtSec / tauSec);
        return new EwmaNodeScorer(calculatedAlpha, defaultLatencyMs, errorPenaltyMultiplier);
    }

    public void registerNode(String nodeId) {
        nodeMetrics.putIfAbsent(nodeId, new NodeMetrics(defaultLatencyMs, 0.0));
    }

    public void unregisterNode(String nodeId) {
        nodeMetrics.remove(nodeId);
    }

    public void clear() {
        nodeMetrics.clear();
    }

    /**
     * Records a completed request outcome for a node, updating latency and error rate EWMAs.
     */
    public void recordObservation(String nodeId, double latencyMs, boolean isError) {
        NodeMetrics metrics = nodeMetrics.computeIfAbsent(
            nodeId,
            k -> new NodeMetrics(defaultLatencyMs, 0.0)
        );

        metrics.update(latencyMs, isError ? 1.0 : 0.0, alpha);
        log.debug("Updated EWMA for node {}: latency={:.2f}ms, errorRate={:.4f}",
                nodeId, metrics.getLatencyEwma(), metrics.getErrorRateEwma());
    }

    /**
     * Computes the positive routing score for a node.
     * Higher score indicates higher preference.
     * Score = 1000.0 / (max(1.0, latencyEwma) * (1.0 + errorPenaltyMultiplier * errorRateEwma))
     */
    public double computeScore(String nodeId) {
        NodeMetrics metrics = nodeMetrics.get(nodeId);
        if (metrics == null) {
            return 1000.0 / Math.max(1.0, defaultLatencyMs);
        }

        double latency = Math.max(1.0, metrics.getLatencyEwma());
        double errorRate = Math.max(0.0, metrics.getErrorRateEwma());
        double penalty = 1.0 + (errorPenaltyMultiplier * errorRate);

        return 1000.0 / (latency * penalty);
    }

    /**
     * Performs weighted random selection across candidate nodes based on their EWMA scores.
     * If all candidate scores are zero or empty, returns empty Optional.
     */
    public Optional<String> selectNodeWeighted(List<String> candidateNodeIds, Map<String, Double> weightModifiers) {
        if (candidateNodeIds == null || candidateNodeIds.isEmpty()) {
            return Optional.empty();
        }
        if (candidateNodeIds.size() == 1) {
            return Optional.of(candidateNodeIds.get(0));
        }

        Map<String, Double> effectiveScores = new LinkedHashMap<>();
        double totalScore = 0.0;

        for (String nodeId : candidateNodeIds) {
            double baseScore = computeScore(nodeId);
            double modifier = (weightModifiers != null) ? weightModifiers.getOrDefault(nodeId, 1.0) : 1.0;
            double effectiveScore = Math.max(0.0, baseScore * modifier);
            effectiveScores.put(nodeId, effectiveScore);
            totalScore += effectiveScore;
        }

        if (totalScore <= 0.000001) {
            // Fallback: uniform random if all scores are 0
            int idx = ThreadLocalRandom.current().nextInt(candidateNodeIds.size());
            return Optional.of(candidateNodeIds.get(idx));
        }

        // Roulette wheel selection
        double randomPoint = ThreadLocalRandom.current().nextDouble() * totalScore;
        double runningSum = 0.0;

        for (Map.Entry<String, Double> entry : effectiveScores.entrySet()) {
            runningSum += entry.getValue();
            if (randomPoint <= runningSum) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.of(candidateNodeIds.get(candidateNodeIds.size() - 1));
    }

    public NodeMetricsSnapshot getMetricsSnapshot(String nodeId) {
        NodeMetrics m = nodeMetrics.get(nodeId);
        if (m == null) {
            return new NodeMetricsSnapshot(nodeId, defaultLatencyMs, 0.0, computeScore(nodeId));
        }
        return new NodeMetricsSnapshot(nodeId, m.getLatencyEwma(), m.getErrorRateEwma(), computeScore(nodeId));
    }

    public record NodeMetricsSnapshot(
        String nodeId,
        double latencyEwma,
        double errorRateEwma,
        double score
    ) {}

    public static class NodeMetrics {
        private volatile double latencyEwma;
        private volatile double errorRateEwma;

        public NodeMetrics(double initialLatency, double initialErrorRate) {
            this.latencyEwma = initialLatency;
            this.errorRateEwma = initialErrorRate;
        }

        public synchronized void update(double measuredLatency, double measuredError, double alpha) {
            this.latencyEwma = (alpha * measuredLatency) + ((1.0 - alpha) * this.latencyEwma);
            this.errorRateEwma = (alpha * measuredError) + ((1.0 - alpha) * this.errorRateEwma);
        }

        public double getLatencyEwma() {
            return latencyEwma;
        }

        public double getErrorRateEwma() {
            return errorRateEwma;
        }
    }
}
