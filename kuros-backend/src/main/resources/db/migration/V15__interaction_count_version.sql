ALTER TABLE posts
    ADD COLUMN interaction_count_version BIGINT NOT NULL DEFAULT 0;
