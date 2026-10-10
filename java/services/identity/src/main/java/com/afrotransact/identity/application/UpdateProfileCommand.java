package com.afrotransact.identity.application;

/** Profile completion / edit; null fields are left unchanged. */
public record UpdateProfileCommand(String firstName, String lastName, String phone, String phoneRegion,
                                   Boolean termsAccepted, Boolean smsConsent) {
}
