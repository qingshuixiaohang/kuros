INSERT INTO game_characters (id, slug, name, role, rarity, attribute_name, weapon_type, version, image_url, description, sort_order, enabled, created_at, updated_at)
VALUES
    ('40000000-0000-0000-0000-000000000004', 'xin', '心', '输出', 5, '导电', '音感仪', '3.7', '/art/official/characters/xin-showcase-01.jpg', '围绕协同攻击与共鸣技能伤害展开战斗的导电主力输出。', 40, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000005', 'lockming', '锁暝', '协同', 5, '导电', '迅刀', '3.7', '/art/official/characters/lockming-showcase-01.png', '擅长快速协奏与普攻输出，并能强化队伍的导电和共鸣技能伤害。', 50, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000006', 'lucy', '露西', '输出', 5, '衍射', '佩枪', '3.4', '/art/official/characters/lucy-showcase-01.jpg', '衍射属性佩枪共鸣者，适合围绕技能循环与队伍适配整理培养思路。', 60, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000007', 'luosella', '洛瑟菈', '输出', 5, '冷凝', '音感仪', '3.4', '/art/official/characters/lucilla-showcase-01.png', '冷凝属性音感仪共鸣者，可按技能循环和队伍需求规划培养。', 70, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000008', 'jingran', '景燃', '输出', 5, '热熔', '长刃', '3.6', '/art/official/characters/jingran-showcase-01.png', '生于梦州的寻幽客，行走于蜃境与遗墟之间，擅长以长刃展开热熔输出。', 80, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000009', 'qingxiao', '清宵', '输出', 5, '气动', '迅刀', '3.6', '/art/official/characters/qingxiao-showcase-01.jpg', '隐世剑修，以迅刀施展气动剑技，兼具灵活位移与持续输出。', 90, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000010', 'suisui', '穗穗', '治疗', 5, '冷凝', '音感仪', '3.5', '/art/official/characters/suisui-showcase-01.png', '能够驾驭自然水流，并通过山河水境为队伍提供治疗与增益。', 100, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    ('40000000-0000-0000-0000-000000000011', 'yangyang-xuanling', '秧秧·玄翎', '输出', 5, '湮灭', '迅刀', '3.5', '/art/official/characters/yangyang-xuanling-showcase-01.png', '湮灭属性迅刀主力输出，围绕虚湮效应与重击伤害展开战斗。', 110, TRUE, '2026-10-26 10:00:00', '2026-10-26 10:00:00')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    role = VALUES(role),
    rarity = VALUES(rarity),
    attribute_name = VALUES(attribute_name),
    weapon_type = VALUES(weapon_type),
    version = VALUES(version),
    image_url = VALUES(image_url),
    description = VALUES(description),
    sort_order = VALUES(sort_order),
    enabled = VALUES(enabled),
    updated_at = VALUES(updated_at);
