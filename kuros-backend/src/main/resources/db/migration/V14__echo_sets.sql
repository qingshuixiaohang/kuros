-- 声骸域（echoes）表：前端审计 H-9 的第二块。
-- 原先是前端 mock.ts 里的 echoSets 数组（4 条硬编码），/echoes 列表页与
-- /echoes/[id] 详情页都读它。接真 API 后声骸数据可由后台维护。
--
-- 与 game_characters / news_articles 同一套形状约定：slug 对外、UUID 主键、
-- enabled 软开关、sort_order 稳定排序。
--
-- 与 news 域的关键差异：guide_slug 可空。mock 里每条声骸都带 guideId，但那些
-- 指向的是 mock 攻略 slug，而真攻略走 posts 表——后端存一个跨域硬引用会让
-- echoes 依赖 posts 的种子数据。存成可空 slug，"未关联攻略"是一等公民，
-- 前端按钮只在有值时才渲染，不会生成打不开的链接。
CREATE TABLE echo_sets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    effect VARCHAR(120) NOT NULL,
    location VARCHAR(200) NOT NULL,
    role VARCHAR(32) NOT NULL,
    description VARCHAR(500) NOT NULL,
    guide_slug VARCHAR(80),
    sort_order INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_echo_role CHECK (role IN ('输出', '协同', '治疗'))
);

-- 列表查询形状：enabled + sort_order；role 过滤是第二维度（页面三选一 tab）
CREATE INDEX idx_echo_visible_order ON echo_sets (enabled, sort_order, name);
CREATE INDEX idx_echo_visible_role ON echo_sets (enabled, role, sort_order);

INSERT INTO echo_sets (id, slug, name, effect, location, role, description, guide_slug, sort_order, enabled, created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000001', 'floating-stars', '浮星祛暗', '湮灭伤害提升', '荒石高地 / 无音区', '输出',
     '适合围绕湮灭伤害展开的主输出构筑。', NULL, 10, TRUE, '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('60000000-0000-0000-0000-000000000002', 'thunder', '彻空冥雷', '导电伤害提升', '虎口矿场 / 归墟港市', '输出',
     '以导电伤害为核心的输出向套装。', NULL, 20, TRUE, '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('60000000-0000-0000-0000-000000000003', 'light-cloud', '轻云出月', '共鸣解放增益', '瑝珑各地', '协同',
     '给轮切队提供稳定的共鸣解放增益。', NULL, 30, TRUE, '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('60000000-0000-0000-0000-000000000004', 'hidden-light', '隐世回光', '治疗效果与协同', '云陵谷 / 乘霄山', '治疗',
     '适合治疗与协同角色的功能性选择。', NULL, 40, TRUE, '2026-10-14 10:00:00', '2026-10-14 10:00:00'),
    ('60000000-0000-0000-0000-000000000099', 'disabled-set', '隐藏套装', '用于验证未上线套装不可见', '不存在的地方', '输出',
     '这条不应出现在列表与详情中。', NULL, 999, FALSE, '2026-10-14 10:00:00', '2026-10-14 10:00:00');
