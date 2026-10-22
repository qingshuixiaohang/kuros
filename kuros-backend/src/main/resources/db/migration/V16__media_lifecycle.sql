ALTER TABLE post_media ADD COLUMN inline_media BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE media_assets ADD COLUMN storage_deleted_at TIMESTAMP NULL;
CREATE INDEX idx_media_assets_cleanup ON media_assets (status, storage_deleted_at, created_at);
