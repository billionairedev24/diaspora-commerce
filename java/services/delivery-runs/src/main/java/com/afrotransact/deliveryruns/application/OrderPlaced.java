package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;

/**
 * The parts of {@code orders.order.placed} the pre-order projection needs.
 *
 * @param orderId       order id
 * @param orderNumber   orders-service number
 * @param customerId    identity subject
 * @param currency      ISO 4217
 * @param placedAt      placed
 * @param shipToName    recipient
 * @param phone         recipient / customer phone
 * @param email         customer email
 * @param line1         street line
 * @param city          city
 * @param region        state / region
 * @param zip           postal code
 * @param deliveryNote  note for the driver
 * @param src           attribution {@code src}
 * @param creative      attribution {@code cr}
 * @param windowCode    attribution {@code po}
 * @param packages      PREORDER packages only
 */
public record OrderPlaced(String orderId, String orderNumber, String customerId, String currency, Instant placedAt,
                          String shipToName, String phone, String email, String line1, String city, String region,
                          String zip, String deliveryNote, String src, String creative, String windowCode,
                          List<Package> packages) {

    public OrderPlaced {
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    /**
     * A pre-order package ({@code fulfilmentType=PREORDER}).
     *
     * @param shipmentId    shipment id
     * @param sellerOrgId   seller
     * @param windowId      pre-order window
     * @param fulfilment    door or pickup
     * @param totalMinor    package total incl. delivery fee
     * @param shippingMinor delivery fee
     * @param lines         products
     */
    public record Package(String shipmentId, String sellerOrgId, UUID windowId, PreorderFulfilment fulfilment,
                          long totalMinor, long shippingMinor, List<PreorderLine> lines) {
    }
}
