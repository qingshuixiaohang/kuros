package com.kuros.kurosbackend.interaction.event;

import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Spring Cloud Stream function 绑定（切片 #11）：把 RocketMQ 消费通道接到投影服务。
 *
 * 为什么用 Consumer<InteractionEvent> 这个 bean 而不是注解式监听？
 * Spring Cloud Stream 4+/5 的 function 模型：容器里名为 interactionConsumer 的函数 bean
 * 会被 spring.cloud.function.definition=interactionConsumer 绑定到输入通道
 * interactionConsumer-in-0（destination=post-interaction-topic，orderly=true 顺序消费）。
 * 方法引用 projectionService::apply 即「收到一条互动事件 → 幂等落库 + 刷 DB 计数列」。
 *
 * 为什么这个 @Configuration 也要 @ConditionalOnProperty(async.enabled=true)？
 * 关键防御：test profile 禁用异步且不连 broker。若消费 bean 无条件存在，Spring Cloud Stream
 * 在 function.definition 为空时可能「自动探测到唯一的函数 bean」并为其创建绑定 → 触发 binder
 * 连接 localhost:9876（无 broker）→ 主测试套件启动失败/挂起。让消费 bean 随开关一起消失，
 * 测试环境就彻底没有任何 Stream 绑定可创建，从根上规避该风险（而非留给 CI 兜底）。
 */
@Configuration
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "true")
public class InteractionStreamConfig {

    @Bean
    public Consumer<InteractionEvent> interactionConsumer(InteractionProjectionService projectionService) {
        return projectionService::apply;
    }
}
