package com.kuros.kurosbackend.user.auth;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * SaToken 权限数据源实现（split-07 后为 Redis-only）。
 *
 * 为什么只读 Redis、不再回源 DB：
 * 角色/权限表（sys_role、sys_permission 等）随用户域整体迁往 kuros-user
 * （V10 从本库 DROP），本服务已无权限数据的权威副本；登录时 kuros-user 的
 * AuthService 会把角色/权限写入共享 Redis（同一 key 契约
 * auth:roles:{userId} / auth:permissions:{userId}），本服务直接读缓存即可。
 *
 * 缓存缺失时返回空列表（=无角色/无权限）而不是报错或放行：
 * SaToken 会把空列表当作鉴权失败（403）——这正是缺数据时的安全默认值，
 * 宁可拒绝、不可放行；且正常链路（登录先写缓存）不会走到这里。
 *
 * 对应小哈书第五章：SaToken 权限认证 → StpInterface 实现。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private static final String ROLE_CACHE_KEY = "auth:roles:";
    private static final String PERMISSION_CACHE_KEY = "auth:permissions:";

    private final StringRedisTemplate redisTemplate;

    public StpInterfaceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 返回用户拥有的权限 code 列表。
     * SaToken 在 StpUtil.checkPermission("report:handle") 时调用此方法。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Set<String> cached = redisTemplate.opsForSet().members(PERMISSION_CACHE_KEY + loginId);
        return cached == null ? List.of() : List.copyOf(cached);
    }

    /**
     * 返回用户拥有的角色 code 列表。
     * SaToken 在 StpUtil.checkRole("ADMIN") 时调用此方法。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Set<String> cached = redisTemplate.opsForSet().members(ROLE_CACHE_KEY + loginId);
        return cached == null ? List.of() : List.copyOf(cached);
    }
}
