package com.urlshortener.controller;

import com.urlshortener.config.CircuitBreakerConfiguration;
import com.urlshortener.entity.ClickAnomalyLog;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.repository.ClickAnomalyLogRepository;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.routing.TrafficRouter;
import com.urlshortener.routing.BanditRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller exposing real-time system intelligence, circuit breaker health,
 * traffic routing topology, and anomaly audit streams for the aesthetic dashboard.
 */
@RestController
@RequestMapping("/api/system")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SystemIntelligenceController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TrafficRouter trafficRouter;
    private final BanditRouter banditRouter;
    private final ClickAnomalyLogRepository anomalyLogRepository;
    private final ClickRollupRepository rollupRepository;
    private final com.urlshortener.pipeline.DeadLetterQueueService dlqService;
    private final com.urlshortener.resilience.CacheWarmingListener cacheWarmingListener;

    public SystemIntelligenceController(
            CircuitBreakerRegistry circuitBreakerRegistry,
            TrafficRouter trafficRouter,
            BanditRouter banditRouter,
            ClickAnomalyLogRepository anomalyLogRepository,
            ClickRollupRepository rollupRepository,
            @Autowired(required = false) com.urlshortener.pipeline.DeadLetterQueueService dlqService,
            @Autowired(required = false) com.urlshortener.resilience.CacheWarmingListener cacheWarmingListener) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.trafficRouter = trafficRouter;
        this.banditRouter = banditRouter;
        this.anomalyLogRepository = anomalyLogRepository;
        this.rollupRepository = rollupRepository;
        this.dlqService = dlqService;
        this.cacheWarmingListener = cacheWarmingListener;
    }

    @GetMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> getCircuitBreakers() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", cb.getState().name());
            details.put("failureRate", cb.getMetrics().getFailureRate());
            details.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            details.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
            details.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
            result.put(cb.getName(), details);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/routing")
    public ResponseEntity<List<TrafficRouter.NodeInstance>> getClusterRouting() {
        return ResponseEntity.ok(trafficRouter.getClusterStatus());
    }

    @GetMapping("/routing/detailed")
    public ResponseEntity<List<TrafficRouter.NodeDetailedStatus>> getDetailedClusterRouting() {
        return ResponseEntity.ok(trafficRouter.getDetailedClusterStatus());
    }

    @GetMapping("/routing/bandit")
    public ResponseEntity<Map<String, List<BanditRouter.DestinationStats>>> getBanditStats() {
        return ResponseEntity.ok(banditRouter.getAllStats());
    }

    @GetMapping("/routing/bandit/{routeKey}")
    public ResponseEntity<List<BanditRouter.DestinationStats>> getBanditRouteStats(@PathVariable String routeKey) {
        return ResponseEntity.ok(banditRouter.getRouteStats(routeKey));
    }

    @GetMapping("/anomalies")
    public ResponseEntity<List<ClickAnomalyLog>> getRecentAnomalies() {
        return ResponseEntity.ok(anomalyLogRepository.findTop50ByOrderByDetectedAtDesc());
    }

    @GetMapping("/rollups")
    public ResponseEntity<List<ClickRollup>> getAnomalousRollups() {
        return ResponseEntity.ok(rollupRepository.findAnomalousRollups());
    }

    @GetMapping("/rollups/{shortCode}")
    public ResponseEntity<List<ClickRollup>> getRollupsForCode(@PathVariable String shortCode) {
        return ResponseEntity.ok(rollupRepository.findRecentRollups(shortCode, java.time.LocalDateTime.now().minusHours(24)));
    }

    @GetMapping("/dlq")
    public ResponseEntity<Map<String, Object>> getDlqEvents() {
        if (dlqService == null) {
            return ResponseEntity.ok(Map.of("events", List.of(), "unreplayedCount", 0));
        }
        return ResponseEntity.ok(Map.of(
            "events", dlqService.getRecentEvents(),
            "unreplayedCount", dlqService.getUnreplayedCount()
        ));
    }

    @PostMapping("/dlq/replay")
    public ResponseEntity<com.urlshortener.pipeline.DeadLetterQueueService.ReplaySummary> replayDlq() {
        if (dlqService == null) {
            return ResponseEntity.ok(new com.urlshortener.pipeline.DeadLetterQueueService.ReplaySummary(0, 0, 0));
        }
        return ResponseEntity.ok(dlqService.replayPendingDlqEvents());
    }

    @GetMapping("/cache-warmer")
    public ResponseEntity<com.urlshortener.resilience.CacheWarmingListener.CacheWarmerStatus> getCacheWarmerStatus() {
        if (cacheWarmingListener == null) {
            return ResponseEntity.ok(new com.urlshortener.resilience.CacheWarmingListener.CacheWarmerStatus(0, 0, null, "none"));
        }
        return ResponseEntity.ok(cacheWarmingListener.getStatus());
    }

    @PostMapping("/cache-warmer/warm")
    public ResponseEntity<Map<String, Object>> warmCache(@RequestParam(defaultValue = "50") int limit) {
        if (cacheWarmingListener == null) {
            return ResponseEntity.ok(Map.of("warmedCount", 0));
        }
        int count = cacheWarmingListener.warmCache(limit);
        return ResponseEntity.ok(Map.of("warmedCount", count));
    }
}
