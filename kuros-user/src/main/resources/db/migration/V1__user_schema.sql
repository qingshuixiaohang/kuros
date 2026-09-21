-- kuros-user 独立库 DDL（切片 #10 Phase B / split-05）。
-- 设计原则：表名与列名逐一对照 kuros-backend 现状（users / user_follows / sys_*），
-- split-06 迁移实体与查询时零改名；users.role 在 backend 是 V6 后期 ALTER 补上的，
-- 全新库直接并入建表语句（默认 USER，管理员由种子数据指定，结果与 backend 一致）。
-- 注意：帖子/评论等表不进本库——用户域之外的取数从 split-08 起走 OpenFeign。

CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    phone VARCHAR(32) NOT NULL UNIQUE,
    nickname VARCHAR(64) NOT NULL,
    avatar_url VARCHAR(512),
    bio VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 关注关系（对照 backend V5 的 user_follows：自关注检查 + 双向外键 + 被关注方索引）
CREATE TABLE user_follows (
    follower_id VARCHAR(36) NOT NULL,
    followed_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (follower_id, followed_id),
    CONSTRAINT chk_user_follows_no_self CHECK (follower_id <> followed_id),
    CONSTRAINT fk_user_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id),
    CONSTRAINT fk_user_follows_followed FOREIGN KEY (followed_id) REFERENCES users (id)
);
CREATE INDEX idx_user_follows_followed ON user_follows (followed_id);

-- RBAC 四表（对照 backend V9）：角色/权限动态配置，支撑"某个管理员只能审核举报但不能删帖"
-- 这类细粒度场景；拆分后 RBAC 的权威数据归用户域所有
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
