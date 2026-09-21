package com.kuros.kurosuser.service;

import cn.dev33.satoken.stp.StpUtil;
import com.kuros.kurosuser.api.AuthUserResponse;
import com.kuros.kurosuser.api.PhoneCodeRequest;
import com.kuros.kurosuser.api.PhoneLoginRequest;
import com.kuros.kurosuser.api.VerificationCodeResponse;
import com.kuros.kurosuser.auth.VerificationCodeService;
import com.kuros.kurosuser.domain.CommunityUser;
import com.kuros.kurosuser.domain.UserRole;
import com.kuros.kurosuser.domain.UserStatus;
import com.kuros.kurosuser.repository.CommunityUserRepository;
import com.kuros.kurosuser.repository.SysPermissionRepository;
import com.kuros.kurosuser.repository.SysRoleRepository;
import com.kuros.kurosuser.shared.exception.AuthRequestException;
import com.kuros.kurosuser.shared.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务（split-06 自 kuros-backend 迁入）。
 *
 * 认证的权威归属已迁移到 kuros-user：登录建号、会话创建、角色/权限同步
 * 全部发生在本服务；backend 不再持有认证代码（/api/v1/auth/** 直连返回 404，
 * 经网关则路由到本服务）。
 *
 * 与迁移前的核心设计（SaToken + Redis 版本）：
 * - 登录不再手动生成 random token + 写 MySQL user_sessions 表
 * - 改为调用 StpUtil.login(userId)，SaToken 自动管理 Token 生成、Redis 存储、Cookie 设置
 * - 登出不再手动删 MySQL 记录，改为 StpUtil.logout()
 * - 获取当前用户不再从 Cookie 读 token + 查 DB，改为 StpUtil.getLoginIdAsString()
 * - 登录后同步角色/权限到 Redis，后续鉴权请求不再查 DB
 *
 * "拆服务不拆会话"的两个落点：
 * 1. SaToken 配置（token-name/timeout/is-read-cookie/cookie-same-site）与 backend 逐字相同，
 *    两侧共享同一 Redis + 同名 Cookie —— 经网关在本服务登录后的会话，backend 直接可见
 * 2. auth:roles:/auth:permissions: 缓存写共享 Redis，backend 的管理员鉴权不受拆分影响
 *
 * 对应小哈书第五章：SaToken 登录/登出/会话管理。
 */
@Service
public class AuthService {

    private static final String ROLE_CACHE_KEY = "auth:roles:";
    private static final String PERMISSION_CACHE_KEY = "auth:permissions:";

    private final CommunityUserRepository userRepository;
    private final VerificationCodeService verificationCodeService;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final StringRedisTemplate redisTemplate;
    private final int codeExpirationSeconds;
    private final boolean devCodeExposed;

