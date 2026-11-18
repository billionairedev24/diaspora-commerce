package com.afrotransact.catalog.domain.brand;

/**
 * Whether two brand comparison keys ({@link BrandNames#normalise}) are close enough that the second is probably the
 * first misspelt or extended ("Adidass" for "Adidas", "Nike Air" for "Nike"). It drives the "Did you mean" line and the
 * review queue hint; it never decides by itself, because a different spelling can be a different brand.
 */
public final class BrandSimilarity {

    private static final int MIN_LENGTH = 4;
    private static final int LONG_NAME = 8;
    private static final int MAX_EXTENSION = 6;

    private BrandSimilarity() {
    }

    /** True when the keys differ but are one or two typing mistakes apart, or one extends the other by a few letters. */
    public static boolean near(String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (shorter.length() < MIN_LENGTH) {
            return false;
        }
        if (longer.startsWith(shorter) && longer.length() - shorter.length() <= MAX_EXTENSION) {
            return true;
        }
        int allowed = shorter.length() >= LONG_NAME ? 2 : 1;
        return longer.length() - shorter.length() <= allowed && distance(a, b, allowed) <= allowed;
    }

    /** Edit distance with adjacent swaps counted as one, giving up once it exceeds {@code limit} (returns limit + 1). */
    static int distance(String a, String b, int limit) {
        int n = a.length();
        int m = b.length();
        int[] twoBack = new int[m + 1];
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, twoBack[j - 2] + 1);
                }
                current[j] = best;
                rowMin = Math.min(rowMin, best);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = twoBack;
            twoBack = previous;
            previous = current;
            current = swap;
        }
        return previous[m];
    }
}
