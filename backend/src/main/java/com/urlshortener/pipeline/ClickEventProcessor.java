package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.entity.ProcessedEvent;
import com.urlshortener.repository.ClickEventDedupRepository;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.repository.ProcessedEventRepository;
import com.urlshortener.service.AnomalyDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared transactional processor for ClickEvents across both In-Memory and Redis Stream consumers.
 * 
 * Enforces:
 * 1. Multi-tier Durable Deduplication (Redis SET NX + durable database unique constraint).
 * 2. Anomaly Detection (Velocity tripwire & Z-score).
 * 3. Atomic time-bucketed rollup aggregation.
 */
@Service
public class ClickEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProcessor.class);
    private static final Duration REDIS_DEDUP_TTL = Duration.ofHours(24);

    private final ClickRollupRepository rollupRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ClickEventDedupRepository legacyDedupRepository;
    private final AnomalyDetector anomalyDetector;
    private final StringRedisTemplate redisTemplate;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong duplicateCount = new AtomicLong(0);
    private final AtomicLong botEventCount = new AtomicLong(0);

    @Autowired
    public ClickEventProcessor(
            ClickRollupRepository rollupRepository,
            @Autowired(required = false) ProcessedEventRepository processedEventRepository,
            @Autowired(required = false) ClickEventDedupRepository legacyDedupRepository,
            @Autowired(required = false) AnomalyDetector anomalyDetector,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.rollupRepository = rollupRepository;
        this.processedEventRepository = processedEventRepository;
        this.legacyDedupRepository = legacyDedupRepository;
        this.anomalyDetector = anomalyDetector;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically process an event within a database transaction.
     * Returns true if event was newly processed, false if duplicate/dropped.
     */
    @Transactional
    public boolean process(ClickEvent event, String consumerId) {
        if (event == null || event.eventId() == null) {
            return false;
        }

        String eventId = event.eventId();

        // Tier 1: Fast Redis SET NX Deduplication (if Redis is present)
        if (redisTemplate != null) {
            try {
                Boolean wasNew = redisTemplate.opsForValue().setIfAbsent(
                    "dedup:event:" + eventId,
                    consumerId != null ? consumerId : "worker",
                    REDIS_DEDUP_TTL
                );
                if (Boolean.FALSE.equals(wasNew)) {
                    duplicateCount.incrementAndGet();
                    log.debug("Redis Dedup: Duplicate event {} dropped.", eventId);
                    return false;
                }
            } catch (Exception e) {
                log.warn("Redis dedup check failed, falling back to database: {}", e.getMessage());
            }
        }

        // Tier 2: Durable Database Unique Constraint Deduplication
        if (processedEventRepository != null) {
            if (processedEventRepository.existsByEventId(eventId)) {
                duplicateCount.incrementAndGet();
                log.debug("DB Dedup: Event {} already in processed_events, dropping.", eventId);
                return false;
            }
            try {
                processedEventRepository.save(new ProcessedEvent(eventId, event.shortCode(), consumerId));
            } catch (Exception e) {
                duplicateCount.incrementAndGet();
                log.debug("DB Dedup constraint tripped for event {}: {}", eventId, e.getMessage());
                return false;
            }
        } else if (legacyDedupRepository != null) {
            if (legacyDedupRepository.existsById(eventId)) {
                duplicateCount.incrementAndGet();
                return false;
            }
        }

        // Stage 2: Anomaly Evaluation (Velocity Heuristic)
        AnomalyDetector.AnomalyResult velocityCheck = anomalyDetector != null
            ? anomalyDetector.evaluateClickVelocity(event)
            : new AnomalyDetector.AnomalyResult(false, null, null, 0.0);

        boolean isBot = velocityCheck.isAnomaly();
        if (isBot) {
            botEventCount.incrementAndGet();
        }

        // Stage 3: Aggregate into 1-minute Rollup Bucket
        LocalDateTime eventTime = event.timestamp() != null ? event.timestamp() : LocalDateTime.now();
        LocalDateTime windowStart = eventTime.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime windowEnd = windowStart.plusMinutes(1);

        if (rollupRepository != null) {
            ClickRollup rollup = rollupRepository.findByShortCodeAndWindowStart(event.shortCode(), windowStart)
                .orElseGet(() -> new ClickRollup(event.shortCode(), windowStart, windowEnd));

            if (isBot) {
                rollup.incrementBotClicks();
            } else {
                rollup.incrementLegitimateClicks();
            }

            // Stage 4: Statistical Z-Score Spike Check on aggregated volume
            if (anomalyDetector != null && !isBot) {
                AnomalyDetector.AnomalyResult zScoreCheck = anomalyDetector.evaluateBucketZScore(
                    event.shortCode(),
                    rollup.getLegitimateClicks()
                );
                if (zScoreCheck.isAnomaly()) {
                    rollup.setIsAnomaly(true);
                    rollup.setAnomalyReason(zScoreCheck.details());
                    rollup.setZScore(zScoreCheck.zScore());
                }
            }

            rollupRepository.save(rollup);
        }

        processedCount.incrementAndGet();
        return true;
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getDuplicateCount() {
        return duplicateCount.get();
    }

    public long getBotEventCount() {
        return botEventCount.get();
    }
}
