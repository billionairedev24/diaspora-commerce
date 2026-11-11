package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.catalog.domain.tax.VariantTax;

/**
 * A purchasable variation of a product.
 *
 * @param options        dimension name → value, e.g. Size=M, Color=Gold (empty for a product without variations)
 * @param gtin           manufacturer barcode digits, or null when exempt
 * @param priceMinor     offer price in minor units as last set through catalog (the offers service owns pricing)
 * @param quantity       sellable quantity as last set through catalog (platform stock comes from inventory)
 * @param compareAtMinor "was" price (offers' list price); null for none
 * @param images         images of this variant only
 * @param sellerSkuGenerated true when the service generated {@code sellerSku} because the seller left it empty
 * @param tax            the variant's tax facts, gate status and decision summary
 * @param packaging      the package of this variant as shipped (size and weight); null only for items that ship
 *                       nothing (digital, gift cards, services) or listings saved before packages were per variant
 */
public record Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt,
                      Map<String, String> options, long priceMinor, String currency, int quantity,
                      Long compareAtMinor, List<ProductImage> images, boolean sellerSkuGenerated,
                      VariantTax tax, PackageDimensions packaging) {

    /** A variant without a package of its own. */
    public Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt,
                   Map<String, String> options, long priceMinor, String currency, int quantity,
                   Long compareAtMinor, List<ProductImage> images, boolean sellerSkuGenerated, VariantTax tax) {
        this(id, atin, sellerSku, gtin, gtinExempt, options, priceMinor, currency, quantity, compareAtMinor, images,
                sellerSkuGenerated, tax, null);
    }

    public Variant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(sellerSku, "sellerSku");
        options = Map.copyOf(options);
        Objects.requireNonNull(currency, "currency");
        images = images == null ? List.of() : List.copyOf(images);
        tax = tax == null ? VariantTax.NONE : tax;
    }

    /** A variant without tax data. */
    public Variant(UUID id, Atin atin, String sellerSku, String gtin, boolean gtinExempt, Map<String, String> options,
                   long priceMinor, String currency, int quantity, Long compareAtMinor, List<ProductImage> images) {
        this(id, atin, sellerSku, gtin, gtinExempt, options, priceMinor, currency, quantity, compareAtMinor, images,
                false, null);
    }

    /** The same variant with another tax state. */
    public Variant withTax(VariantTax next) {
        return new Variant(id, atin, sellerSku, gtin, gtinExempt, options, priceMinor, currency, quantity,
                compareAtMinor, images, sellerSkuGenerated, next, packaging);
    }

    /** The same variant with its own package. */
    public Variant withPackaging(PackageDimensions next) {
        return new Variant(id, atin, sellerSku, gtin, gtinExempt, options, priceMinor, currency, quantity,
                compareAtMinor, images, sellerSkuGenerated, tax, next);
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
