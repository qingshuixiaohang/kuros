package com.kuros.kurosbackend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DevVerificationCodeService implements VerificationCodeService {

    private final String devCode;
    private final int expirationSeconds;
    private final Map<String, IssuedCode> codes = new ConcurrentHashMap<>();

    public DevVerificationCodeService(
            @Value("${app.auth.dev-code:123456}") String devCode,
            @Value("${app.auth.code-expiration-seconds:300}") int expirationSeconds
    ) {
        this.devCode = devCode;
        this.expirationSeconds = expirationSeconds;
    }

    @Override
    public String issue(String phone) {
        codes.put(phone, new IssuedCode(devCode, LocalDateTime.now().plusSeconds(expirationSeconds)));
        return devCode;
    }

    @Override
    public boolean verify(String phone, String code) {
        IssuedCode issued = codes.get(phone);
        if (issued == null || issued.expiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        boolean valid = issued.code().equals(code);
        if (valid) {
            codes.remove(phone);
        }
        return valid;
    }

    private record IssuedCode(String code, LocalDateTime expiresAt) {
    }
}
