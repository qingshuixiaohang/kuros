CREATE TABLE game_characters (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    slug VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    rarity INT NOT NULL,
    attribute_name VARCHAR(32) NOT NULL,
    weapon_type VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    image_url VARCHAR(512) NOT NULL,
    description VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_characters_rarity CHECK (rarity BETWEEN 1 AND 5)
);

CREATE INDEX idx_characters_visible_order ON game_characters (enabled, sort_order, name);
CREATE INDEX idx_characters_visible_role ON game_characters (enabled, role, sort_order);

INSERT INTO game_characters (id, slug, name, role, rarity, attribute_name, weapon_type, version, image_url, description, sort_order, enabled, created_at, updated_at)
VALUES
    ('40000000-0000-0000-0000-000000000001', 'shorekeeper', '守岸人', '辅助', 5, '衍射', '音感仪', '1.3', '/art/修-守岸人 唤取动画.png', '稳定队伍循环并提供持续支援，适合作为长期培养的辅助角色。', 10, TRUE, '2026-09-18 10:00:00', '2026-09-18 10:00:00'),
    ('40000000-0000-0000-0000-000000000002', 'augustus', '奥古斯都', '输出', 5, '湮灭', '长刃', '3.6', '/art/修-奥古斯都  唤取动画.png', '围绕技能循环和爆发窗口展开输出，适合整理成清晰的轮切节奏。', 20, TRUE, '2026-09-18 10:00:00', '2026-09-18 10:00:00'),
    ('40000000-0000-0000-0000-000000000003', 'aemeath', '爱弥斯', '协同', 5, '热熔', '迅刀', '3.6', '/art/修-爱弥斯 唤取动画.png', '通过协同技能补充队伍伤害，并在主输出空窗期维持战斗节奏。', 30, TRUE, '2026-09-18 10:00:00', '2026-09-18 10:00:00'),
    ('40000000-0000-0000-0000-000000000099', 'disabled-character', '隐藏角色', '辅助', 4, '冷凝', '佩枪', '0.0', '/art/修-守岸人 唤取动画.png', '用于验证未启用角色不会被游客访问。', 999, FALSE, '2026-09-18 10:00:00', '2026-09-18 10:00:00');
