package com.afrotransact.offers.adapter.in.module;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.offers.application.CouponService;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link CheckoutOffersApi}: the coupon and offer use cases behind the endpoints orders calls, entered
 * under the calling module's principal. The services apply their permission checks as for a token; the request is built the
 * way the controller builds it (currency defaults to USD).
 */
@Component
class CheckoutOffersApiAdapter implements CheckoutOffersApi {

    private final CouponService coupons;
    private final OfferService offers;
    private final OfferListingService listing;
    private final ModuleEntry entry;

    CheckoutOffersApiAdapter(CouponService coupons, OfferService offers, OfferListingService listing,
                             ActorResolver actors) {
        this.coupons = coupons;
        this.offers = offers;
        this.listing = listing;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public QuoteInfo validate(CouponRequest r) {
        return entry.run(() -> CheckoutViews.quote(coupons.validate(CheckoutViews.command(r))), CheckoutFailures::map);
    }

    @Override
    public RedemptionInfo redeem(String idempotencyKey, CouponRequest r) {
        return entry.run(() -> CheckoutViews.redemption(coupons.redeem(idempotencyKey, CheckoutViews.command(r))
                .redemption()), CheckoutFailures::map);
    }

    @Override
    public RedemptionInfo release(String redemptionId) {
        return entry.run(() -> CheckoutViews.redemption(coupons.release(UUID.fromString(redemptionId)).redemption()),
                CheckoutFailures::map);
    }

    @Override
    public Optional<String> inventorySku(String offerId) {
        return entry.run(() -> {
            try {
                return Optional.ofNullable(offers.get(UUID.fromString(offerId)).offer().inventorySku());
            } catch (NotFoundException notFound) {
                return Optional.<String>empty();
            }
        }, CheckoutFailures::map);
    }

    @Override
    public Optional<String> shipFromLocationId(String offerId) {
        return entry.run(() -> {
            try {
                var offer = listing.offer(UUID.fromString(offerId)).view().offer();
                return Optional.ofNullable(offer.shipFrom() == null ? null : offer.shipFrom().locationId());
            } catch (NotFoundException notFound) {
                return Optional.<String>empty();
            }
        }, CheckoutFailures::map);
    }
}
