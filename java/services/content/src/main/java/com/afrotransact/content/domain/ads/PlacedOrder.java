package com.afrotransact.content.domain.ads;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The parts of orders.order.placed that attribution needs.
 *
 * @param orderId    order
 * @param customerId shopper
 * @param placedAt   placement time
 * @param lines      every line of every shipment
 */
public record PlacedOrder(String orderId, String customerId, Instant placedAt, List<Line> lines) {

    /** Sales are attributed to the shopper's last click on the product within this window (7-day click). */
    public static final Duration ATTRIBUTION_WINDOW = Duration.ofDays(7);

    public PlacedOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One order line.
     *
     * @param lineId      line id
     * @param sellerOrgId seller of the shipment (must be the advertiser)
     * @param variantAtin ordered variant
     * @param salesMinor  quantity × unit price
     */
    public record Line(String lineId, String sellerOrgId, String variantAtin, long salesMinor) {
    }
}
