package com.kuros.kurosbackend.interaction.event;

/**
 * 互动事件类型（切片 #11）：一次点赞/收藏操作的方向 + 维度。
 *
 * targetState 表示操作后关系应处于的状态：
 * - true  = 建立关系（点赞 / 收藏）→ Redis 状态置 "1"、计数 +1、DB insert 关系行
 * - false = 解除关系（取消点赞 / 取消收藏）→ Redis 状态置 "0"、计数 -1、DB delete 关系行
 *
 * 写路径据此判断「是否发生翻转」——只有状态真正翻转时才增减计数，保证重复点击幂等。
 */
public enum InteractionType {

    LIKE(InteractionKind.LIKE, true),
    UNLIKE(InteractionKind.LIKE, false),
    FAVORITE(InteractionKind.FAVORITE, true),
    UNFAVORITE(InteractionKind.FAVORITE, false);

    private final InteractionKind kind;
    private final boolean targetState;

    InteractionType(InteractionKind kind, boolean targetState) {
        this.kind = kind;
        this.targetState = targetState;
    }

    public InteractionKind kind() {
        return kind;
    }

    /** 操作后关系的目标状态：true=已赞/已藏，false=未赞/未藏。 */
    public boolean targetState() {
        return targetState;
    }
}
