package com.kuros.kurosuser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kuros-user：用户域独立微服务（切片 #10 Phase B / split-05）。
 *
 * 从 kuros-backend 拆出的第一个业务微服务：拥有独立库（kuros_user）与独立生命周期，
 * 与 backend 共享 Redis 会话（认证的权威归属从 split-06 起迁移到本服务）。
 * 本 ticket 只交付"能启动、能注册、能迁移"的骨架，不带业务端点。
 */
@SpringBootApplication
public class KurosUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(KurosUserApplication.class, args);
    }

}
