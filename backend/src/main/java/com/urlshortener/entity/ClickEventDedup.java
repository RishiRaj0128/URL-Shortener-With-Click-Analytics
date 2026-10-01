package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Idempotency record ensuring at-least-once queue delivery does not double-count clicks.
 */
@Entity
@Table(name = "click_events_dedup", indexes = {
    @Index(name = "idx_dedup_processed", columnList = "processed_at")
})
public class ClickEventDedup {

    @Id
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "short_code", length = 20, nullable = false)
    private String shortCode;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ClickEventDedup() {}

    public ClickEventDedup(String eventId, String shortCode) {
        this.eventId = eventId;
        this.shortCode = shortCode;
        this.processedAt = LocalDateTime.now();
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
