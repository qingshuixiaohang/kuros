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
 * 安全边界：/internal/** 在 kuros-user 侧不经会话鉴权（SaToken notMatch），
 * 依赖 compose 网络隔离——生产化前须在网关/服务网格层限制内部前缀仅集群内可达。
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
