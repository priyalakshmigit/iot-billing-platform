# Event Consumer

This service is the core processing engine for IoT billing events.

It consumes events from Kafka, keeps active session state in Redis, and writes finalized billing sessions to PostgreSQL.

## Responsibilities

- Consume IoT events with at-least-once delivery semantics
- Maintain active/in-progress sessions in Redis
- Finalize completed sessions into SQL billing records
- Handle duplicates, out-of-order delivery, and missing attach scenarios
- Expose Prometheus metrics for processing observability

## Event Handling Summary

### ATTACHED

- Starts a new ACTIVE session for the device in Redis
- If an active session already exists, it finalizes the previous one first with FINALIZED_INCOMPLETE

### DATA_HEARTBEAT

- Updates session lastActive and totalBytes
- Ignores duplicate event_id for byte aggregation
- Creates INFERRED_ACTIVE session if heartbeat arrives without prior attach
- Tracks out-of-order delivery when event timestamp is older than current lastActive

### DETACHED

- Finalizes the active Redis session into PostgreSQL with COMPLETED
- If no active session exists, creates inferred state and finalizes with DETACHED_WITHOUT_ATTACH

## Data Stores

### Redis (hot state)

- session key: `session:{deviceId}` (Redis Hash)
  - `sessionId` (string UUID)
  - `deviceId` (string)
  - `startTs` (RFC3339/ISO-8601 timestamp)
  - `lastActive` (RFC3339/ISO-8601 timestamp)
  - `totalBytes` (long)
  - `status` (e.g., `ACTIVE`, `INFERRED_ACTIVE`, `FINALIZING`, `STALE_CANDIDATE`, `STALE_PENDING_PUBLISHED`)
  - `endTs` (optional, set during finalization)
- deduplication key: `events:{deviceId}` (Redis Set)
  - value members: processed `eventId` strings for the active session

### PostgreSQL (source of truth for completed records)

- table: billing_sessions
- deduplication guard table: processed_events

`processed_events` (dedup guard):
- `event_id` UUID PRIMARY KEY
- `device_id` VARCHAR(50) NOT NULL
- `event_type` VARCHAR(40) NOT NULL
- `event_ts` TIMESTAMPTZ NOT NULL
- `seen_at` TIMESTAMPTZ NOT NULL DEFAULT now()

`billing_sessions` (finalized records):
- `session_id` UUID PRIMARY KEY
- `device_id` VARCHAR(50) NOT NULL
- `start_ts` TIMESTAMPTZ NOT NULL
- `end_ts` TIMESTAMPTZ NOT NULL
- `total_bytes` BIGINT NOT NULL DEFAULT 0
- `final_status` VARCHAR(40) NOT NULL
- `finalized_at` TIMESTAMPTZ NOT NULL DEFAULT now()
- `finalized_by_event_id` UUID NOT NULL

Constraints and index:
- FK: `billing_sessions.finalized_by_event_id` -> `processed_events.event_id`
- Index: `(device_id, finalized_at DESC)` for billing history lookups

## Build and Run

From repository root:

```powershell
.\gradlew.bat :event-consumer:compileJava
```

```powershell
.\gradlew.bat :event-consumer:bootRun
```

Default service port: 8083

### Run with Docker (recommended)

From repository root:

Run the full stack (Kafka, Redis, PostgreSQL, API, simulator, consumer):

```powershell
docker compose up -d --build
```

Run only `event-consumer` with its dependencies via Compose:

```powershell
docker compose up -d --build event-consumer
```

View `event-consumer` logs:

```powershell
docker compose logs -f event-consumer
```

Stop only `event-consumer`:

```powershell
docker compose stop event-consumer
```

Stop the full stack:

```powershell
docker compose down
```

## Key Configuration (Environment Variables)

- KAFKA_BOOTSTRAP_SERVERS
- IOT_EVENTS_TOPIC
- REDIS_HOST, REDIS_PORT
- DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD

Kafka commit behavior is configured for container-managed commits:

- spring.kafka.consumer.enable-auto-commit=false
- spring.kafka.listener.ack-mode=batch

## Observability

Prometheus scrape endpoint:

- http://localhost:8083/metrics

Required processing metrics exposed:

- events_malformed_total (Counter)
- events_duplicate_total (Counter)
- events_out_of_order_total (Counter)
- processing_latency_seconds (Histogram)

Example p99 query in Prometheus/Grafana:

```promql
histogram_quantile(0.99, sum(rate(processing_latency_seconds_bucket[5m])) by (le))
```

## Notes

- At-least-once delivery means duplicates are expected and handled through idempotency checks.
- Structured logs are emitted for malformed, duplicate, out-of-order, and failed processing paths.
