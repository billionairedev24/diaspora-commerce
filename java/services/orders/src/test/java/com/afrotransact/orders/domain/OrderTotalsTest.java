package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderTotalsTest {

    private static Shipment shipment(String orderId, int seq, long shipping, OrderLine... lines) {
        return new Shipment(UUID.randomUUID().toString(), orderId, seq, "org-" + seq, "Seller " + seq,
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null, null, false, false,
                null, null, ShipmentStatus.PLACED, shipping, null, null, null, null, null, null, null, null,
                Instant.now(), List.of(lines), 0);
    }

    @Test
    void lineAndShipmentTotals() {
        OrderLine l = Shipments.line(1, 1299, 3, 322);
        assertThat(l.itemsMinor()).isEqualTo(3897);
        assertThat(l.totalMinor()).isEqualTo(4219);
        Shipment s = shipment("o", 1, 599, l, Shipments.line(2, 500, 1, 41));
        assertThat(s.itemsMinor()).isEqualTo(4397);
        assertThat(s.taxMinor()).isEqualTo(363);
        assertThat(s.totalMinor()).isEqualTo(4397 + 599 + 363);
    }

    @Test
    void orderTotalsEqualTheSumOfShipmentsPlusTax() {
        String orderId = UUID.randomUUID().toString();
        Shipment a = shipment(orderId, 1, 599, Shipments.line(1, 1250, 2, 206));
        Shipment b = shipment(orderId, 2, 0, Shipments.line(2, 4000, 1, 330), Shipments.line(3, 150, 4, 50));
        OrderTotals t = OrderTotals.ofShipments(List.of(a, b));
        assertThat(t.itemsMinor()).isEqualTo(2500 + 4000 + 600);
        assertThat(t.shippingMinor()).isEqualTo(599);
        assertThat(t.taxMinor()).isEqualTo(206 + 330 + 50);
        assertThat(t.grandTotalMinor()).isEqualTo(a.totalMinor() + b.totalMinor());
        Order order = new Order(orderId, "AT-2026-ABCDEF", "cust-1", "USD", OrderStatus.PLACED, t, null, null, null,
                null, Instant.now(), Instant.now(), List.of(a, b), 0);
        assertThat(order.lines()).hasSize(3);
        assertThatThrownBy(() -> new Order(orderId, "AT-2026-ABCDEF", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.of(1, 0, 0, 0), null, null, null, null, null, Instant.now(), List.of(a, b), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grandTotalInvariantAndNoNegativeMoney() {
        assertThat(OrderTotals.of(10_000, 599, 825, 1_000).grandTotalMinor()).isEqualTo(10_424);
        assertThatThrownBy(() -> new OrderTotals(100, 0, 0, 0, 99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderTotals.of(100, 0, 0, 200)).isInstanceOf(OrdersRuleViolation.class);
        assertThatThrownBy(() -> Shipments.line(1, -1, 1, 0)).isInstanceOf(OrdersRuleViolation.class);
        assertThatThrownBy(() -> Shipments.line(1, 100, 0, 0)).isInstanceOf(OrdersRuleViolation.class);
    }

    @Test
    void orderStatusIsDerivedFromShipments() {
        assertThat(OrderStatus.derive(List.of(ShipmentStatus.PENDING_PAYMENT))).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(OrderStatus.derive(List.of(ShipmentStatus.PLACED, ShipmentStatus.CANCELLED)))
                .isEqualTo(OrderStatus.PLACED);
        assertThat(OrderStatus.derive(List.of(ShipmentStatus.PLACED, ShipmentStatus.PACKED)))
                .isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(OrderStatus.derive(List.of(ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED)))
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(OrderStatus.derive(List.of(ShipmentStatus.CANCELLED, ShipmentStatus.CANCELLED)))
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void shippingFeesAndEstimates() {
        ShippingPolicy p = new DefaultShippingPolicy(DefaultShippingPolicy.Rates.defaults());
        assertThat(p.fee(DeliveryMethod.CARRIER, "standard", 3499)).isEqualTo(599);
        assertThat(p.fee(DeliveryMethod.CARRIER, "standard", 3500)).isZero();
        assertThat(p.fee(DeliveryMethod.CARRIER, "express", 99_999)).isEqualTo(1499);
        assertThat(p.fee(DeliveryMethod.AFROTRANSACT_DELIVERY, "local", 4999)).isEqualTo(499);
        assertThat(p.fee(DeliveryMethod.AFROTRANSACT_DELIVERY, "local", 5000)).isZero();
        assertThat(p.fee(DeliveryMethod.PICKUP, "pickup", 10)).isZero();
        assertThat(p.estimatedDays(DeliveryMethod.CARRIER, "express")).isEqualTo(2);
        assertThat(p.defaultServiceLevel(DeliveryMethod.AFROTRANSACT_DELIVERY)).isEqualTo("local");
    }

    @Test
    void businessDaysSkipWeekends() {
        LocalDate friday = LocalDate.of(2026, 10, 9);
        assertThat(BusinessDays.add(friday, 1)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(BusinessDays.add(friday, 5)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(BusinessDays.add(friday, 0)).isEqualTo(friday);
    }

    @Test
    void orderNumbersLookLikeAtYearAndSixCharacters() {
        OrderNumberGenerator numbers = new DefaultOrderNumberGenerator();
        for (int i = 0; i < 50; i++) {
            assertThat(numbers.next(2026)).matches(DefaultOrderNumberGenerator.FORMAT);
        }
    }
}
