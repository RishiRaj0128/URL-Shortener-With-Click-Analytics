-- V5__Add_url_expiration_and_soft_delete.sql
-- Add link expiration and soft delete columns to urls table

ALTER TABLE urls ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NULL;
ALTER TABLE urls ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE urls ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_urls_expires_at ON urls(expires_at);
CREATE INDEX IF NOT EXISTS idx_urls_is_deleted ON urls(is_deleted);
