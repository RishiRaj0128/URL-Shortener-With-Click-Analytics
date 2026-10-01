package com.urlshortener.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intelligent Traffic Routing Layer for Multi-Instance Deployments.
 * 
 * Features:
 * - Dynamic Node Health Tracking: Actively health-checks downstream nodes.
 * - Gradual Failover: Sheds traffic in stages (100% -> 50% -> 0%) during degradation
 *   rather than a binary cliff edge.
 * - Slow-Start Warm-up Ramp: Restores recovered nodes through progressive traffic allocations
 *   (10% -> 25% -> 50% -> 100%) to protect cold JVMs from the thundering-herd effect.
 * - EWMA-Weighted Selection: Prioritizes nodes with low latency and low error rates via
 *   EwmaNodeScorer.
 */
@Component
public class TrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(TrafficRouter.class);

    public enum RoutingMode {
        ROUND_ROBIN,
        EWMA_WEIGHTED
    }

    public record NodeInstance(
        String nodeId,
        String endpoint,
        boolean isHealthy,
        int consecutiveFailures,
        long totalRoutedRequests
    ) {}

    public record NodeDetailedStatus(
        String nodeId,
        String endpoint,
        boolean isHealthy,
        int consecutiveFailures,
        long totalRoutedRequests,
        double weightModifier,
        int warmupStep,
        double latencyEwma,
        double errorRateEwma,
        double score
    ) {}

    private final Map<String, NodeState> clusterNodes = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final EwmaNodeScorer ewmaNodeScorer;
    private volatile RoutingMode routingMode = RoutingMode.ROUND_ROBIN;

    private static final int MAX_CONSECUTIVE_FAILURES = 2;

    public TrafficRouter() {
        this(new EwmaNodeScorer(), true);
    }

    public TrafficRouter(boolean initDefaults) {
        this(new EwmaNodeScorer(), initDefaults);
    }

    @Autowired
    public TrafficRouter(
            EwmaNodeScorer ewmaNodeScorer,
            @org.springframework.beans.factory.annotation.Value("${app.routing.mode:round_robin}") String routingModeStr
    ) {
        this(ewmaNodeScorer, true);
        setRoutingModeFromString(routingModeStr);
    }

    public TrafficRouter(EwmaNodeScorer ewmaNodeScorer, boolean initDefaults) {
        this.ewmaNodeScorer = ewmaNodeScorer != null ? ewmaNodeScorer : new EwmaNodeScorer();
        if (initDefaults) {
            registerNode("node-primary", "http://localhost:8080");
            registerNode("node-replica", "http://localhost:8081");
        }
    }

    public void setRoutingModeFromString(String mode) {
        if ("ewma_weighted".equalsIgnoreCase(mode) || "ewma".equalsIgnoreCase(mode)) {
            this.routingMode = RoutingMode.EWMA_WEIGHTED;
        } else {
            this.routingMode = RoutingMode.ROUND_ROBIN;
        }
    }

    public synchronized void clearNodes() {
        clusterNodes.clear();
        ewmaNodeScorer.clear();
    }

    public synchronized void registerNode(String nodeId, String endpoint) {
        clusterNodes.put(nodeId, new NodeState(nodeId, endpoint));
        ewmaNodeScorer.registerNode(nodeId);
    }

    public void setRoutingMode(RoutingMode mode) {
        this.routingMode = mode;
        log.info("TrafficRouter routing mode updated to: {}", mode);
    }

    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    public EwmaNodeScorer getEwmaNodeScorer() {
        return ewmaNodeScorer;
    }

    /**
     * Intelligently selects the next healthy node using either EWMA-weighted
     * selection (with gradual failover / slow-start ramp) or round-robin failover.
     * Throws IllegalStateException if all nodes are unhealthy.
     */
    public String routeRequest(String path) {
        List<NodeState> healthyNodes = clusterNodes.values().stream()
            .filter(NodeState::isHealthy)
            .toList();

        if (healthyNodes.isEmpty()) {
            log.error("All backend nodes are unhealthy! Outage imminent.");
            throw new IllegalStateException("No healthy upstream nodes available to route traffic");
        }

        NodeState selectedNode;

        if (routingMode == RoutingMode.EWMA_WEIGHTED) {
            List<String> candidateIds = healthyNodes.stream().map(n -> n.nodeId).toList();
            Map<String, Double> weightModifiers = new HashMap<>();
            for (NodeState n : healthyNodes) {
                weightModifiers.put(n.nodeId, n.getEffectiveWeightModifier());
            }

            Optional<String> chosenId = ewmaNodeScorer.selectNodeWeighted(candidateIds, weightModifiers);
            selectedNode = chosenId.flatMap(id -> Optional.ofNullable(clusterNodes.get(id)))
                .orElse(healthyNodes.get(0));
        } else {
            int index = Math.abs(roundRobinCounter.getAndIncrement() % healthyNodes.size());
            selectedNode = healthyNodes.get(index);
        }

        selectedNode.totalRoutedRequests.incrementAndGet();
        selectedNode.onRouted();

        log.debug("Routed path '{}' to node '{}' ({}) [weightModifier={:.2f}]",
            path, selectedNode.nodeId, selectedNode.endpoint, selectedNode.getEffectiveWeightModifier());

        return selectedNode.endpoint + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Records an observation of latency and error status from an actual request execution.
     */
    public void recordRequestOutcome(String nodeId, double latencyMs, boolean isError) {
        ewmaNodeScorer.recordObservation(nodeId, latencyMs, isError);
    }

    /**
     * Records a health check probe result for a specific node.
     * Handles gradual decay on failure and slow-start warm-up ramp on recovery.
     */
    public synchronized boolean recordHealthProbe(String nodeId, boolean probeSuccess) {
        NodeState node = clusterNodes.get(nodeId);
        if (node == null) return false;

        if (probeSuccess) {
            if (!node.healthy) {
                log.info("[TRAFFIC ROUTER] Node '{}' recovered! Entering slow-start warm-up ramp.", nodeId);
                node.healthy = true;
                node.consecutiveFailures.set(0);
                node.warmupStep.set(1); // 10% initial weight cap
            } else {
                node.consecutiveFailures.set(0);
                node.advanceWarmupOnProbe();
            }
        } else {
            int failures = node.consecutiveFailures.incrementAndGet();
            if (failures >= MAX_CONSECUTIVE_FAILURES && node.healthy) {
                node.healthy = false;
                node.warmupStep.set(0);
                log.warn("[TRAFFIC ROUTER] Node '{}' failed {} consecutive health checks! Rerouting traffic away.",
                        nodeId, failures);
            } else {
                log.warn("[TRAFFIC ROUTER] Node '{}' experienced probe degradation (failure count: {}). Traffic share decayed.",
                        nodeId, failures);
            }
        }
        return node.healthy;
    }

    public double getEffectiveWeightModifier(String nodeId) {
        NodeState node = clusterNodes.get(nodeId);
        return node != null ? node.getEffectiveWeightModifier() : 0.0;
    }

    public int getWarmupStep(String nodeId) {
        NodeState node = clusterNodes.get(nodeId);
        return node != null ? node.warmupStep.get() : 0;
    }

    /**
     * Returns cluster nodes snapshot for dashboard visualization.
     */
    public List<NodeInstance> getClusterStatus() {
        return clusterNodes.values().stream()
            .map(n -> new NodeInstance(
                n.nodeId,
                n.endpoint,
                n.healthy,
                n.consecutiveFailures.get(),
                n.totalRoutedRequests.get()
            ))
            .toList();
    }

    /**
     * Returns detailed cluster metrics including EWMA latency, error rate, scores,
     * and warm-up progression.
     */
    public List<NodeDetailedStatus> getDetailedClusterStatus() {
        return clusterNodes.values().stream()
            .map(n -> {
                EwmaNodeScorer.NodeMetricsSnapshot metrics = ewmaNodeScorer.getMetricsSnapshot(n.nodeId);
                return new NodeDetailedStatus(
                    n.nodeId,
                    n.endpoint,
                    n.healthy,
                    n.consecutiveFailures.get(),
                    n.totalRoutedRequests.get(),
                    n.getEffectiveWeightModifier(),
                    n.warmupStep.get(),
                    metrics.latencyEwma(),
                    metrics.errorRateEwma(),
                    metrics.score()
                );
            })
            .toList();
    }

    public void setNodeHealthManually(String nodeId, boolean healthy) {
        NodeState node = clusterNodes.get(nodeId);
        if (node != null) {
            node.healthy = healthy;
            if (healthy) {
                node.consecutiveFailures.set(0);
                node.warmupStep.set(4); // Fully warmed up when set manually
            } else {
                node.consecutiveFailures.set(MAX_CONSECUTIVE_FAILURES);
                node.warmupStep.set(0);
            }
        }
    }

    private static class NodeState {
        final String nodeId;
        final String endpoint;
        volatile boolean healthy = true;
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicLong totalRoutedRequests = new AtomicLong(0);

        // Slow-start warm-up ramp steps:
        // 0 = Unhealthy / 0%
        // 1 = 10% weight
        // 2 = 25% weight
        // 3 = 50% weight
        // 4 = 100% weight (fully warmed up)
        final AtomicInteger warmupStep = new AtomicInteger(4);
        final AtomicInteger warmupRequestCount = new AtomicInteger(0);

        NodeState(String nodeId, String endpoint) {
            this.nodeId = nodeId;
            this.endpoint = endpoint;
        }

        boolean isHealthy() {
            return healthy;
        }

        double getEffectiveWeightModifier() {
            if (!healthy) return 0.0;

            // Decay schedule on partial failure / degradation
            if (consecutiveFailures.get() == 1) {
                return 0.50; // Shed 50% traffic during initial degradation
            }

            // Slow-start warm-up ramp
            int step = warmupStep.get();
            return switch (step) {
                case 1 -> 0.10; // 10%
                case 2 -> 0.25; // 25%
                case 3 -> 0.50; // 50%
                default -> 1.00; // 100%
            };
        }

        void advanceWarmupOnProbe() {
            int current = warmupStep.get();
            if (current > 0 && current < 4) {
                warmupStep.incrementAndGet();
            }
        }

        void onRouted() {
            int current = warmupStep.get();
            if (current > 0 && current < 4) {
                if (warmupRequestCount.incrementAndGet() >= 10) {
                    warmupRequestCount.set(0);
                    warmupStep.incrementAndGet();
                }
            }
        }
    }
}
