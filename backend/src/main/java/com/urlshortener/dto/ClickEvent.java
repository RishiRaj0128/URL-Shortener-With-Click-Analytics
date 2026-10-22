package com.urlshortener.dto;

import java.time.LocalDateTime;

/**
 * Immutable ClickEvent published asynchronously from the redirect path.
 * 
 * Fields:
 * - eventId: Unique event identifier used for idempotent deduplication
 * - shortCode: Short code targeted by the redirect
 * - urlId: Primary database identifier of the URL
 * - timestamp: Occurrence timestamp
 * - clientIp: Request IP address (pseudonymized downstream)
 * - userAgent: Raw User-Agent header string
 * - referrer: Referer header string
 * - traceId: Distributed tracing correlation ID
 */
public record ClickEvent(
    String eventId,
    String shortCode,
    Long urlId,
    LocalDateTime timestamp,
    String clientIp,
    String userAgent,
    String referrer,
    String traceId
) {
    public ClickEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be null or blank");
        }
        if (shortCode == null || shortCode.isBlank()) {
            throw new IllegalArgumentException("shortCode must not be null or blank");
        }
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    /**
     * Backward-compatible constructor without explicit traceId.
     */
    public ClickEvent(
        String eventId,
        String shortCode,
        Long urlId,
        LocalDateTime timestamp,
        String clientIp,
        String userAgent,
        String referrer
    ) {
        this(eventId, shortCode, urlId, timestamp, clientIp, userAgent, referrer, null);
    }
}
