package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/**
 * The parts of a catalog.product.* event offers-service uses.
 *
 * @param condition    the listed condition (NEW when absent)
 * @param fulfilment   SELLER or AFROTRANSACT (Fulfilled by AfroTransact); null = the default for the seller kind
 * @param handlingDays business days to dispatch; null or 0 = the default
 * @param shipFrom     the seller's ship-from location; null = none on the offer
 */
public record ProductSnapshot(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                              UUID categoryId, List<VariantSnapshot> variants, String condition, String fulfilment,
                              Integer handlingDays, ShipFromSnapshot shipFrom) {

    public ProductSnapshot {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** A snapshot without listing terms (events published before S307). */
    public ProductSnapshot(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                           UUID categoryId, List<VariantSnapshot> variants) {
        this(productId, atin, ownerKind, ownerOrgId, status, categoryId, variants, null, null, null, null);
    }

    /** A product variant with the seller's price, quantity and "was" price (null for none). */
    public record VariantSnapshot(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                                  int quantity, Long compareAtMinor) {

        public VariantSnapshot(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                               int quantity) {
            this(atin, sellerSku, gtin, priceMinor, currency, quantity, null);
        }
    }

    /** catalog's ship-from snapshot. */
    public record ShipFromSnapshot(String locationId, String city, String region, String country, String postalCode) {
    }
}
