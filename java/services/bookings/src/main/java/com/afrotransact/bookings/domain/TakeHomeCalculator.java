package com.afrotransact.bookings.domain;

/** Provider take-home per service and earnings per booking, under the provider's fee schedule. */
public interface TakeHomeCalculator {

    /** Take-home at the listed price, deposit and balance each charged once. */
    TakeHome takeHome(FeeSchedule fees, long priceMinor, long depositMinor, String currency);

    /** What was collected, held and earned on a booking; commission only on what the customer paid. */
    BookingEarnings earnings(FeeSchedule fees, Booking booking);
}
