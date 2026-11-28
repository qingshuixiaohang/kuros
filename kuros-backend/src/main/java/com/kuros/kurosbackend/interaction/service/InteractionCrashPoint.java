package com.kuros.kurosbackend.interaction.service;

import org.springframework.stereotype.Component;

/**
 * 消费投影的可控中断点。
 *
 * 生产环境为空实现；集成测试在独立进程中提供实现，在三个提交/确认边界强制终止进程，
 * 验证「提交前崩溃回滚重投、提交后失效前崩溃保留待恢复标记、失效后确认前崩溃幂等重投」。
 */
public interface InteractionCrashPoint {
    String BEFORE_COMMIT = "BEFORE_COMMIT";
    String AFTER_COMMIT = "AFTER_COMMIT";
    String AFTER_INVALIDATE = "AFTER_INVALIDATE";

    /**
     * 到达指定阶段。生产实现不做事，崩溃实现会在此终止进程且不执行关闭钩子。
     *
     * operationId 让测试只中断目标消息：Broker 可能先重投上一条尚未确认的消息，
     * 若不加区分，崩溃会发生在目标消息之前。
     */
    void reached(String stage, String operationId);
}

@Component
class NoopInteractionCrashPoint implements InteractionCrashPoint {
    @Override
    public void reached(String stage, String operationId) {
    }
}