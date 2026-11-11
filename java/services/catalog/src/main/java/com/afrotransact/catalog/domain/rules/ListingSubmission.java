package com.afrotransact.catalog.domain.rules;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.PackageDimensions;

/**
 * What a listing says, in the neutral form the rule checks read: built from a product draft (form and API) and from
 * the rows of a bulk upload, so both are judged by the same code.
 *
 * @param attributes   product attributes by key (brand, origin, shelf, ingredients ...)
 * @param description  the shopper description
 * @param imageCount   product pictures (the label photo is not one of them)
 * @param labelPhotos  label photos
 * @param dangerous    the dangerous goods answer; null when not answered
 * @param exemptions   rule field id to the seller's reason for not providing it
 * @param sizedType    the product type is sized (has a size dimension and size charts)
 * @param sizeChart    a size chart is chosen
 * @param identifier   the barcode rule of the product type (GTIN or ISBN)
 * @param variants     one entry per variant
 */
public record ListingSubmission(Map<String, String> attributes, String description, int imageCount, int labelPhotos,
                                Boolean dangerous, Map<String, String> exemptions, boolean sizedType,
                                boolean sizeChart, IdentifierRule identifier, List<VariantFacts> variants) {

    public ListingSubmission {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        exemptions = exemptions == null ? Map.of() : Map.copyOf(exemptions);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /**
     * One variant as the checks see it.
     *
     * @param barcode   the barcode digits typed (empty when none)
     * @param exempt    the seller says the item has no barcode
     * @param packaging the variant's own package, or the listing default it inherits; null when none
     */
    public record VariantFacts(String barcode, boolean exempt, PackageDimensions packaging) {

        public VariantFacts {
            barcode = barcode == null ? "" : barcode;
        }
    }

    /** The reason the seller gave for not providing a field; blank when none. */
    public String reason(RuleField field) {
        String r = exemptions.get(field.id());
        return r == null ? "" : r.strip();
    }
}
