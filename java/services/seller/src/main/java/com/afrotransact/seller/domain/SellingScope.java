package com.afrotransact.seller.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A selling scope: the thing a seller is approved for. Keys are canonical: category and product type codes as the
 * catalog spells them, brands normalised to lowercase letters and digits, a brand in a category as
 * {@code brand|category}, a product by its barcode digits.
 */
public record SellingScope(SellingScopeType type, String key) {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    public SellingScope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("A selling scope needs a key");
        }
    }

    public static SellingScope category(String code) {
        return new SellingScope(SellingScopeType.CATEGORY, code(code));
    }

    public static SellingScope productType(String code) {
        return new SellingScope(SellingScopeType.PRODUCT_TYPE, code(code));
    }

    public static SellingScope brand(String brand) {
        return new SellingScope(SellingScopeType.BRAND, normaliseBrand(brand));
    }

    public static SellingScope brandInCategory(String brand, String categoryCode) {
        return new SellingScope(SellingScopeType.BRAND_IN_CATEGORY, normaliseBrand(brand) + "|" + code(categoryCode));
    }

    public static SellingScope product(String gtin) {
        return new SellingScope(SellingScopeType.PRODUCT, gtin == null ? "" : gtin.replaceAll("\\D", ""));
    }

    public static SellingScope capability(String code) {
        return new SellingScope(SellingScopeType.CAPABILITY, code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Brands compare without case, accents, spaces and punctuation, exactly like the catalog brand registry (S630,
     * {@code BrandNames.normalise}): "Levi's", "LEVIS" and "levis" are one brand, and so are "Hermes" and "Herm\u00e8s".
     */
    public static String normaliseBrand(String brand) {
        if (brand == null) {
            return "";
        }
        String folded = MARKS.matcher(Normalizer.normalize(brand, Normalizer.Form.NFD)).replaceAll("");
        return NOT_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String code(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the key is one a scope of {@code type} may have. */
    public static boolean validKey(SellingScopeType type, String key) {
        if (key == null || key.isBlank() || key.length() > 200) {
            return false;
        }
        return switch (type) {
            case CATEGORY, PRODUCT_TYPE -> key.matches("[a-z0-9][a-z0-9-]{0,118}");
            case BRAND -> key.equals(normaliseBrand(key));
            case BRAND_IN_CATEGORY -> key.matches("[\\p{L}\\p{N}]+\\|[a-z0-9][a-z0-9-]{0,118}");
            case PRODUCT -> key.matches("\\d{8,14}");
            case CAPABILITY -> key.matches("[A-Z_]{3,40}");
        };
    }
}
