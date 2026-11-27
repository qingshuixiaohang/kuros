package com.kuros.kurosbackend.interaction.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuros.kurosbackend.interaction.service.InteractionProjectionService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "true")
public class InteractionStreamConfig {
    @Bean(destroyMethod = "shutdown")
    DefaultMQPushConsumer interactionConsumer(InteractionProjectionService projection,
            @Value("${spring.cloud.stream.rocketmq.binder.name-server}") String nameserver) throws MQClientException {
        var consumer = new DefaultMQPushConsumer("interaction-consumer-group");
        consumer.setNamesrvAddr(nameserver);
        consumer.subscribe("post-interaction-topic", "*");
        consumer.setMaxReconsumeTimes(Integer.MAX_VALUE);
        var mapper = new ObjectMapper();
        consumer.registerMessageListener((MessageListenerOrderly) (messages, context) -> {
            for (var message : messages) {
                InteractionEvent event = null;
                try {
                    event = mapper.readValue(message.getBody(), InteractionEvent.class);
                    if (projection.isPostBlocked(event.postId())) {
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                    projection.apply(event);
                } catch (Exception failure) {
                    LoggerFactory.getLogger(InteractionStreamConfig.class).warn("互动消息消费失败: {}", message.getMsgId(), failure);
                    if (event != null) {
                        try {
                            if (projection.isApplied(event)) {
                                continue;
                            }
                        } catch (Exception ignored) {
                            // 数据库不可用时不能确认提交结果。
                        }
                    }
                    if (event != null && message.getReconsumeTimes() >= 3) {
                        try {
                            projection.recordFailure(event, failure.getClass().getName());
                        } catch (Exception recordingFailure) {
                            LoggerFactory.getLogger(InteractionStreamConfig.class).warn("无法保存失败任务: {}", message.getMsgId(), recordingFailure);
                        }
                    }
                    return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                }
            }
            return ConsumeOrderlyStatus.SUCCESS;
        });
        consumer.start();
        return consumer;
    }
}
