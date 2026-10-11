package com.afrotransact.catalog.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;

/**
 * Payload of catalog.product.* events; consumed by offers, inventory, search indexer and trust-ops.
 *
 * @param sellerHidden the owner's listings are hidden by seller-service compliance enforcement: keep the product out
 *                     of public surfaces regardless of {@code status}
 * @param reasonCode   takedown reason code on {@code catalog.product.taken_down}; null otherwise
 * @param preorderEligible may be sold in a pre-order window (S272)
 * @param shipsNow     always ships now by carrier, never as a pre-order
 * @param imageUrl     public URL of the main (first) image; null without images
 * @param brand        the {@code brand} attribute; null when absent
 * @param communities  diaspora communities from the {@code community} / {@code communities} attribute
 *                     (comma-separated there); empty when absent
 */
public record ProductEventPayload(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                                  String title, UUID categoryId, String productTypeCode,
                                  List<VariantPayload> variants, String note, boolean sellerHidden,
                                  String reasonCode, boolean preorderEligible, boolean shipsNow, String imageUrl,
                                  String brand, List<String> communities) {

    /** Variant part of the payload. */
    public record VariantPayload(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                                 int quantity) {
    }

    static ProductEventPayload of(Product p, boolean sellerHidden) {
        return of(p, sellerHidden, null);
    }

    static ProductEventPayload of(Product p, boolean sellerHidden, String reasonCode) {
        return new ProductEventPayload(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                p.status().name(), p.title(), p.categoryId(), p.productTypeCode(),
                p.variants().stream()
                        .map(v -> new VariantPayload(v.atin().value(), v.sellerSku(), v.gtin(), v.priceMinor(),
                                v.currency(), v.quantity()))
                        .toList(),
                p.moderationNote(), sellerHidden, reasonCode, p.preorderEligible(), p.shipsNow(),
                p.images() == null || p.images().isEmpty() ? null : p.images().getFirst().url(),
                blankToNull(attribute(p.attributes(), "brand")), communities(p.attributes()));
    }

    private static List<String> communities(Map<String, String> attributes) {
        String raw = attribute(attributes, "communities");
        if (raw == null || raw.isBlank()) {
            raw = attribute(attributes, "community");
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String attribute(Map<String, String> attributes, String key) {
        return attributes == null ? null : attributes.get(key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
