package com.afrotransact.payments.domain;

import java.util.Objects;

/** Negotiated seller pickup-delivery fee for one organisation (like a commission override). */
public record SellerDeliveryOverride(String orgId, DeliveryTariff sellerFee) {

    public SellerDeliveryOverride {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(sellerFee, "sellerFee");
        orgId = orgId.trim();
    }
}
