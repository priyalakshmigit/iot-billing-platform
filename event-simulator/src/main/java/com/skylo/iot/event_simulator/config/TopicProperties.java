package com.skylo.iot.event_simulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds configuration for the topic used by the event simulator.
 * <p>
 * Properties are bound from <code>iot.topic.events.*</code> entries in
 * <code>application.properties</code> (or environment variables).
 */
@Component
@ConfigurationProperties(prefix = "iot.topic.events")
@Data
public class TopicProperties {

    /** name of the Kafka topic */
    private String name;

    /** number of partitions to create */
    private int partitions = 1;

    /** number of replicas to create */
    private short replicas = 1;
}
