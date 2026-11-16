package com.afrotransact.bookings.api;

import java.time.Instant;
import java.util.List;

import com.afrotransact.module.EndUserActor;

/**
 * The signed-in shopper's own service bookings, read in-process for a call made on their behalf (the AI shopping agent's "my
 * bookings" tool): the in-process equivalent of {@code GET /v1/bookings/mine} with the shopper's token and
 * {@code X-Acting-As: CUSTOMER}. The person is the actor in force in bookings' own use case, so it lists their bookings and nobody
 * else's. The calling module needs the delegation scope {@value #CALLER_SCOPE}.
 */
public interface ShopperBookingsApi {

    /** The scope a module needs to read bookings on behalf of its signed-in users. */
    String CALLER_SCOPE = "bookings:mine.read";

    /** The person's bookings, active first. */
    List<BookingSummary> mine(EndUserActor user);

    /** @param status the customer-facing status label */
    record BookingSummary(String reference, String serviceName, String status, Instant startAt, long priceMinor,
                          long depositMinor) {
    }
}
