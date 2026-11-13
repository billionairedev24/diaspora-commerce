package com.afrotransact.catalog.domain.matching;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Product names as compared by matching: accents folded, case and punctuation ignored, plurals reduced. */
public final class ProductNames {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> FILLER = Set.of("the", "and", "of", "with", "for", "a", "an", "in", "by", "new");

    private ProductNames() {
    }

    /** Lower case, accents folded, every run of other characters one space. */
    public static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String folded = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return NOT_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** The meaningful words of a text: normalised, filler dropped, a plural "s" removed ("plantains" is "plantain"). */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        String n = normalise(text);
        if (n.isEmpty()) {
            return out;
        }
        for (String word : n.split(" ")) {
            if (!word.isEmpty() && !FILLER.contains(word)) {
                out.add(stem(word));
            }
        }
        return out;
    }

    private static String stem(String word) {
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /** The normalised text with the brand cut out wherever its words appear in a row. */
    public static String withoutBrand(String text, String brand) {
        String n = normalise(text);
        String b = normalise(brand);
        if (b.isEmpty()) {
            return n;
        }
        return (" " + n + " ").replace(" " + b + " ", " ").strip().replaceAll("\\s+", " ");
    }

    /** Whether the brand's words appear in a row in the text. */
    public static boolean mentions(String text, String brand) {
        String b = normalise(brand);
        return !b.isEmpty() && (" " + normalise(text) + " ").contains(" " + b + " ");
    }

    /** Whether two words are the same word, allowing one typo in words of five letters or more. */
    static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        return Math.min(a.length(), b.length()) >= 5 && Math.abs(a.length() - b.length()) <= 1 && distanceAtMostOne(a, b);
    }

    private static boolean distanceAtMostOne(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (a.length() == b.length()) {
            return a.substring(i + 1).equals(b.substring(i + 1));
        }
        String longer = a.length() > b.length() ? a : b;
        String shorter = a.length() > b.length() ? b : a;
        return longer.substring(i + 1).equals(shorter.substring(i));
    }
}
