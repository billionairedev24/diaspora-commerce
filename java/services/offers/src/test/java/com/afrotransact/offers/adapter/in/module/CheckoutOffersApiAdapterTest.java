package com.afrotransact.offers.adapter.in.module;

import java.util.List;

import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.offers.application.CouponService;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/**
 * S6 for the offers calls orders makes: the offer read needs {@code offers:read}, the coupon calls need a service principal
 * (coupons:write for a seller's own coupons does not apply to orders). Stand-ins refuse like the real services.
 */
class CheckoutOffersApiAdapterTest {

    private final CheckoutOffersApi api = new CheckoutOffersApiAdapter(
            InProcessKit.guarded(CouponService.class, "coupons:service"),
            InProcessKit.guarded(OfferService.class, "offers:read"),
            InProcessKit.guarded(OfferListingService.class, "offers:read"), InProcessKit.ACTORS);

    private static final String OFFER = "6f1c1a3e-0000-4000-8000-000000000001";

    @Test
    void inventorySkuWithoutOffersReadIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.inventorySku(OFFER));
    }

    @Test
    void couponCallsWithoutTheScopeAre403() {
        CheckoutOffersApi.CouponRequest request = new CheckoutOffersApi.CouponRequest("SAVE5", "c1", "o1", "USD",
                List.of(new CheckoutOffersApi.CouponLine(OFFER, 1)), 0, false);
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.validate(request));
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.redeem("o1", request));
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.release(OFFER));
    }
}
