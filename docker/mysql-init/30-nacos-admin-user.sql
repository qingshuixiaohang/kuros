-- Nacos 控制台登录账号种子（切片 #8 追加）
-- 背景：v3 控制台有独立鉴权开关（nacos.core.auth.console.enabled，默认开启），
-- 即使 NACOS_AUTH_ENABLE=false 控制台也要求登录；但全新库 users 表为空，
-- 无法登录。本文件在每个全新环境初始化出可登录的 admin 账号。
-- 用户名: nacos  密码: nacos123（仅 dev 使用，生产硬化时轮换）
-- BCrypt hash 由 `docker run --rm httpd:alpine htpasswd -bnBC 10 "" nacos123` 生成。
-- 注意：必须放在 20-nacos-schema.sql 之后执行（依赖 users/roles 表）；
-- 文件名前缀 30_ 保证 mysql entrypoint 按字典序最后执行。
USE nacos_config;

INSERT INTO users (username, password, enabled)
VALUES ('nacos', '$2y$10$4ddcbISelnbBWjRDKxCzU.pMQSI5SVzqTij5SPDkaUJPvA.O.DzIe', 1)
ON DUPLICATE KEY UPDATE password = VALUES(password), enabled = 1;

-- ROLE_ADMIN = v3 控制台全局管理员角色
INSERT INTO roles (username, role)
VALUES ('nacos', 'ROLE_ADMIN')
ON DUPLICATE KEY UPDATE role = VALUES(role);
