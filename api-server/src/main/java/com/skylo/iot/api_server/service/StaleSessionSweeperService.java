package com.skylo.iot.api_server.service;

import com.skylo.iot.model.EventType;
import com.skylo.iot.model.IoTEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
@Slf4j
public class StaleSessionSweeperService {

    private static final String SESSION_KEY_PREFIX = "session:";

    private static final String LUA_MARK_STALE_CANDIDATE_IF_ELIGIBLE = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            return ''
        end

        local status = redis.call('HGET', KEYS[1], 'status')
        local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
        if (not sessionId) then
            return ''
        end

        if status == 'FINALIZING' then
            local finalizingTs = redis.call('HGET', KEYS[1], 'endTs')
            if (not finalizingTs) then
                finalizingTs = redis.call('HGET', KEYS[1], 'staleCandidateAt')
            end

            if finalizingTs and finalizingTs >= ARGV[3] then
                return ''
            end

            redis.call('HSET', KEYS[1], 'status', 'STALE_CANDIDATE')
            redis.call('HSET', KEYS[1], 'staleCandidateAt', ARGV[2])
            return sessionId
        end

        if status == 'STALE_CANDIDATE' then
            return sessionId
        end

        if status ~= 'ACTIVE' and status ~= 'INFERRED_ACTIVE' then
            return ''
        end

        local activityTs = redis.call('HGET', KEYS[1], 'lastActive')
        if (not activityTs) then
            activityTs = redis.call('HGET', KEYS[1], 'startTs')
        end

        if activityTs and activityTs >= ARGV[1] then
            return ''
        end

        redis.call('HSET', KEYS[1], 'status', 'STALE_CANDIDATE')
        redis.call('HSET', KEYS[1], 'staleCandidateAt', ARGV[2])
        return sessionId
        """;

    private static final String LUA_MARK_STALE_PUBLISHED = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            return 0
        end

        local status = redis.call('HGET', KEYS[1], 'status')
        local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
        if (not sessionId) then
            return 0
        end

        if sessionId ~= ARGV[1] then
            return 0
        end

        if status ~= 'STALE_CANDIDATE' and status ~= 'STALE_PENDING_PUBLISHED' then
            return 0
        end

        redis.call('HSET', KEYS[1], 'status', 'STALE_PENDING_PUBLISHED')
        redis.call('HSET', KEYS[1], 'stalePublishedAt', ARGV[2])
        return 1
        """;

    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, IoTEvent> kafkaTemplate;

    @Value("${iot.topic.events.name}")
    private String eventsTopic;

    @Value("${sweeper.stale-threshold-seconds:90}")
    private long staleThresholdSeconds;

    @Value("${sweeper.finalizing-recovery-seconds:120}")
    private long finalizingRecoverySeconds;

    @Value("${sweeper.publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    public StaleSessionSweeperService(StringRedisTemplate stringRedisTemplate,
                                      KafkaTemplate<String, IoTEvent> kafkaTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${sweeper.scan-interval-ms:60000}")
    public void detectAndEmitStaleSessions() {
        int scanned = 0;
        int staleCandidates = 0;
        int stalePublished = 0;

        Instant now = Instant.now();
        Instant staleCutoff = now.minusSeconds(staleThresholdSeconds);
        Instant finalizingRecoveryCutoff = now.minusSeconds(finalizingRecoverySeconds);

        for (String sessionKey : scanSessionKeys()) {
            scanned++;
            try {
                String staleSessionId = markSessionAsStaleCandidateAndGetSessionId(
                    sessionKey,
                    staleCutoff,
                    finalizingRecoveryCutoff,
                    now
                );
                if (staleSessionId == null) {
                    continue;
                }
                staleCandidates++;

                String deviceId = deviceIdFromSessionKey(sessionKey);
                if (deviceId == null || deviceId.isBlank()) {
                    log.warn("sweeperSkip reason=invalid_device_id sessionKey={}", sessionKey);
                    continue;
                }

                IoTEvent staleEvent = IoTEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .deviceId(deviceId)
                        .type(EventType.STALE_SESSION_DETECTED)
                        .timestamp(now)
                        .staleSessionId(staleSessionId)
                        .schemaVersion(1)
                        .build();

                    kafkaTemplate.send(eventsTopic, deviceId, staleEvent).get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                    if (!markSessionAsStalePublished(sessionKey, staleSessionId, now)) {
                        log.warn("stalePublishStateMismatch deviceId={} eventId={} staleSessionId={} sessionKey={}",
                            deviceId,
                            staleEvent.getEventId(),
                            staleSessionId,
                            sessionKey);
                        continue;
                    }

                stalePublished++;
                log.info("staleEventPublished deviceId={} eventId={} staleSessionId={} topic={}",
                        deviceId,
                        staleEvent.getEventId(),
                        staleSessionId,
                        eventsTopic);
            } catch (Exception ex) {
                log.error("staleSweeperError sessionKey={}", sessionKey, ex);
            }
        }

        log.info("staleSweeperRun scanned={} staleCandidates={} stalePublished={} thresholdSeconds={}",
                scanned,
            staleCandidates,
                stalePublished,
                staleThresholdSeconds);
    }

    private Iterable<String> scanSessionKeys() {
        return stringRedisTemplate.execute((RedisCallback<Iterable<String>>) connection -> {
            Cursor<byte[]> cursor = connection.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(SESSION_KEY_PREFIX + "*")
                    .count(500)
                    .build());
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
            return keys;
        });
    }

    private String markSessionAsStaleCandidateAndGetSessionId(String sessionKey,
                                                              Instant staleCutoff,
                                                              Instant finalizingRecoveryCutoff,
                                                              Instant now) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>(LUA_MARK_STALE_CANDIDATE_IF_ELIGIBLE, String.class);
        String result = stringRedisTemplate.execute(
                script,
                Collections.singletonList(sessionKey),
                staleCutoff.toString(),
                now.toString(),
                finalizingRecoveryCutoff.toString()
        );
        if (result == null || result.isBlank()) {
            return null;
        }
        return result;
    }

    private boolean markSessionAsStalePublished(String sessionKey, String staleSessionId, Instant now) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_MARK_STALE_PUBLISHED, Long.class);
        Long result = stringRedisTemplate.execute(
                script,
                Collections.singletonList(sessionKey),
                staleSessionId,
                now.toString()
        );
        return result != null && result == 1L;
    }

    private String deviceIdFromSessionKey(String sessionKey) {
        if (sessionKey == null || !sessionKey.startsWith(SESSION_KEY_PREFIX)) {
            return null;
        }
        return sessionKey.substring(SESSION_KEY_PREFIX.length());
    }
}
