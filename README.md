# IoT Billing Platform

Distributed telemetry billing platform that simulates device events, processes them with at-least-once semantics, maintains active session state in Redis, and persists finalized billing records in PostgreSQL.

The system is built as a multi-service Spring Boot architecture with Kafka-based event transport, gRPC APIs, stale-session recovery, and Prometheus/Grafana observability.

## Core Documents

- [REQUIREMENTS.md](REQUIREMENTS.md): runtime prerequisites, setup steps, configuration variables, and monitoring access
- [DESIGN.md](DESIGN.md): architecture, data model, correctness strategy, failure handling, and trade-offs

## Project Highlights

- Event-driven session lifecycle processing (`ATTACHED`, `DATA_HEARTBEAT`, `DETACHED`, `STALE_SESSION_DETECTED`)
- At-least-once processing with replay-safe idempotency controls
- Redis hot-state + PostgreSQL durable-state split for performance and reliability
- gRPC APIs for live status and billing history
- Containerized deployment with `docker compose` and built-in monitoring
