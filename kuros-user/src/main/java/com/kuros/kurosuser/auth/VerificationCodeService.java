package com.kuros.kurosuser.auth;

/**
 * 验证码服务接口（split-06 自 kuros-backend 迁入）。
 * 抽象出接口便于测试替身与未来替换实现（实现类：RedisVerificationCodeService）。
 */
public interface VerificationCodeService {

    String issue(String phone);

    boolean verify(String phone, String code);
}
