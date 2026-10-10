package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Outcome of sign-up: the account exists and an email code was sent. */
public record SignUpResult(UUID userId, String email, boolean verificationRequired, Instant codeExpiresAt) {
}
