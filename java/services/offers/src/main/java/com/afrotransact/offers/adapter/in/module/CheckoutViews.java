package com.afrotransact.offers.adapter.in.module;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.api.CheckoutOffersApi.CouponRequest;
import com.afrotransact.offers.api.CheckoutOffersApi.DiscountLine;
import com.afrotransact.offers.api.CheckoutOffersApi.QuoteInfo;
import com.afrotransact.offers.api.CheckoutOffersApi.RedemptionInfo;
import com.afrotransact.offers.application.CouponCartCommand;
import com.afrotransact.offers.application.CouponQuote;
import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.LineDiscount;

/** The published views of coupon quotes and redemptions, and the command a request becomes (as the controller builds it). */
final class CheckoutViews {

    private CheckoutViews() {
    }

    static CouponCartCommand command(CouponRequest r) {
        return new CouponCartCommand(r.code(), r.customerId(), r.orderId(), r.currency() == null ? "USD" : r.currency(),
                r.lines().stream().map(l -> new CouponCartCommand.Line(UUID.fromString(l.offerId()), l.quantity()))
                        .toList(), r.shippingMinor(), r.firstOrder());
    }

    static QuoteInfo quote(CouponQuote q) {
        return new QuoteInfo(q.applicable(), q.rejection() == null ? null : q.rejection().name(), q.message(),
                q.discountMinor(), q.shippingDiscountMinor(), q.discountMinor() + q.shippingDiscountMinor(),
                q.funding() == null ? null : q.funding().name(), lines(q.lines()));
    }

    static RedemptionInfo redemption(CouponRedemption r) {
        return new RedemptionInfo(String.valueOf(r.id()), String.valueOf(r.couponId()), r.code(),
                r.status() == null ? null : r.status().name(), r.discountMinor(), r.shippingDiscountMinor(),
                r.discountMinor() + r.shippingDiscountMinor(), r.funding() == null ? null : r.funding().name(),
                r.fundedByOrg(), lines(r.lines()));
    }

    private static List<DiscountLine> lines(List<LineDiscount> lines) {
        return lines.stream().map(l -> new DiscountLine(l.lineRef(), l.sellerOrgId(), l.discountMinor())).toList();
    }
}
