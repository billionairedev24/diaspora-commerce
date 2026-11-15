package com.afrotransact.orders.adapter.out.module;

import java.util.List;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CouponsClient} through offers' published {@link CheckoutOffersApi} inside this JVM (mode {@code in-process}). A coupon
 * that does not apply is still {@code applicable=false} on validate and the 422 {@code offers.coupon-not-applicable} on
 * redeem, as over HTTP; the idempotency key is passed through.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "offers", value = ModuleMode.IN_PROCESS)
class InProcessCouponsClient implements CouponsClient {

    private static final String SERVICE = "offers";

    private final ObjectProvider<CheckoutOffersApi> api;
    private final ModuleUpstream upstream;

    InProcessCouponsClient(ObjectProvider<CheckoutOffersApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(CheckoutOffersApi.class, true, false).length > 0,
                "orders", "offers", "at.orders.offers.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Quote validate(Request request) {
        CheckoutOffersApi.QuoteInfo q = upstream.call(SERVICE, () -> api.getObject().validate(body(request)));
        return new Quote(q.applicable(), q.rejection(), q.message(), q.discountMinor(), q.shippingDiscountMinor(),
                q.totalDiscountMinor(), q.funding(), lines(q.lines()));
    }

    @Override
    public Redemption redeem(String idempotencyKey, Request request) {
        return redemption(upstream.call(SERVICE, () -> api.getObject().redeem(idempotencyKey, body(request))));
    }

    @Override
    public Redemption release(String redemptionId) {
        return redemption(upstream.call(SERVICE, () -> api.getObject().release(redemptionId)));
    }

    private static CheckoutOffersApi.CouponRequest body(Request r) {
        return new CheckoutOffersApi.CouponRequest(r.code(), r.customerId(), r.orderId(), r.currency(),
                r.lines().stream().map(l -> new CheckoutOffersApi.CouponLine(l.offerId(), l.quantity())).toList(),
                r.shippingMinor(), r.firstOrder());
    }

    private static Redemption redemption(CheckoutOffersApi.RedemptionInfo r) {
        return new Redemption(r.id(), r.couponId(), r.code(), r.status(), r.discountMinor(), r.shippingDiscountMinor(),
                r.totalDiscountMinor(), r.funding(), r.fundedByOrg(), lines(r.lines()));
    }

    private static List<LineDiscount> lines(List<CheckoutOffersApi.DiscountLine> lines) {
        return lines.stream().map(l -> new LineDiscount(l.lineRef(), l.sellerOrgId(), l.discountMinor())).toList();
    }
}
