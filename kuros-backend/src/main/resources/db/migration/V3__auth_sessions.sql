CREATE TABLE user_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_user_sessions_user ON user_sessions (user_id, expires_at);
CREATE INDEX idx_user_sessions_expiry ON user_sessions (expires_at);
