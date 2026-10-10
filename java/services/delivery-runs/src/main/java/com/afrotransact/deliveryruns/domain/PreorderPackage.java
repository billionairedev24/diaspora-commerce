package com.afrotransact.deliveryruns.domain;

import java.util.List;

/**
 * One pre-order package (orders-service shipment with {@code fulfilmentType=PREORDER}) of an order.
 *
 * @param shipmentId       orders-service shipment id (null for late adds until the order event arrives)
 * @param sellerOrgId      seller of the package
 * @param totalMinor       package total including its delivery fee
 * @param deliveryFeeMinor delivery fee charged on the package
 * @param lines            products
 * @param cancelled        cancelled by the customer (refunded)
 */
public record PreorderPackage(String shipmentId, String sellerOrgId, long totalMinor, long deliveryFeeMinor,
                              List<PreorderLine> lines, boolean cancelled) {

    public PreorderPackage {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public PreorderPackage cancel() {
        return new PreorderPackage(shipmentId, sellerOrgId, totalMinor, deliveryFeeMinor, lines, true);
    }
}
