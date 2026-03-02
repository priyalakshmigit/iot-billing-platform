package com.skylo.iot.event_simulator.service;

import com.skylo.iot.model.EventType;
import com.skylo.iot.model.IoTEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.annotation.Value;

@Component
@Slf4j
public class SimulatorScheduler {

    @Value("${simulator.devices.count:10000}")
    private int deviceCount;

    private final EventProducer producer;
    private final Random random = new Random();

    private final List<String> deviceIds = new ArrayList<>();
    private final Map<String, DeviceState> deviceStates = new ConcurrentHashMap<>();

    // Queue to simulate delayed (out-of-order) delivery
    private final Queue<IoTEvent> delayedEvents = new ConcurrentLinkedQueue<>();

    public SimulatorScheduler(EventProducer producer) {
        this.producer = producer;
    }

    @PostConstruct
    public void init() {
        log.info("simulatorInit module=event-simulator deviceCount={}", deviceCount);

        for (int i = 0; i < deviceCount; i++) {
            String deviceId = "device-" + i;
            deviceIds.add(deviceId);
            deviceStates.put(deviceId, new DeviceState());
        }
    }

    // Main event generator (every 10ms)
    @Scheduled(fixedRate = 10)
    public void emitEvent() {
        try {
            String deviceId = deviceIds.get(random.nextInt(deviceIds.size()));
            DeviceState state = deviceStates.get(deviceId);

            EventType type = determineNextEventType(state);
            Instant timestamp = Instant.now();

            IoTEvent event = IoTEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .deviceId(deviceId)
                    .type(type)
                    .timestamp(timestamp)
                    .schemaVersion(1)
                    .build();

            if (type == EventType.DATA_HEARTBEAT) {
                event.setBytesTransferred((long) random.nextInt(5000));
            }

            updateState(state, type, timestamp);

            // 5% out-of-order → delay sending
            if (random.nextDouble() < 0.05) {
                delayedEvents.add(event);
                log.debug("eventDelayed module=event-simulator deviceId={} eventId={} eventType={} delayedQueueSize={}",
                        event.getDeviceId(),
                        event.getEventId(),
                        event.getType(),
                        delayedEvents.size());
            } else {
                sendWithPossibleDuplicate(event);
            }

        } catch (Exception e) {
            log.error("emitEventFailed module=event-simulator reason=unexpected_exception", e);
        }
    }

    // Flush delayed events every 200ms
    @Scheduled(fixedRate = 200)
    public void flushDelayedEvents() {
        int batchSize = random.nextInt(5) + 1;

        for (int i = 0; i < batchSize; i++) {
            IoTEvent event = delayedEvents.poll();
            if (event == null) break;
            producer.send(event);
        }
    }

    private void sendWithPossibleDuplicate(IoTEvent event) {
        // 2% duplicate
        if (random.nextDouble() < 0.02) {
            producer.send(event);
            log.debug("eventDuplicateInjected module=event-simulator deviceId={} eventId={} eventType={}",
                    event.getDeviceId(),
                    event.getEventId(),
                    event.getType());
        }

        producer.send(event);
    }

    private EventType determineNextEventType(DeviceState state) {
        if (!state.attached) {
            return EventType.ATTACHED;
        }

        double d = random.nextDouble();

        if (d < 0.1) {
            return EventType.DETACHED;
        }

        return EventType.DATA_HEARTBEAT;
    }

    private void updateState(DeviceState state, EventType type, Instant timestamp) {
        switch (type) {
            case ATTACHED -> state.attached = true;
            case DETACHED -> state.attached = false;
            case STALE_SESSION_DETECTED -> { /* emitted by sweeper, not simulator */ }
            case DATA_HEARTBEAT -> { /* nothing special */ }
        }
    }

    private static class DeviceState {
        boolean attached = false;
    }
}