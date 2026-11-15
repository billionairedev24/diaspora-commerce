package com.afrotransact.seller.adapter.in.module;

import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.seller.api.ShipFromAddressApi;
import com.afrotransact.seller.application.ShipFromAddressService;
import org.junit.jupiter.api.Test;

/** S6 for the ship-from reads: {@code sellers:ship-from.read} is required, a caller without it is refused with 403. */
class ShipFromAddressApiAdapterTest {

    private final ShipFromAddressApi api = new ShipFromAddressApiAdapter(
            InProcessKit.guarded(ShipFromAddressService.class, "sellers:ship-from.read"), InProcessKit.ACTORS);

    @Test
    void locationWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"),
                () -> api.location("6f1c1a3e-0000-4000-8000-000000000001"));
    }

    @Test
    void defaultLocationWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.defaultLocation("org"));
    }
}
