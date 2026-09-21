package com.kuros.kurosbackend.interaction.event;

/**
 * 互动种类（切片 #11）：区分「点赞」与「收藏」两条独立的关系 + 计数维度。
 *
 * 为什么单独抽出 kind 而不直接用 InteractionType？
 * type 有四个（LIKE/UNLIKE/FAVORITE/UNFAVORITE），但 Redis key 前缀、DB 关系表、
 * DB 计数列只按「赞 / 藏」两个维度组织；kind 把「方向（建立/解除）」从「维度（赞/藏）」里剥离，
 * 让 Redis 读源与投影落库都能按 kind 复用同一套逻辑。
 */
public enum InteractionKind {

    LIKE("like"),
    FAVORITE("favorite");

    private final String keyPrefix;

    InteractionKind(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /** Redis key 前缀片段（如 interaction:like:... / interaction:count:favorite:...）。 */
    public String keyPrefix() {
        return keyPrefix;
    }
}
