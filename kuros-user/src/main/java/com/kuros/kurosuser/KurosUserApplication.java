package com.kuros.kurosuser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kuros-user：用户域独立微服务（切片 #10 Phase B / split-05 骨架 + split-06 认证迁入）。
 *
 * 从 kuros-backend 拆出的第一个业务微服务：拥有独立库（kuros_user）与独立生命周期，
 * 与 backend 共享 Redis 会话（认证的权威归属从 split-06 起迁移到本服务）。
 * 当前承载：/api/v1/auth/** 全量认证端点（验证码/登录/登出/当前用户/CSRF，
 * 经网关路由到本服务）；backend 侧同路径已无 handler（直连返回 404）。
 */
@SpringBootApplication
public class KurosUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(KurosUserApplication.class, args);
    }

}
