package com.skylo.iot.event_consumer.service;

import com.skylo.iot.event_consumer.model.SessionData;
import com.skylo.iot.event_consumer.repository.FinalizationJdbcRepository;
import com.skylo.iot.model.IoTEvent;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class SessionFinalizerService {

    private final RedisSessionService redisSessionService;
    private final FinalizationJdbcRepository finalizationJdbcRepository;
    private final TransactionTemplate transactionTemplate;
    private final EventProcessingMetrics eventProcessingMetrics;

    public SessionFinalizerService(RedisSessionService redisSessionService,
                                   FinalizationJdbcRepository finalizationJdbcRepository,
                                   TransactionTemplate transactionTemplate,
                                   EventProcessingMetrics eventProcessingMetrics) {
        this.redisSessionService = redisSessionService;
        this.finalizationJdbcRepository = finalizationJdbcRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventProcessingMetrics = eventProcessingMetrics;
    }

    /**
     * Idempotent finalization flow:
     * 1) Take Redis atomic snapshot (marks FINALIZING + sets endTs)
     * 2) Transaction:
     *    - insert into processed_events (ON CONFLICT DO NOTHING)
     *    - if inserted => upsert billing_sessions
     * 3) after commit => delete Redis keys
     *
     * This guarantees:
     * - No double billing writes (event_id idempotency)
     * - Session row ends consistent (session_id UPSERT)
     * - Crash safe (Redis retained until after commit; also has TTL)
     */
    public void finalizeSessionFromRedis(IoTEvent closingEvent,
                                        String sessionKey,
                                        String eventsKey,
                                        String finalStatus) {

        // 1) Snapshot session from Redis (atomic)
        SessionData snapshot = redisSessionService.finalizeAndGetSnapshot(
                sessionKey,
                closingEvent.getTimestamp()
        );

        if (snapshot == null) {
            // No session in Redis -> nothing to finalize; still idempotency for processed_events could be stored,
            // but this assignment typically expects finalization only when session exists.
            log.info("sessionFinalizeSkipped reason=session_missing_in_redis deviceId={} eventId={} finalStatus={}",
                    closingEvent.getDeviceId(),
                    closingEvent.getEventId(),
                    finalStatus);
            return;
        }

        // 2) DB transaction write (idempotent)
        transactionTemplate.execute(status ->
            finalizeInPostgresTxn(closingEvent, snapshot, finalStatus));

        // 3) Cleanup Redis after successful commit OR duplicate event
        //    Even if duplicate, cleanup is safe.
        redisSessionService.deleteSessionAtomic(sessionKey, eventsKey);
    }

    protected boolean finalizeInPostgresTxn(IoTEvent closingEvent, SessionData snapshot, String finalStatus) {

        // Insert processed event first
        int inserted = finalizationJdbcRepository.insertProcessedEventIfAbsent(
                UUID.fromString(closingEvent.getEventId()),
                closingEvent.getDeviceId(),
                closingEvent.getType().name(),
                closingEvent.getTimestamp()
        );

        if (inserted == 0) {
            // Duplicate delivery: already processed
                eventProcessingMetrics.incrementDuplicate(closingEvent.getType().name(), "processed_event_already_present");
                log.info("sessionFinalizeDuplicate eventId={} deviceId={} eventType={} reason=processed_event_already_present",
                    closingEvent.getEventId(), closingEvent.getDeviceId(), closingEvent.getType());
            return false;
        }

        Instant startTs = snapshot.getStartTs();
        Instant endTs = snapshot.getEndTs() != null ? snapshot.getEndTs() : closingEvent.getTimestamp();

        if (startTs == null) {
            // fallback: if missing, use lastActive (best effort)
            startTs = snapshot.getLastActive() != null ? snapshot.getLastActive() : closingEvent.getTimestamp();
        }

        finalizationJdbcRepository.upsertBillingSession(
                UUID.fromString(snapshot.getSessionId()),
                snapshot.getDeviceId(),
                startTs,
                endTs,
                snapshot.getTotalBytes(),
                finalStatus,
                UUID.fromString(closingEvent.getEventId())
        );

        log.info("sessionFinalized sessionId={} deviceId={} totalBytes={} finalStatus={} closingEventId={} eventType={}",
                snapshot.getSessionId(),
                snapshot.getDeviceId(),
                snapshot.getTotalBytes(),
                finalStatus,
            closingEvent.getEventId(),
            closingEvent.getType());

        return true;
    }
}