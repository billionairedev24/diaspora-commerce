package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
import com.afrotransact.orders.port.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V10: the decision a sale used and the line's share of the shipping survive a round trip through the database. */
class OrderLineTaxDecisionIT extends OrdersIntegrationTest {

    OrderRepository orders;

    @BeforeEach
    void wireRepository(@Autowired OrderRepository repository) {
        this.orders = repository;
    }

    @Test
    void theDecisionSnapshotAndTheShippingAllocationArePersistedPerLine() {
        TaxDecision exempt = new TaxDecision("RESOLVED", "EXEMPT", "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS",
                "txcd_40040000", "tx-test", "RULES", "ZIP");
        String orderId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString();
        OrderLine snapshotted = new OrderLine(UUID.randomUUID().toString(), 1, id("ATIN"), "P", "Groundnuts", null, 1,
                1500, 0, 0, "groceries", false, "US-TX", "pantry", null, 0, "org", "Seller", exempt, 200L);
        OrderLine legacy = new OrderLine(UUID.randomUUID().toString(), 2, id("ATIN"), "P", "Old order line", null, 1,
                1500, 124, 0, "groceries", false, "US-TX");
        Shipment shipment = new Shipment(shipmentId, orderId, 1, "org", "Seller", FulfilmentType.SELLER_FULFILLED,
                null, DeliveryMethod.CARRIER, "standard", null, DeliveryPreferences.defaults(), false, false, null,
                null, ShipmentStatus.PENDING_PAYMENT, 599, null, null, LocalDate.now(), LocalDate.now().plusDays(3),
                null, null, null, null, Instant.now(), List.of(snapshotted, legacy), 0);
        Order order = new Order(orderId, "AT-2026-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT), id("cust"), "USD", OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("a", "Ada", null, "1 Main St", null, "Houston",
                "TX", "77002", "US", null), UUID.randomUUID().toString(), "checkout-" + orderId, null, null,
                Instant.now(), List.of(shipment), 0);
        placement.persistPending(order, null);

        List<OrderLine> lines = orders.findById(orderId).orElseThrow().shipments().getFirst().lines();
        OrderLine back = lines.stream().filter(l -> l.lineNo() == 1).findFirst().orElseThrow();
        assertThat(back.taxDecision()).isEqualTo(exempt);
        assertThat(back.shippingAllocMinor()).isEqualTo(200L);
        OrderLine old = lines.stream().filter(l -> l.lineNo() == 2).findFirst().orElseThrow();
        assertThat(old.taxDecision()).isNull();
        assertThat(old.shippingAllocMinor()).isNull();
    }
}
