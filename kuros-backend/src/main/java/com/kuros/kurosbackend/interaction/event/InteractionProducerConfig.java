package com.kuros.kurosbackend.interaction.event;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "true")
public class InteractionProducerConfig {
    @Bean(destroyMethod = "shutdown")
    DefaultMQProducer interactionProducer(@Value("${spring.cloud.stream.rocketmq.binder.name-server}") String nameserver)
            throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer("kuros-interaction-producer");
        producer.setNamesrvAddr(nameserver);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setCreateTopicKey("KUROS_INTERACTION_NO_AUTO_CREATE");
        producer.start();
        return producer;
    }
}
