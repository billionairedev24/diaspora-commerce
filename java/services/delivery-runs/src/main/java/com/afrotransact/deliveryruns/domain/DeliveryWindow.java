package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;

/** When the run delivers (doorstep drops or pickup-point opening). */
public record DeliveryWindow(Instant start, Instant end) {

    public DeliveryWindow {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new RuleViolation("delivery-runs.invalid-window", "The delivery window must end after it starts");
        }
        if (Duration.between(start, end).toHours() > 24) {
            throw new RuleViolation("delivery-runs.invalid-window", "A delivery window is at most 24 hours");
        }
    }

    public DeliveryWindow shift(Duration by) {
        return new DeliveryWindow(start.plus(by), end.plus(by));
    }
}
