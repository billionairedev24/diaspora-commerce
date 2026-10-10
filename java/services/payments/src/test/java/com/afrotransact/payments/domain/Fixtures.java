package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared domain test data. */
final class Fixtures {

    static final String RETAIL = "org_afrotransact_retail";
    static final String USD = "USD";

    private Fixtures() {
    }

    static Money usd(long minor) {
        return Money.of(minor, USD);
    }

    static FeeSchedule schedule() {
        return schedule(1, Instant.parse("2020-01-01T00:00:00Z"), List.of());
    }

    static FeeSchedule schedule(int version, Instant effectiveFrom, List<CommissionOverride> overrides) {
        return new FeeSchedule(UUID.randomUUID(), version, effectiveFrom, USD,
                Map.of("Groceries", 800, "Fashion", 1200, "Services", 1000), 1000, overrides, 290, usd(30), usd(325),
                true, null, "test", effectiveFrom);
    }

    static OrderLine line(String id, String org, String category, Fulfilment f, int qty, long items, long ship,
                          long tax) {
        return new OrderLine(id, org, category, f, qty, usd(items), usd(ship), usd(tax), "US-TX");
    }
}
