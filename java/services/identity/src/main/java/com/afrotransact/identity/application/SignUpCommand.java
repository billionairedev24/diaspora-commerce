package com.afrotransact.identity.application;

/**
 * Unified sign-up (customers, sellers, providers).
 *
 * @param intent shop | sell | provide — chooses which applicant role is added server-side
 */
public record SignUpCommand(String firstName, String lastName, String email, String phone, String phoneRegion,
                            String password, String confirmPassword, boolean termsAccepted, boolean smsConsent,
                            String intent) {
}
