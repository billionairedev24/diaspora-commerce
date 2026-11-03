package com.afrotransact.catalog.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules for values a seller types into a variation dimension that allows custom values ("UK 12", "Free size",
 * "Ankara blue print"): length, characters and the case-insensitive identity two spellings share.
 */
public final class CustomValues {

    /** Longest value. */
    public static final int MAX_LENGTH = 30;
    /** Most different values of one dimension on one product. */
    public static final int MAX_PER_PRODUCT = 40;

    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .,/+()'’&#×:_–—-]*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private CustomValues() {
    }

    /** The value with single spaces and no surrounding blanks; empty for null. */
    public static String clean(String raw) {
        return raw == null ? "" : SPACES.matcher(raw.strip()).replaceAll(" ");
    }

    /** What two spellings of the same value share: "xl", "XL" and " XL " are one value. */
    public static String key(String raw) {
        return clean(raw).toLowerCase(Locale.ROOT);
    }

    /** Why the value cannot be used, or null. {@code dimension} names the field in the message ("Size"). */
    public static String problem(String dimension, String raw) {
        String v = clean(raw);
        String what = dimension.toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return "Choose or type a " + what;
        }
        if (v.length() > MAX_LENGTH) {
            return "At most " + MAX_LENGTH + " characters";
        }
        if (!ALLOWED.matcher(v).matches()) {
            return "Use letters, digits, spaces and . , / + ( ) ' & # - only, starting with a letter or digit";
        }
        return null;
    }
}
