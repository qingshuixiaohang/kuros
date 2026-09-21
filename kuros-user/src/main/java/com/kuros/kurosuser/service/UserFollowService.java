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
 * 关注服务（split-07 自 kuros-backend 迁入，行为逐字保持）。
 *
 * 迁移的关键承诺：并发防重复行为不变——防重与迁移前一致，由三道防线构成：
 * ① 分布式锁把同一对 (target, follower) 的并发请求串行化：绝大多数竞争者在
 *    tryLock 失败时直接拿到 find() 快照返回，根本不进入写路径；
 * ② 锁内 existsById 判断，正常时序下阻止重复写入（迁移前的常态路径）；
 * ③ (follower_id, followed_id) 复合主键兜底：类级 @Transactional 让事务由代理
 *    在方法体之前开启（TransactionTemplate 以 REQUIRED 加入外层、无独立提交），
 *    因此实际时序是 tx begin → lock → 业务 → unlock → tx commit——unlock 先于
 *    commit 存在极窄交错窗口，后者可能读到旧快照并尝试插入，此时唯一约束把
 *    重复挡成冲突而非重复行。"锁包裹事务"（unlock 在 commit 之后）需去掉类级
 *    注解，属并发行为增强而非本次迁移范围，留待后续切片评估。
 *
 * 为什么锁获取失败直接返回当前状态（而不是抛错/重试）：
 * follow/unfollow 是幂等语义，锁失败说明有并发请求正在处理同一对关系，
 * 直接返回 find() 的当前快照即可——调用方拿到的是"可能稍旧但不会错"的状态，
 * 重复调用会收敛到最终一致（这也是接口幂等的体现）。
 *
 * 用户存在性校验（findUser）在本服务成为权威：迁移前查 backend 的 users 副本，
 * 现在查本库（kuros_user）——被关注方与关注方都只在本服务被验证。
 */
@Service
@Transactional
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

    // 设计意图是锁包裹事务（lock → tx → unlock）；类级 @Transactional 下实际为
    // tx begin → lock → 业务 → unlock → commit，防重三道防线见类注释
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
