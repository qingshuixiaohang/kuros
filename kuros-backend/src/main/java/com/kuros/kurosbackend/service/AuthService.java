package com.kuros.kurosbackend.service;

import com.kuros.kurosbackend.api.AuthUserResponse;
import com.kuros.kurosbackend.api.PhoneCodeRequest;
import com.kuros.kurosbackend.api.PhoneLoginRequest;
import com.kuros.kurosbackend.api.VerificationCodeResponse;
import com.kuros.kurosbackend.auth.VerificationCodeService;
import com.kuros.kurosbackend.domain.CommunityUser;
import com.kuros.kurosbackend.domain.UserSession;
import com.kuros.kurosbackend.domain.UserStatus;
import com.kuros.kurosbackend.exception.AuthRequestException;
import com.kuros.kurosbackend.exception.UnauthorizedException;
import com.kuros.kurosbackend.repository.CommunityUserRepository;
import com.kuros.kurosbackend.repository.UserSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final CommunityUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final VerificationCodeService verificationCodeService;
    private final int codeExpirationSeconds;
    private final int sessionExpirationDays;
    private final boolean devCodeExposed;

    public AuthService(
            CommunityUserRepository userRepository,
            UserSessionRepository sessionRepository,
            VerificationCodeService verificationCodeService,
            @Value("${app.auth.code-expiration-seconds:300}") int codeExpirationSeconds,
            @Value("${app.auth.session-expiration-days:30}") int sessionExpirationDays,
            @Value("${app.auth.dev-code-exposed:true}") boolean devCodeExposed
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.verificationCodeService = verificationCodeService;
        this.codeExpirationSeconds = codeExpirationSeconds;
        this.sessionExpirationDays = sessionExpirationDays;
        this.devCodeExposed = devCodeExposed;
    }

    public VerificationCodeResponse issueCode(PhoneCodeRequest request) {
        String phone = normalizePhone(request.phone());
        String devCode = verificationCodeService.issue(phone);
        return new VerificationCodeResponse(codeExpirationSeconds, 60, devCodeExposed ? devCode : null);
    }

    @Transactional
    public LoginResult login(PhoneLoginRequest request) {
        String phone = normalizePhone(request.phone());
        if (!verificationCodeService.verify(phone, request.code())) {
            throw new AuthRequestException("INVALID_VERIFICATION_CODE", "验证码无效或已过期");
        }
        CommunityUser user = userRepository.findByPhone(phone).orElseGet(() -> createUser(phone));
        String rawToken = randomToken();
        LocalDateTime now = LocalDateTime.now();
        sessionRepository.save(new UserSession(
                java.util.UUID.randomUUID().toString(), user.getId(), hash(rawToken),
                now.plusDays(sessionExpirationDays), now
        ));
        return new LoginResult(toResponse(user), rawToken, sessionExpirationDays * 24L * 60L * 60L);
    }

    @Transactional(readOnly = true)
    public AuthUserResponse currentUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("请先登录");
        }
        UserSession session = sessionRepository.findByTokenHash(hash(rawToken))
                .filter(item -> item.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new UnauthorizedException("登录已过期，请重新登录"));
        CommunityUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new UnauthorizedException("用户不存在，请重新登录"));
        return toResponse(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessionRepository.deleteByTokenHash(hash(rawToken));
        }
    }

    public String normalizePhone(String phone) {
        if (phone == null || !phone.matches("1\\d{10}")) {
            throw new AuthRequestException("INVALID_PHONE", "请输入正确的 11 位手机号");
        }
        return phone;
    }

    private CommunityUser createUser(String phone) {
        String nickname = "漂泊者" + phone.substring(phone.length() - 4);
        return userRepository.save(new CommunityUser(
                java.util.UUID.randomUUID().toString(), phone, nickname, UserStatus.NORMAL, LocalDateTime.now()
        ));
    }

    private AuthUserResponse toResponse(CommunityUser user) {
        return new AuthUserResponse(user.getId(), user.getPhone(), user.getNickname(), user.getAvatarUrl(), user.getBio());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record LoginResult(AuthUserResponse user, String token, long maxAgeSeconds) {
    }
}
