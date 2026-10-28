package com.afrotransact.catalog.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ShipFromRef;

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
 * @param condition    the listed item condition (NEW, RENEWED, USED_*)
 * @param fulfilment   SELLER or AFROTRANSACT (Fulfilled by AfroTransact)
 * @param handlingDays business days from order to dispatch
 * @param shipFrom     the seller's ship-from location; null = the seller's default
 * @param description  the shopper description (searched); null when none
 * @param keywords     the seller's search keywords (searched, as well as the title and description)
 */
public record ProductEventPayload(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                                  String title, UUID categoryId, String productTypeCode,
                                  List<VariantPayload> variants, String note, boolean sellerHidden,
                                  String reasonCode, boolean preorderEligible, boolean shipsNow, String imageUrl,
                                  String brand, List<String> communities, String condition, String fulfilment,
                                  int handlingDays, ShipFromRef shipFrom, String description,
                                  List<String> keywords) {

    /** Variant part of the payload; {@code compareAtMinor} is the offer's list price ("was" price), may be null. */
    public record VariantPayload(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                                 int quantity, Long compareAtMinor) {
    }

    static ProductEventPayload of(Product p, boolean sellerHidden) {
        return of(p, sellerHidden, null);
    }

    static ProductEventPayload of(Product p, boolean sellerHidden, String reasonCode) {
        return new ProductEventPayload(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                p.status().name(), p.title(), p.categoryId(), p.productTypeCode(),
                p.variants().stream()
                        .map(v -> new VariantPayload(v.atin().value(), v.sellerSku(), v.gtin(), v.priceMinor(),
                                v.currency(), v.quantity(), v.compareAtMinor()))
                        .toList(),
                p.moderationNote(), sellerHidden, reasonCode, p.preorderEligible(), p.shipsNow(),
                p.images() == null || p.images().isEmpty() ? null : p.images().getFirst().url(),
                blankToNull(attribute(p.attributes(), "brand")), communities(p.attributes()),
                p.details().condition().name(), p.details().fulfilment().name(), p.details().handlingDays(),
                p.details().shipFrom(), p.details().description(), p.details().keywords());
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
