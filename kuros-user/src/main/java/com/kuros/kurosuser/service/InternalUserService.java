package com.kuros.kurosuser.service;

import com.kuros.kurosuser.api.UserBriefResponse;
import com.kuros.kurosuser.domain.CommunityUser;
import com.kuros.kurosuser.domain.UserFollow;
import com.kuros.kurosuser.repository.CommunityUserRepository;
import com.kuros.kurosuser.repository.UserFollowRepository;
import com.kuros.kurosuser.shared.api.PageMeta;
import com.kuros.kurosuser.shared.api.PageResult;
import com.kuros.kurosuser.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户目录服务（split-07 新增，内部 API 的查询面）。
 *
 * 动机（Q11-A 决策）：backend 在窗口期完全失去用户域数据源——作者组装降级为占位、
 * 资料页 503；split-08 起 backend 经 OpenFeign 消费这些内部接口重新组装组合视图。
 * 也就是说，本类的方法面是 split-08 的契约前置：批量用户摘要 + 关注/粉丝分页。
 *
 * 为什么批量查询要"保留请求顺序、跳过未知 ID"：
 * 消费方（内容域作者组装）手里是"帖子列表 → 作者 ID 列表"，它需要按原序对齐回填；
 * 未知 ID 静默跳过而不是整体报错——一条帖子的作者缺失不应让整页列表 500/404。
 * 单点查询的 404 语义由 UserFollowService.find 承担（follow-stats 走那条路）。
 */
@Service
@Transactional(readOnly = true)
public class InternalUserService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityUserRepository userRepository;
    private final UserFollowRepository followRepository;

    public InternalUserService(CommunityUserRepository userRepository, UserFollowRepository followRepository) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
    }

    /**
     * 批量用户摘要：按输入顺序返回命中项，未知 ID 跳过。
     * 去重用 LinkedHashSet：重复 ID 不放大查询，同时保持首次出现顺序（Stream.distinct 的有序性不够显式）。
     */
    public List<UserBriefResponse> findBriefs(List<String> ids) {
        List<String> ordered = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (ordered.isEmpty()) {
            return List.of();
        }
        Map<String, CommunityUser> usersById = usersById(ordered);
        return ordered.stream().map(usersById::get).filter(Objects::nonNull).map(UserBriefResponse::from).toList();
    }

    /** 某用户的关注列表（该用户关注了谁）。用户不存在 → 404（与公开端点语义一致）。 */
    public PageResult<UserBriefResponse> findFollowing(String userId, int page, int pageSize) {
        ensureUser(userId);
        return toBriefPage(followRepository.findByFollowerIdOrderByCreatedAtDesc(userId, pageRequest(page, pageSize)), UserFollow::getFollowedId);
    }

    /** 某用户的粉丝列表（谁关注了该用户）。 */
    public PageResult<UserBriefResponse> findFollowers(String userId, int page, int pageSize) {
        ensureUser(userId);
        return toBriefPage(followRepository.findByFollowedIdOrderByCreatedAtDesc(userId, pageRequest(page, pageSize)), UserFollow::getFollowerId);
    }

    private PageResult<UserBriefResponse> toBriefPage(Page<UserFollow> follows, Function<UserFollow, String> idOf) {
        List<String> ids = follows.getContent().stream().map(idOf).toList();
        Map<String, CommunityUser> usersById = usersById(ids);
        List<UserBriefResponse> items = ids.stream()
                .map(usersById::get)
                .filter(Objects::nonNull)
                .map(UserBriefResponse::from)
                .toList();
        return new PageResult<>(items, new PageMeta(follows.getNumber() + 1, follows.getSize(), follows.getTotalElements(), follows.getTotalPages()));
    }

    private Map<String, CommunityUser> usersById(List<String> ids) {
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(CommunityUser::getId, Function.identity()));
    }

    private Pageable pageRequest(int page, int pageSize) {
        // 归一化与 backend 端旧实现一致：页码下限 1、页大小钳制在 [1, 50]
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        return PageRequest.of(normalizedPage - 1, normalizedPageSize);
    }

    private void ensureUser(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("用户不存在");
        }
    }
}
