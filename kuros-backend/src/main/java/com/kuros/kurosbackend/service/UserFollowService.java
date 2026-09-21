package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.UserFollowResponse;
import com.kuros.kurosbackend.domain.CommunityUser;
import com.kuros.kurosbackend.domain.UserFollow;
import com.kuros.kurosbackend.domain.UserFollowId;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import com.kuros.kurosbackend.repository.UserFollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
@Transactional
public class UserFollowService {

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

    @Transactional(readOnly = true)
    public UserFollowResponse find(String targetUserId, String followerId) {
        findUser(targetUserId);
        boolean followed = followerId != null && followRepository.existsById(new UserFollowId(followerId, targetUserId));
        return new UserFollowResponse(targetUserId, followRepository.countByFollowedId(targetUserId), followed);
    }

    // 分布式锁必须包裹事务：lock → [tx begin → 业务 → tx commit] → unlock
    public UserFollowResponse follow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        String lockValue = distributedLock.tryLock("lock:follow:" + targetUserId + ":" + followerId, 3);
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
            distributedLock.unlock("lock:follow:" + targetUserId + ":" + followerId, lockValue);
        }
    }

    // unfollow 也需要锁，防止并发取消导致数据不一致
    public UserFollowResponse unfollow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        String lockValue = distributedLock.tryLock("lock:follow:" + targetUserId + ":" + followerId, 3);
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
            distributedLock.unlock("lock:follow:" + targetUserId + ":" + followerId, lockValue);
        }
    }

    private CommunityUser findUser(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
    }

    private void ensureNotSelf(String targetUserId, String followerId) {
        if (targetUserId.equals(followerId)) {
            throw new AuthRequestException("FOLLOW_SELF_NOT_ALLOWED", "不能关注自己");
        }
    }
}
