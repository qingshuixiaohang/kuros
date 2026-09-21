package com.kuros.kurosbackend.interaction.event;

/**
 * 互动事件发布器（切片 #11）：把「写路径」与「用哪种通道异步落库」解耦的接缝。
 *
 * 为什么抽这个接口而不是直接在 Service 里调 StreamBridge？
 * 1. 降级可测：测试注入一个「返回 false」的实现即可驱动同步降级路径，无需真实 broker
 * 2. 开关可切：异步启用时用 RocketMQ 实现，禁用时用 Noop 实现（写路径退回同步落库）
 * 3. Service 不感知消息中间件细节，符合深模块（窄接口、隐藏实现）
 */
public interface InteractionEventPublisher {

    /**
     * 投递互动事件。
     *
     * @return true = 已成功投递到 MQ（消费端将异步落库）；
     *         false = 未启用异步或投递失败，调用方**必须**同步降级落库以保证互动不丢。
     */
    boolean publish(InteractionEvent event);
}
