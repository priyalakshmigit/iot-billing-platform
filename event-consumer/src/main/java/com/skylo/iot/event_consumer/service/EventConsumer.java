package com.skylo.iot.event_consumer.service;

import com.skylo.iot.model.IoTEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;


import com.skylo.iot.event_consumer.model.SessionData;

import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class EventConsumer {

    private final RedisSessionService redisSessionService;
    private final SessionFinalizerService sessionFinalizerService;
    private final EventProcessingMetrics eventProcessingMetrics;

    public EventConsumer(RedisSessionService redisSessionService,
                         SessionFinalizerService sessionFinalizerService,
                         EventProcessingMetrics eventProcessingMetrics) {
        this.redisSessionService = redisSessionService;
        this.sessionFinalizerService = sessionFinalizerService;
        this.eventProcessingMetrics = eventProcessingMetrics;
    }

    // Consume messages from the topic defined in application.properties
    @KafkaListener(topics = "${iot.topic.events.name}", groupId = "event-consumer-group")
    public void consume(IoTEvent event) {
        long startNanos = System.nanoTime();
        String eventType = resolveEventType(event);
        try {
            if (isMalformed(event)) {
                eventProcessingMetrics.incrementMalformed(eventType, "missing_or_invalid_fields");
                log.warn("eventRejected reason=malformed eventType={} event={}", eventType, event);
                return;
            }

            String sessionKey = "session:" + event.getDeviceId();
            String eventsKey = "events:" + event.getDeviceId();
            SessionData session = redisSessionService.getSessionData(sessionKey);
            Set<String> existingEventIds = redisSessionService.getExistingEventIds(eventsKey);

            switch (event.getType()) {
            case ATTACHED:
                if (session != null && existingEventIds != null) {
                    // Session already exists, finalize the existing session before starting a new one
                    sessionFinalizerService.finalizeSessionFromRedis(
                        event,
                        sessionKey,
                        eventsKey,
                        "FINALIZED_INCOMPLETE"
                    );
                }
                session = SessionData.builder()
                        .sessionId(UUID.randomUUID().toString())
                        .deviceId(event.getDeviceId())
                        .startTs(event.getTimestamp())
                        .lastActive(event.getTimestamp())
                        .totalBytes(0)
                        .status("ACTIVE")
                        .build();
                existingEventIds = Set.of(event.getEventId());
                redisSessionService.saveSessionAtomic(sessionKey, eventsKey, session, event.getEventId());
                break;
            case DATA_HEARTBEAT:
                if (session != null && existingEventIds != null) {
                    if (isStaleTrackingState(session)) {
                        String previousStatus = session.getStatus();
                        session.setStatus("ACTIVE");
                        log.info("sessionReactivatedFromStaleTracking deviceId={} sessionId={} eventId={} prevStatus={}",
                                event.getDeviceId(),
                                session.getSessionId(),
                                event.getEventId(),
                                previousStatus);
                    }

                    // Handling out-of-order events: only update lastActive if this event is newer
                    if (event.getTimestamp().isAfter(session.getLastActive())) {
                        session.setLastActive(event.getTimestamp());
                    } else {
                        eventProcessingMetrics.incrementOutOfOrder(eventType);
                        log.info("eventOutOfOrder eventType={} deviceId={} eventId={} eventTs={} sessionLastActive={}",
                                eventType,
                                event.getDeviceId(),
                                event.getEventId(),
                                event.getTimestamp(),
                                session.getLastActive());
                    }

                    // Only update totalBytes if this eventId is new (idempotency)
                    boolean isNewEvent = !existingEventIds.contains(event.getEventId());
                    if (isNewEvent) {
                        session.setTotalBytes(session.getTotalBytes() + event.getBytesTransferred());
                        existingEventIds.add(event.getEventId());
                    } else {
                        eventProcessingMetrics.incrementDuplicate(eventType, "event_id_already_seen");
                        log.info("eventDuplicate eventType={} deviceId={} eventId={}",
                                eventType,
                                event.getDeviceId(),
                                event.getEventId());
                    }
                } else {
                    // Edge case: received DATA_HEARTBEAT without existing session (e.g., missed ATTACHED event)
                    session = SessionData.builder()
                        .sessionId(UUID.randomUUID().toString())
                        .deviceId(event.getDeviceId())
                        .startTs(event.getTimestamp())
                        .lastActive(event.getTimestamp())
                        .totalBytes(event.getBytesTransferred())
                        .status("INFERRED_ACTIVE")
                        .build();
                    existingEventIds = Set.of(event.getEventId());
                }
                redisSessionService.saveSessionAtomic(sessionKey, eventsKey, session, event.getEventId());
                break;
            case DETACHED:
                if (session != null && existingEventIds != null) {
                    sessionFinalizerService.finalizeSessionFromRedis(
                        event,
                        sessionKey,
                        eventsKey,
                        "COMPLETED"
                    );
                } else {
                    // Edge case: received DETACHED without existing session (e.g., missed ATTACHED event)
                    session = SessionData.builder()
                        .sessionId(UUID.randomUUID().toString())
                        .deviceId(event.getDeviceId())
                        .startTs(event.getTimestamp())
                        .lastActive(event.getTimestamp())
                        .totalBytes(0)
                        .status("INFERRED_ACTIVE")
                        .build();
                    existingEventIds = Set.of(event.getEventId());
                    redisSessionService.saveSessionAtomic(sessionKey, eventsKey, session, event.getEventId());
                    sessionFinalizerService.finalizeSessionFromRedis(
                            event,
                            sessionKey,
                            eventsKey,
                            "DETACHED_WITHOUT_ATTACH"
                    );
                }
                break;
            case STALE_SESSION_DETECTED:
                if (session != null && existingEventIds != null) {
                    if (!isMatchingStaleSession(event, session)) {
                        log.info("staleEventIgnored reason=session_id_mismatch deviceId={} eventId={} staleSessionId={} currentSessionId={}",
                                event.getDeviceId(),
                                event.getEventId(),
                                event.getStaleSessionId(),
                                session.getSessionId());
                        break;
                    }

                    if (!isStaleReadyForFinalize(session)) {
                        log.info("staleEventIgnored reason=session_not_stale_ready deviceId={} eventId={} sessionId={} status={}",
                                event.getDeviceId(),
                                event.getEventId(),
                                session.getSessionId(),
                                session.getStatus());
                        break;
                    }

                    sessionFinalizerService.finalizeSessionFromRedis(
                        event,
                        sessionKey,
                        eventsKey,
                        "STALE_TIMEOUT"
                    );
                } else {
                    log.info("staleEventNoActiveSession deviceId={} eventId={}", event.getDeviceId(), event.getEventId());
                }
                break;
            }
        } catch (RuntimeException ex) {
            log.error("eventProcessingFailed eventType={} event={}", eventType, event, ex);
            throw ex;
        } finally {
            eventProcessingMetrics.recordProcessingLatencyNanos(System.nanoTime() - startNanos);
        }
    }

    private String resolveEventType(IoTEvent event) {
        if (event == null || event.getType() == null) {
            return "UNKNOWN";
        }
        return event.getType().name();
    }

    private boolean isMalformed(IoTEvent event) {
        if (event == null) {
            return true;
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            return true;
        }
        if (event.getDeviceId() == null || event.getDeviceId().isBlank()) {
            return true;
        }
        if (event.getType() == null || event.getTimestamp() == null) {
            return true;
        }
        if (event.getType().name().equals("DATA_HEARTBEAT")) {
            return event.getBytesTransferred() == null || event.getBytesTransferred() < 0;
        }
        if (event.getType().name().equals("STALE_SESSION_DETECTED")) {
            return event.getStaleSessionId() == null || event.getStaleSessionId().isBlank();
        }
        return false;
    }

    private boolean isStaleReadyForFinalize(SessionData session) {
        if (session == null || session.getStatus() == null) {
            return false;
        }
        return "STALE_CANDIDATE".equals(session.getStatus())
                || "STALE_PENDING_PUBLISHED".equals(session.getStatus());
    }

    private boolean isStaleTrackingState(SessionData session) {
        if (session == null || session.getStatus() == null) {
            return false;
        }
        return "STALE_CANDIDATE".equals(session.getStatus())
                || "STALE_PENDING_PUBLISHED".equals(session.getStatus());
    }

    private boolean isMatchingStaleSession(IoTEvent event, SessionData session) {
        return session != null
                && event != null
                && event.getStaleSessionId() != null
                && event.getStaleSessionId().equals(session.getSessionId());
    }
}