package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * The snapshot of a placed order kept for returns and tax (from {@code orders.order.placed}, delivery times from
 * {@code orders.shipment.delivered}).
 */
public record PlacedOrder(UUID orderId, String orderNumber, String customerId, String currency, TaxAddress shipTo,
                          Instant placedAt, List<Shipment> shipments, ReturnAddress customerAddress) {

    public PlacedOrder {
        shipments = List.copyOf(shipments);
    }

    /** A snapshot without the customer's street address (orders placed before orders sent it). */
    public PlacedOrder(UUID orderId, String orderNumber, String customerId, String currency, TaxAddress shipTo,
                       Instant placedAt, List<Shipment> shipments) {
        this(orderId, orderNumber, customerId, currency, shipTo, placedAt, shipments, null);
    }

    /** The order's delivery address when the snapshot has the street (return labels, pickups). */
    public Optional<ReturnAddress> returnFrom() {
        return customerAddress != null && customerAddress.complete() ? Optional.of(customerAddress)
                : Optional.empty();
    }

    /**
     * A shipment (sub-order).
     *
     * @param shippingMinor shipping charged for the shipment (derived: total − items − tax), used for estimates only
     */
    public record Shipment(UUID shipmentId, String sellerOrgId, String sellerName, FulfilmentType fulfilment,
                           DeliveryMethod delivery, long totalMinor, long shippingMinor, Instant deliveredAt,
                           List<Line> lines) {

        public Shipment {
            lines = List.copyOf(lines);
        }
    }

    /** An order line ({@code discountMinor}: coupon discount, so items charged = price × quantity − discount). */
    public record Line(String lineId, String atin, String title, int quantity, long unitPriceMinor, long taxMinor,
                       String productType, boolean perishable, long discountMinor) {

        public Line(String lineId, String atin, String title, int quantity, long unitPriceMinor, long taxMinor,
                    String productType, boolean perishable) {
            this(lineId, atin, title, quantity, unitPriceMinor, taxMinor, productType, perishable, 0L);
        }

        /** Price × quantity − discount. */
        public long itemsMinor() {
            return Math.max(0L, unitPriceMinor * quantity - discountMinor);
        }
    }
}
