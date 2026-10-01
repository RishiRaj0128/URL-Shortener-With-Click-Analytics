package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Dead Letter Queue (DLQ) event record for permanently failed, poisoned,
 * or repeatedly unparseable click stream messages.
 */
@Entity
@Table(name = "dead_letter_events", indexes = {
    @Index(name = "idx_dlq_events_event_id", columnList = "event_id"),
    @Index(name = "idx_dlq_events_replayed", columnList = "replayed"),
    @Index(name = "idx_dlq_events_time", columnList = "created_at")
})
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "short_code", length = 20)
    private String shortCode;

    @Column(name = "raw_payload", columnDefinition = "TEXT", nullable = false)
    private String rawPayload;

    @Column(name = "failure_reason", columnDefinition = "TEXT", nullable = false)
    private String failureReason;

    @Column(name = "retry_count")
    private Integer retryCount = 1;

    @Column(name = "replayed")
    private Boolean replayed = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "replayed_at")
    private LocalDateTime replayedAt;

    public DeadLetterEvent() {}

    public DeadLetterEvent(String eventId, String shortCode, String rawPayload, String failureReason, int retryCount) {
        this.eventId = eventId;
        this.shortCode = shortCode;
        this.rawPayload = rawPayload;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.replayed = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getReplayed() {
        return replayed;
    }

    public void setReplayed(Boolean replayed) {
        this.replayed = replayed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReplayedAt() {
        return replayedAt;
    }

    public void setReplayedAt(LocalDateTime replayedAt) {
        this.replayedAt = replayedAt;
    }
}
