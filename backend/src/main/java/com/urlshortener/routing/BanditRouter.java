package com.urlshortener.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Multi-Armed Bandit (MAB) Router for Multi-Destination A/B Link Optimization.
 * 
 * Supports dynamically routing traffic across 2 to 5 destination variants (landing pages)
 * to maximize conversion rates while balancing exploration vs exploitation.
 * 
 * Features:
 * - Thompson Sampling: Bayesian Beta-Bernoulli conjugate model sampling theta ~ Beta(alpha, beta).
 * - Epsilon-Greedy: Selectable exploration vs exploitation mode.
 * - Real-Time Conversion Feedback Loop: Dynamically adjusts routing distribution based on conversion callbacks.
 * - Pure Mathematical Gamma/Beta Sampling: Zero external math dependencies.
 */
@Component
public class BanditRouter {

    private static final Logger log = LoggerFactory.getLogger(BanditRouter.class);

    public static final int MIN_DESTINATIONS = 2;
    public static final int MAX_DESTINATIONS = 5;

    public enum Strategy {
        THOMPSON_SAMPLING,
        EPSILON_GREEDY
    }

    public record DestinationStats(
        String destinationId,
        String targetUrl,
        long successes,
        long failures,
        long totalTrials,
        double empiricalSuccessRate,
        double meanLatencyMs,
        double alpha,
        double beta
    ) {}

    private final Map<String, Map<String, DestinationArm>> routes = new ConcurrentHashMap<>();
    private volatile Strategy strategy = Strategy.THOMPSON_SAMPLING;
    private volatile double epsilon = 0.10; // For Epsilon-Greedy

    public BanditRouter() {}

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        log.info("BanditRouter strategy updated to: {}", strategy);
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setEpsilon(double epsilon) {
        if (epsilon < 0.0 || epsilon > 1.0) {
            throw new IllegalArgumentException("Epsilon must be between 0.0 and 1.0");
        }
        this.epsilon = epsilon;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public void clear() {
        routes.clear();
    }

    /**
     * Registers a candidate destination variant for a given campaign/route key.
     * Enforces an upper limit of 5 destinations per campaign.
     */
    public void registerDestination(String routeKey, String destinationId, String targetUrl) {
        Map<String, DestinationArm> arms = routes.computeIfAbsent(routeKey, k -> new ConcurrentHashMap<>());
        if (arms.size() >= MAX_DESTINATIONS && !arms.containsKey(destinationId)) {
            throw new IllegalArgumentException("Maximum of " + MAX_DESTINATIONS + " destination variants allowed per campaign");
        }
        arms.put(destinationId, new DestinationArm(destinationId, targetUrl));
        log.debug("Registered destination variant '{}' ({}) for campaign '{}'", destinationId, targetUrl, routeKey);
    }

    /**
     * Selects a destination variant for the given campaign key
     * according to the configured Bandit strategy (Thompson Sampling or Epsilon-Greedy).
     */
    public Optional<String> selectDestination(String routeKey) {
        Map<String, DestinationArm> arms = routes.get(routeKey);
        if (arms == null || arms.isEmpty()) {
            return Optional.empty();
        }

        List<DestinationArm> armList = new ArrayList<>(arms.values());
        if (armList.size() == 1) {
            return Optional.of(armList.get(0).destinationId);
        }

        if (strategy == Strategy.EPSILON_GREEDY) {
            return Optional.of(selectEpsilonGreedy(armList));
        } else {
            return Optional.of(selectThompsonSampling(armList));
        }
    }

    private String selectThompsonSampling(List<DestinationArm> arms) {
        DestinationArm bestArm = null;
        double maxSampledTheta = -1.0;

        for (DestinationArm arm : arms) {
            double alpha = arm.getAlpha();
            double beta = arm.getBeta();
            double sampledTheta = sampleBeta(alpha, beta);

            if (sampledTheta > maxSampledTheta) {
                maxSampledTheta = sampledTheta;
                bestArm = arm;
            }
        }

        return (bestArm != null) ? bestArm.destinationId : arms.get(0).destinationId;
    }

    private String selectEpsilonGreedy(List<DestinationArm> arms) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        // Explore with probability epsilon
        if (rand.nextDouble() < epsilon) {
            int idx = rand.nextInt(arms.size());
            return arms.get(idx).destinationId;
        }

        // Exploit arm with highest empirical conversion rate
        DestinationArm bestArm = arms.get(0);
        double highestRate = -1.0;

        for (DestinationArm arm : arms) {
            double rate = arm.getEmpiricalSuccessRate();
            if (rate > highestRate) {
                highestRate = rate;
                bestArm = arm;
            }
        }

        return bestArm.destinationId;
    }

