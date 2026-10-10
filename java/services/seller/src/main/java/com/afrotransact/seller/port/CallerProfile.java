package com.afrotransact.seller.port;

/** Identity claims of the signed-in user used to prefill onboarding (OIDC name, email, phone_number). */
public record CallerProfile(String givenName, String familyName, String fullName, String email, String phone) {

    public static CallerProfile empty() {
        return new CallerProfile(null, null, null, null, null);
    }
}
