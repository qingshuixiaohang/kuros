CREATE TABLE media_assets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    attached_at TIMESTAMP NULL,
    CONSTRAINT fk_media_assets_owner FOREIGN KEY (owner_id) REFERENCES users (id)
);

CREATE TABLE post_media (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL UNIQUE,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES posts (id),
    CONSTRAINT fk_post_media_asset FOREIGN KEY (asset_id) REFERENCES media_assets (id),
    CONSTRAINT uq_post_media_asset_order UNIQUE (post_id, sort_order),
    CONSTRAINT uq_post_media_post_asset UNIQUE (post_id, asset_id)
);

CREATE INDEX idx_media_assets_owner_status ON media_assets (owner_id, status, created_at);
CREATE INDEX idx_post_media_post_order ON post_media (post_id, sort_order);
