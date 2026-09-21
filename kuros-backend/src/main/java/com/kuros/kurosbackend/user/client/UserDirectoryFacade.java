package com.kuros.kurosbackend.user.client;

import com.kuros.kurosbackend.shared.api.ApiResponse;
import com.kuros.kurosbackend.shared.api.AuthorResponse;
import com.kuros.kurosbackend.shared.api.PageMeta;
import com.kuros.kurosbackend.shared.api.PageResult;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.shared.exception.ServiceUnavailableException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * kuros-user 用户目录的门面（split-08）：把裸 Feign 客户端包成"带降级策略"的领域查询。
 *
 * 为什么要这层门面而不是让内容域/资料域直接注入 UserDirectoryClient：
 * 降级策略（工单 #3）在不同调用面语义相反——内容域列表要"失败也不 500"，
 * 资料页要"失败即 503"。把这套 try/catch + 异常映射收敛到一处，
 * 调用方只表达意图（findAuthors = 尽力而为、requireUser = 必须成功），
 * 不必各自重复 FeignException 的分类处理。
 *
 * 两条降级路线：
 * - findAuthors：吞掉一切 Feign 失败返回空 map → 内容域对未命中 id 回退占位作者，列表照常 200
 * - requireUser / following / followers：失败 → 503（ServiceUnavailable），未知 id → 404（ResourceNotFound）
 */
@Service
public class UserDirectoryFacade {

    /** 用户域不可用或作者未知时，内容域作者卡片的中性占位昵称（区别于 split-07 窗口期的"未知漂泊者"）。 */
    public static final String FALLBACK_NICKNAME = "用户";

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryFacade.class);

    private final UserDirectoryClient client;

    public UserDirectoryFacade(UserDirectoryClient client) {
        this.client = client;
    }

    /**
     * 内容域作者批量组装：一批 authorId → id→摘要 映射。
     *
     * 降级（工单 #3）：任何 Feign 失败（连接拒绝 / 超时 / 5xx）都吞掉并返回空 map，
     * 调用方对未命中的 id 回退占位作者——列表 / 详情绝不因用户域抖动而 500。
     * kuros-user 侧本就不存在的未知 id 也不在返回里，与"失败"走同一条占位路径，
     * 因此这里无需区分二者（都表现为 map.get(id) == null）。
     */
    public Map<String, UserBriefDto> findAuthors(Collection<String> ids) {
        List<String> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            ApiResponse<List<UserBriefDto>> response = client.batch(distinct);
            List<UserBriefDto> data = response == null ? null : response.data();
            if (data == null) {
                return Map.of();
            }
            return data.stream()
                    .filter(user -> user != null && user.id() != null)
                    .collect(Collectors.toMap(UserBriefDto::id, Function.identity(), (first, second) -> first));
        } catch (RuntimeException e) {
            // 只 warn 不 error：这是计划内的优雅降级，不是缺陷
            log.warn("批量用户摘要降级为空（kuros-user 暂不可用）：{}", e.toString());
            return Map.of();
        }
    }

    /**
     * 内容域作者卡片组装：把批量结果里的某个 id 转成 AuthorResponse。
     * 未命中（降级 / 未知作者）→ 中性占位"用户" + 空头像，但保留 authorId——
     * 前端关注按钮据此仍能定位目标用户（与 split-07 占位设计同理）。
     */
    public AuthorResponse toAuthor(String authorId, Map<String, UserBriefDto> authors) {
        UserBriefDto user = authors.get(authorId);
        return user != null
                ? new AuthorResponse(authorId, user.nickname(), user.avatarUrl(), user.bio())
                : new AuthorResponse(authorId, FALLBACK_NICKNAME, null, null);
    }

    /**
     * 资料页单用户：与 findAuthors 相反，失败必须让调用方感知。
     * - Feign 失败（kuros-user 不可用）→ 503 ServiceUnavailable
     * - 命中不到（未知 id，batch 静默跳过）→ 404 ResourceNotFound
     *
     * 为什么复用 batch 而非单查接口：kuros-user 内部契约只暴露 batch/following/followers，
     * batch([id]) 命中即返回单元素、未命中返回空列表，语义足够，无需为单查扩契约面。
     */
    public UserBriefDto requireUser(String id) {
        ApiResponse<List<UserBriefDto>> response;
        try {
            response = client.batch(List.of(id));
        } catch (RuntimeException e) {
            log.warn("读取用户资料失败，降级为 503（kuros-user 暂不可用）：{}", e.toString());
            throw new ServiceUnavailableException("用户资料暂不可用");
        }
        List<UserBriefDto> data = response == null ? null : response.data();
        if (data == null || data.isEmpty()) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return data.get(0);
    }

    /**
     * 关注列表分页（个人中心用）：失败 → 503，目标用户不存在（服务端 404）→ 404。
     * 返回 PageResult 携带 meta（totalItems/totalPages），供 findOwn 直接组装分页视图。
     */
    public PageResult<UserBriefDto> following(String userId, int page, int pageSize) {
        return toPage(callDirectory(() -> client.following(userId, page, pageSize)));
    }

    /** 粉丝列表分页：语义与 {@link #following} 对称。 */
    public PageResult<UserBriefDto> followers(String userId, int page, int pageSize) {
        return toPage(callDirectory(() -> client.followers(userId, page, pageSize)));
    }

    private ApiResponse<List<UserBriefDto>> callDirectory(Supplier<ApiResponse<List<UserBriefDto>>> call) {
        try {
            return call.get();
        } catch (FeignException.NotFound e) {
            // 服务端 ensureUser 判定目标用户不存在 → 映射为 404，与公开资料端点同一语义
            throw new ResourceNotFoundException("用户不存在");
        } catch (RuntimeException e) {
            log.warn("读取关注关系失败，降级为 503（kuros-user 暂不可用）：{}", e.toString());
            throw new ServiceUnavailableException("用户资料暂不可用");
        }
    }

    private PageResult<UserBriefDto> toPage(ApiResponse<List<UserBriefDto>> response) {
        List<UserBriefDto> items = response == null || response.data() == null ? List.of() : response.data();
        PageMeta meta = response == null ? null : response.meta();
        // 服务端正常都会带 meta；兜底防止桩/异常响应缺 meta 时 NPE
        if (meta == null) {
            meta = new PageMeta(1, items.size(), items.size(), items.isEmpty() ? 0 : 1);
        }
        return new PageResult<>(items, meta);
    }
}
