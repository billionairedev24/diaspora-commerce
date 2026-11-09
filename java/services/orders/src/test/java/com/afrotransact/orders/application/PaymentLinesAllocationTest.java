package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TaxDecision;
import com.afrotransact.orders.port.PaymentsClient;
import org.junit.jupiter.api.Test;

/** Payments lines, refunds and the service lookup follow the stored shipping allocation (TX-05 to TX-07). */
class PaymentLinesAllocationTest {

    private static final TaxDecision EXEMPT = new TaxDecision("RESOLVED", "EXEMPT", "AT.NUTS_SEEDS.X", "txcd_40040000",
            "tx-test", "RULES", "ZIP");

    private static OrderLine line(int no, long unit, long tax, Long shippingAlloc, TaxDecision decision) {
        return new OrderLine(UUID.randomUUID().toString(), no, "ATIN-" + no, "P", "Item " + no, null, 1, unit, tax, 0,
                "groceries", false, "US-TX", "pantry", null, 0, "org", "Seller", decision, shippingAlloc);
    }

    private static Shipment shipment(long shipping, OrderLine... lines) {
        return new Shipment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, "org", "Seller",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, ShipmentStatus.PENDING_PAYMENT, shipping,
                null, null, LocalDate.now(), LocalDate.now().plusDays(3), null, null, null, null, Instant.now(),
                List.of(lines), 0);
    }

    private static Order order(Shipment s) {
        return new Order(s.orderId(), "AT-2026-AAAAAA", "c1", "USD", OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(List.of(s)), new ShipTo("a", "Ada", null, "1 Main St", null, "Houston", "TX",
                "77002", "US", null), "pay", "wf", null, null, Instant.now(), List.of(s), 0);
    }

    @Test
    void paymentLinesAndRefundsCarryEachLinesAllocatedShippingAndTheTotalIsUnchanged() {
        Shipment s = shipment(800, line(1, 1000, 0, 200L, EXEMPT), line(2, 3000, 297, 600L, null));
        List<PaymentsClient.Line> lines = PaymentLines.of(order(s));
        assertThat(lines).extracting(PaymentsClient.Line::shippingMinor).containsExactly(200L, 600L);
        assertThat(PaymentLines.refundOf(s)).extracting(PaymentsClient.RefundLine::shippingMinor)
                .containsExactly(200L, 600L);
        assertThat(lines.stream().mapToLong(PaymentsClient.Line::shippingMinor).sum()).isEqualTo(s.shippingMinor());
    }

    @Test
    void ordersPlacedBeforeAllocationsWereKeptStillChargeAllShippingOnTheFirstLine() {
        Shipment s = shipment(599, line(2, 3000, 297, null, null), line(1, 1000, 0, null, null));
        assertThat(PaymentLines.of(order(s))).extracting(PaymentsClient.Line::shippingMinor)
                .containsExactly(599L, 0L);
        assertThat(s.lineShippingMinor(s.lines().get(1))).isEqualTo(599);
        assertThat(s.lineShippingMinor(s.lines().get(0))).isZero();
    }
}
