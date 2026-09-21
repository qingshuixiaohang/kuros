package com.kuros.kurosuser.service;

import com.kuros.kurosuser.api.UserFollowResponse;
import com.kuros.kurosuser.domain.CommunityUser;
import com.kuros.kurosuser.domain.UserFollow;
import com.kuros.kurosuser.domain.UserFollowId;
import com.kuros.kurosuser.repository.CommunityUserRepository;
import com.kuros.kurosuser.repository.UserFollowRepository;
import com.kuros.kurosuser.shared.exception.AuthRequestException;
import com.kuros.kurosuser.shared.exception.ResourceNotFoundException;
import com.kuros.kurosuser.shared.lock.DistributedLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 关注服务（split-07 自 kuros-backend 迁入；split-08 收尾修复锁-事务竞态）。
 *
 * 并发防重复由三道防线构成，且时序保证“锁包裹事务”：
 * ① 分布式锁把同一对 (target, follower) 的并发请求串行化：绝大多数竞争者在
 *    tryLock 失败时直接拿到 find() 快照返回，根本不进入写路径；
 * ② 锁内 existsById 判断阻止重复写入；
 * ③ (follower_id, followed_id) 复合主键兜底。
 *
 * 为什么刻意去掉类级 @Transactional（split-08 修复的关键）：
 * 类级注解会让代理在方法体之前开启事务、方法返回之后才提交，而锁在方法体的
 * finally 里释放——实际时序退化成 tx begin → lock → 业务 → unlock → tx commit，
 * “unlock 先于 commit”留下一个极窄窗口：某个启动稍晚的线程恰在赢家已释放锁、
 * 事务却尚未提交时拿到锁，它的 existsById 读不到那条未提交的行，于是重复 insert
 * 撞上复合主键 → DataIntegrityViolationException → 500（并发测试随机飘红的根因）。
 * 去掉类级注解后，follow/unfollow 各自用 TransactionTemplate 在锁内开启并提交事务，
 * 时序变成 lock → tx begin → 业务 → tx commit → unlock：解锁时数据已落库可见，
 * 后到的竞争者要么 tryLock 失败走幂等快照、要么进锁后 existsById 命中而跳过写入，
 * 窗口彻底关闭。这正是 split-07 注释里“留待后续切片评估”的并发行为增强。
 *
 * 为什么锁获取失败直接返回当前状态（而不是抛错/重试）：
 * follow/unfollow 是幂等语义，锁失败说明有并发请求正在处理同一对关系，
 * 直接返回 find() 的当前快照即可——调用方拿到的是“可能稍旧但不会错”的状态，
 * 重复调用会收敛到最终一致（这也是接口幂等的体现）。
 *
 * 用户存在性校验（findUser）在本服务成为权威：迁移前查 backend 的 users 副本，
 * 现在查本库（kuros_user）——被关注方与关注方都只在本服务被验证。
 */
@Service
public class UserFollowService {

    // 锁 key 与迁移前逐字一致：迁移期若两侧短暂共存（回滚场景），同一把锁仍互斥
    private static final String FOLLOW_LOCK_PREFIX = "lock:follow:";
    private static final int LOCK_TTL_SECONDS = 3;

    private final CommunityUserRepository userRepository;
    private final UserFollowRepository followRepository;
    private final DistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;

    public UserFollowService(CommunityUserRepository userRepository, UserFollowRepository followRepository, DistributedLock distributedLock, TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.distributedLock = distributedLock;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 查询关注状态。viewerId 可为空（匿名视角：只回计数）。
     * 内部 API 的 follow-stats 也走这里——404 语义（目标不存在）与公开端点一致。
     */
    @Transactional(readOnly = true)
    public UserFollowResponse find(String targetUserId, String followerId) {
        findUser(targetUserId);
        boolean followed = followerId != null && followRepository.existsById(new UserFollowId(followerId, targetUserId));
        return new UserFollowResponse(targetUserId, followRepository.countByFollowedId(targetUserId), followed);
    }

    // 锁包裹事务：TransactionTemplate 在锁内开启并提交事务（commit 先于 unlock），
    // 关闭“解锁后事务未提交”的竞态窗口，防重三道防线见类注释
    public UserFollowResponse follow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        String key = FOLLOW_LOCK_PREFIX + targetUserId + ":" + followerId;
        String lockValue = distributedLock.tryLock(key, LOCK_TTL_SECONDS);
        if (lockValue == null) return find(targetUserId, followerId);
        try {
            return transactionTemplate.execute(status -> {
                findUser(targetUserId);
                UserFollowId id = new UserFollowId(followerId, targetUserId);
                if (!followRepository.existsById(id)) {
                    followRepository.save(new UserFollow(followerId, targetUserId, LocalDateTime.now()));
                }
                return find(targetUserId, followerId);
            });
        } finally {
            distributedLock.unlock(key, lockValue);
        }
    }

    // unfollow 也需要锁，防止并发取消导致数据不一致
    public UserFollowResponse unfollow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        String key = FOLLOW_LOCK_PREFIX + targetUserId + ":" + followerId;
        String lockValue = distributedLock.tryLock(key, LOCK_TTL_SECONDS);
        if (lockValue == null) return find(targetUserId, followerId);
        try {
            return transactionTemplate.execute(status -> {
                findUser(targetUserId);
                UserFollowId id = new UserFollowId(followerId, targetUserId);
                if (followRepository.existsById(id)) {
                    followRepository.deleteById(id);
                }
                return find(targetUserId, followerId);
            });
        } finally {
            distributedLock.unlock(key, lockValue);
        }
    }

    private CommunityUser findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
    }

    private void ensureNotSelf(String targetUserId, String followerId) {
        if (targetUserId.equals(followerId)) {
            throw new AuthRequestException("FOLLOW_SELF_NOT_ALLOWED", "不能关注自己");
        }
    }
}
