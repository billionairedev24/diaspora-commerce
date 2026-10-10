package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

/** A passkey challenge: {@code optionsJson} is the {@code PublicKeyCredentialRequestOptions} for the browser. */
public record PasskeyChallengeView(UUID challengeId, Instant expiresAt, String optionsJson) {
}
