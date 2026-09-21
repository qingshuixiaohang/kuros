package com.kuros.kurosbackend.interaction.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空发布器（切片 #11）：async.enabled=false（或未配置）时启用，publish 恒返回 false。
 *
 * 存在的意义：
 * 1. 让写路径始终能注入到一个 InteractionEventPublisher（无需 @Autowired(required=false) 判空）；
 * 2. 返回 false → 写路径走「同步降级落库」，行为等价于切片 #11 之前的同步写——
 *    这正是主测试套件（test profile 禁用异步、无 broker）所依赖的路径，现有互动断言因此无需改动；
 * 3. matchIfMissing=true：属性缺省时也走同步，避免「忘配开关就静默丢互动」。
 */
@Component
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "false", matchIfMissing = true)
public class NoopInteractionEventPublisher implements InteractionEventPublisher {

    @Override
    public boolean publish(InteractionEvent event) {
        return false;
    }
}
