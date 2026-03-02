# Event Simulator

## Overview
`event-simulator` generates synthetic IoT traffic and publishes it to Kafka for downstream processing.

It simulates:
- device attach/detach lifecycle
- heartbeat events with byte payloads
- duplicate delivery (~2%)
- out-of-order delivery (~5% delayed queue)

## Event Behavior
- Scheduler emits events every 10 ms.
- Events are keyed by `deviceId` when sent to Kafka.
- If a device is detached, next event is `ATTACHED`.
- If a device is attached, simulator emits mostly `DATA_HEARTBEAT` with occasional `DETACHED`.
- `STALE_SESSION_DETECTED` is not emitted by this service (only by sweeper flow).

## Configuration
The service reads these properties (with environment overrides):

- `KAFKA_BOOTSTRAP_SERVERS` (default: `localhost:9092,localhost:9093,localhost:9094`)
- `IOT_EVENTS_TOPIC` (default: `iot-events`)
- `SIMULATOR_DEVICES_COUNT` (default: `10000`)
- `IOT_EVENTS_PARTITIONS` (default: `3`)
- `IOT_EVENTS_REPLICAS` (default: `2`)

Source config file: `src/main/resources/application.properties`.

## Run Locally
From repository root:

```powershell
.\gradlew :event-simulator:bootRun
```

Optional env overrides (PowerShell):

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092,localhost:9093,localhost:9094"
$env:IOT_EVENTS_TOPIC="iot-events"
$env:SIMULATOR_DEVICES_COUNT="1000"
.\gradlew :event-simulator:bootRun
```

## Run with Docker Compose
From repository root:

```powershell
docker compose up -d event-simulator
```

In this workspace, the compose service is configured to wait until all Kafka brokers are healthy.

## Verify It Is Publishing
Check simulator logs:

```powershell
docker compose logs -f event-simulator
```

You should see lines similar to:
- `sent event <eventId> to topic iot-events`

## Notes
- Java toolchain target is Java 25.
- Module package root is `com.skylo.iot.event_simulator`.
