package com.kuros.kurosbackend.interaction.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.common.ClientErrorCode;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.interaction.async.enabled", havingValue = "true")
public class RocketMqInteractionEventPublisher implements InteractionEventPublisher {
    private final DefaultMQProducer producer;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();

    public RocketMqInteractionEventPublisher(DefaultMQProducer producer) {
        this(producer, "post-interaction-topic");
    }

    /** 可注入 Topic：部署可换主题；测试用不存在的 Topic 验证「无路由」被归类为明确拒绝。 */
    @org.springframework.beans.factory.annotation.Autowired
    public RocketMqInteractionEventPublisher(DefaultMQProducer producer,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.interaction.rocketmq.topic:post-interaction-topic}") String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public Result publish(InteractionEvent event) {
        try {
            Message message = new Message(topic, mapper.writeValueAsBytes(event));
            message.setKeys(event.eventId());
            message.putUserProperty("contentType", "application/json");
            var result = producer.send(message, (queues, ignored, key) ->
                    queues.get(Math.abs(((String) key).hashCode() % 4) % queues.size()), event.postId(), 3000L);
            return result.getSendStatus() == SendStatus.SEND_OK ? Result.SENT : Result.UNKNOWN;
        } catch (MQClientException ex) {
            if (isNoRoute(ex) || ex.getCause() instanceof org.apache.rocketmq.remoting.exception.RemotingConnectException) {
                return Result.REJECTED;
            }
            return Result.UNKNOWN;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.UNKNOWN;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        } catch (org.apache.rocketmq.remoting.exception.RemotingConnectException ex) {
            return Result.REJECTED;
        } catch (org.apache.rocketmq.remoting.exception.RemotingException |
                 org.apache.rocketmq.client.exception.MQBrokerException ex) {
            return Result.UNKNOWN;
        }
    }

    /**
     * 「无路由」在两种发送重载下的形状不同：普通 send 是 responseCode=10005，
     * 选择队列的 send 在 5.3.1 用 responseCode=-1 + 固定前缀抛出（无对应错误码）。
     */
    private static boolean isNoRoute(MQClientException ex) {
        if (ex.getResponseCode() == ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION ||
                ex.getResponseCode() == ClientErrorCode.NO_NAME_SERVER_EXCEPTION) {
            return true;
        }
        return ex.getResponseCode() == -1 && ex.getMessage() != null
                && ex.getMessage().startsWith("No route info for this topic");
    }
}
