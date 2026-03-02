Handling atomic state transitions:

When moving the ACTIVE session (from redis) to COMPLETED session (in SQL), atomicity is ensured by using SQL Transactions and Lua scripts. 

e.g. When there is a timeout or other exceptions in the midway, the previous commonds are roll backed.

Concurrency:

Since we are using DEVICE_ID as the key in event messages, all the events that belong to a particular device are always produce to the same partition of the topic and kafka will guarantee the order of the events inside a paritition. By this, even if there are more consumers consuming the messages parellely, only one consumer will handle all the events from a particular device. By this race condition is avoided.

Stale session policy:
I'm not going to rely on redis key expiration or key notifications to handle the stale session policy. The system is assumed to receive heartbeat for every 30 seconds. It would wait for 90 seconds to check if it is still active and sending heartbeat. If not, the session whould be marked as stale and move as INCOMPLETE session to SQL. This is handled by a CRON job that runs every minute to check and handle the stale sessions. Reason for not using redis based solution: When we use key space notification, what will happen when the consumer/service that is supposed to receive these notification went down? Those notification will be missed, redis keys would be deleted and we would be missing few billings.

Scaling strategy:
Need to add more brokers in-order to store all that high volume of data. Also, we need to add more consumers to ensure that there is no lag while consuming. Also, if need to make sure that we have enough number of partitions in the topic. If not, we need to create a new topic with increased number of partitions and need to shift the producers and consumers to use the new topic. 

Architecture:

DATABASES:

REDIS: SESSIONS::DEVICEID::SESSIONID -> Key
Key: session:{device_id}:{session_id}
Type: Hash

Field	Description
start_ts	Session start timestamp
last_heartbeat_ts	Last DATA_HEARTBEAT timestamp
bytes	Total bytes transferred
state	ACTIVE, STALE_TIMEOUT, INFERRED_ATTACH
event_ids	Set of processed event_ids (deduplication)

SQL: Sessions table:
SESSIONID (PRIMARY KEY), DEVICEID, START_TIME, END_TIME, TOTAL_BYTES_CONSUMED, STATE, CREATED_AT

This IOT-BILLING-PLATFORM contains the below services
1. Event simulator - A Java Spring Boot microservice the produces the simulated events into a kafka topic.
2. Event consumer - A Java Spring Boot microservice the consumes the events from the topic. It consumes the event and do the below operations based on the STATE of the event
    1. ATTACHED_EVENT: Creates a new session and store in REDIS with session:{device_id}:{session_id} as KEY and sets bytes to zero and event_ids to empty LIST. In case, we are receiving a new ATTACH event when there is already an active session going on for the DEVICE, then the existing session whould be finalized and transferred to the SQL and then a new session will be created IN REDIS
    2. DATA_HEARTBEAT: It searches for a REDIS_KEY with DEVICE_ID and checks if the EVENT_ID is present in the event_ids of the REDIS_VALUE (to handle idempotency). If EVENT_ID is not present in the EVENT_IDS list, bytes_transferred is added to the bytes value in the REDIS hash. Also, if there is no REDIS_KEY already exists with the DEVICE_ID, then it would created with the state INFERRED_ATTACH
    3. DETACHED: Whenever a deteached event is received, it would take the REDIS key and then insert a row in Sessions table in SQL and mark it as complete. This action is done with SQL Transactions and Redis Lua Script.
3. API Server - It has two purposes - serving the apis (/live-device-status & /billing history) and in the background it runs a sweeper to check all the redis key and detect stale sessions and produce a event of type "STALE_SESSION_DETECTED" to the same topic. Later, when the consumer receives that event, it would finalize the session and do all the operations to complete it. The decision for not to run the sweeper job in all the consumers: All consumers would be scanning all the keys unnecessarily. I want consumers to be free from this job and focus on their consuming job more. Also, the other most important reason is only the consumer responsible for the DEVICE_ID should make changes to it. No other consumer should interfere with other DEVICE_ID in any way. It would some time causes race conditions and inconsistencies. That's why I produce a new event with the same device_id key, so that it will be consumed by the same consumer and it would complete the session accordingly.

Also, all the components are containerized using docker.

Okay now lets discuss what will happen when any of the service crashes,

When we are finalizing a session from Redis from SQL:

The Active session in Redis key is marked as FINALIZING and 

## gRPC API Demo (Requirement 4.7)

API Server exposes gRPC endpoints for:
1. Live device status (attached state + current aggregated usage from Redis)
2. Billing history (last 10 completed sessions from SQL)

Token-based auth is enforced using Bearer token in metadata.

Default runtime config:
- gRPC port: `9091` (`GRPC_PORT`)
- Auth token: `skylo-dev-token` (`API_AUTH_TOKEN`)

### 1) Live Device Status

```bash
grpcurl -plaintext \
    -H "authorization: Bearer skylo-dev-token" \
    -d '{"device_id":"device-123"}' \
    localhost:9091 skylo.billing.v1.BillingApi/GetLiveDeviceStatus
```

### 2) Billing History (last 10)

```bash
grpcurl -plaintext \
    -H "authorization: Bearer skylo-dev-token" \
    -d '{"device_id":"device-123"}' \
    localhost:9091 skylo.billing.v1.BillingApi/GetBillingHistory
```

### Validation & Auth behavior

- `device_id` must be non-empty, <= 50 chars, and match: `[a-zA-Z0-9._:-]+`
- Missing/invalid token returns `UNAUTHENTICATED`
- Invalid input returns `INVALID_ARGUMENT`