-- RBAC 权限模型：角色表、权限表、用户角色关联、角色权限关联
-- 为什么用四张表而不是继续在 users.role 枚举上硬编码？
-- 因为微服务演进后，Gateway 统一鉴权需要从 Redis 读取用户的角色和权限集合，
-- 硬编码枚举无法动态配置，也无法支持"某个管理员只能审核举报但不能删帖"这种细粒度场景。

CREATE TABLE sys_role (
    id INT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
    id INT AUTO_INCREMENT PRIMARY KEY,
    permission_code VARCHAR(64) NOT NULL UNIQUE,
    permission_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    role_id INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

CREATE TABLE sys_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id INT NOT NULL,
    permission_id INT NOT NULL,
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id),
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

-- Seed 角色：与现有 UserRole 枚举对应
INSERT INTO sys_role (role_code, role_name) VALUES ('USER', '普通用户'), ('ADMIN', '管理员');

-- Seed 权限：覆盖当前业务中所有需要鉴权的操作
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

-- 将现有用户按 users.role 字段关联到 RBAC 表，保证迁移后数据一致
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM users u, sys_role r WHERE r.role_code = 'ADMIN' AND u.role = 'ADMIN';

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM users u, sys_role r WHERE r.role_code = 'USER' AND u.role = 'USER';
