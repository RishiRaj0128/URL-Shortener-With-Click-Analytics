-- V3__Add_processed_events_and_stream_support.sql
-- Durable event deduplication and stream metadata

CREATE TABLE IF NOT EXISTS processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    short_code VARCHAR(20) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    consumer_id VARCHAR(100),
    delivery_count INT DEFAULT 1,
    CONSTRAINT uq_processed_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_processed_events_time ON processed_events(processed_at);
CREATE INDEX IF NOT EXISTS idx_processed_events_code ON processed_events(short_code);
