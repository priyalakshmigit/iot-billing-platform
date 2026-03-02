package com.skylo.iot.event_consumer.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EventProcessingMetrics {

    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;
    private final Timer processingLatencyTimer;

    public EventProcessingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.processingLatencyTimer = Timer.builder("processing_latency_seconds")
                .description("End-to-end event processing latency")
                .publishPercentileHistogram(true)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void incrementMalformed(String eventType, String reason) {
        meterRegistry.counter(
                "events_malformed_total",
                "event_type", safe(eventType),
                "reason", safe(reason)
        ).increment();
    }

    public void incrementDuplicate(String eventType, String reason) {
        meterRegistry.counter(
                "events_duplicate_total",
                "event_type", safe(eventType),
                "reason", safe(reason)
        ).increment();
    }

    public void incrementOutOfOrder(String eventType) {
        meterRegistry.counter(
                "events_out_of_order_total",
                "event_type", safe(eventType)
        ).increment();
    }

    public void recordProcessingLatencyNanos(long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        processingLatencyTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }
}
