package com.kuros.kurosbackend.interaction.web;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosbackend.interaction.service.PostInteractionService;
import com.kuros.kurosbackend.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量互动状态查询（前端审计 H-2：首页 Feed N+1 消除）。
 *
 * 为什么不挂在 PostInteractionController 的 {@code /posts/{postId}/interactions} 下：
 * 批量查询不针对单个 postId，挂在带路径参数的父路径下会让 URL 出现一个
 * 无意义的 {@code {postId}}（如 /posts/whatever/interactions/batch-states），
 * 语义混乱且容易被误读成"这一帖的批量状态"。
 *
 * 为什么单独一个端点而不是把状态塞进列表响应：列表接口对游客也开放，
 * 而关系状态是 per-user 的——未登录时它恒为 false，塞进去只会让每个响应
 * 都背一份无用字段。拆开后游客请求完全不碰这个端点。
 *
 * 为什么用 POST 而不是 GET：参数是一批 id，20 个 UUID 就是 720 字符，
 * 顶到 URL 长度限制的风险真实存在；且语义上是"提交一批 id 换状态"，
 * 放 body 更稳。这是查询型 POST（无副作用、不写任何状态）。
 */
@RestController
@RequestMapping("/api/v1/interactions")
public class InteractionStateController {

    /** 单次批量查询的帖子数上限，对齐列表接口的 MAX_PAGE_SIZE。 */
    private static final int MAX_IDS = 50;

    private final PostInteractionService interactionService;

    public InteractionStateController(PostInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 批量读「当前用户对一批帖子的赞/藏状态」。
     *
     * 响应：postId → [liked, favorited]，各为 1/0。
     * 用 1/0 而不是 true/false：JSON 数组里 [1,0] 比 [true,false] 省一半
     * 字节，且前端解构为数字后直接可用于条件判断。
     *
     * 未登录时不查 DB，返回全 0（游客没有关系状态）——这条路径必须保持
     * 匿名可达，否则首页首屏会对游客弹登录。
     *
     * 为什么用 GET 而不是 POST：SaTokenConfigure 的公开白名单只豁免 GET
     * （见那里的注释——旧版 Spring Security 白名单同样是 GET 范围）。
     * 用 POST 的话已登录用户会被 CSRF 拦截器要求带 X-XSRF-TOKEN，而游客
     * 直接被 SaToken 拦成 401，两者都破坏"匿名可达"这个核心需求。
     *
     * 为什么用逗号分隔的 ids 而不是重复参数：20 个 UUID 的重复参数形式
     * （?ids=a&ids=b...）URL 同样长，但逗号分隔更短，且服务端 split 一次
     * 即可，前端拼装也简单。上限由 MAX_IDS 兜住，避免无界查询。
     */
    @GetMapping("/batch-states")
    public ApiResponse<Map<String, int[]>> findStates(@RequestParam String ids) {
        List<String> postIds = parseIds(ids);
        String userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        Map<String, int[]> payload = new HashMap<>();
        interactionService.findStatesFor(postIds, userId).forEach((postId, state) ->
                payload.put(postId, new int[]{state[0] ? 1 : 0, state[1] ? 1 : 0}));
        return new ApiResponse<>(payload, null);
    }

    /**
     * 解析逗号分隔的 id 列表。
     *
     * 上限 MAX_IDS：这个端点服务于"一页帖子"的场景，页大小有上界
     * （CommunityPostService.MAX_PAGE_SIZE=50）。超出即拒绝，避免被
     * 当成任意批量查询接口滥用。
     */
    private List<String> parseIds(String ids) {
        List<String> parsed = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
        if (parsed.size() > MAX_IDS) {
            throw new com.kuros.kurosbackend.shared.exception.AuthRequestException(
                    "TOO_MANY_POST_IDS", "一次查询的帖子数量不能超过 " + MAX_IDS);
        }
        return parsed;
    }
}
