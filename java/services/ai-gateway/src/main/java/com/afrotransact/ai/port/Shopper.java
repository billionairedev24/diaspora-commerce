package com.afrotransact.ai.port;

import com.afrotransact.module.EndUserActor;

/**
 * The shopper Lola acts for, as the readers need them: the access token an HTTP adapter relays to a service in another process, and
 * the validated person an in-process adapter acts as. Both come from the same request and are captured on the request thread before
 * Lola's worker thread starts (only there is the security context). Neither is ever read from a tool argument.
 *
 * @param bearerToken the incoming access token, null in the local profile with security off
 * @param user        the person of the request acting as a shopper; null when no signed-in person could be established (a service
 *                    token or no authentication), in which case an in-process reader answers "not available", as the HTTP 401 does
 */
public record Shopper(String bearerToken, EndUserActor user) {

    /** A shopper with no person (HTTP-only callers and tests). */
    public static Shopper token(String bearerToken) {
        return new Shopper(bearerToken, null);
    }

    @Override
    public String toString() {
        return "Shopper[user=" + (user == null ? "none" : user.userId()) + ", token=" + (bearerToken == null ? "none" : "***") + "]";
    }
}
