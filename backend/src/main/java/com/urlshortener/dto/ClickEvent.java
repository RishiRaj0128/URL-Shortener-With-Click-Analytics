package com.urlshortener.dto;

import java.time.LocalDateTime;

/**
 * Immutable ClickEvent published asynchronously from the redirect path.
 * 
 * Fields:
 * - eventId: Unique event identifier (Snowflake or UUID) used for idempotent deduplication
 * - shortCode: Short code targeted by the redirect
 * - urlId: Primary database identifier of the URL
 * - timestamp: Occurrence timestamp
 * - clientIp: Request IP address (anonymized/hashed downstream)
 * - userAgent: Raw User-Agent header string
 * - referrer: Referer header string
 */
public record ClickEvent(
    String eventId,
    String shortCode,
    Long urlId,
    LocalDateTime timestamp,
    String clientIp,
    String userAgent,
    String referrer
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
}
