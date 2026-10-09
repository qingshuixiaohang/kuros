package com.kuros.kurosbackend.user.client;

import com.kuros.kurosbackend.shared.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * kuros-user 内部 API 的 Feign 客户端（split-08）。
 *
 * 服务发现：name="kuros-user" 经 Nacos + Spring Cloud LoadBalancer 解析为 lb://kuros-user。
 * url 属性留空时走服务发现；测试用 app.feign.kuros-user.url 注入 JDK HttpServer 桩地址直连，
 * 这样桩测试无需起 Nacos（秒级），真实 lb 链路另由 NacosFeignIntegrationTest 覆盖。
 *
 * 契约对齐：返回体是 kuros-user 的 ApiResponse（data + meta），与 backend 自己的
 * ApiResponse 逐字同形（split-06/07 迁移时刻意保持），Feign + Jackson 解码零适配；
 * 用户摘要单元用 backend 侧独立定义的 UserBriefDto（字段与 kuros-user 的
 * UserBriefResponse 一致），避免跨工程编译依赖。
 *
 * 安全边界（sec-01 A2 漏洞修复后）：/internal/** 在 kuros-user 侧要求
 * X-Internal-Token 共享密钥（不再零鉴权），由本包 InternalTokenFeignConfig 注册的
 * InternalTokenRequestInterceptor 自动注入——客户端无需在每个方法上带 @RequestHeader。
 * 密钥未配置时不加头，服务端 fail-closed 返回 401（宁可链路显式断开，也不静默放行）。
 * 网络侧另有一层：compose 不再把 user 端口发布到宿主机 0.0.0.0（只绑 127.0.0.1），
 * 内部调用走 compose 服务名。两层缺一不可——单靠任一层都留"配置写错就全裸"的口子。
 */
@FeignClient(name = "kuros-user", url = "${app.feign.kuros-user.url:}")
public interface UserDirectoryClient {

    /**
     * 批量用户摘要：GET /internal/v1/users/batch?ids=a,b,c。
     * 服务端按输入顺序返回命中项、未知 ID 跳过（见 kuros-user InternalUserService.findBriefs）。
     * Feign 把 List 参数编码为重复查询参数 ids=a&ids=b，Spring MVC 侧正常绑定为 List。
     */
    @GetMapping("/internal/v1/users/batch")
    ApiResponse<List<UserBriefDto>> batch(@RequestParam("ids") List<String> ids);

    /** 某用户的关注列表（该用户关注了谁），分页。用户不存在 → 服务端 404。 */
    @GetMapping("/internal/v1/users/{userId}/following")
    ApiResponse<List<UserBriefDto>> following(
            @PathVariable("userId") String userId,
            @RequestParam("page") int page,
            @RequestParam("pageSize") int pageSize);

    /** 某用户的粉丝列表（谁关注了该用户），分页。用户不存在 → 服务端 404。 */
    @GetMapping("/internal/v1/users/{userId}/followers")
    ApiResponse<List<UserBriefDto>> followers(
            @PathVariable("userId") String userId,
            @RequestParam("page") int page,
            @RequestParam("pageSize") int pageSize);
}
