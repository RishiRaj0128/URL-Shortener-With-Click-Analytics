package com.urlshortener.controller;

import com.urlshortener.resilience.ChaosState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Chaos Engineering Controller for Resilience and Self-Healing Drills.
 * 
 * Exposes live fault-injection controls mirroring payments engineering chaos testing:
 * - Artificial Redis latency spikes
 * - Simulated Redis connection partitions
 * - Asynchronous consumer worker killing
 * - Automatic fault expiration and reset
 */
@RestController
@RequestMapping("/api/chaos")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChaosController {

    private final ChaosState chaosState;

    public ChaosController(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @GetMapping("/status")
    public ResponseEntity<ChaosState.ChaosStatus> getStatus() {
        return ResponseEntity.ok(chaosState.getStatus());
    }

    @PostMapping("/redis/slow")
    public ResponseEntity<Map<String, Object>> injectRedisSlow(
            @RequestParam(defaultValue = "500") long latencyMs,
            @RequestParam(defaultValue = "15") int durationSeconds) {
        chaosState.injectRedisLatency(latencyMs, durationSeconds);
        return ResponseEntity.ok(Map.of(
            "message", "Injected artificial Redis latency",
            "latencyMs", latencyMs,
            "durationSeconds", durationSeconds
        ));
    }

    @PostMapping("/redis/fail")
    public ResponseEntity<Map<String, Object>> injectRedisFail(
            @RequestParam(defaultValue = "15") int durationSeconds) {
        chaosState.injectRedisOutage(durationSeconds);
        return ResponseEntity.ok(Map.of(
            "message", "Injected simulated Redis outage",
            "durationSeconds", durationSeconds
        ));
    }

    @PostMapping("/consumer/kill")
    public ResponseEntity<Map<String, String>> killConsumer() {
        chaosState.setConsumerPaused(true);
        return ResponseEntity.ok(Map.of(
            "message", "Click pipeline consumer paused (simulated worker crash)"
        ));
    }

    @PostMapping("/consumer/restart")
    public ResponseEntity<Map<String, String>> restartConsumer() {
        chaosState.setConsumerPaused(false);
        return ResponseEntity.ok(Map.of(
            "message", "Click pipeline consumer resumed (simulated worker recovery)"
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetChaos() {
        chaosState.reset();
        return ResponseEntity.ok(Map.of(
            "message", "All chaos faults cleared. System restored to normal state."
        ));
    }
}
