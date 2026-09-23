package com.kuros.kurosbackend.shared.cache;

/**
 * 两级缓存的缓存名常量（切片 #13）。
 *
 * 为什么集中到一个类而不是散落在各 service？
 * 缓存名同时决定 L1（CaffeineCacheManager 注册名，见 CacheConfig）与 L2（Redis key 命名空间
 * cache:{name}:{key}）两层，且会被「读路径」（post/user 域）与「写路径驱逐」（post/comment/interaction 域）
 * 跨包引用。散落成各 service 的包级常量会导致跨包不可见或重复字面量；集中定义保证读写两侧用的是同一个名字。
 */
public final class CacheNames {

    /** 帖子详情内容缓存（只存内容字段，计数解耦见 PostDetailContent）。 */
    public static final String POST_DETAIL = "postDetail";

    /** 用户公开资料缓存（昵称/头像来自 kuros-user，靠 TTL 兜底失效）。 */
    public static final String PUBLIC_PROFILE = "publicProfile";

    private CacheNames() {
    }
}
