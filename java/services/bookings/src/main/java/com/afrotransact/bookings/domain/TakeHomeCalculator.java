package com.afrotransact.bookings.domain;

/** Provider take-home per service and earnings per booking. */
public interface TakeHomeCalculator {

    /** Take-home at the listed price, deposit and balance each charged once. */
    TakeHome takeHome(long priceMinor, long depositMinor, String currency);

    /** What was collected, held and earned on a booking; commission only on what the customer paid. */
    BookingEarnings earnings(Booking booking);
}
