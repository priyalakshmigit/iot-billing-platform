package com.skylo.iot.event_consumer.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.skylo.iot.event_consumer.model.SessionData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RedisSessionService {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisSessionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static final String LUA_SAVE_SESSION = """
        redis.call('HSET', KEYS[1],
            'sessionId', ARGV[1],
            'deviceId', ARGV[2],
            'startTs', ARGV[3],
            'lastActive', ARGV[4],
            'totalBytes', ARGV[5],
            'status', ARGV[6]
        )
        local added = redis.call('SADD', KEYS[2], ARGV[7])
        return added
        """;

    /**
     * Marks session as FINALIZING, sets endTs, and returns a consistent snapshot.
     * Returns list: [sessionId, deviceId, startTs, lastActive, totalBytes, status, endTs]
     * If session doesn't exist, returns empty list.
     */
    private static final String LUA_FINALIZE_AND_SNAPSHOT = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            return {}
        end

        -- if already finalized/finalizing, still return snapshot deterministically
        redis.call('HSET', KEYS[1], 'status', 'FINALIZING')
        redis.call('HSET', KEYS[1], 'endTs', ARGV[1])

        local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
        local deviceId = redis.call('HGET', KEYS[1], 'deviceId')
        local startTs = redis.call('HGET', KEYS[1], 'startTs')
        local lastActive = redis.call('HGET', KEYS[1], 'lastActive')
        local totalBytes = redis.call('HGET', KEYS[1], 'totalBytes')
        local status = redis.call('HGET', KEYS[1], 'status')
        local endTs = redis.call('HGET', KEYS[1], 'endTs')

        return {sessionId, deviceId, startTs, lastActive, totalBytes, status, endTs}
        """;
    private static final String LUA_DELETE_SESSION = """
        redis.call('DEL', KEYS[1])
        redis.call('DEL', KEYS[2])
        return 1
        """;

    public Long saveSessionAtomic(String sessionKey, String eventsKey, SessionData session, String eventId) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SAVE_SESSION, Long.class);
        return redisTemplate.execute(
                script,
                List.of(sessionKey, eventsKey),
                session.getSessionId(),
                session.getDeviceId(),
                session.getStartTs() != null ? session.getStartTs().toString() : null,
                session.getLastActive() != null ? session.getLastActive().toString() : null,
                String.valueOf(session.getTotalBytes()),
                session.getStatus(),
                eventId
        );
    }

    public void deleteSessionAtomic(String sessionKey, String eventsKey) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_DELETE_SESSION, Long.class);
        redisTemplate.execute(
                script,
                List.of(sessionKey, eventsKey)
        );
    }

    public SessionData getSessionData(String sessionKey) {
        Map<Object, Object> sessionObj = redisTemplate.opsForHash().entries(sessionKey);
        if (sessionObj == null || sessionObj.isEmpty()) {
            return null;
        }
        if(sessionObj.get("lastActive") == null){
            log.warn("redisSessionFieldMissing field=lastActive sessionKey={} availableFields={} rawSession={}",
                    sessionKey,
                    sessionObj.keySet(),
                    sessionObj);
        }
        return SessionData.builder()
                .sessionId((String) sessionObj.get("sessionId"))
                .deviceId((String) sessionObj.get("deviceId"))
                .startTs(sessionObj.get("startTs") != null ? java.time.Instant.parse((String) sessionObj.get("startTs")) : null)
                .lastActive(sessionObj.get("lastActive") != null ? java.time.Instant.parse((String) sessionObj.get("lastActive")) : null)
                .totalBytes(sessionObj.get("totalBytes") != null ? Long.parseLong(sessionObj.get("totalBytes").toString()) : 0)
                .status((String) sessionObj.get("status"))
                .endTs(sessionObj.get("endTs") != null ? java.time.Instant.parse((String) sessionObj.get("endTs")) : null)
                .build();
    }

    public Set<String> getExistingEventIds(String eventsKey) {
        return redisTemplate.opsForSet().members(eventsKey);
    }

    public SessionData finalizeAndGetSnapshot(String sessionKey, Instant endTs) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA_FINALIZE_AND_SNAPSHOT, List.class);

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) redisTemplate.execute(
                script,
                List.of(sessionKey),
                endTs.toString()
        );

        if (result == null || result.isEmpty()) {
            return null;
        }

        // result: [sessionId, deviceId, startTs, lastActive, totalBytes, status, endTs]
        String sessionId = toStr(result, 0);
        String deviceId = toStr(result, 1);
        Instant startTsVal = parseInstant(toStr(result, 2));
        Instant lastActiveVal = parseInstant(toStr(result, 3));
        long totalBytes = parseLong(toStr(result, 4));
        String status = toStr(result, 5);
        Instant endTsVal = parseInstant(toStr(result, 6));

        return SessionData.builder()
                .sessionId(sessionId)
                .deviceId(deviceId)
                .startTs(startTsVal)
                .lastActive(lastActiveVal)
                .totalBytes(totalBytes)
                .status(status)
                .endTs(endTsVal)
                .build();
    }
    
    private static String toStr(List<Object> arr, int idx) {
        if (idx >= arr.size() || arr.get(idx) == null) return null;
        return arr.get(idx).toString();
    }

    private static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        return Instant.parse(v);
    }

    private static long parseLong(String v) {
        if (v == null || v.isBlank()) return 0L;
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }
}