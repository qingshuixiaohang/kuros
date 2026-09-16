CREATE TABLE post_likes (
    user_id VARCHAR(36) NOT NULL,
    post_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id)
);

CREATE TABLE post_favorites (
    user_id VARCHAR(36) NOT NULL,
    post_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_post_favorites_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_post_favorites_post FOREIGN KEY (post_id) REFERENCES posts (id)
);

CREATE TABLE user_follows (
    follower_id VARCHAR(36) NOT NULL,
    followed_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (follower_id, followed_id),
    CONSTRAINT chk_user_follows_no_self CHECK (follower_id <> followed_id),
    CONSTRAINT fk_user_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id),
    CONSTRAINT fk_user_follows_followed FOREIGN KEY (followed_id) REFERENCES users (id)
);

CREATE INDEX idx_post_likes_post ON post_likes (post_id);
CREATE INDEX idx_post_favorites_post ON post_favorites (post_id);
CREATE INDEX idx_user_follows_followed ON user_follows (followed_id);
