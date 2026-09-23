package com.kuros.kurosbackend.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 跨节点 L1 失效订阅者（切片 #13 / rp-03 / ADR 0006 D4）。
 *
 * 为什么需要它？两级缓存的 L1（Caffeine）是「进程内」的：某节点写路径驱逐只能清「本节点 L1 + 共享 L2」，
 * 其他节点的 L1 仍揣着旧内容，最长要等 L1 TTL（10s）到期才收敛。发布方在 {@link TwoLevelCache#evict} 里
 * 向 cache:evict:{cacheName} 频道广播失效键，本订阅者在每个节点收到消息后清掉「本节点」对应的 L1，实现准实时一致。
 *
 * 收到消息后只调用 {@link TwoLevelCache#evictLocalL1}（只清本地 L1）：L2 早已被发布方删除、也无需再广播，
 * 否则会造成重复删除与消息风暴。
 *
 * 容错：pub/sub 是「尽力而为」——Redis 瞬断可能丢消息，此时不重发、不阻塞，由 L1 短 TTL 兜底最终一致（见 CacheConfig）。
 */
@Component
public class CacheEvictionListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionListener.class);

    private final TwoLevelCache twoLevelCache;

    public CacheEvictionListener(TwoLevelCache twoLevelCache) {
        this.twoLevelCache = twoLevelCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!channel.startsWith(TwoLevelCache.EVICT_CHANNEL_PREFIX)) {
            return;
        }
        // 频道名 cache:evict:{cacheName} → 截出 cacheName；消息体即业务键（如 postId）
        String cacheName = channel.substring(TwoLevelCache.EVICT_CHANNEL_PREFIX.length());
        twoLevelCache.evictLocalL1(cacheName, key);
        log.debug("收到跨节点缓存失效广播 channel={} key={}", channel, key);
    }
}
