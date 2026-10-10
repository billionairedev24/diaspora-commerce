package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DistanceClient;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.stereotype.Component;

@Component
class LocalDeliveryQuotesImpl implements LocalDeliveryQuotes {

    private final PickupLocations locations;
    private final DistanceClient distances;
    private final PaymentsClient payments;

    LocalDeliveryQuotesImpl(PickupLocations locations, DistanceClient distances, PaymentsClient payments) {
        this.locations = locations;
        this.distances = distances;
        this.payments = payments;
    }

    @Override
    public PickupLocations.PickupLocation pickupFor(CartClient.Item item) {
        String what = item.title() == null ? item.variantAtin() : item.title();
        PickupLocations.PickupLocation l = locations.forOffer(item.offerId()).orElseThrow(() -> unavailable(what));
        if (!l.pickupEnabled() || l.lat() == null || l.lng() == null || !item.sellerOrgId().equals(l.orgId())) {
            throw unavailable(what);
        }
        return l;
    }

    @Override
    public LocalDelivery quote(String sellerOrgId, String sellerName, PickupLocations.PickupLocation from,
                               ShipTo to) {
        String seller = sellerName == null ? sellerOrgId : sellerName;
        DistanceClient.Distance d;
        try {
            d = distances.measure(from.lat(), from.lng(), to);
        } catch (UpstreamFailure f) {
            if (f.retryable()) {
                throw f;
            }
            throw new OrdersRuleViolation(CheckoutCodes.DELIVERY_ADDRESS_NOT_LOCATED, "We couldn't locate "
                    + to.summary() + " to measure the delivery from " + seller + "; check the address");
        }
        PaymentsClient.DeliveryQuote q = payments.quoteDelivery(sellerOrgId, d.roadMiles(), 1);
        if (!q.available()) {
            if ("BEYOND_MAX_RADIUS".equals(q.unavailableReason())) {
                throw new OrdersRuleViolation(CheckoutCodes.DELIVERY_OUT_OF_RANGE, seller + " is "
                        + Math.round(d.roadMiles()) + " miles from " + to.summary() + ": too far for "
                        + "AfroTransact Delivery from the seller's store. Choose another seller or address.");
            }
            throw new OrdersRuleViolation(CheckoutCodes.DELIVERY_UNAVAILABLE,
                    "AfroTransact Delivery from sellers' stores is not available right now");
        }
        return new LocalDelivery(from.locationId(), from.lat(), from.lng(), d.straightLineMiles(), d.roadMiles(),
                d.method(), q.customerChargeMinor(), q.sellerFeeMinor(), q.scheduleVersion(), d.durationMinutes());
    }

    private static OrdersRuleViolation unavailable(String what) {
        return new OrdersRuleViolation(CheckoutCodes.DELIVERY_PICKUP_UNAVAILABLE, what + " can't be delivered by "
                + "AfroTransact from the seller's store right now");
    }
}
