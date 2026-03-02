package com.skylo.iot.event_consumer.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;
}