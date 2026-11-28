CREATE TABLE interaction_bindings (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL
);

CREATE TABLE interaction_operations (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    applied_at TIMESTAMP NOT NULL,
    cache_pending BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_interaction_operations_cache ON interaction_operations (cache_pending, applied_at);

CREATE TABLE interaction_failures (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    occurred_at BIGINT NOT NULL,
    error_type VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
