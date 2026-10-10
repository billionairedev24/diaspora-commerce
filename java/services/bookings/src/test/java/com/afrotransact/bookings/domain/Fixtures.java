package com.afrotransact.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test data: Aminata's knotless braids ($180, $40 deposit, 300 min) from the design. */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    static final Instant START = NOW.plus(Duration.ofDays(5));

    private Fixtures() {
    }

    static ServiceOffering braids() {
        return new ServiceOffering(UUID.randomUUID(), "org_aminata", "Knotless box braids · medium", "Hair included",
                "hair & beauty", 300, 18_000, 4_000, "USD", Set.of(ServiceMode.IN_PERSON, ServiceMode.AT_HOME),
                List.of(new AddOn("hair", "Extra length", 2_000, 30)), Set.of(), true, NOW, NOW, 0);
    }

    static Booking hold(CancellationPolicy policy, boolean instant) {
        return hold(policy, instant, START);
    }

    static Booking hold(CancellationPolicy policy, boolean instant, Instant start) {
        BookingRules rules = new BookingRules(720, 30, 30, 30, 3, instant, false);
        Booking b = Booking.hold(UUID.randomUUID(), "BK-24018", "org_aminata", UUID.randomUUID(), "cust-1",
                new CustomerContact("Adaeze Nwosu", "adaeze@example.com", "+17135550142"),
                BookedService.of(braids(), List.of(), ServiceMode.IN_PERSON, "Aminata's Braids", "Aminata"), null,
                null, "web", start, rules, policy, NOW.plus(Duration.ofMinutes(15)), NOW);
        b.attachPayment(UUID.randomUUID(), "pi_secret");
        return b;
    }

    static Booking requested(CancellationPolicy policy) {
        Booking b = hold(policy, false);
        b.paymentAuthorized(NOW, Duration.ofHours(4));
        return b;
    }

    static Booking confirmed(CancellationPolicy policy) {
        Booking b = requested(policy);
        b.confirm(NOW.plusSeconds(60));
        return b;
    }

    /** A confirmed booking {@code start} away, deposit captured, balance still on the original authorisation. */
    static Booking confirmedAt(CancellationPolicy policy, Instant start) {
        Booking b = hold(policy, false, start);
        b.paymentAuthorized(NOW, Duration.ofHours(4));
        b.confirm(NOW.plusSeconds(60));
        return b;
    }
}
