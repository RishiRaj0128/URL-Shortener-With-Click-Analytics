package com.urlshortener.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global Chaos Engineering State.
 * 
 * Manages controlled, deterministic failure injection for drills and demonstrations:
 * - Artificial Redis latency injection
 * - Simulated Redis outage
 * - Consumer thread suspension
 * - Automatic fault expiration timers
 */
@Component
public class ChaosState {

    private static final Logger log = LoggerFactory.getLogger(ChaosState.class);

    private final AtomicBoolean redisSlow = new AtomicBoolean(false);
    private final AtomicLong redisLatencyMs = new AtomicLong(0);

    private final AtomicBoolean redisDown = new AtomicBoolean(false);
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chaos-expiration-daemon");
        t.setDaemon(true);
        return t;
    });

    public void injectRedisLatency(long latencyMs, int durationSeconds) {
        redisLatencyMs.set(latencyMs);
        redisSlow.set(true);
        log.warn("[CHAOS] Injected artificial Redis latency of {}ms for {}s", latencyMs, durationSeconds);

        if (durationSeconds > 0) {
            scheduler.schedule(() -> {
                redisSlow.set(false);
                redisLatencyMs.set(0);
                log.info("[CHAOS] Redis latency fault expired.");
            }, durationSeconds, TimeUnit.SECONDS);
        }
    }

    public void injectRedisOutage(int durationSeconds) {
        redisDown.set(true);
        log.warn("[CHAOS] Injected simulated Redis outage for {}s", durationSeconds);

        if (durationSeconds > 0) {
            scheduler.schedule(() -> {
                redisDown.set(false);
                log.info("[CHAOS] Redis outage fault expired.");
            }, durationSeconds, TimeUnit.SECONDS);
        }
    }

    public void setConsumerPaused(boolean paused) {
        consumerPaused.set(paused);
        log.warn("[CHAOS] Consumer paused state set to: {}", paused);
    }

    public void reset() {
        redisSlow.set(false);
        redisLatencyMs.set(0);
        redisDown.set(false);
        consumerPaused.set(false);
        log.info("[CHAOS] All active chaos faults have been reset.");
    }

    public boolean isRedisSlow() {
        return redisSlow.get();
    }

    public long getRedisLatencyMs() {
        return redisLatencyMs.get();
    }

    public boolean isRedisDown() {
        return redisDown.get();
    }

    public boolean isConsumerPaused() {
        return consumerPaused.get();
    }

    public record ChaosStatus(
        boolean redisSlow,
        long redisLatencyMs,
        boolean redisDown,
        boolean consumerPaused
    ) {}

    public ChaosStatus getStatus() {
        return new ChaosStatus(
            redisSlow.get(),
            redisLatencyMs.get(),
            redisDown.get(),
            consumerPaused.get()
        );
    }
}
