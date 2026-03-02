package com.skylo.iot.api_server.repository;

import com.skylo.iot.api_server.persistence.BillingSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BillingSessionRepository extends JpaRepository<BillingSessionEntity, UUID> {

    List<BillingSessionEntity> findTop10ByDeviceIdOrderByFinalizedAtDesc(String deviceId);
}
