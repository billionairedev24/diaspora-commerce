package com.afrotransact.identity.adapter.out.metrics;

import com.afrotransact.identity.port.TourMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code identity_tour_events_total{event}} with event one of started, completed, dismissed, reset. No tour or user tags. */
@Component
class MicrometerTourMetrics implements TourMetrics {

    private final MeterRegistry registry;

    MicrometerTourMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void event(String event) {
        registry.counter("identity.tour.events", "event", event).increment();
    }
}
