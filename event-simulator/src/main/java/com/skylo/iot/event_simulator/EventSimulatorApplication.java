package com.skylo.iot.event_simulator;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.skylo.iot.event_simulator.config.TopicProperties;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class EventSimulatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventSimulatorApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		log.info("serviceStartup module=event-simulator status=ready");
	}

	@Bean
	NewTopic usersTopic(TopicProperties topicProps) {
		log.info("kafkaTopicConfigured module=event-simulator topic={} partitions={} replicas={}",
			topicProps.getName(),
			topicProps.getPartitions(),
			topicProps.getReplicas());
		return TopicBuilder.name(topicProps.getName())
			.partitions(topicProps.getPartitions())
			.replicas(topicProps.getReplicas())
			.build();
	}

}
