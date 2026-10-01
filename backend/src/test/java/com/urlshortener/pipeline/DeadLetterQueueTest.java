package com.urlshortener.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ClickEvent;
import com.urlshortener.entity.DeadLetterEvent;
import com.urlshortener.repository.DeadLetterEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Track 3.1: Dead Letter Queue (DLQ) Tests")
class DeadLetterQueueTest {

    private DeadLetterEventRepository dlqRepository;
    private ClickEventChannel clickEventChannel;
    private DeadLetterQueueService dlqService;

    @BeforeEach
    void setUp() {
        dlqRepository = mock(DeadLetterEventRepository.class);
        clickEventChannel = mock(ClickEventChannel.class);
        dlqService = new DeadLetterQueueService(dlqRepository, null, clickEventChannel, new ObjectMapper());
    }

    @Test
    @DisplayName("Should capture poisoned event in DLQ with metadata and error reason")
    void shouldCapturePoisonedEventInDlq() {
        when(dlqRepository.save(any(DeadLetterEvent.class))).thenAnswer(invocation -> {
            DeadLetterEvent e = invocation.getArgument(0);
            e.setId(101L);
            return e;
        });

        DeadLetterEvent result = dlqService.sendToDlq(
            "poison_evt_999",
            "juspayPromo",
            "{\"corrupted\":\"true\"}",
            "JSON parse exception: unexpected token",
            3
        );

        assertNotNull(result);
        assertEquals(101L, result.getId());
        assertEquals("poison_evt_999", result.getEventId());
        assertEquals("juspayPromo", result.getShortCode());
        assertEquals("JSON parse exception: unexpected token", result.getFailureReason());
        assertEquals(3, result.getRetryCount());
        assertFalse(result.getReplayed());
        assertEquals(1, dlqService.getDlqEnqueuedCount());
    }

    @Test
    @DisplayName("Should successfully replay unreplayed DLQ events back into the click channel")
    void shouldReplayPendingDlqEvents() {
        DeadLetterEvent entry1 = new DeadLetterEvent(
            "evt_replay_1",
            "short1",
            "{\"eventId\":\"evt_replay_1\",\"shortCode\":\"short1\",\"timestamp\":\"2026-09-30T10:00:00\"}",
            "Temporary DB timeout",
            1
        );
        entry1.setId(1L);

        when(dlqRepository.findByReplayedFalseOrderByCreatedAtAsc()).thenReturn(List.of(entry1));
        when(clickEventChannel.publish(any(ClickEvent.class))).thenReturn(true);

        DeadLetterQueueService.ReplaySummary summary = dlqService.replayPendingDlqEvents();

        assertEquals(1, summary.totalEvaluated());
        assertEquals(1, summary.successfullyRequeued());
        assertEquals(0, summary.failed());

        assertTrue(entry1.getReplayed());
        assertNotNull(entry1.getReplayedAt());
        verify(dlqRepository, times(1)).save(entry1);
    }
}
