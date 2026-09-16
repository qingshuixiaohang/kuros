package com.kuros.kurosbackend.auth;

public interface VerificationCodeService {

    String issue(String phone);

    boolean verify(String phone, String code);
}