    public AuthService(
            CommunityUserRepository userRepository,
            VerificationCodeService verificationCodeService,
            SysRoleRepository roleRepository,
            SysPermissionRepository permissionRepository,
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.code-expiration-seconds:300}") int codeExpirationSeconds,
            @Value("${app.auth.dev-code-exposed:true}") boolean devCodeExposed
    ) {
        this.userRepository = userRepository;
        this.verificationCodeService = verificationCodeService;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.redisTemplate = redisTemplate;
        this.codeExpirationSeconds = codeExpirationSeconds;
        this.devCodeExposed = devCodeExposed;
    }

    public VerificationCodeResponse issueCode(PhoneCodeRequest request) {
        String phone = normalizePhone(request.phone());
        String devCode = verificationCodeService.issue(phone);
        return new VerificationCodeResponse(codeExpirationSeconds, 60, devCodeExposed ? devCode : null);
    }

    /**
     * 登录流程（SaToken 版本）：
     * 1. 验证码校验（Redis）
     * 2. 查找或创建用户
     * 3. StpUtil.login(userId) → SaToken 自动生成 Token、存 Redis、设 Cookie
     * 4. 同步角色/权限到 Redis（后续鉴权不再查 DB）
     * 5. 同步 users.role 冗余字段（保持现有 API 响应格式不变）
     */
    @Transactional
    public LoginResult login(PhoneLoginRequest request) {
        String phone = normalizePhone(request.phone());
        if (!verificationCodeService.verify(phone, request.code())) {
            throw new AuthRequestException("INVALID_VERIFICATION_CODE", "验证码无效或已过期");
        }

        CommunityUser user = userRepository.findByPhone(phone).orElseGet(() -> createUser(phone));

        // SaToken 登录：自动生成 Token、存入 Redis、通过 Cookie 返回给前端
        // 为什么不用手动 set-cookie？因为 SaToken 配置了 is-read-cookie=true + token-name=KUROS_SESSION，
        // 它会自动在响应中设置 HttpOnly Cookie，前端零改动（配置随迁移逐字复制，Cookie 名不变）。
        StpUtil.login(user.getId());

        // 同步 users.role 冗余字段：保持 AuthUserResponse 格式不变，前端不用改
        syncRoleField(user);

        // 事务提交后再同步 RBAC 数据到 Redis，避免 DB 回滚时 Redis 产生孤儿会话
        // 为什么延迟？因为 Redis 是非事务资源，如果 syncPermissionsToRedis 在事务内执行，
        // 而后续的 syncRoleField 失败导致 DB 回滚，Redis 中已写入的权限缓存无法回滚，
        // 导致缓存与 DB 不一致。延迟到事务提交后执行，保证 DB 已持久化再写缓存。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                syncPermissionsToRedis(user.getId());
            }
        });

        String token = StpUtil.getTokenValue();
        long maxAgeSeconds = StpUtil.getTokenTimeout();
        return new LoginResult(toResponse(user), token, maxAgeSeconds);
    }

    /**
     * 获取当前登录用户（SaToken 版本）。
     * 不再从 Cookie 手动读 token + 查 DB，而是直接从 SaToken 会话中获取 userId。
     */
    @Transactional(readOnly = true)
    public AuthUserResponse currentUser() {
        return toResponse(currentUserEntity());
    }

    @Transactional(readOnly = true)
    public CommunityUser currentUserEntity() {
        // StpUtil.getLoginIdAsString() 内部从 Redis 读取当前 Token 对应的 userId
        // 如果未登录或 Token 过期，SaToken 会抛出 NotLoginException（由全局异常处理器转为 401）
        String userId;
        try {
            userId = StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            throw new UnauthorizedException("请先登录");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("用户不存在，请重新登录"));
    }

    /**
     * 登出（SaToken 版本）。
     * StpUtil.logout() 会自动删除 Redis 中的 Token 数据，并清除 Cookie。
     */
    public void logout() {
        try {
            String userId = StpUtil.getLoginIdAsString();
            // 清除 Redis 中的权限缓存
            redisTemplate.delete(ROLE_CACHE_KEY + userId);
            redisTemplate.delete(PERMISSION_CACHE_KEY + userId);
            StpUtil.logout();
        } catch (Exception ignored) {
            // 未登录时调用 logout 不报错，保持幂等
        }
    }

    public String normalizePhone(String phone) {
        if (phone == null || !phone.matches("1\\d{10}")) {
            throw new AuthRequestException("INVALID_PHONE", "请输入正确的 11 位手机号");
        }
        return phone;
    }

    /**
     * 登录时将角色和权限同步到 Redis。
     * 为什么在登录时同步而不是每次鉴权时查 DB？
     * 因为鉴权是高频操作（每个请求都要），登录是低频操作（用户一天一次）。
     * 把查询成本从“每次请求”转移到“登录时一次”，认证性能从 2次DB 降为 1次Redis。
     */
    private void syncPermissionsToRedis(String userId) {
        List<String> roles = roleRepository.findRoleCodesByUserId(userId);
        List<String> permissions = permissionRepository.findPermissionCodesByUserId(userId);

        // 先删后写，保证数据一致性（避免角色变更后残留旧权限）
        redisTemplate.delete(ROLE_CACHE_KEY + userId);
        redisTemplate.delete(PERMISSION_CACHE_KEY + userId);

        if (!roles.isEmpty()) {
            redisTemplate.opsForSet().add(ROLE_CACHE_KEY + userId, roles.toArray(String[]::new));
        }
        if (!permissions.isEmpty()) {
            redisTemplate.opsForSet().add(PERMISSION_CACHE_KEY + userId, permissions.toArray(String[]::new));
        }
    }

    /**
     * 同步 users.role 冗余字段。
     * RBAC 表是权威来源，users.role 是快捷查询字段（保持现有 API 响应格式不变）。
     */
    private void syncRoleField(CommunityUser user) {
        List<String> roles = roleRepository.findRoleCodesByUserId(user.getId());
        UserRole highestRole = roles.contains("ADMIN") ? UserRole.ADMIN : UserRole.USER;
        user.setRole(highestRole);
        userRepository.save(user);
    }

    private CommunityUser createUser(String phone) {
        String nickname = "漂泊者" + phone.substring(phone.length() - 4);
        CommunityUser user = new CommunityUser(
                java.util.UUID.randomUUID().toString(), phone, nickname, UserStatus.NORMAL, LocalDateTime.now()
        );
        user = userRepository.save(user);
        // 新用户默认分配 USER 角色到 RBAC 表
        assignDefaultRole(user.getId());
        return user;
    }

    private void assignDefaultRole(String userId) {
        // 按 role_code 分配，不依赖自增 ID 顺序（V9 迁移 seed 了 USER/ADMIN 两个角色）
        userRepository.assignRole(userId, "USER");
    }

    private AuthUserResponse toResponse(CommunityUser user) {
        return new AuthUserResponse(user.getId(), user.getPhone(), user.getNickname(), user.getAvatarUrl(), user.getBio(), user.getRole());
    }

    public record LoginResult(AuthUserResponse user, String token, long maxAgeSeconds) {
    }
}
