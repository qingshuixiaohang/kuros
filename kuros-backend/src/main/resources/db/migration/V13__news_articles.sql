-- 资讯域（news）表：前端审计 H-9 的第一块。
-- 原先是前端 mock.ts 里的 newsItems 数组（4 条硬编码），/news 与首页右栏
-- 「最新资讯」都读它。接真 API 后资讯可以由后台维护，不再改前端代码发版。
--
-- 与 game_characters 同一套形状约定：slug 对外、UUID 做主键、enabled 软开关、
-- sort_order 稳定排序。news 的正文是分段数组（前端按段渲染），故用独立子表
-- 而不是一个大 TEXT——这样段序可显式维护，也避免在前端 split 换行符。
CREATE TABLE news_articles (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(32) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    published_at DATE NOT NULL,
    hot BOOLEAN NOT NULL,
    sort_order INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_news_category CHECK (category IN ('版本前瞻', '官方公告', '活动资讯', '社区资讯'))
);

-- 列表查询的形状：enabled 过滤 + sort_order 排序 + 分页。published_at 进索引
-- 是为了「按时间倒序」这个备用排序不至于 filesort。
CREATE INDEX idx_news_visible_order ON news_articles (enabled, sort_order, published_at);

CREATE TABLE news_article_paragraphs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    article_id VARCHAR(36) NOT NULL,
    paragraph_index INT NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT fk_news_paragraph_article FOREIGN KEY (article_id) REFERENCES news_articles (id) ON DELETE CASCADE
);

CREATE INDEX idx_news_paragraphs_article ON news_article_paragraphs (article_id, paragraph_index);

INSERT INTO news_articles (id, slug, title, category, summary, published_at, hot, sort_order, enabled, created_at, updated_at)
VALUES
    ('50000000-0000-0000-0000-000000000001', 'version-preview', '鸣潮 2.4 版本前瞻特别节目总结', '版本前瞻',
     '新区域、角色体验和版本活动的重点信息已经整理完毕。', '2025-09-13', TRUE, 10, TRUE,
     '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('50000000-0000-0000-0000-000000000002', 'maintenance-notice', '10 月 14 日服务器维护与补偿说明', '官方公告',
     '维护期间将暂时无法登录，补偿已发送至全员邮箱。', '2025-10-14', FALSE, 20, TRUE,
     '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('50000000-0000-0000-0000-000000000003', 'unpublished-draft', '尚未发布的草稿资讯', '社区资讯',
     '用于验证未发布资讯不会出现在列表与详情中。', '2025-10-14', FALSE, 999, FALSE,
     '2026-10-14 10:00:00', '2026-10-14 10:00:00');

INSERT INTO news_article_paragraphs (id, article_id, paragraph_index, content)
VALUES
    ('51000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 0,
     '本次前瞻围绕新版本的探索节奏、限时活动和角色试玩展开。'),
    ('51000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', 1,
     '建议先完成版本主线，再根据自身资源安排角色与武器的养成优先级。'),
    ('51000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000002', 0,
     '维护时间为当日 02:00 至 06:00，届时登录与匹配功能将暂停。'),
    ('51000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000002', 1,
     '补偿内容为特制定轨共鸣与结晶单抽凭证，将于维护结束后统一发放。'),
    ('51000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000003', 0,
     '这条内容不应被任何游客读到。');
