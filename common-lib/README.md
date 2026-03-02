# Common Library (`common-lib`)

## Overview
`common-lib` contains shared domain models used across services in this workspace.

Current shared types:
- `IoTEvent`
- `EventType`

Keeping these in one module ensures the simulator, consumer, and API server serialize/deserialize the same event schema.

## Package Structure
Source root:
- `src/main/java/com/skylo/iot/model/`

Main classes:
- `IoTEvent` — shared event payload (`eventId`, `deviceId`, `type`, `timestamp`, `bytesTransferred`, `staleSessionId`, `schemaVersion`)
- `EventType` — event enum (`ATTACHED`, `DATA_HEARTBEAT`, `DETACHED`, `STALE_SESSION_DETECTED`)

## How Other Modules Use It
This project includes `common-lib` via Gradle project dependency, for example:

- `event-simulator` (`implementation(project(":common-lib"))`)
- `event-consumer` (`implementation(project(":common-lib"))`)
- `api-server` (`implementation(project(":common-lib"))`)

## Build
From repository root:

```powershell
.\gradlew :common-lib:build
```

## Notes
- Java toolchain target: 25
- Lombok is used for model boilerplate generation
- This module stays focused on shared contracts/types to avoid service coupling
