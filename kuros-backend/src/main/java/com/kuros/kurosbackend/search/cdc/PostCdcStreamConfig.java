package com.kuros.kurosbackend.search.cdc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Spring Cloud Stream function 绑定（切片 #14 se-05）：把 RocketMQ 的 CDC 消费通道接到 {@link PostCdcHandler}。
 *
 * <p>复用 #11 {@code InteractionStreamConfig} 的 function 模型：容器里名为 {@code postCdcConsumer} 的函数 bean
 * 会被 {@code spring.cloud.function.definition} 绑定到输入通道 {@code postCdcConsumer-in-0}
 * （destination={@code kuros-post-cdc}——与 compose 里 canal-server 的 {@code canal.mq.topic} 一致，orderly 顺序消费）。
 * 方法引用 {@code handler::handle} 即「收到一条 Canal 变更 → 认出 postId → 回源重组索引」。
 *
 * <p>为什么这个 {@code @Configuration} 要 {@code @ConditionalOnProperty(app.search.cdc.enabled=true)}（同 #11 的关键防御）：
 * test profile 关掉 CDC 且不连 broker。若消费 bean 无条件存在，Spring Cloud Stream 可能为其创建绑定 →
 * 触发 binder 连 localhost:9876（无 broker）→ 主测试套件启动失败/挂起。让消费 bean 随开关一起消失，
 * 测试环境就彻底没有该 Stream 绑定可创建，从根上规避（而非留给 CI 兜底）。
 *
 * <p>⚠️ 与 {@code spring.cloud.function.definition} 的耦合：prod/compose 该属性列为
 * {@code interactionConsumer;postCdcConsumer}，两个消费 bean 都必须存在（两个开关默认均 true）；
 * test profile 把 definition 置空 + 两开关置 false，两个 bean 均消失，无绑定可建（见 application-test.properties）。
 */
@Configuration
@ConditionalOnProperty(name = "app.search.cdc.enabled", havingValue = "true")
public class PostCdcStreamConfig {

    @Bean
    public Consumer<FlatMessage> postCdcConsumer(PostCdcHandler handler) {
        return handler::handle;
    }
}
