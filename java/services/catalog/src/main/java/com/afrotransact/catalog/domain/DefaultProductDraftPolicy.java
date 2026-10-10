package com.afrotransact.catalog.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The catalog rules from the product builder design, applied server-side so no client can skip them. */
public class DefaultProductDraftPolicy implements ProductDraftPolicy {

    @Override
    public void validate(ProductDraft draft) {
        Map<String, String> errors = new LinkedHashMap<>();
        ProductType type = draft.productType();

        String title = draft.title() == null ? "" : draft.title().trim();
        if (title.length() < MIN_TITLE) {
            errors.put("title", "At least " + MIN_TITLE + " characters");
        } else if (title.length() > MAX_TITLE) {
            errors.put("title", "At most " + MAX_TITLE + " characters");
        }

        for (AttributeDefinition def : type.attributes()) {
            String value = draft.attributes().get(def.key());
            if (!def.accepts(value)) {
                errors.put("attributes." + def.key(),
                        value == null || value.isBlank() ? "Required" : "Choose one of " + def.options());
            }
        }

        checkImages(draft.images(), errors);
        checkVariants(draft, type, errors);

        if (!errors.isEmpty()) {
            throw new CatalogRuleViolation("The product has " + errors.size() + " problem(s)", errors);
        }
    }

    private static void checkImages(List<ProductImage> images, Map<String, String> errors) {
        if (images.isEmpty()) {
            errors.put("images", "Add at least one image");
            return;
        }
        if (images.size() > MAX_IMAGES) {
            errors.put("images", "Up to " + MAX_IMAGES + " images");
        }
        for (int i = 0; i < images.size(); i++) {
            ProductImage img = images.get(i);
            if (!img.allowedType()) {
                errors.put("images[" + i + "]", "Use JPG, PNG or WebP");
            } else if (img.bytes() > ProductImage.MAX_BYTES) {
                errors.put("images[" + i + "]", "Larger than 8 MB");
            }
        }
        if (!images.getFirst().largeEnoughForMain()) {
            errors.put("images[0]", "Main image must be at least 1000 × 1000 px");
        }
    }

    private static void checkVariants(ProductDraft draft, ProductType type, Map<String, String> errors) {
        VariationTheme theme = type.theme(draft.variationTheme()).orElse(null);
        if (theme == null) {
            errors.put("variationTheme", "Choose a variation type for this product");
            return;
        }
        if (draft.variants().isEmpty()) {
            errors.put("variants", "Pick at least one value for each variation");
            return;
        }
        Set<String> skus = new HashSet<>();
        Set<String> combos = new HashSet<>();
        for (int i = 0; i < draft.variants().size(); i++) {
            VariantDraft v = draft.variants().get(i);
            String p = "variants[" + i + "].";
            for (VariationDimension dim : theme.dimensions()) {
                String value = v.options().get(dim.name());
                if (value == null || !dim.options().contains(value)) {
                    errors.put(p + "options." + dim.name(), "Choose a " + dim.name().toLowerCase(Locale.ROOT));
                }
            }
            if (v.options().size() != theme.dimensions().size()) {
                errors.put(p + "options", "Expected values for " + theme.name());
            }
            if (!combos.add(String.valueOf(v.options()))) {
                errors.put(p + "options", "Duplicate variation");
            }
            String sku = v.sellerSku() == null ? "" : v.sellerSku().trim().toUpperCase(Locale.ROOT);
            if (!sku.matches("[A-Z0-9][A-Z0-9._-]{1,39}")) {
                errors.put(p + "sellerSku", "2–40 letters, digits, dot, dash or underscore");
            } else if (!skus.add(sku)) {
                errors.put(p + "sellerSku", "SKU must be unique");
            }
            checkIdentifier(v, type.identifierRule(), p, errors);
            if (v.priceMinor() <= 0) {
                errors.put(p + "price", "Enter a price above zero");
            }
            if (v.quantity() < 0) {
                errors.put(p + "quantity", "Quantity cannot be negative");
            }
        }
    }

    private static void checkIdentifier(VariantDraft v, IdentifierRule rule, String p, Map<String, String> errors) {
        String digits = Gtin.digits(v.gtin());
        if (rule == IdentifierRule.ISBN) {
            if (!Gtin.isValid(digits) || digits.length() != 13 || !(digits.startsWith("978") || digits.startsWith("979"))) {
                errors.put(p + "gtin", "ISBN-13 required (978/979 prefix, valid check digit)");
            }
            return;
        }
        if (v.gtinExempt()) {
            return;
        }
        if (digits.isEmpty()) {
            errors.put(p + "gtin", "Required, or request a barcode exemption");
        } else if (!Gtin.isValid(digits)) {
            String fix = Gtin.suggestCorrection(digits);
            errors.put(p + "gtin", fix == null ? "Not a valid GTIN length" : "Invalid check digit — did you mean " + fix + "?");
        }
    }
}
