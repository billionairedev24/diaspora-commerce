package com.afrotransact.bookings.domain;

import java.time.Instant;

/** A start time a customer can book (end = start + service duration). */
public record Slot(Instant start, Instant end) {
}
