package com.kuros.kurosbackend.web;

import com.kuros.kurosbackend.api.ApiResponse;
import com.kuros.kurosbackend.api.AuthUserResponse;
import com.kuros.kurosbackend.api.PhoneCodeRequest;
import com.kuros.kurosbackend.api.PhoneLoginRequest;
import com.kuros.kurosbackend.api.VerificationCodeResponse;
import com.kuros.kurosbackend.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final String cookieName;
    private final boolean cookieSecure;

    public AuthController(
            AuthService authService,
            @Value("${app.auth.session-cookie-name:KUROS_SESSION}") String cookieName,
            @Value("${app.auth.session-cookie-secure:false}") boolean cookieSecure
    ) {
        this.authService = authService;
        this.cookieName = cookieName;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/code")
    public ApiResponse<VerificationCodeResponse> code(@RequestBody PhoneCodeRequest request) {
        return new ApiResponse<>(authService.issueCode(request), null);
    }

    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        String token = UUID.randomUUID().toString();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, ResponseCookie.from("XSRF-TOKEN", token)
                        .httpOnly(false)
                        .secure(false)
                        .sameSite("Lax")
                        .path("/")
                        .build()
                        .toString())
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthUserResponse>> login(@RequestBody PhoneLoginRequest request) {
        AuthService.LoginResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.token(), result.maxAgeSeconds()).toString())
                .body(new ApiResponse<>(result.user(), null));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(HttpServletRequest request) {
        return new ApiResponse<>(authService.currentUser(readToken(request)), null);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(readToken(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString())
                .build();
    }

    private ResponseCookie sessionCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
