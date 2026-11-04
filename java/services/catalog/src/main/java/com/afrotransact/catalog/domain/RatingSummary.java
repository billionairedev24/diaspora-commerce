package com.afrotransact.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A product's rating over its published reviews: the average (two decimals, 0 when there are none), the count and
 * how many reviews gave each of 1 to 5 stars.
 *
 * @param breakdown counts for 1, 2, 3, 4 and 5 stars, in that order
 */
public record RatingSummary(BigDecimal average, int count, List<Integer> breakdown) {

    /** A product nobody has reviewed. */
    public static final RatingSummary NONE = new RatingSummary(BigDecimal.ZERO.setScale(2), 0,
            List.of(0, 0, 0, 0, 0));

    public RatingSummary {
        if (breakdown.size() != 5) {
            throw new IllegalArgumentException("breakdown has one count per star, 1 to 5");
        }
        breakdown = List.copyOf(breakdown);
    }

    /** The summary of reviews with these counts per star (index 0 = 1 star). */
    public static RatingSummary ofCounts(int s1, int s2, int s3, int s4, int s5) {
        int count = s1 + s2 + s3 + s4 + s5;
        if (count == 0) {
            return NONE;
        }
        long stars = s1 + 2L * s2 + 3L * s3 + 4L * s4 + 5L * s5;
        BigDecimal average = BigDecimal.valueOf(stars).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new RatingSummary(average, count, List.of(s1, s2, s3, s4, s5));
    }

    /** Reviews that gave {@code stars} (1 to 5). */
    public int countFor(int stars) {
        return breakdown.get(stars - 1);
    }
}