    /**
     * Records a conversion callback event (conversion success or bounce/failure) to update the variant's posterior.
     */
    public void recordConversion(String routeKey, String destinationId, boolean converted, double latencyMs) {
        recordOutcome(routeKey, destinationId, converted, latencyMs);
    }

    /**
     * Records routing feedback (conversion or trial failure) to update the variant's posterior.
     */
    public void recordOutcome(String routeKey, String destinationId, boolean success, double latencyMs) {
        Map<String, DestinationArm> arms = routes.get(routeKey);
        if (arms == null) return;

        DestinationArm arm = arms.get(destinationId);
        if (arm == null) return;

        arm.recordOutcome(success, latencyMs);
        log.debug("Recorded conversion feedback for campaign '{}', variant '{}': converted={}, alpha={:.1f}, beta={:.1f}",
            routeKey, destinationId, success, arm.getAlpha(), arm.getBeta());
    }

    /**
     * Returns statistical observability data for all variants of a given campaign.
     */
    public List<DestinationStats> getRouteStats(String routeKey) {
        Map<String, DestinationArm> arms = routes.get(routeKey);
        if (arms == null) return Collections.emptyList();

        return arms.values().stream()
            .map(arm -> new DestinationStats(
                arm.destinationId,
                arm.targetUrl,
                arm.successes.get(),
                arm.failures.get(),
                arm.getTotalTrials(),
                arm.getEmpiricalSuccessRate(),
                arm.getMeanLatencyMs(),
                arm.getAlpha(),
                arm.getBeta()
            ))
            .toList();
    }

    public Map<String, List<DestinationStats>> getAllStats() {
        Map<String, List<DestinationStats>> result = new LinkedHashMap<>();
        for (String routeKey : routes.keySet()) {
            result.put(routeKey, getRouteStats(routeKey));
        }
        return result;
    }

    /**
     * Generates a sample from Beta(alpha, beta) using Marsaglia & Tsang's Gamma generator:
     * If X ~ Gamma(alpha, 1) and Y ~ Gamma(beta, 1), then X / (X + Y) ~ Beta(alpha, beta).
     */
    public static double sampleBeta(double alpha, double beta) {
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        double sum = x + y;
        if (sum <= 0.0) return 0.5;
        return x / sum;
    }

    /**
     * Generates a Gamma(k, 1) variate using Marsaglia & Tsang (2000) method.
     */
    public static double sampleGamma(double k) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        if (k < 1.0) {
            double u = rand.nextDouble();
            return sampleGamma(k + 1.0) * Math.pow(u, 1.0 / k);
        }

        double d = k - (1.0 / 3.0);
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double z = rand.nextGaussian();
            double v = 1.0 + (c * z);
            if (v <= 0.0) continue;

            v = v * v * v;
            double u = rand.nextDouble();

            // Squeeze test
            if (u < 1.0 - (0.0331 * z * z * z * z)) {
                return d * v;
            }
            if (Math.log(u) < (0.5 * z * z) + d - (d * v) + (d * Math.log(v))) {
                return d * v;
            }
        }
    }

    private static class DestinationArm {
        final String destinationId;
        final String targetUrl;
        final AtomicLong successes = new AtomicLong(0);
        final AtomicLong failures = new AtomicLong(0);
        final DoubleAdder totalLatency = new DoubleAdder();

        DestinationArm(String destinationId, String targetUrl) {
            this.destinationId = destinationId;
            this.targetUrl = targetUrl;
        }

        void recordOutcome(boolean success, double latencyMs) {
            if (success) {
                successes.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
            totalLatency.add(Math.max(0.0, latencyMs));
        }

        long getTotalTrials() {
            return successes.get() + failures.get();
        }

        double getEmpiricalSuccessRate() {
            long total = getTotalTrials();
            if (total == 0) return 0.5; // Neutral prior
            return (double) successes.get() / total;
        }

        double getMeanLatencyMs() {
            long total = getTotalTrials();
            if (total == 0) return 0.0;
            return totalLatency.sum() / total;
        }

        double getAlpha() {
            return 1.0 + successes.get();
        }

        double getBeta() {
            return 1.0 + failures.get();
        }
    }
}
