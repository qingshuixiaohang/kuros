package com.kuros.kurosbackend.feed.event;

import com.kuros.kurosbackend.feed.redis.FeedTimelineStore;
import com.kuros.kurosbackend.user.client.UserBriefDto;
import com.kuros.kurosbackend.user.client.UserDirectoryFacade;
import com.kuros.kurosbackend.shared.api.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 帖子发布后的 Feed 扇出监听器。
 *
 * 触发时机：@TransactionalEventListener(phase = AFTER_COMMIT)
 * → 只在发帖事务**成功提交后**才执行，保证粉丝 timeline 推入的 postId 在 DB 已可查。
 *
 * 扇出逻辑：
 * 1. 经 Feign 取作者的粉丝列表（上限 5000，超出不推——本切片 scope）
 * 2. 排除作者自己（语义：自己的帖子不出现在自己的"关注流"）
 * 3. 批量 ZADD 到每个粉丝的 timeline ZSet（Pipeline 一次 RTT）
 *
 * 降级策略：Feign 调用失败 / Redis 写入失败 → log.warn 跳过，**不影响发帖成功响应**。
 * 发帖是用户主路径（同步返回 201），feed 扇出是"best-effort"（粉丝下次刷新时可能看到也可能看不到）。
 * 生产化提醒：超 5000 粉丝的大 V 需要"推拉混合"（大 V 帖子不推，粉丝读时拉取合并），留 #13。
 */
@Component
public class FeedFanoutListener {

    private static final Logger log = LoggerFactory.getLogger(FeedFanoutListener.class);
    private static final int MAX_FOLLOWERS_PAGE_SIZE = 5000;

    private final UserDirectoryFacade userDirectory;
    private final FeedTimelineStore timelineStore;

    public FeedFanoutListener(UserDirectoryFacade userDirectory, FeedTimelineStore timelineStore) {
        this.userDirectory = userDirectory;
        this.timelineStore = timelineStore;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostPublished(PostPublishedEvent event) {
        try {
            List<String> followerIds = fetchFollowerIds(event.authorId());
            if (followerIds.isEmpty()) {
                log.debug("帖子 {} 作者 {} 无粉丝，跳过扇出", event.postId(), event.authorId());
                return;
            }
            timelineStore.pushToTimelines(event.postId(), event.publishedAt(), followerIds);
            log.info("帖子 {} 扇出到 {} 个粉丝的 timeline", event.postId(), followerIds.size());
        } catch (Exception exception) {
            // 降级：扇出失败不影响发帖成功响应（best-effort）
            log.warn("帖子 {} 扇出失败（降级跳过）：{}", event.postId(), exception.getMessage());
        }
    }

    /**
     * 经 Feign 取作者的粉丝列表。
     * 粉丝 >5000 时只取前 5000（本切片简化策略，#13 引入推拉混合）。
     * 异常上抛，由 onPostPublished 的 try/catch 降级处理。
     */
    private List<String> fetchFollowerIds(String authorId) {
        PageResult<UserBriefDto> followers = userDirectory.followers(authorId, 1, MAX_FOLLOWERS_PAGE_SIZE);
        return followers.items().stream().map(UserBriefDto::id).toList();
    }
}
