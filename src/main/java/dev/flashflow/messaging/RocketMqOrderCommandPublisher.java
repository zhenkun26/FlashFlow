package dev.flashflow.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "flashflow.messaging", name = "mode", havingValue = "LIVE")
public final class RocketMqOrderCommandPublisher implements OrderCommandPublisher {
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final FlashFlowMetrics metrics;
    private DefaultMQProducer producer;

    public RocketMqOrderCommandPublisher(
            MessagingProperties properties, ObjectMapper objectMapper, FlashFlowMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() throws MQClientException {
        producer = new DefaultMQProducer(properties.producerGroup());
        producer.setNamesrvAddr(properties.namesrvAddr());
        producer.setRetryTimesWhenSendFailed(properties.producerRetries());
        producer.setSendMsgTimeout(Math.toIntExact(properties.sendTimeout().toMillis()));
        producer.start();
    }

    @PreDestroy
    void stop() {
        if (producer != null) producer.shutdown();
    }

    @Override
    public PublicationResult publish(OrderCommandEnvelope envelope) {
        Message message;
        try {
            message = new Message(properties.orderTopic(), "ORDER_V1", envelope.commandId(),
                    objectMapper.writeValueAsBytes(envelope));
            message.putUserProperty("schemaVersion", Integer.toString(envelope.schemaVersion()));
        } catch (JsonProcessingException exception) {
            return result(PublicationOutcome.DEFINITELY_NOT_PUBLISHED, "SERIALIZATION_FAILED");
        }
        try {
            SendResult sent = producer.send(message, properties.sendTimeout().toMillis());
            if (sent != null && sent.getSendStatus() == SendStatus.SEND_OK) {
                return result(PublicationOutcome.BROKER_ACKNOWLEDGED, "SEND_OK");
            }
            String status = sent == null ? "NO_SEND_RESULT" : sent.getSendStatus().name();
            return result(PublicationOutcome.AMBIGUOUS, bounded(status));
        } catch (RemotingConnectException | MQBrokerException | MQClientException exception) {
            return result(PublicationOutcome.DEFINITELY_NOT_PUBLISHED, bounded(exception.getClass().getSimpleName()));
        } catch (RemotingTimeoutException exception) {
            return result(PublicationOutcome.AMBIGUOUS, "REMOTING_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(PublicationOutcome.AMBIGUOUS, "INTERRUPTED");
        } catch (RemotingException exception) {
            return result(PublicationOutcome.AMBIGUOUS, bounded(exception.getClass().getSimpleName()));
        }
    }

    private PublicationResult result(PublicationOutcome outcome, String cause) {
        metrics.publication(outcome.name());
        return new PublicationResult(outcome, cause);
    }

    private static String bounded(String value) {
        String normalized = value == null ? "UNKNOWN" : value.toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }
}
