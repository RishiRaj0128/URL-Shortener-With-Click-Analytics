-- V4__Add_dlq_events.sql
-- Dead Letter Queue storage for poisoned and unparseable click events

CREATE TABLE IF NOT EXISTS dead_letter_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64),
    short_code VARCHAR(20),
    raw_payload TEXT NOT NULL,
    failure_reason TEXT NOT NULL,
    retry_count INT DEFAULT 1,
    replayed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    replayed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dlq_events_event_id ON dead_letter_events(event_id);
CREATE INDEX IF NOT EXISTS idx_dlq_events_replayed ON dead_letter_events(replayed);
CREATE INDEX IF NOT EXISTS idx_dlq_events_time ON dead_letter_events(created_at);
