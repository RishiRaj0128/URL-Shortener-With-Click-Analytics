package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Audit log recording detected traffic anomalies (bot bursts, z-score volume spikes).
 * Preserves the full audit trail without silently discarding suspect clicks.
 */
@Entity
@Table(name = "click_anomaly_logs", indexes = {
    @Index(name = "idx_anomaly_code", columnList = "short_code"),
    @Index(name = "idx_anomaly_detected_at", columnList = "detected_at")
})
public class ClickAnomalyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 20)
    private String shortCode;

    @Column(name = "client_ip_hash", nullable = false, length = 64)
    private String clientIpHash;

    @Column(name = "anomaly_type", nullable = false, length = 50)
    private String anomalyType;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    public ClickAnomalyLog() {}

    public ClickAnomalyLog(String shortCode, String clientIpHash, String anomalyType, String details) {
        this.shortCode = shortCode;
        this.clientIpHash = clientIpHash;
        this.anomalyType = anomalyType;
        this.details = details;
        this.detectedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getClientIpHash() {
        return clientIpHash;
    }

    public void setClientIpHash(String clientIpHash) {
        this.clientIpHash = clientIpHash;
    }

    public String getAnomalyType() {
        return anomalyType;
    }

    public void setAnomalyType(String anomalyType) {
        this.anomalyType = anomalyType;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(LocalDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }
}
