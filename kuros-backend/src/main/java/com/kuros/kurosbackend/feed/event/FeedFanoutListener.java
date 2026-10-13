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

import java.util.ArrayList;
import java.util.List;

/**
 * 帖子发布后的 Feed 扇出监听器。
 *
 * 触发时机：@TransactionalEventListener(phase = AFTER_COMMIT)
 * → 只在发帖事务**成功提交后**才执行，保证粉丝 timeline 推入的 postId 在 DB 已可查。
 *
 * 扇出逻辑：
 * 1. 经 Feign 取作者的粉丝列表（**翻页取全**，单页上限 5000）
 * 2. 排除作者自己（语义：自己的帖子不出现在自己的"关注流"）
 * 3. 批量 ZADD 到每个粉丝的 timeline ZSet（Pipeline 一次 RTT）
 *
 * 关于第 1 条：原实现只取第一页，粉丝 >5000 的大 V 会静默丢人（#13 之前
 * 的最低修正是取全；彻底方案是"推拉混合"——大 V 帖子不推、粉丝读时拉取合并）。
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
            timelineStore.pushToTimelines(event.postId(), event.authorId(), event.publishedAt(), followerIds);
            log.info("帖子 {} 扇出到 {} 个粉丝的 timeline", event.postId(), followerIds.size());
        } catch (Exception exception) {
            // 降级：扇出失败不影响发帖成功响应（best-effort）
            log.warn("帖子 {} 扇出失败（降级跳过）：{}", event.postId(), exception.getMessage());
        }
    }

    /**
     * 经 Feign 取作者的粉丝列表，**翻页取全**（审计 D3）。
     *
     * 原实现只请求第一页（size=5000），不看 totalPages——粉丝超过 5000 的
     * 大 V 会静默丢掉第 5001 个以后的粉丝：那些人的 timeline 里永远看不到
     * 这条帖子，且没有任何日志。生产化方向是"推拉混合"（大 V 帖子不推、
     * 粉丝读时拉取合并，见 #13），但在那之前，取全是最低要求。
     *
     * 翻页终止条件用 totalPages 而不是"本页是否满"：最后一页可能刚好不满，
     * 用"页大小 < 请求大小"判断会多发一次无用请求；且 totalPages 由服务端
     * 给出，比客户端推断可靠。
     *
     * 异常上抛，由 onPostPublished 的 try/catch 降级处理。
     */
    List<String> fetchFollowerIds(String authorId) {
        List<String> collected = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<UserBriefDto> followers = userDirectory.followers(authorId, page, MAX_FOLLOWERS_PAGE_SIZE);
            List<UserBriefDto> items = followers.items();
            if (items.isEmpty()) {
                break;
            }
            for (UserBriefDto item : items) {
                collected.add(item.id());
            }
            int totalPages = followers.meta().totalPages();
            if (page >= totalPages) {
                break;
            }
            page++;
        }
        return collected;
    }
}
