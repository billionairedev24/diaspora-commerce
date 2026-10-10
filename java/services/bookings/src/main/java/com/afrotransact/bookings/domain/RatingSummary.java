package com.afrotransact.bookings.domain;

/**
 * Average rating and number of reviews (0 and 0 without reviews).
 *
 * @param average mean of the ratings, rounded to one decimal
 */
public record RatingSummary(double average, int count) {

    public static final RatingSummary NONE = new RatingSummary(0, 0);

    public RatingSummary {
        if (count < 0 || average < 0 || average > 5) {
            throw new IllegalArgumentException("Invalid rating summary");
        }
        average = Math.round(average * 10) / 10.0;
    }
}
