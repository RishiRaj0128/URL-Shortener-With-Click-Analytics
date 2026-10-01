-- V2__Add_analytics_rollups_and_anomalies.sql
-- Analytics rollups, idempotency deduplication, and anomaly audit log

CREATE TABLE click_rollups (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(20) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    legitimate_clicks BIGINT DEFAULT 0,
    bot_clicks BIGINT DEFAULT 0,
    is_anomaly BOOLEAN DEFAULT FALSE,
    anomaly_reason VARCHAR(255),
    z_score DOUBLE PRECISION,
    CONSTRAINT uq_rollup_code_window UNIQUE (short_code, window_start)
);

CREATE INDEX idx_rollups_code_window ON click_rollups(short_code, window_start);

CREATE TABLE click_events_dedup (
    event_id VARCHAR(64) PRIMARY KEY,
    short_code VARCHAR(20) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dedup_processed ON click_events_dedup(processed_at);

CREATE TABLE click_anomaly_logs (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(20) NOT NULL,
    client_ip_hash VARCHAR(64) NOT NULL,
    anomaly_type VARCHAR(50) NOT NULL,
    details TEXT,
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_anomaly_code ON click_anomaly_logs(short_code);
CREATE INDEX idx_anomaly_detected_at ON click_anomaly_logs(detected_at);
