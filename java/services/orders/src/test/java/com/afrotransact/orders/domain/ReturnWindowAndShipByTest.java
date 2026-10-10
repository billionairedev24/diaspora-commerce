package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReturnWindowAndShipByTest {

    private final ReturnWindowPolicy windows = new DefaultReturnWindowPolicy(30, Map.of("electronics", 15),
            Set.of("frozen-food"));

    private static OrderLine line(String productType, boolean perishable) {
        return new OrderLine(UUID.randomUUID().toString(), 1, "A", "P", "Item", null, 1, 1000, 0, 0, "general",
                perishable, "US-TX", productType, null, 0);
    }

    private static Shipment delivered(Instant at, OrderLine... lines) {
        Shipment s = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.DELIVERED);
        return new Shipment(s.id(), s.orderId(), 1, s.sellerOrgId(), s.sellerName(), s.fulfilmentType(), s.nodeId(),
                s.deliveryMethod(), s.serviceLevel(), null, s.preferences(), false, false, null, null,
                ShipmentStatus.DELIVERED, 0, null, null, null, null, null, null, at, null, at, List.of(lines), 0);
    }

    @Test
    void windowIsTheLongestOfTheReturnableLines() {
        Instant at = Instant.parse("2026-10-01T12:00:00Z");
        assertThat(windows.windowEndsAt(delivered(at, line("electronics", false), line(null, false))))
                .contains(at.plus(Duration.ofDays(30)));
        assertThat(windows.windowEndsAt(delivered(at, line("electronics", false))))
                .contains(at.plus(Duration.ofDays(15)));
        Shipment food = delivered(at, line("frozen-food", false), line("snacks", true));
        assertThat(windows.windowEndsAt(food)).isEmpty();
        assertThat(windows.problemReportOnly(food)).isTrue();
        assertThat(windows.windowEndsAt(Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER,
                ShipmentStatus.IN_TRANSIT))).isEmpty();
    }

    @Test
    void shipByCountsBusinessDaysPerFulfilment() {
        ShipByPolicy policy = new DefaultShipByPolicy(1, 2);
        LocalDate friday = LocalDate.of(2026, 10, 9);
        assertThat(policy.shipBy(friday, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER))
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(policy.shipBy(friday, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER))
                .isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(policy.shipBy(null, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER)).isNull();
    }
}
