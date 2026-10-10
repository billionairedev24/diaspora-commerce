package com.afrotransact.identity.domain;

import java.time.Instant;

/** A registered WebAuthn credential (passkey, Face ID / fingerprint via platform authenticators). */
public record Passkey(String credentialId, String label, Instant createdAt, Instant lastUsedAt) {
}
