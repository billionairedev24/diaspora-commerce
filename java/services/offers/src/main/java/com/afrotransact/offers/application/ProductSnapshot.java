package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/** The parts of a catalog.product.* event offers-service uses. */
public record ProductSnapshot(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                              UUID categoryId, List<VariantSnapshot> variants) {

    public ProductSnapshot {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** A product variant with the seller's initial price and quantity. */
    public record VariantSnapshot(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                                  int quantity) {
    }
}
