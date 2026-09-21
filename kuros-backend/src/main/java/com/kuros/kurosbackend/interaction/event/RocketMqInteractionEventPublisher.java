package com.kuros.kurosbackend.interaction.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 互动事件发布器（切片 #11）：async.enabled=true 时启用，经 StreamBridge 投递到 RocketMQ。
 *
 * 为什么用 StreamBridge 而不是直接注入 Producer/Binding？
 * StreamBridge 是 Spring Cloud Stream 的「按需发送」入口——无需为发送端声明 function bean，
 * 首次 send 时按逻辑通道名（interactionOutbound）动态创建生产绑定并套用 application.properties
 * 里该通道的分区/orderly 配置。发送端只有一个通道，StreamBridge 比声明式 Output 更轻。
 *
 * 为什么设 KEYS 头？
 * RocketMQ 的 KEYS 是消息的业务索引（可在控制台按 key 查消息轨迹），设为 postId 便于按帖排查；
 * 顺序性由 binder 的 partition-key-expression=payload.postId + orderly=true 保证，与 KEYS 头无关。
 *
 * 为什么 send 失败/异常都返回 false？
 * 让调用方（写路径）统一走「同步降级落库」，保证互动绝不因 MQ 抖动而丢失（ADR 0004 决策）。
 */
@Component
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "true")
public class RocketMqInteractionEventPublisher implements InteractionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqInteractionEventPublisher.class);

    /** 逻辑发送通道名，对应 application.properties 的 spring.cloud.stream.bindings.interactionOutbound.*。 */
    private static final String OUT_BINDING = "interactionOutbound";

    private final StreamBridge streamBridge;

    public RocketMqInteractionEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public boolean publish(InteractionEvent event) {
        try {
            Message<InteractionEvent> message = MessageBuilder.withPayload(event)
                    .setHeader("KEYS", event.postId())
                    .build();
            return streamBridge.send(OUT_BINDING, message);
        } catch (RuntimeException ex) {
            // 投递异常（binder 未就绪、broker 不可达等）→ 返回 false 触发同步降级，互动不丢
            log.warn("互动事件投递 MQ 失败，转同步降级落库: postId={} userId={} type={}",
                    event.postId(), event.userId(), event.type(), ex);
            return false;
        }
    }
}
