package com.afrotransact.bookings.api;

import com.afrotransact.module.EndUserActor;

/**
 * Service search for the signed-in person (MCP shopper tool): the in-process equivalent of {@code GET /v1/services/search} with their
 * bearer token. The search is public over HTTP, so it needs no permission here either; the person is still the actor in force. The
 * calling module needs the delegation scope {@value #READ_SCOPE}. The result is the JSON tree of the HTTP response (ten hits); failures
 * are {@code ModuleApiException} with the HTTP problem's status and code (422 {@code bookings.invalid-zip}, 502
 * {@code bookings.geo-unavailable}).
 */
public interface UserServiceSearchApi {

    /** Delegation scope for this API. */
    String READ_SCOPE = "bookings:user.read";

    /** {@code GET /v1/services/search?q=&zip=&size=10}; {@code zip} may be null. */
    Object findServices(EndUserActor user, String query, String zip);
}
