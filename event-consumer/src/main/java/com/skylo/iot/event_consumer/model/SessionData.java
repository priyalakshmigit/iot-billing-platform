package com.skylo.iot.event_consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionData {
    private String sessionId;
    private String deviceId;
    private Instant startTs;
    private Instant endTs;
    private Instant lastActive;
    private long totalBytes;
    private String status;
}