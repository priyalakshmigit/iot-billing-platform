package com.skylo.iot.event_consumer.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.sql.Types;

@Repository
public class FinalizationJdbcRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public FinalizationJdbcRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return 1 if inserted, 0 if event_id already existed
     */
    public int insertProcessedEventIfAbsent(UUID eventId, String deviceId, String eventType, Instant eventTs) {
        String sql = """
            INSERT INTO processed_events(event_id, device_id, event_type, event_ts, seen_at)
            VALUES (:eventId, :deviceId, :eventType, :eventTs, now())
            ON CONFLICT (event_id) DO NOTHING
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("deviceId", deviceId)
                .addValue("eventType", eventType)
            .addValue("eventTs", toOffsetDateTime(eventTs), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbcTemplate.update(sql, params);
    }

    public int upsertBillingSession(UUID sessionId,
                                   String deviceId,
                                   Instant startTs,
                                   Instant endTs,
                                   long totalBytes,
                                   String finalStatus,
                                   UUID finalizedByEventId) {

        String sql = """
            INSERT INTO billing_sessions(
                session_id, device_id, start_ts, end_ts, total_bytes,
                final_status, finalized_at, finalized_by_event_id
            )
            VALUES (:sessionId, :deviceId, :startTs, :endTs, :totalBytes, :finalStatus, now(), :finalizedByEventId)
            ON CONFLICT (session_id) DO nothing
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("deviceId", deviceId)
                .addValue("startTs", toOffsetDateTime(startTs), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endTs", toOffsetDateTime(endTs), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("totalBytes", totalBytes)
                .addValue("finalStatus", finalStatus)
                .addValue("finalizedByEventId", finalizedByEventId);

        return jdbcTemplate.update(sql, params);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}