package com.urlshortener.pipeline;

import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.ClickEventDedup;
import com.urlshortener.entity.ClickRollup;
import com.urlshortener.repository.ClickEventDedupRepository;
import com.urlshortener.repository.ClickRollupRepository;
import com.urlshortener.service.AnomalyDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 3: Async Click Ingestion Pipeline & Idempotency Tests")
class ClickIngestionPipelineTest {

    @Mock
    private ClickRollupRepository rollupRepository;

    @Mock
    private ClickEventDedupRepository dedupRepository;

    @Mock
    private AnomalyDetector anomalyDetector;

    private ClickEventChannel channel;
    private ClickEventConsumer consumer;

    @BeforeEach
    void setUp() {
        channel = new InMemoryClickEventChannel(1000);
        consumer = new ClickEventConsumer(channel, rollupRepository, dedupRepository, anomalyDetector);
    }

    @Test
    @DisplayName("Channel Decoupling: Non-blocking publish takes < 1ms and preserves event integrity")
    void shouldPublishNonBlockingly() {
        ClickEvent event = new ClickEvent(
            "evt_test_001",
            "juspay123",
            10L,
            LocalDateTime.now(),
            "127.0.0.1",
            "Mozilla/5.0",
            "https://google.com"
        );

        long start = System.nanoTime();
        boolean published = channel.publish(event);
        long elapsedMicros = (System.nanoTime() - start) / 1000;

        assertTrue(published);
        assertEquals(1, channel.size());
        assertTrue(elapsedMicros < 1000, "Publish to in-memory channel must complete in < 1ms, took: " + elapsedMicros + "us");
    }

    @Test
    @DisplayName("Rollup Aggregation: First event creates a 1-minute rollup bucket and increments legitimate count")
    void shouldAggregateEventIntoRollupBucket() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.truncatedTo(ChronoUnit.MINUTES);
        String shortCode = "payNow789";

        ClickEvent event = new ClickEvent(
            "evt_agg_001",
            shortCode,
            5L,
            now,
            "10.0.0.1",
            "Safari/17.0",
            null
        );

        when(dedupRepository.existsById("evt_agg_001")).thenReturn(false);
        when(rollupRepository.findByShortCodeAndWindowStart(eq(shortCode), eq(windowStart)))
            .thenReturn(Optional.empty());
        when(anomalyDetector.evaluateClickVelocity(event))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));
        when(anomalyDetector.evaluateBucketZScore(eq(shortCode), eq(1L)))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));

        boolean processed = consumer.processEvent(event);

        assertTrue(processed);
        assertEquals(1, consumer.getProcessedCount());

        ArgumentCaptor<ClickRollup> rollupCaptor = ArgumentCaptor.forClass(ClickRollup.class);
        verify(rollupRepository, times(1)).save(rollupCaptor.capture());

        ClickRollup savedRollup = rollupCaptor.getValue();
        assertEquals(shortCode, savedRollup.getShortCode());
        assertEquals(windowStart, savedRollup.getWindowStart());
        assertEquals(1L, savedRollup.getLegitimateClicks());
        assertEquals(0L, savedRollup.getBotClicks());
        assertFalse(savedRollup.getIsAnomaly());
    }

    @Test
    @DisplayName("Idempotent Consumption: At-least-once queue redelivery must drop duplicate event without double-counting")
    void shouldDeduplicateIdenticalEventId() {
        LocalDateTime now = LocalDateTime.now();
        String eventId = "evt_dedup_unique_42";
        String shortCode = "dedupTest";

        ClickEvent event = new ClickEvent(
            eventId,
            shortCode,
            8L,
            now,
            "192.168.1.1",
            "Chrome/120.0",
            null
        );

        when(dedupRepository.existsById(eventId)).thenReturn(false);
        when(rollupRepository.findByShortCodeAndWindowStart(eq(shortCode), any()))
            .thenReturn(Optional.of(new ClickRollup(shortCode, now.truncatedTo(ChronoUnit.MINUTES), now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1))));
        when(anomalyDetector.evaluateClickVelocity(event))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));
        when(anomalyDetector.evaluateBucketZScore(any(), anyLong()))
            .thenReturn(new AnomalyDetector.AnomalyResult(false, null, null, 0.0));

        // First delivery: should process normally
        boolean firstProcessed = consumer.processEvent(event);
        assertTrue(firstProcessed, "Initial event processing must succeed");
        assertEquals(1, consumer.getProcessedCount());
        assertEquals(0, consumer.getDuplicateCount());

        // Second delivery (simulated at-least-once redelivery): should be detected as duplicate
        boolean secondProcessed = consumer.processEvent(event);
        assertFalse(secondProcessed, "Duplicate event must be dropped by idempotency check");
        assertEquals(1, consumer.getProcessedCount(), "Processed count must not increase on duplicate");
        assertEquals(1, consumer.getDuplicateCount(), "Duplicate count metric must increment");

        // Rollup must only have been saved once
        verify(rollupRepository, times(1)).save(any(ClickRollup.class));
    }
}
