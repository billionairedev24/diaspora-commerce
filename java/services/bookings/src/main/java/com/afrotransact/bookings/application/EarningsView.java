package com.afrotransact.bookings.application;

import java.util.List;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingEarnings;

/** Earnings screen: per booking what was collected minus commission, card fees and late-cancellation fees. */
public record EarningsView(String orgId, String currency, List<Row> rows, Totals totals) {

    /** One booking's money. */
    public record Row(Booking booking, BookingEarnings earnings) {
    }

    /** Sums over the rows. */
    public record Totals(long collectedMinor, long commissionMinor, long cardFeesMinor, long providerFeesMinor,
                         long netMinor, long heldMinor) {
    }
}
