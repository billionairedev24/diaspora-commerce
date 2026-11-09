package com.afrotransact.catalog.domain.tax;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The generated seller SKU: {@code <org prefix>-<ATIN>}, for a variant whose seller left the SKU empty. The prefix is
 * four characters derived from the organisation id, so every organisation's generated SKUs look alike and never
 * collide with another organisation's; the ATIN makes it unique within the organisation and stable (it is set once and
 * never regenerated).
 */
public final class SellerSkus {

    /** What the catalog accepts as a seller SKU. */
    public static final Pattern VALID = Pattern.compile("[A-Z0-9][A-Z0-9._-]{1,39}");
    private static final int PREFIX_LENGTH = 4;
    private static final long PREFIX_SPACE = 36L * 36 * 36 * 36;

    private SellerSkus() {
    }

    /** Four upper-case letters or digits, the same for every call with the same organisation id. */
    public static String prefix(String orgId) {
        long h = 1125899906842597L;
        for (char c : (orgId == null ? "" : orgId).toCharArray()) {
            h = 31 * h + c;
        }
        String text = Long.toString(Math.floorMod(h, PREFIX_SPACE), 36).toUpperCase(Locale.ROOT);
        return "0".repeat(PREFIX_LENGTH - text.length()) + text;
    }

    public static String generate(String orgId, String atin) {
        return prefix(orgId) + "-" + atin.toUpperCase(Locale.ROOT);
    }

    /** The same SKU with a numeric suffix, for the (unlikely) case a seller already used the generated one. */
    public static String withSuffix(String sku, int attempt) {
        return sku + "-" + attempt;
    }

    /** Normal form of a seller-typed SKU: trimmed, upper case. Empty when blank. */
    public static String normalise(String sku) {
        return sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT);
    }
}
