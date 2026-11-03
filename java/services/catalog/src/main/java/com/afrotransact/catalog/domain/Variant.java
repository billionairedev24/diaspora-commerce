package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A purchasable variation of a product.
 *
 * @param options        dimension name → value, e.g. Size=M, Color=Gold (empty for a product without variations)
 * @param gtin           manufacturer barcode digits, or null when exempt
 * @param priceMinor     offer price in minor units as last set through catalog (the offers service owns pricing)
 * @param quantity       sellable quantity as last set through catalog (platform stock comes from inventory)
 * @param compareAtMinor "was" price (offers' list price); null for none
 * @param images         images of this variant only
 */
public record Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt,
                      Map<String, String> options, long priceMinor, String currency, int quantity,
                      Long compareAtMinor, List<ProductImage> images) {

    public Variant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(sellerSku, "sellerSku");
        options = Map.copyOf(options);
        Objects.requireNonNull(currency, "currency");
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** A variant without compare-at price or images. */
    public Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt, Map<String, String> options,
                   long priceMinor, String currency, int quantity) {
        this(id, atin, sellerSku, gtin, gtinExempt, options, priceMinor, currency, quantity, null, List.of());
    }

    public String optionLabel() {
        return String.join(" / ", options.values());
    }
}
