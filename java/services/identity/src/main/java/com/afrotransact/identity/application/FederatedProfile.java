package com.afrotransact.identity.application;

/** Verified claims from a social provider after its own consent screen. */
public record FederatedProfile(String provider, String subject, String email, boolean emailVerified,
                               String givenName, String familyName) {
}
