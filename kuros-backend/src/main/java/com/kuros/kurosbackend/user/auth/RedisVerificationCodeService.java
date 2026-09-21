package com.kuros.kurosbackend.user.auth;

import com.kuros.kurosbackend.shared.exception.AuthRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 基于 Redis 的验证码服务，替代原来的 DevVerificationCodeService（ConcurrentHashMap）。
 *
 * 为什么要换成 Redis？
 * 1. ConcurrentHashMap 是 JVM 内存，服务重启后所有未使用的验证码丢失
 * 2. 多实例部署时，实例 A 发的验证码在实例 B 上无法验证
 * 3. 无法实现精确的 TTL 过期（原来靠手动比对 expiresAt）
 * 4. 无法实现频率限制（原来没有这个功能）
 *
 * Redis 解决方案：
 * - key = "sms:code:{phone}"，value = 6位验证码，TTL = 300s（自动过期）
 * - key = "sms:limit:{phone}"，value = "1"，TTL = 60s（频率限制）
 * - 多实例共享同一个 Redis，天然支持分布式
 *
 * 对应小哈书第五章 5.3：Redis 存储验证码 + 频率限制。
 */
@Service
public class RedisVerificationCodeService implements VerificationCodeService {

    private static final String CODE_KEY_PREFIX = "sms:code:";
    private static final String LIMIT_KEY_PREFIX = "sms:limit:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final SmsSender smsSender;
    private final int expirationSeconds;
    private final boolean devCodeExposed;
    private final String devCode;

    public RedisVerificationCodeService(
            StringRedisTemplate redisTemplate,
            SmsSender smsSender,
            @Value("${app.auth.code-expiration-seconds:300}") int expirationSeconds,
            @Value("${app.auth.dev-code-exposed:false}") boolean devCodeExposed,
            @Value("${app.auth.dev-code:}") String devCode
    ) {
        this.redisTemplate = redisTemplate;
        this.smsSender = smsSender;
        this.expirationSeconds = expirationSeconds;
        this.devCodeExposed = devCodeExposed;
        this.devCode = devCode;
    }

    /**
     * 发送验证码。
     * 流程：频率限制检查 → 生成验证码 → 存 Redis（带 TTL）→ 异步发送短信
     */
    @Override
    public String issue(String phone) {
        // 生成验证码：dev 环境使用固定码（方便测试），生产环境随机生成
        boolean devMode = devCode != null && !devCode.isBlank();

        // 频率限制：同一手机号 60s 内不可重复发送。
        // 为什么 dev 固定码模式跳过限制？
        // 频率限制的目的是防短信轰炸（保护真实短信通道钱袋）和防 Redis 被刷。
        // dev 模式不真发短信（DevSmsSender 只打日志），若也限制会卡死两个合法场景：
        // 1. compose-smoke 脚本重启验证时 60s 内二次登录
        // 2. 前端演示登录/登出/再登录的反复调试
        // 生产环境 devCode 为空 → 限制永远生效，安全性不受影响
        String limitKey = LIMIT_KEY_PREFIX + phone;
        if (!devMode && Boolean.TRUE.equals(redisTemplate.hasKey(limitKey))) {
            throw new AuthRequestException("CODE_RATE_LIMITED", "验证码发送过于频繁，请 60 秒后重试");
        }

        String code = devMode ? devCode : generateCode();

        // 存入 Redis，带 TTL 自动过期（不需要手动清理）
        String codeKey = CODE_KEY_PREFIX + phone;
        redisTemplate.opsForValue().set(codeKey, code, Duration.ofSeconds(expirationSeconds));

        // 设置频率限制标记（60s 内不可重复发送；仅生产模式写入）
        if (!devMode) {
            redisTemplate.opsForValue().set(limitKey, "1", Duration.ofSeconds(60));
        }

        // 异步发送短信（不阻塞主线程，发送失败不影响验证码已存入 Redis 的事实）
        smsSender.send(phone, code);

        return code;
    }

    /**
     * 验证验证码。
     * 从 Redis 读取并比对，成功后立即删除（一次性使用）。
     */
    @Override
    public boolean verify(String phone, String code) {
        String codeKey = CODE_KEY_PREFIX + phone;
        String stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null || !stored.equals(code)) {
            return false;
        }
        // 验证成功，立即删除（防止重放攻击）
        redisTemplate.delete(codeKey);
        return true;
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }
}
