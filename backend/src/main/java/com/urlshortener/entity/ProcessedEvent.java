package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Durable processed event record guaranteeing at-least-once stream redeliveries
 * never double-count analytical rollups.
 */
@Entity
@Table(name = "processed_events", indexes = {
    @Index(name = "idx_processed_events_time", columnList = "processed_at"),
    @Index(name = "idx_processed_events_code", columnList = "short_code")
})
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 64, nullable = false, unique = true)
    private String eventId;

    @Column(name = "short_code", length = 20, nullable = false)
    private String shortCode;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "consumer_id", length = 100)
    private String consumerId;

    @Column(name = "delivery_count")
    private Integer deliveryCount = 1;

    public ProcessedEvent() {}

    public ProcessedEvent(String eventId, String shortCode, String consumerId) {
        this.eventId = eventId;
        this.shortCode = shortCode;
        this.consumerId = consumerId;
        this.processedAt = LocalDateTime.now();
        this.deliveryCount = 1;
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

    public String getConsumerId() {
        return consumerId;
    }

    public void setConsumerId(String consumerId) {
        this.consumerId = consumerId;
    }

    public Integer getDeliveryCount() {
        return deliveryCount;
    }

    public void setDeliveryCount(Integer deliveryCount) {
        this.deliveryCount = deliveryCount;
    }
}
