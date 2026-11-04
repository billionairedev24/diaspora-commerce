package com.afrotransact.catalog.domain.brand;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.CatalogRuleViolation;

/** Brand names: what a valid one looks like and the comparison key that makes "Nike", "NIKE" and "Levi's"/"Levis" one. */
public final class BrandNames {

    public static final int MAX_LENGTH = 120;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private BrandNames() {
    }

    /**
     * Lower case, accents folded, everything but letters and digits removed. The Postgres function {@code brand_key}
     * computes the same key for the legacy backfill.
     */
    public static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String folded = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        return NOT_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** The display name: trimmed, inner whitespace collapsed, 2 to 120 characters with something to compare on. */
    public static String clean(String name, String field) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > MAX_LENGTH || CONTROL.matcher(n).find()) {
            throw new CatalogRuleViolation("Brand names have 2 to " + MAX_LENGTH + " characters",
                    Map.of(field, "2 to " + MAX_LENGTH + " characters"));
        }
        if (normalise(n).isEmpty()) {
            throw new CatalogRuleViolation("A brand name needs letters or digits", Map.of(field, "Use letters or digits"));
        }
        return n;
    }
}
