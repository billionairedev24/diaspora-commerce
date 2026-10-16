package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.Attribution;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.ShipTo;

/**
 * An order as one viewer may see it: customers see their whole order with handover codes; sellers only their own
 * shipments (totals are then the sum of those); staff everything except the codes.
 *
 * @param id              order id
 * @param orderNumber     AT-2026-XXXXXX
 * @param status          pending_payment | placed | in_progress | completed | cancelled
 * @param customerId      shopper (staff and the customer only)
 * @param placedAt        when the payment was authorised
 * @param createdAt       when checkout created it
 * @param currency        ISO currency
 * @param itemsMinor      items total
 * @param shippingMinor   shipping total
 * @param taxMinor        tax total
 * @param discountMinor   discounts
 * @param totalMinor      grand total
 * @param shipTo          "City, ST" (order lists)
 * @param shippingAddress full delivery address snapshot
 * @param cancelReason    why it was voided / cancelled
 * @param paymentId       payments-service payment (staff only)
 * @param partial         true when the viewer (a seller) sees only some shipments
 * @param shipments       visible shipments
 */
public record OrderView(String id, String orderNumber, String status, String customerId, Instant placedAt,
                        Instant createdAt, String currency, long itemsMinor, long shippingMinor, long taxMinor,
                        long discountMinor, long totalMinor, String shipTo, ShipTo shippingAddress,
                        String cancelReason, String paymentId, boolean partial, List<ShipmentView> shipments,
                        String channel, List<String> tags, Attribution attribution) {

    /**
     * One shipment.
     *
     * @param id               shipment id
     * @param shipmentId       same as id (gateway-bff field name)
     * @param label            "Shipment 1 of 2 · Seller"
     * @param handoverCode     4-digit code to give the driver (only in the customer's own view)
     * @param cancellable      the customer may still cancel it (before packed)
     * @param nextActions      statuses the viewer may move it to now
     * @param timeline         status history, oldest first
     * @param returnable       delivered and inside the return window of at least one returnable line (a hint for
     *                         "Return or replace items"; tax-returns decides per line)
     * @param returnWindowEndsAt end of the return window (null when not delivered or nothing is returnable)
     * @param problemReportOnly delivered with only perishable / non-returnable lines ("Report a problem")
     * @param cancellableUntil  when the customer's Cancel stops being offered (the cancellation window's end, or a
     *                          pre-order window's close); null when the shipment can no longer be cancelled by the customer
     * @param returns          where the apps read eligibility and returns (tax-returns, through gateway-bff)
     */
    public record ShipmentView(String id, String shipmentId, int seq, String label, String sellerOrgId,
                               String sellerName, String fulfilmentType, String nodeId, String deliveryMethod,
                               String serviceLevel, String pickupLocationId, DeliveryPreferences preferences,
                               boolean perishable, boolean handoverRequired, String handoverCode, String status,
                               String items, long itemsMinor, long shippingMinor, long taxMinor, long totalMinor,
                               String carrier, String trackingNumber, LocalDate promisedFrom, LocalDate promisedBy,
                               Instant deliveredAt, boolean captured, boolean cancellable, Instant cancellableUntil,
                               List<String> nextActions,
                               List<LineView> lines, List<TimelineEntry> timeline, boolean returnable,
                               Instant returnWindowEndsAt, boolean problemReportOnly, ReturnLinks returns,
                               LocalDeliveryView localDelivery, PreorderView preorder) {
    }

    /**
     * The pre-order window of a PREORDER shipment (S272).
     *
     * @param fulfilment       DOOR | PICKUP
     * @param cancellableUntil the customer may cancel for a full refund (delivery fee included) until then
     * @param deliveryMissed   the delivery date passed undelivered: the customer may choose REFUND or WAIT
     * @param missedChoice     the customer's answer (null until answered)
     */
    public record PreorderView(String windowId, String windowCode, String windowName, LocalDate deliversOn,
                               String fulfilment, Instant cancellableUntil, boolean deliveryMissed,
                               String missedChoice) {
    }

    /**
     * AfroTransact Delivery from the seller's store (S255): distance and, for the seller and staff, the seller's
     * pickup-delivery fee (customers see the delivery charge as shippingMinor; the fee is hidden from them).
     */
    public record LocalDeliveryView(String shipFromLocationId, double roadMiles, double durationMinutes,
                                    String distanceMethod, long customerChargeMinor, Long sellerFeeMinor,
                                    int pricingVersion) {
    }

    /**
     * tax-returns endpoints for this order (tax-returns owns RMAs; orders only links them).
     *
     * @param eligibility {@code GET /v1/returns/eligibility?orderId=}
     * @param list        {@code GET /v1/returns?orderId=}
     */
    public record ReturnLinks(String eligibility, String list) {
    }

    /** One line. */
    public record LineView(String id, int lineNo, String variantAtin, String productAtin, String title,
                           String imageUrl, int quantity, long unitPriceMinor, long itemsMinor, long taxMinor,
                           long discountMinor, String category, boolean perishable, String taxJurisdiction,
                           String productType) {
    }

    /** One status change. */
    public record TimelineEntry(String from, String to, String actorType, String source, String reason,
                                Instant at, DeliveryProof proof) {
    }

    /**
     * What the driver app recorded when it handed a parcel over (or failed to): how, to whom, where it was left, and
     * whether the photo and the signature exist. The assets themselves are fetched from media with their ids.
     */
    public record DeliveryProof(String handoverMethod, String recipientName, String relationship, String safePlace,
                                String failureReason, String photoAssetId, String signatureAssetId, Double latitude,
                                Double longitude) {
    }
}
