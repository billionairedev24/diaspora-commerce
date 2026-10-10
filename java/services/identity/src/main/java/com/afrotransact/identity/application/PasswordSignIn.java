package com.afrotransact.identity.application;

/**
 * @param trustedDeviceToken value the BFF kept from an earlier {@code trustDevice} verification (skips the second
 *                           factor for 30 days), or null
 */
public record PasswordSignIn(String email, String password, String trustedDeviceToken, ClientContext context) {
}
