package com.skylo.iot.api_server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "billing_sessions")
public class BillingSessionEntity {

    @Id
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "start_ts", nullable = false)
    private Instant startTs;

    @Column(name = "end_ts", nullable = false)
    private Instant endTs;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "final_status", nullable = false, length = 40)
    private String finalStatus;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    @Column(name = "finalized_by_event_id", nullable = false)
    private UUID finalizedByEventId;
}
