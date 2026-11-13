package com.afrotransact.catalog.domain.matching;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.catalog.domain.matching.PackSize.Agreement;

/**
 * Scores how likely an existing product page is the product a seller means, from what a seller naturally knows: the words
 * of the name (the brand and the size may be inside it), an optional category, and nothing else. Pure and deterministic.
 *
 * <p>Score, 0 to 100: words of the name 55, brand 20 (a different brand takes 25 off), size 15 (a different size caps the
 * score below {@link MatchThresholds#likelyMin()}), category 10 (another department takes 15 off). A part number the page
 * holds and the seller happened to type adds 15: a hidden signal, never asked for.
 *
 * <p>Exact versus fuzzy: only {@link Kind#SAME_NAME} is exact (the normalised name, brand and size are all equal inside
 * the category). Everything else is {@link Kind#SIMILAR}: it is shown as a suggestion and the seller must confirm it.
 */
public final class CandidateRanker {

    private static final double WORDS = 55;
    private static final double BRAND = 20;
    private static final double BRAND_CONFLICT = 25;
    private static final double SIZE = 15;
    private static final double SAME_CATEGORY = 10;
    private static final double SAME_BRANCH = 5;
    private static final double OTHER_DEPARTMENT = 15;
    private static final double HIDDEN_PART_NUMBER = 15;
    private static final int MIN_PART_NUMBER_KEY = 4;
    private static final String PATH_SEPARATOR = "›";

    private CandidateRanker() {
    }

    public enum Kind {
        /** Name, brand and size are equal once normalised, in the same category: exact, but still a click for the seller. */
        SAME_NAME,
        /** A suggestion by name: the seller must confirm it is theirs. */
        SIMILAR
    }

    /**
     * What the seller gave.
     *
     * @param name         the typed name, size and brand may be inside it
     * @param brand        the brand when the caller has it (bulk column); null when only the name is known
     * @param categoryId   the category the seller or the screen chose; null when none
     * @param categoryPath its human path
     */
    public record Query(String name, String brand, UUID categoryId, String categoryPath) {
    }

    /** One existing page; {@code partNumber} is the hidden signal. */
    public record Page(String atin, String title, String brand, UUID categoryId, String categoryPath, String sizeText,
                       String partNumber) {
    }

    /**
     * @param size   what the seller's size says against the page's
     * @param pageSize the page's size as found, null when it has none
     */
    public record Score(int score, Kind kind, Agreement size, PackSize pageSize, boolean brandMatches) {
    }

    public static Score score(Query q, Page p, MatchThresholds t) {
        PackSize.Found asked = PackSize.find(q.name()).orElse(null);
        PackSize.Found written = PackSize.find(p.sizeText() != null && !p.sizeText().isBlank() ? p.sizeText() : p.title())
                .or(() -> PackSize.find(p.title())).orElse(null);
        PackSize pageSize = written == null ? null : written.size();
        Agreement size = asked == null ? Agreement.UNKNOWN : asked.size().against(pageSize);
        String queryText = asked == null ? q.name() : asked.rest();
        String pageText = PackSize.find(p.title()).map(PackSize.Found::rest).orElse(p.title());

        boolean brandInName = ProductNames.mentions(queryText, p.brand());
        String explicit = q.brand() == null || q.brand().isBlank() ? null : q.brand();
        boolean brandGiven = explicit != null;
        boolean brandMatches = (brandGiven && ProductNames.normalise(explicit).equals(ProductNames.normalise(p.brand())))
                || brandInName;
        boolean brandConflicts = brandGiven && !brandMatches && p.brand() != null && !p.brand().isBlank();

        List<String> queryWords = ProductNames.tokens(ProductNames.withoutBrand(queryText, p.brand() == null ? "" : p.brand()));
        List<String> pageWords = ProductNames.tokens(ProductNames.withoutBrand(pageText, p.brand() == null ? "" : p.brand()));
        double words = similarity(queryWords, pageWords);

        double score = WORDS * words;
        if (brandMatches) {
            score += BRAND;
        } else if (brandConflicts) {
            score -= BRAND_CONFLICT;
        }
        if (size == Agreement.SAME) {
            score += SIZE;
        }
        score += categoryEffect(q, p);
        if (hasPartNumber(q.name(), p.partNumber())) {
            score += HIDDEN_PART_NUMBER;
        }
        int rounded = (int) Math.round(Math.max(0, Math.min(100, score)));
        if (size == Agreement.DIFFERENT) {
            rounded = Math.min(rounded, t.likelyMin() - 1);
        }
        boolean sameCategory = q.categoryId() == null || p.categoryId() == null || q.categoryId().equals(p.categoryId());
        boolean exact = words == 1.0 && sameCategory && size != Agreement.DIFFERENT && sameSizePresence(asked, written)
                && brandEquals(q, p, brandMatches) && !queryWords.isEmpty();
        return new Score(rounded, exact ? Kind.SAME_NAME : Kind.SIMILAR, size, pageSize, brandMatches);
    }

    private static boolean sameSizePresence(PackSize.Found asked, PackSize.Found written) {
        return (asked == null) == (written == null);
    }

    /** The brand is part of the key: both unbranded, or the page's brand named by the seller (as a column or in the name). */
    private static boolean brandEquals(Query q, Page p, boolean brandMatches) {
        boolean pageUnbranded = p.brand() == null || p.brand().isBlank();
        if (pageUnbranded) {
            return q.brand() == null || q.brand().isBlank();
        }
        return brandMatches;
    }

    private static double categoryEffect(Query q, Page p) {
        if (q.categoryId() == null || p.categoryId() == null) {
            return 0;
        }
        if (q.categoryId().equals(p.categoryId())) {
            return SAME_CATEGORY;
        }
        String a = parent(q.categoryPath());
        String b = parent(p.categoryPath());
        if (!a.isEmpty() && a.equals(b)) {
            return SAME_BRANCH;
        }
        String deptA = department(q.categoryPath());
        String deptB = department(p.categoryPath());
        return !deptA.isEmpty() && !deptB.isEmpty() && !deptA.equals(deptB) ? -OTHER_DEPARTMENT : 0;
    }

    private static String parent(String path) {
        if (path == null) {
            return "";
        }
        int i = path.lastIndexOf(PATH_SEPARATOR);
        return i < 0 ? "" : path.substring(0, i).strip();
    }

    private static String department(String path) {
        if (path == null) {
            return "";
        }
        int i = path.indexOf(PATH_SEPARATOR);
        return (i < 0 ? path : path.substring(0, i)).strip();
    }

    private static boolean hasPartNumber(String name, String partNumber) {
        if (partNumber == null) {
            return false;
        }
        String key = partNumber.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (key.length() < MIN_PART_NUMBER_KEY) {
            return false;
        }
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").contains(key);
    }

    /** Words in common: how much of the seller's name the page covers (65%) and how much of the page's name the seller covered (35%). */
    static double similarity(List<String> query, List<String> page) {
        if (query.isEmpty() || page.isEmpty()) {
            return 0;
        }
        long queryHits = query.stream().filter(w -> page.stream().anyMatch(x -> ProductNames.sameWord(w, x))).count();
        long pageHits = page.stream().filter(w -> query.stream().anyMatch(x -> ProductNames.sameWord(w, x))).count();
        double coverage = 0.65 * queryHits / query.size() + 0.35 * pageHits / page.size();
        return query.equals(page) ? 1.0 : Math.min(coverage, 0.99);
    }
}
