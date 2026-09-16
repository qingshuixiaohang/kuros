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

import java.time.LocalDateTime;

@Service
@Transactional
public class UserFollowService {

    private final CommunityUserRepository userRepository;
    private final UserFollowRepository followRepository;

    public UserFollowService(CommunityUserRepository userRepository, UserFollowRepository followRepository) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
    }

    @Transactional(readOnly = true)
    public UserFollowResponse find(String targetUserId, String followerId) {
        findUser(targetUserId);
        boolean followed = followerId != null && followRepository.existsById(new UserFollowId(followerId, targetUserId));
        return new UserFollowResponse(targetUserId, followRepository.countByFollowedId(targetUserId), followed);
    }

    public UserFollowResponse follow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        findUser(targetUserId);
        UserFollowId id = new UserFollowId(followerId, targetUserId);
        if (!followRepository.existsById(id)) {
            followRepository.save(new UserFollow(followerId, targetUserId, LocalDateTime.now()));
        }
        return find(targetUserId, followerId);
    }

    public UserFollowResponse unfollow(String targetUserId, String followerId) {
        ensureNotSelf(targetUserId, followerId);
        findUser(targetUserId);
        UserFollowId id = new UserFollowId(followerId, targetUserId);
        if (followRepository.existsById(id)) {
            followRepository.deleteById(id);
        }
        return find(targetUserId, followerId);
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
