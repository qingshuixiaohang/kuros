CREATE TABLE comments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    parent_id VARCHAR(36),
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id)
);

CREATE INDEX idx_comments_post_latest ON comments (post_id, status, created_at);
CREATE INDEX idx_comments_post_hot ON comments (post_id, status, like_count, created_at);

INSERT INTO comments (id, post_id, author_id, parent_id, content, status, like_count, created_at, updated_at)
VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', NULL, '轮切顺序写得很清楚，尤其是先把声骸触发安排进循环这一点，实战里确实舒服很多。', 'NORMAL', 61, '2026-09-13 14:20:00', '2026-09-13 14:20:00'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '谢谢反馈！低配队伍可以先保证循环完整，再慢慢补面板，不用一开始就追求毕业词条。', 'NORMAL', 55, '2026-09-13 15:06:00', '2026-09-13 15:06:00'),
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003', NULL, '已收藏，等下一次深塔刷新后按这个思路试一遍。', 'NORMAL', 18, '2026-09-14 09:12:00', '2026-09-14 09:12:00'),
    ('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000004', NULL, '这条内容已经被作者删除。', 'DELETED', 0, '2026-09-14 10:18:00', '2026-09-14 10:18:00');

UPDATE posts
SET comment_count = 4, updated_at = '2026-09-15 10:00:00'
WHERE id = '10000000-0000-0000-0000-000000000001';
