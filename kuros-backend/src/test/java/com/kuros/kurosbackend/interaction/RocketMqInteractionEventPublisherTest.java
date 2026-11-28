package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.event.RocketMqInteractionEventPublisher;
import org.apache.rocketmq.client.common.ClientErrorCode;
import org.apache.rocketmq.client.exception.MQClientException;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RocketMqInteractionEventPublisherTest {

    @Test
    void 刷盘超时不能视为受理成功() throws Exception {
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any(), anyLong()))
                .thenReturn(result);
        assertThat(new RocketMqInteractionEventPublisher(producer).publish(
                InteractionEvent.of("post", "user", InteractionType.LIKE))).isEqualTo(Result.UNKNOWN);
    }

    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final RocketMqInteractionEventPublisher publisher = new RocketMqInteractionEventPublisher(producer);
    private final InteractionEvent event = InteractionEvent.of("post", "user", InteractionType.LIKE);

    @Test
    void missingRouteIsAnExplicitRejection() throws Exception {
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any(), anyLong())).thenThrow(
                new MQClientException(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION, "no route"));

        assertThat(publisher.publish(event)).isEqualTo(Result.REJECTED);
    }

    @Test
    void 选择队列发送的无路由异常同样明确拒绝() throws Exception {
        // 5.3.1 的 selector 重载没有复用 10005，而是 -1 + 固定前缀
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any(), anyLong()))
                .thenThrow(new MQClientException(-1, "No route info for this topic, post-interaction-topic"));

        assertThat(publisher.publish(event)).isEqualTo(Result.REJECTED);
    }

    @Test
    void brokerTimeoutLeavesDeliveryUnknown() throws Exception {
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any(), anyLong())).thenThrow(
                new MQClientException(ClientErrorCode.ACCESS_BROKER_TIMEOUT, "timeout"));

        assertThat(publisher.publish(event)).isEqualTo(Result.UNKNOWN);
    }

    @Test
    void broker连接未建立时明确拒绝() throws Exception {
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any(), anyLong()))
                .thenThrow(new org.apache.rocketmq.remoting.exception.RemotingConnectException("broker"));
        assertThat(publisher.publish(event)).isEqualTo(Result.REJECTED);
    }
}
