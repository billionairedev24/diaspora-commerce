package com.afrotransact.catalog.domain.matching;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a seller typed, reduced to the words the database fallback looks for: lower case, accents removed, letters and
 * digits only, no filler words and no size or pack words ("5kg", "pack of 6" are the ranker's business, not a filter:
 * a page stored as "5 kg" must still be found). Pure; the same words are never invented, only dropped.
 */
public final class NameTokens {

    /** Words that never narrow a product name. */
    private static final Set<String> FILLER = Set.of("the", "of", "and", "for", "with", "pack", "pcs", "pc", "x", "in", "a", "kg", "g", "lb", "lbs", "oz", "ml", "l", "cl", "ct", "pk");
    private static final int MAX_TOKENS = 6;

    private NameTokens() {
    }

    /** At most six distinct words of two or more characters, in the order typed. Empty when nothing searchable is left. */
    public static List<String> of(String text) {
        if (text == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        Set<String> words = new LinkedHashSet<>();
        for (String raw : plain.split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2 || FILLER.contains(raw) || isSize(raw)) {
                continue;
            }
            words.add(raw);
        }
        List<String> out = new ArrayList<>(words);
        return out.size() > MAX_TOKENS ? List.copyOf(out.subList(0, MAX_TOKENS)) : List.copyOf(out);
    }

    /** A number, or a number glued to a unit: "5", "5kg", "500g", "12oz". */
    private static boolean isSize(String word) {
        return word.matches("\\d+(\\.\\d+)?(kg|g|lb|lbs|oz|ml|l|cl|ct|pk)?");
    }
}
