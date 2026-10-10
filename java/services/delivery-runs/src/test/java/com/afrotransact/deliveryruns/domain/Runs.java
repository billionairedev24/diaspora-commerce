package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Test fixtures: a Bastrop run delivering Saturday 10:00–14:00 CDT with a Thursday 20:00 cutoff. */
final class Runs {

    static final Instant NOW = Instant.parse("2026-10-19T15:00:00Z");
    static final Instant CUTOFF = Instant.parse("2026-10-23T01:00:00Z");
    static final DeliveryWindow WINDOW = new DeliveryWindow(Instant.parse("2026-10-24T15:00:00Z"),
            Instant.parse("2026-10-24T19:00:00Z"));

    private Runs() {
    }

    static RunTerms terms(int minOrders, Capacity capacity, CutoffPolicy policy) {
        return new RunTerms(WINDOW, CUTOFF, null, CUTOFF.plus(Duration.ofHours(10)), 799, "USD", minOrders, capacity,
                DropMode.DOORSTEP, null, policy, 7);
    }

    static Run open(int minOrders, Capacity capacity, CutoffPolicy policy) {
        return Run.draft(UUID.randomUUID(), UUID.randomUUID(), terms(minOrders, capacity, policy), null, NOW)
                .open(NOW);
    }

    static Run withOrders(Run run, int orders) {
        Run r = run;
        for (int i = 0; i < orders; i++) {
            r = r.reserve(1, NOW);
        }
        return r;
    }
}
