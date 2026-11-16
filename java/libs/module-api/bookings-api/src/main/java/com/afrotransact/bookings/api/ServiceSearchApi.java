package com.afrotransact.bookings.api;

import java.util.List;

/**
 * The anonymous search of bookable services that the AI shopping agent makes ({@code GET /v1/services/search?q=&zip=}, the alias of
 * {@code /v1/public/services/search}). No scope is needed over HTTP, so none is needed here; the call still runs under an
 * in-process caller principal. An invalid ZIP is 422 {@code bookings.invalid-zip} as over HTTP.
 */
public interface ServiceSearchApi {

    /** Providers' services matching {@code query} near {@code zip} (null: no location), at most {@code size}, nearest first. */
    List<ServiceListing> search(String query, String zip, int size);

    /** @param nextAvailable ISO instant of the next free time, null when none */
    record ServiceListing(String serviceId, String name, String providerName, String description, long priceFromMinor,
                          String currency, long depositMinor, double rating, String nextAvailable, String location) {
    }
}
