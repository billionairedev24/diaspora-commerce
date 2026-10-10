package com.afrotransact.catalog.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A purchasable variation of a product.
 *
 * @param options      dimension name → value, e.g. Size=M, Color=Gold
 * @param gtin         manufacturer barcode digits, or null when exempt
 * @param priceMinor   initial offer price in minor units (the offers service owns pricing after creation)
 * @param quantity     initial sellable quantity declared by a seller (platform stock comes from inventory)
 */
public record Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt,
                      Map<String, String> options, long priceMinor, String currency, int quantity) {

    public Variant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(sellerSku, "sellerSku");
        options = Map.copyOf(options);
        Objects.requireNonNull(currency, "currency");
    }

    public String optionLabel() {
        return String.join(" / ", options.values());
    }
}
