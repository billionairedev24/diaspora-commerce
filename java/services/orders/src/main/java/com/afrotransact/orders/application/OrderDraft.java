package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.ShipTo;

/**
 * The order after stock was reserved: packages split into shipments per fulfilment node, shipping fees, promised
 * dates and handover codes (hash and sealed value only — never the plain code).
 *
 * @param reserved true when inventory holds a reservation for this order
 * @param coupon   the redeemed coupon (null: none)
 */
public record OrderDraft(String orderId, String orderNumber, String customerId, String workflowId, String currency,
                         ShipTo shipTo, boolean reserved, List<DraftShipment> shipments, Instant createdAt,
                         CouponRef coupon) {

    /** The draft with a coupon's discounts applied. */
    public OrderDraft withCoupon(List<DraftShipment> discounted, CouponRef ref) {
        return new OrderDraft(orderId, orderNumber, customerId, workflowId, currency, shipTo, reserved, discounted,
                createdAt, ref);
    }

    /** Shipping of every shipment. */
    public long shippingMinor() {
        return shipments.stream().mapToLong(DraftShipment::shippingMinor).sum();
    }

    /**
     * A coupon redeemed for this order.
     *
     * @param redemptionId offers-service redemption (released when the order fails or is cancelled)
     */
    public record CouponRef(String code, String redemptionId, long discountMinor, long shippingDiscountMinor,
                            String funding, String fundedByOrg) {
    }

    /** One shipment to create. */
    public record DraftShipment(String id, int seq, String sellerOrgId, String sellerName,
                                FulfilmentType fulfilmentType, String nodeId, DeliveryMethod method,
                                String serviceLevel, String pickupLocationId, DeliveryPreferences preferences,
                                boolean perishable, boolean handoverRequired, String handoverCodeHash,
                                String handoverCodeSealed, long shippingMinor, LocalDate promisedFrom,
                                LocalDate promisedBy, List<CheckoutPlan.Line> lines, LocalDelivery localDelivery) {

        /** A shipment that is not AfroTransact Delivery from a seller's store (tests, older workflow histories). */
        public DraftShipment(String id, int seq, String sellerOrgId, String sellerName, FulfilmentType fulfilmentType,
                             String nodeId, DeliveryMethod method, String serviceLevel, String pickupLocationId,
                             DeliveryPreferences preferences, boolean perishable, boolean handoverRequired,
                             String handoverCodeHash, String handoverCodeSealed, long shippingMinor,
                             LocalDate promisedFrom, LocalDate promisedBy, List<CheckoutPlan.Line> lines) {
            this(id, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, method, serviceLevel, pickupLocationId,
                    preferences, perishable, handoverRequired, handoverCodeHash, handoverCodeSealed, shippingMinor,
                    promisedFrom, promisedBy, lines, null);
        }

        /** The shipment with other lines and shipping (coupon discounts). */
        public DraftShipment with(List<CheckoutPlan.Line> newLines, long newShippingMinor) {
            return new DraftShipment(id, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, method, serviceLevel,
                    pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash,
                    handoverCodeSealed, newShippingMinor, promisedFrom, promisedBy, newLines, localDelivery);
        }
    }
}
