package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
                        String cancelReason, String paymentId, boolean partial, List<ShipmentView> shipments) {

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
     */
    public record ShipmentView(String id, String shipmentId, int seq, String label, String sellerOrgId,
                               String sellerName, String fulfilmentType, String nodeId, String deliveryMethod,
                               String serviceLevel, String pickupLocationId, DeliveryPreferences preferences,
                               boolean perishable, boolean handoverRequired, String handoverCode, String status,
                               String items, long itemsMinor, long shippingMinor, long taxMinor, long totalMinor,
                               String carrier, String trackingNumber, LocalDate promisedFrom, LocalDate promisedBy,
                               Instant deliveredAt, boolean captured, boolean cancellable, List<String> nextActions,
                               List<LineView> lines, List<TimelineEntry> timeline) {
    }

    /** One line. */
    public record LineView(String id, int lineNo, String variantAtin, String productAtin, String title,
                           String imageUrl, int quantity, long unitPriceMinor, long itemsMinor, long taxMinor,
                           long discountMinor, String category, boolean perishable, String taxJurisdiction) {
    }

    /** One status change. */
    public record TimelineEntry(String from, String to, String actorType, String source, String reason,
                                Instant at) {
    }
}
