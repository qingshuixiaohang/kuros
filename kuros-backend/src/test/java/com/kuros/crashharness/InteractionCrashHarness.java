package com.kuros.crashharness;

import com.kuros.kurosbackend.KurosBackendApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立进程中的消费端：连接父测试启动的 MySQL/Redis/RocketMQ 容器，运行真实消费者。
 *
 * 由 {@code InteractionRocketMQIntegrationTest} 以子进程启动：
 * - crash.stage 指定崩溃边界（BEFORE_COMMIT / AFTER_COMMIT / AFTER_INVALIDATE），
 *   空字符串表示正常消费；
 * - crash.marker 在崩溃前写入标记文件；
 * - crash.ready 在 Spring 上下文与消费者启动完成后写入就绪文件。
 */
public final class InteractionCrashHarness {

    private InteractionCrashHarness() {
    }

    public static void main(String[] args) throws Exception {
        List<String> arguments = new ArrayList<>();
        arguments.add("--server.port=0");
        arguments.add("--spring.flyway.enabled=false");
        arguments.add("--spring.datasource.url=" + require("db.url"));
        arguments.add("--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver");
        arguments.add("--spring.datasource.username=" + require("db.username"));
        arguments.add("--spring.datasource.password=" + System.getProperty("db.password", ""));
        arguments.add("--spring.data.redis.host=" + require("redis.host"));
        arguments.add("--spring.data.redis.port=" + require("redis.port"));
        arguments.add("--spring.data.redis.timeout=2s");
        arguments.add("--spring.cloud.stream.rocketmq.binder.name-server=" + require("rocketmq.nameserver"));
        arguments.add("--app.interaction.async.enabled=true");
        arguments.add("--spring.cloud.function.definition=");
        // 崩溃场景需要观察「待失效标记尚未被定时任务清理」，恢复由消息重投触发。
        arguments.add("--app.interaction.cache-recovery-delay-ms=3600000");

        new SpringApplicationBuilder(KurosBackendApplication.class, CrashHookConfig.class)
                .profiles("test")
                .run(arguments.toArray(String[]::new));

        String ready = System.getProperty("crash.ready", "");
        if (!ready.isEmpty()) {
            Files.writeString(Path.of(ready), "ready");
        }
        Thread.currentThread().join();
    }

    private static String require(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing -D" + key);
        }
        return value;
    }
}