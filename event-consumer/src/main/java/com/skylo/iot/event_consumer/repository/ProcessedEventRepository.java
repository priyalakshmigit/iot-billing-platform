package com.skylo.iot.event_consumer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.skylo.iot.event_consumer.persistence.ProcessedEventEntity;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
}