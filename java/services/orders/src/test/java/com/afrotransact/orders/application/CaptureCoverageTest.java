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
import com.afrotransact.orders.port.PaymentsClient;
import org.junit.jupiter.api.Test;

/** A single-capture payment (no Stripe multicapture) takes every line with the first shipment; orders follows. */
class CaptureCoverageTest {

    private static final String ORDER = UUID.randomUUID().toString();

    private static Shipment shipment(int seq, String lineId, ShipmentStatus status) {
        OrderLine line = new OrderLine(lineId, seq, "ATIN-" + seq, "P", "Item " + seq, null, 1, 1500, 124, 0,
                "groceries", false, "US-TX");
        return new Shipment(UUID.randomUUID().toString(), ORDER, seq, "org-" + seq, "Seller " + seq,
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, status, 599, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), null, null, null, null, Instant.now(), List.of(line), 0);
    }

    private static Order order(Shipment... shipments) {
        return new Order(ORDER, "AT-2026-CAP1", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipments)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", null), UUID.randomUUID().toString(),
                "checkout-" + ORDER, null, null, Instant.now(), List.of(shipments), 0);
    }

    @Test
    void aPerShipmentCaptureCoversOnlyItsShipment() {
        Shipment first = shipment(1, "L1", ShipmentStatus.PICKED_UP);
        Shipment second = shipment(2, "L2", ShipmentStatus.CONFIRMED);

        List<Shipment> covered = FollowUpServiceImpl.coveredBy(order(first, second), first.id(),
                new PaymentsClient.Capture("cap-1", 1724, List.of("L1")));

        assertThat(covered).extracting(Shipment::id).containsExactly(first.id());
    }

    @Test
    void aSingleCaptureCoversEveryLiveShipmentWhoseLinesItTook() {
        Shipment first = shipment(1, "L1", ShipmentStatus.PICKED_UP);
        Shipment second = shipment(2, "L2", ShipmentStatus.CONFIRMED);
        Shipment cancelled = shipment(3, "L3", ShipmentStatus.CANCELLED);

        List<Shipment> covered = FollowUpServiceImpl.coveredBy(order(first, second, cancelled), first.id(),
                new PaymentsClient.Capture("cap-1", 3448, List.of("L1", "L2")));

        assertThat(covered).extracting(Shipment::id).containsExactlyInAnyOrder(first.id(), second.id());
    }
}
