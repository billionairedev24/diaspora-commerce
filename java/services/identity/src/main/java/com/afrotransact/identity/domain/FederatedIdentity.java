package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A social sign-in identity (Google, Apple, Facebook subject) linked to an account. */
public record FederatedIdentity(String provider, String subject, UUID userId, String email, Instant linkedAt) {
}
