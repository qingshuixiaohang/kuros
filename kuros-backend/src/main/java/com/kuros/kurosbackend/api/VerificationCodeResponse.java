package com.kuros.kurosbackend.api;

public record VerificationCodeResponse(int expiresIn, int retryAfter, String devCode) {
}
