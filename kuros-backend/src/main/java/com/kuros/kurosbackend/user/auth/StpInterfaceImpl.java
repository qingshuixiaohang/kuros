package com.kuros.kurosbackend.user.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.kuros.kurosbackend.user.repository.SysPermissionRepository;
import com.kuros.kurosbackend.user.repository.SysRoleRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * SaToken 权限数据源实现。
 *
 * SaToken 在调用 StpUtil.checkRole() / StpUtil.checkPermission() 时，
 * 会通过这个接口查询当前用户拥有的角色和权限列表。
 *
 * 为什么先查 Redis 再查 DB？
 * 登录时我们已经把角色和权限同步到了 Redis（AuthService.login），
 * 这里优先从 Redis 读取（O(1)），只有 Redis 缺失时才回源 DB。
 * 这样每次鉴权请求不需要查数据库，认证性能从 2 次 DB 查询降为 1 次 Redis 查询。
 *
 * 对应小哈书第五章：SaToken 权限认证 → StpInterface 实现。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private static final String ROLE_CACHE_KEY = "auth:roles:";
    private static final String PERMISSION_CACHE_KEY = "auth:permissions:";

    private final StringRedisTemplate redisTemplate;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;

    public StpInterfaceImpl(StringRedisTemplate redisTemplate,
                            SysRoleRepository roleRepository,
                            SysPermissionRepository permissionRepository) {
        this.redisTemplate = redisTemplate;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * 返回用户拥有的权限 code 列表。
     * SaToken 在 StpUtil.checkPermission("report:handle") 时调用此方法。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String userId = loginId.toString();
        // 优先从 Redis 读取（登录时已同步）
        Set<String> cached = redisTemplate.opsForSet().members(PERMISSION_CACHE_KEY + userId);
        if (cached != null && !cached.isEmpty()) {
            return List.copyOf(cached);
        }
        // Redis 缺失（可能是 Redis 重启或首次），回源 DB 并重新缓存
        List<String> permissions = permissionRepository.findPermissionCodesByUserId(userId);
        if (!permissions.isEmpty()) {
            redisTemplate.opsForSet().add(PERMISSION_CACHE_KEY + userId, permissions.toArray(String[]::new));
        }
        return permissions;
    }

    /**
     * 返回用户拥有的角色 code 列表。
     * SaToken 在 StpUtil.checkRole("ADMIN") 时调用此方法。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        String userId = loginId.toString();
        Set<String> cached = redisTemplate.opsForSet().members(ROLE_CACHE_KEY + userId);
        if (cached != null && !cached.isEmpty()) {
            return List.copyOf(cached);
        }
        List<String> roles = roleRepository.findRoleCodesByUserId(userId);
        if (!roles.isEmpty()) {
            redisTemplate.opsForSet().add(ROLE_CACHE_KEY + userId, roles.toArray(String[]::new));
        }
        return roles;
    }
}
