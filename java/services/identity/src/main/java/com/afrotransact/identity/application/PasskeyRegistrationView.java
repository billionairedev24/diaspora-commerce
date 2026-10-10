package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A started passkey registration (S016): {@code optionsJson} is the {@code PublicKeyCredentialCreationOptions} for
 * {@code navigator.credentials.create} (web) or the platform passkey APIs (iOS {@code ASAuthorization}, Android
 * Credential Manager).
 */
public record PasskeyRegistrationView(UUID registrationId, Instant expiresAt, String optionsJson) {
}
