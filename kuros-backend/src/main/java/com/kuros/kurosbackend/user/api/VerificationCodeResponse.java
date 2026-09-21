package com.kuros.kurosbackend.user.api;

public record VerificationCodeResponse(int expiresIn, int retryAfter, String devCode) {
}
