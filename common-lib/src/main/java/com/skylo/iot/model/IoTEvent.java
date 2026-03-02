package com.skylo.iot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IoTEvent {
    private String eventId;
    private String deviceId;
    private EventType type;
    private Instant timestamp;
    private Long bytesTransferred;
    private String staleSessionId;
    private int schemaVersion;
}