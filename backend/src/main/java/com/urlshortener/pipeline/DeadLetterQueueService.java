package com.urlshortener.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.DeadLetterEvent;
import com.urlshortener.repository.DeadLetterEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dead Letter Queue (DLQ) Management Service.
 * 
 * Intercepts poisoned, malformed, or repeatedly failed click events:
 * 1. Prevents consumer group deadlocks and PEL head-of-line blocking.
 * 2. Writes to durable PostgreSQL `dead_letter_events` table and Redis Stream `clicks:dlq`.
 * 3. Supports manual/automated replay into the active ingestion channel.
 */
@Service
public class DeadLetterQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueService.class);
    public static final String DLQ_STREAM_KEY = "clicks:dlq";

    private final DeadLetterEventRepository dlqRepository;
    private final StringRedisTemplate redisTemplate;
    private final ClickEventChannel clickEventChannel;
    private final ObjectMapper objectMapper;

    private final AtomicLong dlqEnqueuedCount = new AtomicLong(0);
    private final AtomicLong dlqReplayedCount = new AtomicLong(0);

    @Autowired
    public DeadLetterQueueService(
            DeadLetterEventRepository dlqRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ClickEventChannel clickEventChannel,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.dlqRepository = dlqRepository;
        this.redisTemplate = redisTemplate;
        this.clickEventChannel = clickEventChannel;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Routes a failed/poisoned event to the Dead Letter Queue.
     */
    @Transactional
    public DeadLetterEvent sendToDlq(String eventId, String shortCode, String rawPayload, String failureReason, int retryCount) {
        log.warn("[DLQ] Routing poisoned event to DLQ: eventId={}, shortCode={}, retries={}, reason={}",
            eventId, shortCode, retryCount, failureReason);

        DeadLetterEvent dlqEntry = new DeadLetterEvent(
            eventId != null ? eventId : "malformed_" + UUID.randomUUID(),
            shortCode != null ? shortCode : "UNKNOWN",
            rawPayload != null ? rawPayload : "{}",
            failureReason != null ? failureReason : "Unknown error",
            retryCount
        );

        DeadLetterEvent saved = dlqRepository.save(dlqEntry);
        dlqEnqueuedCount.incrementAndGet();

        // Write to Redis Stream clicks:dlq if Redis is active
        if (redisTemplate != null) {
            try {
                Map<String, String> streamFields = new HashMap<>();
                streamFields.put("dlq_id", String.valueOf(saved.getId()));
                streamFields.put("event_id", saved.getEventId());
                streamFields.put("short_code", saved.getShortCode());
                streamFields.put("failure_reason", saved.getFailureReason());
                streamFields.put("payload", saved.getRawPayload());
                streamFields.put("retry_count", String.valueOf(saved.getRetryCount()));

                RecordId recordId = redisTemplate.opsForStream().add(
                    StreamRecords.string(streamFields).withStreamKey(DLQ_STREAM_KEY)
                );
                log.debug("[DLQ] Published DLQ event {} to Redis stream {}: {}", saved.getId(), DLQ_STREAM_KEY, recordId);
            } catch (Exception e) {
                log.warn("[DLQ] Failed to publish event {} to Redis stream (DB record is safe): {}", saved.getId(), e.getMessage());
            }
        }

        return saved;
    }

    /**
     * Replays un-replayed DLQ events back into the active processing pipeline.
     */
    @Transactional
    public ReplaySummary replayPendingDlqEvents() {
        List<DeadLetterEvent> pending = dlqRepository.findByReplayedFalseOrderByCreatedAtAsc();
        int successfullyRequeued = 0;
        int failedReplays = 0;

        for (DeadLetterEvent entry : pending) {
            try {
                ClickEvent event = parseClickEvent(entry);
                if (event != null && clickEventChannel != null) {
                    boolean enqueued = clickEventChannel.publish(event);
                    if (enqueued) {
                        entry.setReplayed(true);
                        entry.setReplayedAt(LocalDateTime.now());
                        dlqRepository.save(entry);
                        successfullyRequeued++;
                        dlqReplayedCount.incrementAndGet();
                    } else {
                        failedReplays++;
                    }
                } else {
                    failedReplays++;
                }
            } catch (Exception e) {
                log.error("[DLQ] Failed to replay DLQ event {}: {}", entry.getId(), e.getMessage());
                failedReplays++;
            }
        }

        log.info("[DLQ] Replay finished: {} requeued, {} failed, {} total evaluated.",
            successfullyRequeued, failedReplays, pending.size());

        return new ReplaySummary(pending.size(), successfullyRequeued, failedReplays);
    }

    public List<DeadLetterEvent> getRecentEvents() {
        return dlqRepository.findTop50ByOrderByCreatedAtDesc();
    }

    public long getUnreplayedCount() {
        return dlqRepository.countByReplayedFalse();
    }

    public long getDlqEnqueuedCount() {
        return dlqEnqueuedCount.get();
    }

    public long getDlqReplayedCount() {
        return dlqReplayedCount.get();
    }

    private ClickEvent parseClickEvent(DeadLetterEvent entry) {
        try {
            return objectMapper.readValue(entry.getRawPayload(), ClickEvent.class);
        } catch (Exception e) {
            // Reconstruct minimal fallback ClickEvent
            return new ClickEvent(
                entry.getEventId(),
                entry.getShortCode(),
                null,
                LocalDateTime.now(),
                "127.0.0.1",
                "Replayed-DLQ-Agent",
                "https://juspay.in/dlq-replay"
            );
        }
    }

    public record ReplaySummary(
        int totalEvaluated,
        int successfullyRequeued,
        int failed
    ) {}
}
