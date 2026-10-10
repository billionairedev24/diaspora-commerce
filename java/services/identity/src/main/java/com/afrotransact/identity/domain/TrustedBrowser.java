package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A browser the user chose to trust; skips the second factor until {@code expiresAt}. */
public record TrustedBrowser(UUID id, UUID userId, String tokenHash, String userAgent, Instant createdAt,
                             Instant expiresAt) {
}
