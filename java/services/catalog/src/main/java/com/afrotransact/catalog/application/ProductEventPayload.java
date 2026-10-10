package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;

/**
 * Payload of catalog.product.* events; consumed by offers, inventory, search indexer and trust-ops.
 *
 * @param sellerHidden the owner's listings are hidden by seller-service compliance enforcement: keep the product out
 *                     of public surfaces regardless of {@code status}
 */
public record ProductEventPayload(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                                  String title, UUID categoryId, String productTypeCode,
                                  List<VariantPayload> variants, String note, boolean sellerHidden) {

    /** Variant part of the payload. */
    public record VariantPayload(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                                 int quantity) {
    }

    static ProductEventPayload of(Product p, boolean sellerHidden) {
        return new ProductEventPayload(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                p.status().name(), p.title(), p.categoryId(), p.productTypeCode(),
                p.variants().stream()
                        .map(v -> new VariantPayload(v.atin().value(), v.sellerSku(), v.gtin(), v.priceMinor(),
                                v.currency(), v.quantity()))
                        .toList(),
                p.moderationNote(), sellerHidden);
    }
}
