package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionEventPublisher.Result;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.event.RocketMqInteractionEventPublisher;
import org.apache.rocketmq.client.common.ClientErrorCode;
import org.apache.rocketmq.client.exception.MQClientException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessagingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RocketMqInteractionEventPublisherTest {

    private final StreamBridge bridge = mock(StreamBridge.class);
    private final RocketMqInteractionEventPublisher publisher = new RocketMqInteractionEventPublisher(bridge);
    private final InteractionEvent event = InteractionEvent.of("post", "user", InteractionType.LIKE);

    @Test
    void missingRouteIsAnExplicitRejection() {
        when(bridge.send(eq("interactionOutbound"), any())).thenThrow(
                new MessagingException("send failed",
                        new MQClientException(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION, "no route")));

        assertThat(publisher.publish(event)).isEqualTo(Result.REJECTED);
    }

    @Test
    void brokerTimeoutLeavesDeliveryUnknown() {
        when(bridge.send(eq("interactionOutbound"), any())).thenThrow(
                new MessagingException("send failed",
                        new MQClientException(ClientErrorCode.ACCESS_BROKER_TIMEOUT, "timeout")));

        assertThat(publisher.publish(event)).isEqualTo(Result.UNKNOWN);
    }
}
