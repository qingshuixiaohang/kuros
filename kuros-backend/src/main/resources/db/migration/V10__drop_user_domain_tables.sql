-- split-07：用户域数据整体迁往 kuros-user（用户/关注/RBAC 的权威副本已在其库 kuros_user），
-- 本库的副本表与指向它们的 FK 一并清理。分两步执行：

-- 第一步：摘除保留表上指向 users 的外键约束。
-- 这些表继续服务内容域（帖子/评论/点赞/收藏/举报/媒体），只是不再校验用户维度的引用完整性——
-- 用户存在性自 split-07 起由 kuros-user 保证（经网关会话与内部 API 提供）。
ALTER TABLE posts DROP CONSTRAINT fk_posts_author;
ALTER TABLE comments DROP CONSTRAINT fk_comments_author;
ALTER TABLE post_likes DROP CONSTRAINT fk_post_likes_user;
ALTER TABLE post_favorites DROP CONSTRAINT fk_post_favorites_user;
ALTER TABLE content_reports DROP CONSTRAINT fk_reports_reporter;
ALTER TABLE content_reports DROP CONSTRAINT fk_reports_handler;
ALTER TABLE media_assets DROP CONSTRAINT fk_media_assets_owner;

-- 第二步：按"引用方先删"顺序 DROP 用户域表，避免被残留 FK 阻挡。
-- 顺序理由（MySQL 与 H2 双兼容）：
-- - 前四张（user_sessions/sys_user_role/sys_role_permission/user_follows）是引用方，
--   其自身 FK 随表消失，且没有任何表引用它们；
-- - sys_role/sys_permission 被两个关联表引用，必须在其后删；
-- - users 最后删：此时指向它的 FK 已全部摘除或随表消失。
DROP TABLE IF EXISTS user_sessions;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS user_follows;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS users;
