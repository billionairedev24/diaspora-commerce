package com.afrotransact.identity.domain;

/** Raw sign-up form values as submitted (before normalisation). */
public record SignUpDetails(String firstName, String lastName, String email, String phone, String phoneRegion,
                            String password, String confirmPassword, boolean termsAccepted) {
}
