package com.urlshortener.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Aggregated click metrics partitioned into 1-minute time windows per short_code.
 * Stored separately from the hot redirect path to optimize read queries for dashboards.
 */
@Entity
@Table(name = "click_rollups", uniqueConstraints = {
    @UniqueConstraint(name = "uq_rollup_code_window", columnNames = {"short_code", "window_start"})
}, indexes = {
    @Index(name = "idx_rollups_code_window", columnList = "short_code, window_start")
})
public class ClickRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 20)
    private String shortCode;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "legitimate_clicks", nullable = false)
    private Long legitimateClicks = 0L;

    @Column(name = "bot_clicks", nullable = false)
    private Long botClicks = 0L;

    @Column(name = "is_anomaly", nullable = false)
    private Boolean isAnomaly = false;

    @Column(name = "anomaly_reason", length = 255)
    private String anomalyReason;

    @Column(name = "z_score")
    private Double zScore;

    public ClickRollup() {}

    public ClickRollup(String shortCode, LocalDateTime windowStart, LocalDateTime windowEnd) {
        this.shortCode = shortCode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.legitimateClicks = 0L;
        this.botClicks = 0L;
        this.isAnomaly = false;
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

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Long getLegitimateClicks() {
        return legitimateClicks;
    }

    public void setLegitimateClicks(Long legitimateClicks) {
        this.legitimateClicks = legitimateClicks;
    }

    public Long getBotClicks() {
        return botClicks;
    }

    public void setBotClicks(Long botClicks) {
        this.botClicks = botClicks;
    }

    public Boolean getIsAnomaly() {
        return isAnomaly;
    }

    public void setIsAnomaly(Boolean anomaly) {
        isAnomaly = anomaly;
    }

    public String getAnomalyReason() {
        return anomalyReason;
    }

    public void setAnomalyReason(String anomalyReason) {
        this.anomalyReason = anomalyReason;
    }

    public Double getZScore() {
        return zScore;
    }

    public void setZScore(Double zScore) {
        this.zScore = zScore;
    }

    public void incrementLegitimateClicks() {
        this.legitimateClicks++;
    }

    public void incrementBotClicks() {
        this.botClicks++;
    }
}
