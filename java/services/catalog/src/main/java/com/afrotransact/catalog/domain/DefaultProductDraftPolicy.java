package com.afrotransact.catalog.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.tax.SellerSkus;

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

        for (AttributeDefinition def : type.applicableAttributes()) {
            if (def.kind() == AttributeKind.BRAND && draft.attributes().containsKey(BrandKeys.REASON)) {
                continue;
            }
            String problem = def.problem(draft.attributes().get(def.key()));
            if (problem != null) {
                errors.put("attributes." + def.key(), problem);
            }
        }

        checkImages(draft.images(), errors);
        checkVariants(draft, type, errors);
        checkDetails(draft.details(), errors);

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
    }

    private static void checkDetails(ListingDetails d, Map<String, String> errors) {
        if (d.description() != null && d.description().length() > ListingDetails.MAX_DESCRIPTION) {
            errors.put("description", "At most " + ListingDetails.MAX_DESCRIPTION + " characters");
        }
        if (d.keywords().size() > ListingDetails.MAX_KEYWORDS) {
            errors.put("keywords", "Up to " + ListingDetails.MAX_KEYWORDS + " keywords");
        } else if (d.keywords().stream().anyMatch(k -> k.length() > ListingDetails.MAX_KEYWORD)) {
            errors.put("keywords", "Each keyword at most " + ListingDetails.MAX_KEYWORD + " characters");
        }
        if (d.handlingDays() < 1 || d.handlingDays() > ListingDetails.MAX_HANDLING_DAYS) {
            errors.put("handlingDays", "Between 1 and " + ListingDetails.MAX_HANDLING_DAYS + " days");
        }
        if (d.packaging() == null) {
            if (d.fulfilment() == FulfilmentMode.AFROTRANSACT) {
                errors.put("packaging", "Package size and weight are required for Fulfilled by AfroTransact");
            }
        } else if (d.packaging().problem() != null) {
            errors.put("packaging", d.packaging().problem());
        }
    }

    private static void checkVariants(ProductDraft draft, ProductType type, Map<String, String> errors) {
        VariationTheme theme = draft.variationTheme().isEmpty() ? null : type.theme(draft.variationTheme()).orElse(null);
        if (theme == null && !draft.variationTheme().isEmpty()) {
            errors.put("variationTheme", "Choose a variation type for this product");
            return;
        }
        if (draft.variants().isEmpty()) {
            errors.put("variants", theme == null ? "Add the price and quantity" : "Pick at least one value for each variation");
            return;
        }
        if (theme == null && draft.variants().size() > 1) {
            errors.put("variationTheme", "Choose how the product varies to list more than one variant");
            return;
        }
        Set<String> skus = new HashSet<>();
        Set<String> combos = new HashSet<>();
        Map<String, Map<String, String>> customSpellings = new HashMap<>();
        for (int i = 0; i < draft.variants().size(); i++) {
            VariantDraft v = draft.variants().get(i);
            String p = "variants[" + i + "].";
            List<VariationDimension> dims = theme == null ? List.of() : theme.dimensions();
            for (VariationDimension dim : dims) {
                String problem = optionProblem(dim, v.options().get(dim.name()), customSpellings);
                if (problem != null) {
                    errors.put(p + "options." + dim.name(), problem);
                }
            }
            if (v.options().size() != dims.size()) {
                errors.put(p + "options", theme == null ? "A product without variations has no options"
                        : "Expected values for " + theme.name());
            }
            if (!combos.add(comboKey(dims, v.options()))) {
                errors.put(p + "options", "Duplicate variation");
            }
            String sku = SellerSkus.normalise(v.sellerSku());
            if (!sku.isEmpty() && !SellerSkus.VALID.matcher(sku).matches()) {
                errors.put(p + "sellerSku", "2–40 letters, digits, dot, dash or underscore, or leave it empty to get one");
            } else if (!sku.isEmpty() && !skus.add(sku)) {
                errors.put(p + "sellerSku", "SKU must be unique");
            }
            checkIdentifier(v, type.identifierRule(), p, errors);
            if (v.priceMinor() <= 0) {
                errors.put(p + "price", "Enter a price above zero");
            }
            if (v.quantity() < 0) {
                errors.put(p + "quantity", "Quantity cannot be negative");
            } else if (v.quantity() > MAX_QUANTITY) {
                errors.put(p + "quantity", "At most " + MAX_QUANTITY);
            }
            if (v.compareAtMinor() != null && v.compareAtMinor() <= v.priceMinor()) {
                errors.put(p + "compareAtPrice", "Must be higher than the price");
            }
            if (v.images().size() > VariantDraft.MAX_IMAGES) {
                errors.put(p + "images", "Up to " + VariantDraft.MAX_IMAGES + " images per variant");
            } else if (v.images().stream().anyMatch(img -> !img.allowedType() || img.bytes() > ProductImage.MAX_BYTES)) {
                errors.put(p + "images", "Use JPG, PNG or WebP of at most 8 MB");
            }
        }
    }

    /**
     * Why a variant's value of this dimension is refused, or null. A dimension that allows custom values accepts the
     * category's options and values of the seller's own within {@link CustomValues}' limits; the same value spelled two
     * ways on one product and more than {@link CustomValues#MAX_PER_PRODUCT} different ones are refused.
     */
    private static String optionProblem(VariationDimension dim, String value, Map<String, Map<String, String>> seen) {
        String what = dim.name().toLowerCase(Locale.ROOT);
        if (value == null || value.isBlank()) {
            return "Choose a " + what;
        }
        if (!dim.allowCustomValues()) {
            return dim.options().contains(value) ? null : "Choose a " + what;
        }
        if (dim.options().contains(value)) {
            return null;
        }
        String problem = CustomValues.problem(dim.name(), value);
        if (problem != null) {
            return problem;
        }
        String cleaned = CustomValues.clean(value);
        Optional<String> suggestion = dim.suggestion(cleaned);
        if (suggestion.isPresent() && !suggestion.get().equals(value)) {
            return "Write it as " + suggestion.get();
        }
        Map<String, String> spellings = seen.computeIfAbsent(dim.name(), k -> new HashMap<>());
        String earlier = spellings.putIfAbsent(CustomValues.key(cleaned), value);
        if (earlier != null && !earlier.equals(value)) {
            return "\"" + value + "\" and \"" + earlier + "\" are the same " + what + "; use one spelling";
        }
        if (earlier == null && spellings.size() > CustomValues.MAX_PER_PRODUCT) {
            return "Up to " + CustomValues.MAX_PER_PRODUCT + " different " + what + " values on one product";
        }
        return null;
    }

    private static String comboKey(List<VariationDimension> dims, Map<String, String> options) {
        if (dims.isEmpty()) {
            return String.valueOf(options);
        }
        StringBuilder key = new StringBuilder();
        for (VariationDimension dim : dims) {
            key.append(dim.name()).append('=').append(CustomValues.key(options.get(dim.name()))).append('|');
        }
        return key.toString();
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
