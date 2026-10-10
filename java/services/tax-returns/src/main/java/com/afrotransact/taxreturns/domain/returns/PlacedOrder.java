package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * The snapshot of a placed order kept for returns and tax (from {@code orders.order.placed}, delivery times from
 * {@code orders.shipment.delivered}).
 */
public record PlacedOrder(UUID orderId, String orderNumber, String customerId, String currency, TaxAddress shipTo,
                          Instant placedAt, List<Shipment> shipments) {

    public PlacedOrder {
        shipments = List.copyOf(shipments);
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

    /** An order line. */
    public record Line(String lineId, String atin, String title, int quantity, long unitPriceMinor, long taxMinor,
                       String productType, boolean perishable) {
    }
}
