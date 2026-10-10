package com.afrotransact.bookings.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Business settings of the booking flow (from {@code at.bookings.*}).
 *
 * @param holdTtl                how long a slot is held while the customer authorises the deposit
 * @param requestSla             providers must answer a request within this time, or it expires
 * @param autoCompleteAfter      confirmed bookings auto-complete this long after they end
 * @param lateProviderFeeMinor   fee for provider cancellations under 24 h
 * @param manageUrlBase          customer link to manage a booking (reference is appended)
 */
public record BookingsSettings(Duration holdTtl, Duration requestSla, Duration autoCompleteAfter,
                               long lateProviderFeeMinor, String manageUrlBase) {

    public BookingsSettings {
        Objects.requireNonNull(holdTtl, "holdTtl");
        Objects.requireNonNull(requestSla, "requestSla");
        Objects.requireNonNull(autoCompleteAfter, "autoCompleteAfter");
        manageUrlBase = manageUrlBase == null ? "" : manageUrlBase;
    }
}
