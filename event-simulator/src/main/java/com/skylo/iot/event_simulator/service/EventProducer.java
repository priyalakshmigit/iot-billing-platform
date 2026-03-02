package com.skylo.iot.event_simulator.service;

import com.skylo.iot.event_simulator.config.TopicProperties;
import com.skylo.iot.model.IoTEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.support.SendResult;

@Service
@Slf4j
public class EventProducer {

    private final KafkaTemplate<String, IoTEvent> kafkaTemplate;
    private final String topic;

    public EventProducer(KafkaTemplate<String, IoTEvent> kafkaTemplate,
                         TopicProperties topicProps) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topicProps.getName();
    }

    public void send(IoTEvent event) {
        CompletableFuture<SendResult<String, IoTEvent>> future = kafkaTemplate.send(topic, event.getDeviceId(), event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("eventPublishFailed module=event-simulator topic={} deviceId={} eventId={} eventType={}",
                        topic,
                        event.getDeviceId(),
                        event.getEventId(),
                        event.getType(),
                        ex);
            } else {
                long partition = result != null && result.getRecordMetadata() != null
                        ? result.getRecordMetadata().partition()
                        : -1;
                long offset = result != null && result.getRecordMetadata() != null
                        ? result.getRecordMetadata().offset()
                        : -1;
                log.debug("eventPublished module=event-simulator topic={} deviceId={} eventId={} eventType={} partition={} offset={}",
                        topic,
                        event.getDeviceId(),
                        event.getEventId(),
                        event.getType(),
                        partition,
                        offset);
            }
        });
    }
}
