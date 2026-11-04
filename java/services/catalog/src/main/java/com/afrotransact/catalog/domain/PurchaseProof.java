package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Orders-service's answer that a customer received a product: the delivering order. Kept with the review as the
 * evidence behind "Verified purchase"; never shown publicly.
 *
 * @param deliveredAt when it was delivered (null when orders did not keep the time)
 */
public record PurchaseProof(String orderId, String orderNumber, Instant deliveredAt) {

    public PurchaseProof {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderNumber, "orderNumber");
    }
}
