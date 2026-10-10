package com.afrotransact.identity.domain;

/** Normalised sign-up values: trimmed names, lower-case email and an E.164 phone number. */
public record ValidatedSignUp(String firstName, String lastName, String email, String phoneE164) {
}
