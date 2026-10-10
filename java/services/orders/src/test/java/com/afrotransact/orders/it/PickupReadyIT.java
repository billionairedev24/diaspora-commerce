package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.orders.application.CarrierEventService;
import com.afrotransact.orders.application.CarrierEventService.PickupReady;
import com.afrotransact.orders.application.CarrierEventService.Result;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S219: carrier.pickup.ready moves eligible pickup shipments to ready (once per event, forward only). */
class PickupReadyIT extends OrdersIntegrationTest {

    CarrierEventService carriers;

    @BeforeEach
    void wire(@Autowired CarrierEventService carrierEvents) {
        this.carriers = carrierEvents;
    }

    private Result ready(UUID eventId, String orderRef, String customer) {
        return carriers.pickupReady(eventId, new PickupReady("PICKUP-1", "Houston counter", orderRef, customer,
                Instant.now()));
    }

    @Test
    void pickupReadyMovesPackedPickupShipmentsToReady() {
        String customer = id("cust");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedOrder(customer, paymentId, spec(id("org"), FulfilmentType.FBA_AFROTRANSACT,
                DeliveryMethod.PICKUP));
        String shipment = order.shipments().getFirst().id();

        // Not packed yet: nothing to collect.
        assertThat(ready(UUID.randomUUID(), order.number(), customer)).isEqualTo(Result.REFUSED);
        assertThat(status(shipment)).isEqualTo("PLACED");

        TestSupport.as(TestSupport.admin());
        transition(shipment, "confirmed", null, null);
        transition(shipment, "packed", null, null);

        // Another customer's reference and unknown orders never match.
        assertThat(ready(UUID.randomUUID(), order.number(), "someone-else")).isEqualTo(Result.UNMATCHED);
        assertThat(ready(UUID.randomUUID(), "AT-2026-NOPE00", customer)).isEqualTo(Result.UNMATCHED);

        UUID eventId = UUID.randomUUID();
        assertThat(ready(eventId, order.number().toLowerCase(), customer)).isEqualTo(Result.APPLIED);
        assertThat(status(shipment)).isEqualTo("READY");
        assertThat(jdbc.sql("select actor_type from shipment_status_change where shipment_id = :s and to_status = "
                + "'READY'").param("s", UUID.fromString(shipment)).query(String.class).single())
                .isEqualTo("PICKUP_POINT");
        // Redelivery is a no-op; a later notice for the same order changes nothing.
        assertThat(ready(eventId, order.number(), customer)).isEqualTo(Result.DUPLICATE);
        assertThat(ready(UUID.randomUUID(), shipment, customer)).isEqualTo(Result.UNCHANGED);
        assertThat(history(shipment)).isEqualTo(4);
    }

    @Test
    void onlyPickupShipmentsAreEligible() {
        String customer = id("cust");
        Order order = placedOrder(customer, UUID.randomUUID().toString(), spec(id("org"),
                FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));
        assertThat(ready(UUID.randomUUID(), order.id(), customer)).isEqualTo(Result.UNMATCHED);
        assertThat(status(order.shipments().getFirst().id())).isEqualTo("PLACED");
    }
}
