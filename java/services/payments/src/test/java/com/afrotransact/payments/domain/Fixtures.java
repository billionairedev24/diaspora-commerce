package com.afrotransact.payments.domain;

import java.math.BigDecimal;
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

    /** An AfroTransact Delivery line with the seller's pickup-delivery fee quoted at checkout. */
    static OrderLine deliveryLine(String id, String org, String category, int qty, long items, long ship, long tax,
                                  long sellerFee) {
        return new OrderLine(id, org, category, Fulfilment.AFROTRANSACT_DELIVERY, qty, usd(items), usd(ship), usd(tax),
                "US-TX", usd(0), usd(sellerFee));
    }

    /** The launch delivery pricing: customer $4.99 + $0.85/mi (min $5.99, max $24.99) within 25 mi, seller $2. */
    static DeliveryPricing launchDelivery(List<SellerDeliveryOverride> overrides) {
        return new DeliveryPricing(new DeliveryTariff(DeliveryMode.DISTANCE, usd(0), usd(499), 85, List.of(), usd(599),
                usd(2499)), DeliveryTariff.flat(usd(200)), new BigDecimal("25"), overrides);
    }

    static FeeSchedule scheduleWithDelivery(DeliveryPricing delivery) {
        Instant from = Instant.parse("2020-01-01T00:00:00Z");
        return new FeeSchedule(UUID.randomUUID(), 1, from, USD,
                Map.of("Groceries", 800, "Fashion", 1200, "Services", 1000), 1000, List.of(), 290, usd(30), usd(325),
                true, null, "test", from, null, delivery);
    }
}
