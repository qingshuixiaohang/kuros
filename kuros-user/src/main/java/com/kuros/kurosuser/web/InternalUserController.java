package com.kuros.kurosuser.web;

import com.kuros.kurosuser.api.UserBriefResponse;
import com.kuros.kurosuser.api.UserFollowResponse;
import com.kuros.kurosuser.service.InternalUserService;
import com.kuros.kurosuser.service.UserFollowService;
import com.kuros.kurosuser.shared.api.ApiResponse;
import com.kuros.kurosuser.shared.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部 API（split-07 新增，Q11-A 决策的内部契约面）。
 *
 * 定位与消费方：不经网关、面向服务间调用——split-08 起 backend 的
 * ProfileServiceImpl 与内容域作者组装经 OpenFeign 消费这里的接口，
 * 把"被拆掉的本库 JOIN"重建为跨服务组合。
 *
 * 安全边界（sec-01 A2 漏洞修复后，两层纵深）：
 * - 应用层：本前缀要求 X-Internal-Token 头且值等于 app.internal.token，
 *   由 InternalTokenInterceptor 校验，未配置密钥时全部 401（fail-closed）。
 *   backend 侧由 InternalTokenRequestInterceptor 自动带上该头。
 * - 网络层：compose 只把端口绑到宿主机 127.0.0.1（不再 0.0.0.0），
 *   内部调用走 compose 服务名，宿主机网络不可达。
 * 本前缀在 SaTokenConfigure 中仍显式 notMatch（不走会话鉴权，服务间调用不带
 * 用户 Cookie）；CSRF 拦截器覆盖 /api/**，天然不涉及 /internal/**。
 * 修复前这里是零鉴权状态：任何能连到该端口的人都能批量导出全站用户与关注关系。
 *
 * 路径与响应结构对齐 backend 既有分页约定（items + meta），
 * 让 split-08 的 Feign 解码零适配成本。
 */
@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserController {

    private final InternalUserService internalUserService;
    private final UserFollowService followService;

    public InternalUserController(InternalUserService internalUserService, UserFollowService followService) {
        this.internalUserService = internalUserService;
        this.followService = followService;
    }

    /**
     * 批量用户摘要：?ids=a,b,c（Spring 自动按逗号拆分）。
     * 按输入顺序返回、未知 ID 跳过（见 InternalUserService.findBriefs 的语义说明）。
     */
    @GetMapping("/batch")
    public ApiResponse<List<UserBriefResponse>> batch(@RequestParam("ids") List<String> ids) {
        return new ApiResponse<>(internalUserService.findBriefs(ids), null);
    }

    /**
     * 关注状态/计数：可携带可选的 viewerId（无则匿名视角，followed=false）。
     * 目标不存在 → 404 USER_NOT_FOUND（与公开端点同一语义，复用 UserFollowService.find）。
     */
    @GetMapping("/{userId}/follow-stats")
    public ApiResponse<UserFollowResponse> followStats(
            @PathVariable String userId,
            @RequestParam(required = false) String viewerId
    ) {
        return new ApiResponse<>(followService.find(userId, viewerId), null);
    }

    @GetMapping("/{userId}/following")
    public ApiResponse<List<UserBriefResponse>> following(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        PageResult<UserBriefResponse> result = internalUserService.findFollowing(userId, page, pageSize);
        return new ApiResponse<>(result.items(), result.meta());
    }

    @GetMapping("/{userId}/followers")
    public ApiResponse<List<UserBriefResponse>> followers(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        PageResult<UserBriefResponse> result = internalUserService.findFollowers(userId, page, pageSize);
        return new ApiResponse<>(result.items(), result.meta());
    }
}
