ALTER TABLE users ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER';

UPDATE users
SET role = 'ADMIN'
WHERE id = '10000000-0000-0000-0000-000000000001';

CREATE TABLE content_reports (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reporter_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    handled_by VARCHAR(36),
    handled_at TIMESTAMP,
    handling_note VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_reports_handler FOREIGN KEY (handled_by) REFERENCES users (id)
);

CREATE INDEX idx_reports_status_created ON content_reports (status, created_at);
CREATE INDEX idx_reports_target ON content_reports (target_type, target_id, status);
CREATE INDEX idx_reports_reporter_target ON content_reports (reporter_id, target_type, target_id, status);
