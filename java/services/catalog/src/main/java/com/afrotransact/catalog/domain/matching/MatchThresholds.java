package com.afrotransact.catalog.domain.matching;

/**
 * The numbers of search-first matching (docs/engineering/product-matching-without-barcode.md section 4).
 *
 * @param showMin               a page scoring below this is not shown
 * @param likelyMin             from this score a page is shown as a likely match; a "None of these" answer next to one is
 *                              queued for staff as a possible duplicate
 * @param maxCandidates         pages shown (six)
 * @param recall                how many pages the search index is asked for before ranking
 * @param minQueryLength        letters a name needs before it is searched
 * @param searchesPerHour       candidate searches one organisation may make in an hour
 * @param noneDecisionsPerDay   "None of these" answers one organisation may give in 24 hours
 */
public record MatchThresholds(int showMin, int likelyMin, int maxCandidates, int recall, int minQueryLength,
                              int searchesPerHour, int noneDecisionsPerDay) {

    public MatchThresholds {
        if (showMin < 0 || likelyMin < showMin || likelyMin > 100 || maxCandidates < 1 || recall < maxCandidates
                || minQueryLength < 2 || searchesPerHour < 1 || noneDecisionsPerDay < 1) {
            throw new IllegalArgumentException("Invalid matching thresholds");
        }
    }

    public static MatchThresholds defaults() {
        return new MatchThresholds(45, 75, 6, 30, 3, 600, 30);
    }
}
