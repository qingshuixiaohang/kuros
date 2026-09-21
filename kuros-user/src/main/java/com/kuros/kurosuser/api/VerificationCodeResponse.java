package com.kuros.kurosuser.api;

public record VerificationCodeResponse(int expiresIn, int retryAfter, String devCode) {
}
