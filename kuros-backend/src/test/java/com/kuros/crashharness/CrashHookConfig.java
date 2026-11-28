package com.kuros.crashharness;

import com.kuros.kurosbackend.interaction.service.InteractionCrashPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 崩溃进程的接缝实现：到达目标阶段时先写标记文件，再 {@link Runtime#halt(int)}。
 *
 * halt 不执行 Spring 关闭钩子、不提交/回滚事务，等价于进程被强制杀死；
 * 事务由数据库在连接断开时回滚，未确认的消息由 Broker 重投。
 */
@Configuration
public class CrashHookConfig {

    @Bean
    @Primary
    public InteractionCrashPoint crashHook() {
        String stage = System.getProperty("crash.stage", "");
        String operation = System.getProperty("crash.operation", "");
        String marker = System.getProperty("crash.marker", "");
        return (reached, operationId) -> {
            if (!marker.isEmpty() && reached.equals(stage)
                    && (operation.isEmpty() || operation.equals(operationId))) {
                try {
                    Files.writeString(Path.of(marker), reached);
                } catch (IOException ignored) {
                    // 标记写失败也照常终止进程；父进程会以超时和子进程日志诊断。
                }
                Runtime.getRuntime().halt(86);
            }
        };
    }
}