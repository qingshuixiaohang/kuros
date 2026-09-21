-- 演示种子（切片 #10 / split-05）：ID、手机号、昵称与 kuros-backend 的 V2/V6 种子逐一相同。
-- 为什么必须逐字对齐：split-08 起帖子作者昵称由 backend 经 Feign 从本服务回填，
-- 两侧种子漂移会让跨服务组合视图与前端演示数据对不上。
INSERT INTO users (id, phone, nickname, avatar_url, bio, status, role, created_at, updated_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', '13800000001', '潮声档案员', NULL, '记录版本变化，也记录每一次实战尝试。', 'NORMAL', 'ADMIN', '2026-09-10 10:00:00', '2026-09-10 10:00:00'),
    ('10000000-0000-0000-0000-000000000002', '13800000002', '无音区夜行者', NULL, '把复杂机制拆成可以直接练习的步骤。', 'NORMAL', 'USER', '2026-09-10 10:00:00', '2026-09-10 10:00:00'),
    ('10000000-0000-0000-0000-000000000003', '13800000003', '今汐的留声机', NULL, '整理角色与声骸之间的配合思路。', 'NORMAL', 'USER', '2026-09-10 10:00:00', '2026-09-10 10:00:00'),
    ('10000000-0000-0000-0000-000000000004', '13800000004', '漂泊者手册', NULL, '给刚刚踏上旅途的漂泊者一些方向。', 'NORMAL', 'USER', '2026-09-10 10:00:00', '2026-09-10 10:00:00');

-- RBAC 种子（对照 backend V9：角色/权限/映射逐条相同；用户1=管理员，与 backend V6 的 UPDATE 结果一致）
INSERT INTO sys_role (role_code, role_name) VALUES ('USER', '普通用户'), ('ADMIN', '管理员');

INSERT INTO sys_permission (permission_code, permission_name) VALUES
('post:create', '发布帖子'),
('post:delete:own', '删除自己的帖子'),
('post:delete:any', '删除任意帖子'),
('comment:create', '发表评论'),
('comment:delete:own', '删除自己的评论'),
('comment:delete:any', '删除任意评论'),
('report:create', '提交举报'),
('report:handle', '处理举报'),
('user:ban', '封禁用户'),
('character:manage', '管理角色图鉴');

-- ADMIN 拥有全部权限（用 role_code 子查询，不依赖自增 ID 顺序）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p WHERE r.role_code = 'ADMIN';

-- USER 拥有基础社区权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER' AND p.permission_code IN
('post:create', 'post:delete:own', 'comment:create', 'comment:delete:own', 'report:create');

-- 用户-角色映射：按 users.role 冗余列关联，保证与 backend 迁移后状态完全一致
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM users u, sys_role r WHERE r.role_code = 'ADMIN' AND u.role = 'ADMIN';

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM users u, sys_role r WHERE r.role_code = 'USER' AND u.role = 'USER';
