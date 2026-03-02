package com.skylo.iot.event_consumer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.skylo.iot.event_consumer.persistence.BillingSessionEntity;

public interface BillingSessionRepository extends JpaRepository<BillingSessionEntity, String> {
    
}