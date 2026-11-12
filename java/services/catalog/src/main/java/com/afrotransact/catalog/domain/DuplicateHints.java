package com.afrotransact.catalog.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shows staff a page that may already exist for an item without a barcode (S387): the same brand and part number, or a
 * similar title under the same brand. A hint is never a block; staff decide.
 */
public final class DuplicateHints {

    /** Title similarity (shared words over all words) from which a page is shown as a hint. */
    public static final double TITLE_THRESHOLD = 0.5;
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private DuplicateHints() {
    }

    /**
     * @param matchedOn BRAND_MPN, TITLE or both
     * @param score     1 for the same part number, else the title similarity (0 to 1)
     */
    public record Hint(String atin, String title, String brand, String mpn, double score, List<String> matchedOn) {

        public Hint {
            matchedOn = List.copyOf(matchedOn);
        }
    }

    /**
     * @param page           the page under review
     * @param candidates     pages of the same brand
     * @param mpnAttributeKey the attribute that holds the manufacturer part number
     */
    public static List<Hint> of(Product page, List<Product> candidates, String mpnAttributeKey) {
        String mpn = key(page.attributes().get(mpnAttributeKey));
        Set<String> words = words(page.title());
        List<Hint> hints = new ArrayList<>();
        for (Product other : candidates) {
            if (other.id().equals(page.id())) {
                continue;
            }
            List<String> why = new ArrayList<>();
            double score = 0;
            if (mpn != null && mpn.equals(key(other.attributes().get(mpnAttributeKey)))) {
                why.add("BRAND_MPN");
                score = 1;
            }
            double similarity = similarity(words, words(other.title()));
            if (similarity >= TITLE_THRESHOLD) {
                why.add("TITLE");
                score = Math.max(score, similarity);
            }
            if (!why.isEmpty()) {
                hints.add(new Hint(other.atin().value(), other.title(), other.attributes().get("brand"),
                        other.attributes().get(mpnAttributeKey), score, why));
            }
        }
        hints.sort(Comparator.comparingDouble(Hint::score).reversed());
        return hints;
    }

    /** The part number as compared: upper case, letters and digits only; null when empty. */
    public static String key(String mpn) {
        if (mpn == null) {
            return null;
        }
        String key = mpn.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return key.isEmpty() ? null : key;
    }

    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) common.size() / all.size();
    }

    static Set<String> words(String title) {
        Set<String> out = new HashSet<>();
        for (String w : WORDS.split(title == null ? "" : title.toLowerCase(Locale.ROOT))) {
            if (w.length() > 1) {
                out.add(w);
            }
        }
        return out;
    }
}
