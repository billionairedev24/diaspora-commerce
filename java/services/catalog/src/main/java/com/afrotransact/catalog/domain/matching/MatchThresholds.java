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
 * @param openSizeRequests      different-size requests (new variants on pages of others) one organisation may have waiting
 *                              for staff at once; answering or withdrawing one frees a place
 */
public record MatchThresholds(int showMin, int likelyMin, int maxCandidates, int recall, int minQueryLength,
                              int searchesPerHour, int noneDecisionsPerDay, int openSizeRequests) {

    /** The default number of waiting different-size requests per organisation. */
    public static final int DEFAULT_OPEN_SIZE_REQUESTS = 20;

    public MatchThresholds(int showMin, int likelyMin, int maxCandidates, int recall, int minQueryLength,
                           int searchesPerHour, int noneDecisionsPerDay) {
        this(showMin, likelyMin, maxCandidates, recall, minQueryLength, searchesPerHour, noneDecisionsPerDay,
                DEFAULT_OPEN_SIZE_REQUESTS);
    }

    public MatchThresholds {
        if (showMin < 0 || likelyMin < showMin || likelyMin > 100 || maxCandidates < 1 || recall < maxCandidates
                || minQueryLength < 2 || searchesPerHour < 1 || noneDecisionsPerDay < 1 || openSizeRequests < 1) {
            throw new IllegalArgumentException("Invalid matching thresholds");
        }
    }

    public static MatchThresholds defaults() {
        return new MatchThresholds(45, 75, 6, 30, 3, 600, 30);
    }
}
