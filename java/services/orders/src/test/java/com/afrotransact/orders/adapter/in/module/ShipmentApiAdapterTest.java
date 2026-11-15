package com.afrotransact.orders.adapter.in.module;

import java.time.Instant;

import com.afrotransact.orders.api.ShipmentApi;
import com.afrotransact.orders.application.ShipmentService;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** S6 for the shipment calls: courier events need {@code delivery:relay}; a caller without it is refused with 403. */
class ShipmentApiAdapterTest {

    private final ShipmentApi api = new ShipmentApiAdapter(InProcessKit.guarded(ShipmentService.class, "delivery:relay"),
            InProcessKit.ACTORS);

    @Test
    void courierEventWithoutDeliveryRelayIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("fulfilment"), () -> api.courierEvent("s1",
                new ShipmentApi.CourierEvent("e1", "picked_up", Instant.now(), null, "driver-1", null)));
    }

    @Test
    void transitionAndTrackingWithoutTheScopeAre403() {
        ShipmentApi strict = new ShipmentApiAdapter(InProcessKit.guarded(ShipmentService.class, "orders:update-status"),
                InProcessKit.ACTORS);
        InProcessKit.assertForbidden(InProcessKit.unscoped("fulfilment"), () -> strict.transition("s1", "packed", null, "k"));
        InProcessKit.assertForbidden(InProcessKit.unscoped("fulfilment"), () -> strict.attachTracking("s1", "ups", "1Z"));
    }
}
